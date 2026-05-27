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

/**
 * SpeedOver Safety Awareness
 * Holle TechNolle, 2026
 *
 * Foreground service that owns the overlay window and GPS pipeline.
 *
 * Architecture:
 *   - Single TYPE_APPLICATION_OVERLAY window (textView) — always FLAG_NOT_TOUCHABLE,
 *     so all touches pass through to the app beneath (MIUI-safe via alpha = 0.5f trick).
 *   - GPS delivered via PendingIntent rather than LocationListener, which is more
 *     resilient to MIUI background throttling.
 *   - GPS keepalive handler re-registers GPS every N seconds as an additional safeguard.
 *   - Speed limit fetched from HERE Routing API v8 every 15 seconds when speed >= 20 km/h.
 *     Shows "00" when below threshold or when no HERE API key is configured.
 *   - Screen kept on via FLAG_KEEP_SCREEN_ON + SCREEN_DIM_WAKE_LOCK.
 *   - Auto-hide: overlay hidden after 2 minutes below 5 km/h, restored above 5 km/h.
 *   - Arrow initialised to NW (315°) at boot and on settings exit so it is always
 *     visible before GPS delivers its first bearing fix.
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
        const val BEARING_FALLBACK       = 315f
        const val TAG                    = "SpeedOver"

        var isRunning = false
    }

    private lateinit var windowManager: WindowManager
    private lateinit var prefs: Prefs
    private lateinit var locationManager: LocationManager
    private lateinit var wakeLock: PowerManager.WakeLock

    private lateinit var textView: SpeedOverlayView
    private lateinit var textParams: WindowManager.LayoutParams

    private var locationPendingIntent: PendingIntent? = null

    private var currentLat = 0.0
    private var currentLon = 0.0

    // --- Auto-hide state ---
    private var isAutoHidden     = false
    private var hideScheduled    = false
    private var isInSettingsMode = false

    private val autoHideHandler  = Handler(Looper.getMainLooper())
    private val autoHideRunnable = Runnable {
        isAutoHidden  = true
        hideScheduled = false
        if (!isInSettingsMode) {
            textParams.alpha = 0f
            windowManager.updateViewLayout(textView, textParams)
        }
    }

    // --- GPS keepalive ---
    private val gpsKeepaliveHandler  = Handler(Looper.getMainLooper())
    private val gpsKeepaliveRunnable = object : Runnable {
        override fun run() {
            stopGps(); startGps()
            gpsKeepaliveHandler.postDelayed(this, prefs.gpsKeepaliveSeconds * 1000L)
        }
    }

    // --- Speed limit fetch (every 15 seconds when speed >= 20 km/h) ---
    private val speedLimitHandler  = Handler(Looper.getMainLooper())
    private val speedLimitRunnable = object : Runnable {
        override fun run() {
            // Log.d(TAG, "speedLimitRunnable fired — speed=${textView.speedKmh} keyEmpty=${prefs.hereApiKey.isEmpty()}")
            if (textView.speedKmh >= 20f && prefs.hereApiKey.isNotEmpty()) {
                fetchSpeedLimit(currentLat, currentLon)
            } else if (textView.speedKmh < 20f) {
                textView.speedLimitKmh = 0
            }
            speedLimitHandler.postDelayed(this, 15_000)
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
                if (it.hasBearing()) textView.bearing = it.bearing
                if (kmh >= 5f) {
                    if (hideScheduled) {
                        autoHideHandler.removeCallbacks(autoHideRunnable)
                        hideScheduled = false
                    }
                    if (isAutoHidden) {
                        isAutoHidden = false
                        if (!isInSettingsMode) {
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

        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.SCREEN_DIM_WAKE_LOCK or PowerManager.ON_AFTER_RELEASE,
            "SpeedOver::ScreenWakeLock"
        )
        wakeLock.acquire()

        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        buildTextWindow()

        textView.bearing = BEARING_FALLBACK

        autoHideHandler.postDelayed(autoHideRunnable, 2 * 60 * 1000L)
        hideScheduled = true

        //startTestLoop()
        startGps()
        gpsKeepaliveHandler.postDelayed(gpsKeepaliveRunnable, prefs.gpsKeepaliveSeconds * 1000L)
        speedLimitHandler.postDelayed(speedLimitRunnable, 15_000)

        val filter = IntentFilter().apply {
            addAction(ACTION_ENTER_SETTINGS); addAction(ACTION_EXIT_SETTINGS)
            addAction(ACTION_UPDATE_PREFS);   addAction(ACTION_RESIZE)
            addAction(ACTION_MOVE)
        }
        registerReceiver(receiver, filter, RECEIVER_NOT_EXPORTED)
        registerReceiver(locationReceiver, IntentFilter(ACTION_LOCATION_UPDATE), RECEIVER_EXPORTED)

        // Log.d(TAG, "OverlayService started — hereApiKey length=${prefs.hereApiKey.length}")
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
        unregisterReceiver(receiver)
        unregisterReceiver(locationReceiver)
        try { windowManager.removeView(textView) } catch (_: Exception) {}
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // --- Overlay window ---
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
        textParams.alpha = 0.5f
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
        val testBottom     = testArrowH * 2.5f + testLimitPaint.measureText("199") / 2f
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
        textParams.alpha = 0f
        windowManager.updateViewLayout(textView, textParams)
    }

    private fun exitSettingsMode() {
        isInSettingsMode = false
        if (textView.bearing == null) textView.bearing = BEARING_FALLBACK
        textParams.alpha = if (isAutoHidden) 0f else 0.5f
        windowManager.updateViewLayout(textView, textParams)
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
        val bottomWidth = arrowH * 2.5f + limitPaint.measureText("199") / 2f
        val w  = maxOf(numberWidth, bottomWidth).toInt()
        val fm = paint.fontMetrics
        val h  = (fm.bottom - fm.top + sizePx * 0.05f + arrowGap + arrowH + sizePx * 0.05f).toInt()
        return Pair(w, h)
    }

    // --- HERE speed limit fetch ---
    private fun fetchSpeedLimit(lat: Double, lon: Double) {
        // Log.d(TAG, "fetchSpeedLimit called — lat=$lat lon=$lon key=${prefs.hereApiKey.take(8)}...")
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
                conn.requestMethod  = "GET"
                conn.connectTimeout = 5000
                conn.readTimeout    = 5000

                val code = conn.responseCode
                // Log.d(TAG, "HTTP response code: $code")

                if (code == 200) {
                    val json = conn.inputStream.bufferedReader().readText()
                    // Log.d(TAG, "Response: ${json.take(1000)}")
                    val limit = parseSpeedLimit(json)
                    // Log.d(TAG, "Parsed limit: $limit km/h")
                    Handler(Looper.getMainLooper()).post { textView.speedLimitKmh = limit }
                } else {
                    val err = conn.errorStream?.bufferedReader()?.readText() ?: "no error body"
                    Log.e(TAG, "Error response ($code): $err")
                }
                conn.disconnect()
            } catch (e: Exception) {
                Log.e(TAG, "Exception in fetchSpeedLimit: ${e.message}", e)
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
            // speedLimit is a direct number (m/s), not a nested object
            val valueMs = spans.getJSONObject(0).optDouble("speedLimit", 0.0)
            if (valueMs == 0.0) return 0
            (valueMs * 3.6).toInt()
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

    private fun startTestLoop() {
        var toggle = false
        testHandler = Handler(Looper.getMainLooper())
        testHandler?.post(object : Runnable {
            override fun run() {
                textView.speedKmh = if (toggle) 1f else 0f
                toggle = !toggle
                testHandler?.postDelayed(this, 1000)
            }
        })
    }
}