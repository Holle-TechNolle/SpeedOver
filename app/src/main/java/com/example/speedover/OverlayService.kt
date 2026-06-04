// OverlayService.kt
package com.example.speedover

import android.annotation.SuppressLint
import android.app.*
import android.content.*
import android.graphics.*
import android.graphics.PixelFormat
import android.location.*
import android.os.*
import android.util.Log
import android.view.*
import androidx.core.app.NotificationCompat
import org.json.JSONObject
import kotlin.math.roundToInt

/**
 * SpeedOver Safety Awareness
 * Holle TechNolle, 2026
 *
 * Manages two overlay windows:
 *
 *   textView       — speed number, speed limit, direction arrow.
 *                    Always FLAG_NOT_TOUCHABLE. MIUI click-through via alpha = 0.5f.
 *                    Locked to alpha = 1.0f (not click-through) when violation > 10%.
 *
 *   violationView  — ViolationGradient: yellow→red thermometer bar, left side of screen.
 *                    Fixed position and size — independent of user text-size settings.
 *                    Always fully opaque (alpha = 1.0f) when visible: safety element.
 *
 * ViolationGradient visibility rules:
 *   speed <= limit + 3 km/h  →  hidden (3 km/h = Danish enforcement tolerance)
 *   speed >  limit + 3 km/h  →  shown, grows bottom-to-top up to 30% overspeed
 *   violation > 10%           →  textView also locked to alpha = 1.0f
 *
 * GPS via PendingIntent — resilient to MIUI background throttling.
 * Speed limit from HERE Routing API v8 every 5 seconds when speed >= 20 km/h.
 * Screen kept on via FLAG_KEEP_SCREEN_ON + SCREEN_DIM_WAKE_LOCK.
 * Auto-hide: both windows hidden after 2 minutes below 5 km/h.
 *
 * Personal use only. Untested. Built for Xiaomi T10 — may work on other devices.
 */
class OverlayService : Service() {

    companion object {
        const val ACTION_ENTER_SETTINGS  = "com.example.speedover.ENTER_SETTINGS"
        const val ACTION_EXIT_SETTINGS   = "com.example.speedover.EXIT_SETTINGS"
        const val ACTION_UPDATE_PREFS    = "com.example.speedover.UPDATE_PREFS"
        const val ACTION_RESIZE          = "com.example.speedover.RESIZE"
        const val ACTION_MOVE            = "com.example.speedover.MOVE"
        const val ACTION_LOCATION_UPDATE = "com.example.speedover.LOCATION_UPDATE"
        const val CHANNEL_ID             = "SpeedOverChannel"
        const val BEARING_FALLBACK       = 315f   // NW — shown before GPS delivers a bearing
        const val TAG                    = "SpeedOver"

        var isRunning = false
    }

    private lateinit var windowManager: WindowManager
    private lateinit var prefs: Prefs
    private lateinit var locationManager: LocationManager
    private lateinit var wakeLock: PowerManager.WakeLock

    // Window 1 — speed number, speed limit, direction arrow
    private lateinit var textView: SpeedOverlayView
    private lateinit var textParams: WindowManager.LayoutParams

    // Window 2 — ViolationGradient bar (fixed size, left side of screen)
    private lateinit var violationView: ViolationView
    private lateinit var violationParams: WindowManager.LayoutParams

    private var locationPendingIntent: PendingIntent? = null
    private var currentLat = 0.0
    private var currentLon = 0.0

    // --- Auto-hide state ---
    private var isAutoHidden     = false
    private var hideScheduled    = false
    private var isInSettingsMode = false

    private val autoHideHandler  = Handler(Looper.getMainLooper())
    private val autoHideRunnable = Runnable {
        // After 2 minutes below 5 km/h, hide both windows
        isAutoHidden  = true
        hideScheduled = false
        if (!isInSettingsMode) {
            textParams.alpha      = 0f
            violationParams.alpha = 0f
            windowManager.updateViewLayout(textView, textParams)
            windowManager.updateViewLayout(violationView, violationParams)
        }
    }

    // --- GPS keepalive ---
    private val gpsKeepaliveHandler  = Handler(Looper.getMainLooper())
    private val gpsKeepaliveRunnable = object : Runnable {
        override fun run() {
            // Re-register GPS to prevent MIUI silently throttling delivery
            stopGps(); startGps()
            gpsKeepaliveHandler.postDelayed(this, prefs.gpsKeepaliveSeconds * 1000L)
        }
    }

    // --- Speed limit fetch (every 1 seconds when speed >= 20 km/h) ---
    private val speedLimitHandler  = Handler(Looper.getMainLooper())
    private val speedLimitRunnable = object : Runnable {
        override fun run() {
            if (textView.speedKmh >= 20f && prefs.hereApiKey.isNotEmpty()) {
                fetchSpeedLimit(currentLat, currentLon)
            }
            speedLimitHandler.postDelayed(this, 2_000)
        }
    }

    // --- Internal broadcast receiver ---
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            when (intent.action) {
                ACTION_ENTER_SETTINGS -> enterSettingsMode()
                ACTION_EXIT_SETTINGS  -> exitSettingsMode()
                ACTION_UPDATE_PREFS   -> applyPrefs()
                ACTION_RESIZE         -> resizeOverlay(intent.getFloatExtra("scaleFactor", 1f))
                ACTION_MOVE -> {
                    textParams.x = intent.getIntExtra("x", textParams.x)
                    textParams.y = intent.getIntExtra("y", textParams.y)
                    windowManager.updateViewLayout(textView, textParams)
                }
            }
        }
    }

    // --- Location receiver (EXPORTED — delivered by system LocationManager) ---
    private val locationReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            if (intent.action != ACTION_LOCATION_UPDATE) return
            val location: Location? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(LocationManager.KEY_LOCATION_CHANGED, Location::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(LocationManager.KEY_LOCATION_CHANGED)
            }
            location?.let {
                val kmh = if (it.hasSpeed()) it.speed * 3.6f else 0f
                textView.speedKmh = kmh.coerceAtLeast(0f)
                currentLat = it.latitude
                currentLon = it.longitude

                // Keep last known bearing — only update when GPS reports a fresh value
                if (it.hasBearing()) textView.bearing = it.bearing

                // Auto-hide: restore when speed climbs back above 5 km/h
                if (kmh >= 5f) {
                    if (hideScheduled) {
                        autoHideHandler.removeCallbacks(autoHideRunnable)
                        hideScheduled = false
                    }
                    if (isAutoHidden) {
                        isAutoHidden = false
                        if (!isInSettingsMode) {
                            // Restore textView — violationView alpha set by updateViolationView below
                            textParams.alpha = 0.5f
                            windowManager.updateViewLayout(textView, textParams)
                        }
                    }
                } else {
                    if (!hideScheduled && !isAutoHidden) {
                        autoHideHandler.postDelayed(autoHideRunnable, 2 * 60 * 1000L)
                        hideScheduled = true
                    }
                }

                // Update violation gradient with current speed and limit
                updateViolationView(kmh, textView.speedLimitKmh)
            }
        }
    }

    private var testHandler: Handler? = null

    // --- Lifecycle ---
    override fun onCreate() {
        super.onCreate()
        isRunning = true
        prefs = Prefs(this)

        createNotificationChannel()
        startForeground(1, buildNotification())

        // SCREEN_DIM_WAKE_LOCK keeps screen on as backup if FLAG_KEEP_SCREEN_ON is ignored by MIUI
        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.SCREEN_DIM_WAKE_LOCK or PowerManager.ON_AFTER_RELEASE,
            "SpeedOver::ScreenWakeLock"
        )
        wakeLock.acquire()

        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        buildTextWindow()
        buildViolationWindow()

        // Initialise arrow to NW — visible before GPS delivers its first bearing
        textView.bearing = BEARING_FALLBACK

        // Start auto-hide countdown — speed is zero until GPS delivers its first fix
        autoHideHandler.postDelayed(autoHideRunnable, 2 * 60 * 1000L)
        hideScheduled = true

        // startTestLoop()  — uncomment to activate breath test for vioGrad
        startGps()
        gpsKeepaliveHandler.postDelayed(gpsKeepaliveRunnable, prefs.gpsKeepaliveSeconds * 1000L)
        speedLimitHandler.postDelayed(speedLimitRunnable, 5_000)

        val filter = IntentFilter().apply {
            addAction(ACTION_ENTER_SETTINGS); addAction(ACTION_EXIT_SETTINGS)
            addAction(ACTION_UPDATE_PREFS);   addAction(ACTION_RESIZE)
            addAction(ACTION_MOVE)
        }
        registerReceiver(receiver, filter, RECEIVER_NOT_EXPORTED)
        registerReceiver(locationReceiver, IntentFilter(ACTION_LOCATION_UPDATE), RECEIVER_EXPORTED)

        Log.d(TAG, "OverlayService started — hereApiKey length=${prefs.hereApiKey.length}")
    }

    override fun onTaskRemoved(rootIntent: Intent?) { super.onTaskRemoved(rootIntent); stopSelf() }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        if (wakeLock.isHeld) wakeLock.release()
        stopGps()
        gpsKeepaliveHandler.removeCallbacks(gpsKeepaliveRunnable)
        autoHideHandler.removeCallbacks(autoHideRunnable)
        speedLimitHandler.removeCallbacks(speedLimitRunnable)
        testHandler?.removeCallbacksAndMessages(null)
        unregisterReceiver(receiver)
        unregisterReceiver(locationReceiver)
        try { windowManager.removeView(textView)      } catch (_: Exception) {}
        try { windowManager.removeView(violationView) } catch (_: Exception) {}
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // --- Text window ---
    private fun buildTextWindow() {
        textView = SpeedOverlayView(this)
        applyPrefs()
        val (initWidth, initHeight) = calcOverlaySize(prefs.textSizePx)
        val dm = resources.displayMetrics
        prefs.overlayX = prefs.overlayX.coerceIn(0, (dm.widthPixels  - initWidth ).coerceAtLeast(0))
        prefs.overlayY = prefs.overlayY.coerceIn(0, (dm.heightPixels - initHeight).coerceAtLeast(0))
        textParams = WindowManager.LayoutParams(
            initWidth, initHeight, prefs.overlayX, prefs.overlayY,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE  or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE  or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START }
        windowManager.addView(textView, textParams)
        // 0.5f = MIUI click-through threshold for TYPE_APPLICATION_OVERLAY
        textParams.alpha = 0.5f
        windowManager.updateViewLayout(textView, textParams)
    }

    // --- Violation gradient window ---
    // Fixed position and size — independent of user text-size settings.
    // Left side of screen, spanning 25%-75% of screen height.
    private fun buildViolationWindow() {
        val dm = resources.displayMetrics
        val vx = (dm.widthPixels  * 0.02f).toInt()   // 2% margin from left edge
        val vy = (dm.heightPixels * 0.25f).toInt()   // top at 25% of screen height
        val vw = (dm.widthPixels  * 0.10f).toInt()   // 10% of screen width
        val vh = (dm.heightPixels * 0.50f).toInt()   // 50% height → bottom at 75%

        violationView = ViolationView(this)
        violationParams = WindowManager.LayoutParams(
            vw, vh, vx, vy,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE  or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE  or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START }
        windowManager.addView(violationView, violationParams)
        // Start hidden — shown only when violation threshold is exceeded
        violationParams.alpha = 0f
        windowManager.updateViewLayout(violationView, violationParams)
    }

    // --- ViolationGradient logic ---
    // Called on every GPS update with current speed and speed limit.
    private fun updateViolationView(speedKmh: Float, limitKmh: Int) {
        if (isAutoHidden || isInSettingsMode) return

        // 3 km/h tolerance — reflects Danish enforcement measurement margin
        if (limitKmh <= 0 || speedKmh <= limitKmh + 3f) {
            // Below threshold: hide vioGrad, restore normal text alpha
            violationView.progress = 0f
            violationParams.alpha  = 0f
            textParams.alpha       = 0.5f
            windowManager.updateViewLayout(violationView, violationParams)
            windowManager.updateViewLayout(textView, textParams)
            return
        }

        // Violation fraction: 0.03 = 3% over limit, 0.10 = 10%, 0.30 = 30%
        val violation = (speedKmh - limitKmh) / limitKmh

        // Bar fills linearly: 0% at threshold (3 km/h over), 100% at 30% over
        violationView.progress = (violation / 0.30f).coerceIn(0f, 1f)

        // vioGrad always fully opaque — safety element
        violationParams.alpha = 1.0f
        windowManager.updateViewLayout(violationView, violationParams)

        // > 10% over: lock textView fully opaque — strong signal to slow down
        // 0-10% over: keep normal click-through alpha
        textParams.alpha = if (violation > 0.10f) 1.0f else 0.5f
        windowManager.updateViewLayout(textView, textParams)
    }

    private fun applyPrefs() {
        textView.fillColor         = prefs.fillColor
        textView.strokeColor       = prefs.strokeColor
        textView.textAlpha         = prefs.textAlpha
        textView.textSizePx        = prefs.textSizePx
        textView.strokeWidthFactor = prefs.strokeWidth
    }

    private fun resizeOverlay(scaleFactor: Float) {
        val dm = resources.displayMetrics
        val testPaint      = Paint().apply { textSize = 800f }
        val testArrowH     = 800f * 0.38f
        val testLimitPaint = Paint().apply { textSize = testArrowH * 0.75f }
        val testBottom     = testArrowH * 1.25f + testLimitPaint.measureText("199") / 2f + testArrowH * 1.75f
        val testNumber     = testPaint.measureText("1") + testPaint.measureText("00") + 800f * 0.08f
        val maxSizeByWidth = dm.widthPixels / (maxOf(testNumber, testBottom) / 800f)

        val newSize = (prefs.textSizePx * scaleFactor).coerceIn(40f, maxSizeByWidth)
        prefs.textSizePx    = newSize
        textView.textSizePx = newSize

        val (w, h) = calcOverlaySize(newSize)
        textParams.width  = w;  textParams.height = h
        prefs.overlayWidth = w; prefs.overlayHeight = h
        textParams.x = textParams.x.coerceIn(0, (dm.widthPixels  - w).coerceAtLeast(0))
        textParams.y = textParams.y.coerceIn(0, (dm.heightPixels - h).coerceAtLeast(0))
        prefs.overlayX = textParams.x; prefs.overlayY = textParams.y
        windowManager.updateViewLayout(textView, textParams)
    }

    private fun enterSettingsMode() {
        isInSettingsMode = true
        // Hide both windows while settings are open
        textParams.alpha      = 0f
        violationParams.alpha = 0f
        windowManager.updateViewLayout(textView, textParams)
        windowManager.updateViewLayout(violationView, violationParams)
    }

    private fun exitSettingsMode() {
        isInSettingsMode = false
        if (textView.bearing == null) textView.bearing = BEARING_FALLBACK
        // Restore textView — violation state will be corrected by next GPS update
        textParams.alpha = if (isAutoHidden) 0f else 0.5f
        windowManager.updateViewLayout(textView, textParams)
        // Start fresh auto-hide countdown when returning from settings
        if (!hideScheduled && !isAutoHidden) {
            autoHideHandler.postDelayed(autoHideRunnable, 2 * 60 * 1000L)
            hideScheduled = true
        }
    }

    private fun calcOverlaySize(sizePx: Float): Pair<Int, Int> {
        val paint = Paint().apply { textSize = sizePx }
        val numberWidth = paint.measureText("1") + paint.measureText("00") + sizePx * 0.08f
        val arrowH      = sizePx * 0.38f
        val arrowGap    = arrowH * 0.3f
        val limitPaint  = Paint().apply { textSize = arrowH * 0.75f }
        // talLim centre at arrowH*1.25f from left, arrow zone = arrowH*1.75f from right
        val bottomWidth = arrowH * 1.25f + limitPaint.measureText("199") / 2f + arrowH * 1.75f
        val w  = maxOf(numberWidth, bottomWidth).toInt()
        val fm = paint.fontMetrics
        val h  = (fm.bottom - fm.top + sizePx * 0.05f + arrowGap + arrowH + sizePx * 0.05f).toInt()
        return Pair(w, h)
    }

    // --- HERE speed limit fetch ---
    private fun fetchSpeedLimit(lat: Double, lon: Double) {
        Thread {
            try {
                val url = java.net.URL(
                    "https://router.hereapi.com/v8/routes?" +
                            "origin=$lat,$lon&" +
                            "destination=${lat + 0.0001},$lon&" +
                            "transportMode=car&" +
                            "return=polyline&" +
                            "spans=speedLimit&" +
                            "apiKey=${prefs.hereApiKey}"
                )
                val conn = url.openConnection() as java.net.HttpURLConnection
                conn.requestMethod = "GET"; conn.connectTimeout = 5000; conn.readTimeout = 5000
                if (conn.responseCode == 200) {
                    val limit = parseSpeedLimit(conn.inputStream.bufferedReader().readText())
                    Handler(Looper.getMainLooper()).post { textView.speedLimitKmh = limit }
                } else {
                    Log.e(TAG, "HERE error (${conn.responseCode}): ${conn.errorStream?.bufferedReader()?.readText()}")
                }
                conn.disconnect()
            } catch (e: Exception) {
                Log.e(TAG, "fetchSpeedLimit exception: ${e.message}", e)
            }
        }.start()
    }

    private fun parseSpeedLimit(json: String): Int {
        return try {
            val routes   = JSONObject(json).getJSONArray("routes")
            if (routes.length() == 0) return 0
            val sections = routes.getJSONObject(0).getJSONArray("sections")
            if (sections.length() == 0) return 0
            val spans    = sections.getJSONObject(0).getJSONArray("spans")
            if (spans.length() == 0) return 0
            // speedLimit is m/s as a direct number — roundToInt avoids floating-point
            // truncation errors (e.g. 36.111... * 3.6 = 129.999... → 130 with rounding)
            val valueMs = spans.getJSONObject(0).optDouble("speedLimit", 0.0)
            if (valueMs == 0.0) return 0
            (valueMs * 3.6).roundToInt()
        } catch (e: Exception) {
            Log.e(TAG, "parseSpeedLimit exception: ${e.message}")
            0
        }
    }

    @SuppressLint("MissingPermission")
    private fun startGps() {
        locationManager = getSystemService(LOCATION_SERVICE) as LocationManager
        val locationIntent = Intent(ACTION_LOCATION_UPDATE).setPackage(packageName)
        locationPendingIntent = PendingIntent.getBroadcast(
            this, 0, locationIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        )
        try {
            locationManager.requestLocationUpdates(
                LocationManager.GPS_PROVIDER, 500L, 0f, locationPendingIntent!!
            )
        } catch (e: Exception) { e.printStackTrace() }
    }

    private fun stopGps() {
        try { locationPendingIntent?.let { locationManager.removeUpdates(it) } } catch (_: Exception) {}
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(CHANNEL_ID, "SpeedOver", NotificationManager.IMPORTANCE_LOW)
            .apply { description = "GPS speedometer overlay" }
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("SpeedOver")
            .setContentText("Tap for settings")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentIntent(pi)
            .build()
    }

    // --- Breath test ---
    // Oscillates vioGrad up and down over 5 seconds to verify rendering.
    // Call startTestLoop() from onCreate() to activate. Remove before release.
    private fun startTestLoop() {
        val startTime = System.currentTimeMillis()
        testHandler = Handler(Looper.getMainLooper())
        testHandler?.post(object : Runnable {
            override fun run() {
                val phase = ((System.currentTimeMillis() - startTime) % 5000L).toFloat() / 5000f
                // Triangle wave: 0→1 in first 2.5 s, 1→0 in next 2.5 s
                val wave = if (phase < 0.5f) phase * 2f else (1f - phase) * 2f
                val fakeLimit = 50f
                val fakeSpeed = fakeLimit * (1f + wave * 0.35f)  // 50 → 67.5 km/h and back
                updateViolationView(fakeSpeed, fakeLimit.toInt())
                testHandler?.postDelayed(this, 50L)
            }
        })
    }
}

// =============================================================================
// ViolationGradient view
// =============================================================================
/**
 * Draws the ViolationGradient — a thermometer-style bar with:
 *   - Blue outline (RGB 0,0,255) showing full potential height at all times
 *   - Horizontal tick marks: long at 10%/20% overspeed, short at 5%/15%/25%
 *   - Yellow→red gradient fill growing from bottom upward
 *   - Rounded corners
 *
 * progress = 0..1 where 1 = 30% overspeed (maximum fill)
 */
class ViolationView(context: Context) : View(context) {

    // 0..1 — fill fraction from bottom upward
    var progress: Float = 0f
        set(value) { field = value; invalidate() }

    // Physical stroke width: 2dp converted to device pixels
    private val strokePx = 2f * resources.displayMetrics.density

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val outlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style       = Paint.Style.STROKE
        color       = Color.rgb(0, 0, 255)
        strokeJoin  = Paint.Join.ROUND
        strokeCap   = Paint.Cap.ROUND
    }
    private val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style       = Paint.Style.STROKE
        color       = Color.rgb(0, 0, 255)
        strokeCap   = Paint.Cap.ROUND
    }

    override fun onDraw(canvas: Canvas) {
        if (progress <= 0f) return

        val w          = width.toFloat()
        val h          = height.toFloat()
        val halfStroke = strokePx / 2f
        val radius     = w * 0.25f

        // Rect inset by half stroke so outline sits fully inside the window
        val rect = RectF(halfStroke, halfStroke, w - halfStroke, h - halfStroke)

        // --- Gradient fill (grows from bottom upward) ---
        val barTop = h - progress * (h - halfStroke * 2f) - halfStroke
        fillPaint.shader = LinearGradient(
            0f, halfStroke, 0f, h - halfStroke,
            Color.RED, Color.YELLOW,
            Shader.TileMode.CLAMP
        )
        // Clip to rounded rect before drawing fill so corners stay clean
        canvas.save()
        val clipPath = Path().apply {
            addRoundRect(rect, radius, radius, Path.Direction.CW)
        }
        canvas.clipPath(clipPath)
        canvas.drawRect(halfStroke, barTop, w - halfStroke, h - halfStroke, fillPaint)
        canvas.restore()

        // --- Tick marks ---
        // The bar represents 0–30% overspeed.
        // Long ticks at 10% and 20%; short ticks at 5%, 15%, 25%.
        // Each tick's y position = bottom - (overspeed% / 30%) * barHeight
        outlinePaint.strokeWidth = strokePx
        tickPaint.strokeWidth    = strokePx

        val barHeight  = h - halfStroke * 2f
        val tickData   = listOf(
            Pair(5f  / 30f, false),   //  5% — short
            Pair(10f / 30f, true),    // 10% — long
            Pair(15f / 30f, false),   // 15% — short
            Pair(20f / 30f, true),    // 20% — long
            Pair(25f / 30f, false)    // 25% — short
        )
        tickData.forEach { (fraction, isLong) ->
            val tickY     = h - halfStroke - fraction * barHeight
            val tickLeft  = if (isLong) w * 0.12f else w * 0.25f
            val tickRight = if (isLong) w * 0.88f else w * 0.75f
            canvas.drawLine(tickLeft, tickY, tickRight, tickY, tickPaint)
        }

        // --- Outline (drawn last so it sits on top of fill and ticks) ---
        outlinePaint.strokeWidth = strokePx
        canvas.drawRoundRect(rect, radius, radius, outlinePaint)
    }
}