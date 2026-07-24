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
 * Manages three overlay windows:
 *
 *   textView       — speed number, speed limit, direction arrow.
 *                    MIUI click-through via alpha = 0.5f.
 *                    Locked to alpha = 1.0f when violation > 10%.
 *
 *   violationView  — ViolationGradient: yellow→red thermometer bar, left side of screen.
 *                    Fixed position and size. Always fully opaque when visible.
 *
 *   roadInfoView   — Road name (top) and road type (bottom), centred, bottom of screen.
 *                    Follows same alpha as textView.
 *
 * Road data from OpenStreetMap via Overpass API — free, no key required.
 * Query: way['highway'] — returns all road attributes including maxspeed when present.
 * Speed limit: explicit maxspeed tag preferred; falls back to per-type prefs values.
 * Road priority: motorway > trunk > primary > secondary > tertiary > unclassified > residential.
 * Data timeout: all OSM-derived values cleared after 30 seconds without a successful fetch.
 *
 * GPS via PendingIntent — resilient to MIUI background throttling.
 * Screen kept on via FLAG_KEEP_SCREEN_ON + SCREEN_DIM_WAKE_LOCK.
 * Auto-hide: all windows hidden after 15 seconds below 10 km/h.
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

        const val AUTO_HIDE_DELAY_MS      = 15_000L
        const val AUTO_HIDE_THRESHOLD_KMH = 10f
        // All OSM-derived values cleared after this delay without a successful fetch
        const val DATA_TIMEOUT_MS         = 30_000L

        // Road type priority — lower number = higher priority
        val HIGHWAY_PRIORITY = mapOf(
            "motorway"          to 0,
            "motorway_link"     to 1,
            "trunk"             to 2,
            "trunk_link"        to 3,
            "primary"           to 4,
            "primary_link"      to 5,
            "secondary"         to 6,
            "secondary_link"    to 7,
            "tertiary"          to 8,
            "tertiary_link"     to 9,
            "unclassified"      to 10,
            "residential"       to 11,
            "living_street"     to 12
        )

        var isRunning = false
    }

    // Road info data container
    private data class RoadInfo(
        val highway: String,   // OSM highway type, e.g. "motorway"
        val name: String,      // Road name, may be empty
        val speedLimit: Int    // 0 = unknown/disabled
    )

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

    // Window 3 — Road name and road type (bottom of screen, full width)
    private lateinit var roadInfoView: RoadInfoView
    private lateinit var roadInfoParams: WindowManager.LayoutParams

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
            textParams.alpha      = 0f
            violationParams.alpha = 0f
            roadInfoParams.alpha  = 0f
            windowManager.updateViewLayout(textView, textParams)
            windowManager.updateViewLayout(violationView, violationParams)
            windowManager.updateViewLayout(roadInfoView, roadInfoParams)
        }
    }

    // --- Data timeout — clears all OSM-derived data after 30 s without a successful fetch ---
    private val dataTimeoutHandler  = Handler(Looper.getMainLooper())
    private val dataTimeoutRunnable = Runnable {
        textView.speedLimitKmh  = 0
        roadInfoView.roadName   = ""
        roadInfoView.roadType   = ""
    }

    // --- GPS keepalive ---
    private val gpsKeepaliveHandler  = Handler(Looper.getMainLooper())
    private val gpsKeepaliveRunnable = object : Runnable {
        override fun run() {
            stopGps(); startGps()
            gpsKeepaliveHandler.postDelayed(this, prefs.gpsKeepaliveSeconds * 1000L)
        }
    }

    // --- Road info fetch (interval from prefs, only when speed >= 20 km/h) ---
    private val speedLimitHandler  = Handler(Looper.getMainLooper())
    private val speedLimitRunnable = object : Runnable {
        override fun run() {
            if (textView.speedKmh >= 20f) {
                fetchRoadInfo(currentLat, currentLon)
            }
            // Always re-evaluate violation state on each tick
            updateViolationView(textView.speedKmh, textView.speedLimitKmh)
            speedLimitHandler.postDelayed(this, prefs.speedLimitIntervalSeconds * 1000L)
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

                if (kmh >= AUTO_HIDE_THRESHOLD_KMH) {
                    if (hideScheduled) {
                        autoHideHandler.removeCallbacks(autoHideRunnable)
                        hideScheduled = false
                    }
                    if (isAutoHidden) {
                        isAutoHidden = false
                        if (!isInSettingsMode) {
                            // Restore textView and roadInfoView —
                            // violationView alpha set by updateViolationView below
                            textParams.alpha     = 0.5f
                            roadInfoParams.alpha = 0.5f
                            windowManager.updateViewLayout(textView, textParams)
                            windowManager.updateViewLayout(roadInfoView, roadInfoParams)
                        }
                    }
                } else {
                    if (!hideScheduled && !isAutoHidden) {
                        autoHideHandler.postDelayed(autoHideRunnable, AUTO_HIDE_DELAY_MS)
                        hideScheduled = true
                    }
                }

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

        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.SCREEN_DIM_WAKE_LOCK or PowerManager.ON_AFTER_RELEASE,
            "SpeedOver::ScreenWakeLock"
        )
        wakeLock.acquire()

        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        buildTextWindow()
        buildViolationWindow()
        buildRoadInfoWindow()

        textView.bearing = BEARING_FALLBACK

        autoHideHandler.postDelayed(autoHideRunnable, AUTO_HIDE_DELAY_MS)
        hideScheduled = true

        // startTestLoop()
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

        Log.d(TAG, "OverlayService started")
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
        dataTimeoutHandler.removeCallbacks(dataTimeoutRunnable)
        testHandler?.removeCallbacksAndMessages(null)
        unregisterReceiver(receiver)
        unregisterReceiver(locationReceiver)
        try { windowManager.removeView(textView)      } catch (_: Exception) {}
        try { windowManager.removeView(violationView) } catch (_: Exception) {}
        try { windowManager.removeView(roadInfoView)  } catch (_: Exception) {}
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
        textParams.alpha = 0.5f
        windowManager.updateViewLayout(textView, textParams)
    }

    // --- Violation gradient window ---
    // Fixed position and size — independent of user text-size settings.
    // Left side of screen, spanning 25%–75% of screen height.
    private fun buildViolationWindow() {
        val dm = resources.displayMetrics
        val vx = (dm.widthPixels  * 0.02f).toInt()
        val vy = (dm.heightPixels * 0.25f).toInt()
        val vw = (dm.widthPixels  * 0.10f).toInt()
        val vh = (dm.heightPixels * 0.50f).toInt()

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
        violationParams.alpha = 0f
        windowManager.updateViewLayout(violationView, violationParams)
    }

    // --- Road info window ---
    // Full screen width, anchored at bottom of screen.
    // Height: fixed 12% of screen — accommodates two lines at any text size.
    private fun buildRoadInfoWindow() {
        val dm = resources.displayMetrics
        val rw = dm.widthPixels
        val rh = (dm.heightPixels * 0.12f).toInt()
        val ry = dm.heightPixels - rh

        roadInfoView = RoadInfoView(this)
        applyRoadInfoPrefs()

        roadInfoParams = WindowManager.LayoutParams(
            rw, rh, 0, ry,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE  or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE  or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START }
        windowManager.addView(roadInfoView, roadInfoParams)
        roadInfoParams.alpha = 0.5f
        windowManager.updateViewLayout(roadInfoView, roadInfoParams)
    }

    // --- ViolationGradient logic ---
    private fun updateViolationView(speedKmh: Float, limitKmh: Int) {
        if (isAutoHidden || isInSettingsMode) return

        if (limitKmh <= 0 || speedKmh <= limitKmh + 3f) {
            // Below threshold — hide vioGrad, restore normal alphas
            violationView.progress = 0f
            violationParams.alpha  = 0f
            textParams.alpha       = 0.5f
            roadInfoParams.alpha   = 0.5f
            windowManager.updateViewLayout(violationView, violationParams)
            windowManager.updateViewLayout(textView, textParams)
            windowManager.updateViewLayout(roadInfoView, roadInfoParams)
            return
        }

        val violation = (speedKmh - limitKmh) / limitKmh
        violationView.progress = (violation / 0.30f).coerceIn(0f, 1f)
        violationParams.alpha  = 1.0f
        windowManager.updateViewLayout(violationView, violationParams)

        // > 10% over: lock both overlays fully opaque
        val targetAlpha = if (violation > 0.10f) 1.0f else 0.5f
        textParams.alpha     = targetAlpha
        roadInfoParams.alpha = targetAlpha
        windowManager.updateViewLayout(textView, textParams)
        windowManager.updateViewLayout(roadInfoView, roadInfoParams)
    }

    private fun applyPrefs() {
        textView.fillColor         = prefs.fillColor
        textView.strokeColor       = prefs.strokeColor
        textView.textAlpha         = prefs.textAlpha
        textView.textSizePx        = prefs.textSizePx
        textView.strokeWidthFactor = prefs.strokeWidth
        // roadInfoView may not be initialised yet during buildTextWindow()
        if (::roadInfoView.isInitialized) applyRoadInfoPrefs()
    }

    private fun applyRoadInfoPrefs() {
        roadInfoView.fillColor         = prefs.fillColor
        roadInfoView.strokeColor       = prefs.strokeColor
        roadInfoView.textAlpha         = prefs.textAlpha
        roadInfoView.strokeWidthFactor = prefs.strokeWidth
        // Font size: half of talLim size
        roadInfoView.fontSizePx        = prefs.textSizePx * 0.38f * 0.75f * 0.5f
        roadInfoView.showName          = prefs.showRoadName
        roadInfoView.showType          = prefs.showRoadType
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
        // Update road info font size to match new text size
        roadInfoView.fontSizePx = newSize * 0.38f * 0.75f * 0.5f

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
        textParams.alpha      = 0f
        violationParams.alpha = 0f
        roadInfoParams.alpha  = 0f
        windowManager.updateViewLayout(textView, textParams)
        windowManager.updateViewLayout(violationView, violationParams)
        windowManager.updateViewLayout(roadInfoView, roadInfoParams)
    }

    private fun exitSettingsMode() {
        isInSettingsMode = false
        if (textView.bearing == null) textView.bearing = BEARING_FALLBACK
        val alpha = if (isAutoHidden) 0f else 0.5f
        textParams.alpha     = alpha
        roadInfoParams.alpha = alpha
        windowManager.updateViewLayout(textView, textParams)
        windowManager.updateViewLayout(roadInfoView, roadInfoParams)
        if (!hideScheduled && !isAutoHidden) {
            autoHideHandler.postDelayed(autoHideRunnable, AUTO_HIDE_DELAY_MS)
            hideScheduled = true
        }
    }

    private fun calcOverlaySize(sizePx: Float): Pair<Int, Int> {
        val paint = Paint().apply { textSize = sizePx }
        val numberWidth = paint.measureText("1") + paint.measureText("00") + sizePx * 0.08f
        val arrowH      = sizePx * 0.38f
        val arrowGap    = arrowH * 0.3f
        val limitPaint  = Paint().apply { textSize = arrowH * 0.75f }
        val bottomWidth = arrowH * 1.25f + limitPaint.measureText("199") / 2f + arrowH * 1.75f
        val w  = maxOf(numberWidth, bottomWidth).toInt()
        val fm = paint.fontMetrics
        val h  = (fm.bottom - fm.top + sizePx * 0.05f + arrowGap + arrowH + sizePx * 0.05f).toInt()
        return Pair(w, h)
    }

    // --- Overpass road info fetch ---
    private fun fetchRoadInfo(lat: Double, lon: Double) {
        val radius = prefs.overpassRadiusMeters
        Thread {
            try {
                // Query all highway types — maxspeed returned when present via out tags
                val query   = "[out:json][timeout:5];way['highway'](around:$radius,$lat,$lon);out tags;"
                val encoded = java.net.URLEncoder.encode(query, "UTF-8")
                val url     = java.net.URL("https://overpass-api.de/api/interpreter")
                val conn    = url.openConnection() as java.net.HttpURLConnection
                conn.requestMethod  = "POST"
                conn.doOutput       = true
                conn.connectTimeout = 5000   // matches Overpass [timeout:5]
                conn.readTimeout    = 5000
                conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                conn.outputStream.bufferedWriter().use { it.write("data=$encoded") }

                if (conn.responseCode == 200) {
                    val body = conn.inputStream.bufferedReader().readText()
                    val info = parseRoadInfo(body)
                    Handler(Looper.getMainLooper()).post {
                        if (info != null) {
                            textView.speedLimitKmh = info.speedLimit
                            roadInfoView.roadName  = info.name
                            roadInfoView.roadType  = info.highway
                            // Reset timeout — we have fresh data
                            resetDataTimeout()
                        }
                        // No roads found: let existing timeout handle expiry
                    }
                } else {
                    // HTTP error — clear immediately, don't wait for timeout
                    Log.e(TAG, "Overpass HTTP error (${conn.responseCode})")
                    Handler(Looper.getMainLooper()).post { clearOsmData() }
                }
                conn.disconnect()
            } catch (e: Exception) {
                // Timeout or network error — clear immediately
                Log.e(TAG, "fetchRoadInfo exception: ${e.message}")
                Handler(Looper.getMainLooper()).post { clearOsmData() }
            }
        }.start()
    }

    private fun clearOsmData() {
        dataTimeoutHandler.removeCallbacks(dataTimeoutRunnable)
        textView.speedLimitKmh = 0
        roadInfoView.roadName  = ""
        roadInfoView.roadType  = ""
    }

    private fun resetDataTimeout() {
        dataTimeoutHandler.removeCallbacks(dataTimeoutRunnable)
        dataTimeoutHandler.postDelayed(dataTimeoutRunnable, DATA_TIMEOUT_MS)
    }

    private fun parseRoadInfo(json: String): RoadInfo? {
        return try {
            val elements = JSONObject(json).getJSONArray("elements")
            if (elements.length() == 0) return null

            // Select the highest-priority road type from all returned elements
            var bestTags: org.json.JSONObject? = null
            var bestPriority = Int.MAX_VALUE

            for (i in 0 until elements.length()) {
                val tags     = elements.getJSONObject(i).optJSONObject("tags") ?: continue
                val hw       = tags.optString("highway", "")
                val priority = HIGHWAY_PRIORITY[hw] ?: 99
                if (priority < bestPriority) {
                    bestPriority = priority
                    bestTags     = tags
                }
            }

            val tags    = bestTags ?: return null
            val highway = tags.optString("highway", "")
            val name    = tags.optString("name", "")
            val rawMax  = tags.optString("maxspeed", "").trim()

            val speedLimit = if (rawMax.isNotEmpty()) {
                parseMaxspeedString(rawMax)
            } else {
                speedLimitForHighway(highway)
            }

            RoadInfo(highway, name, speedLimit)
        } catch (e: Exception) {
            Log.e(TAG, "parseRoadInfo exception: ${e.message}")
            null
        }
    }

    private fun parseMaxspeedString(value: String): Int {
        return when (value.lowercase()) {
            "none", "unlimited" -> 0
            "dk:urban"          -> 50
            "dk:rural"          -> 80
            "dk:motorway"       -> 130
            else -> {
                if (value.contains("mph", ignoreCase = true)) {
                    val mph = value.replace("mph", "", ignoreCase = true).trim().toDoubleOrNull() ?: 0.0
                    (mph * 1.60934).roundToInt()
                } else {
                    value.toIntOrNull() ?: 0
                }
            }
        }
    }

    // Returns fallback speed limit from prefs for a given OSM highway type.
    // 0 = disabled for this road type.
    private fun speedLimitForHighway(highway: String): Int = when (highway) {
        "motorway"                       -> prefs.limitMotorway
        "motorway_link"                  -> prefs.limitMotorwayLink
        "trunk"                          -> prefs.limitTrunk
        "trunk_link"                     -> prefs.limitTrunkLink
        "primary", "primary_link"        -> prefs.limitPrimary
        "secondary", "secondary_link"    -> prefs.limitSecondary
        "tertiary", "tertiary_link"      -> prefs.limitTertiary
        "unclassified"                   -> prefs.limitUnclassified
        "residential"                    -> prefs.limitResidential
        "living_street"                  -> prefs.limitLivingStreet
        else                             -> 0
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

    // Breath test — oscillates vioGrad for rendering verification.
    // Uncomment startTestLoop() in onCreate() to activate. Remove before release.
    private fun startTestLoop() {
        val startTime = System.currentTimeMillis()
        testHandler = Handler(Looper.getMainLooper())
        testHandler?.post(object : Runnable {
            override fun run() {
                val phase = ((System.currentTimeMillis() - startTime) % 5000L).toFloat() / 5000f
                val wave  = if (phase < 0.5f) phase * 2f else (1f - phase) * 2f
                val fakeLimit = 50f
                updateViolationView(fakeLimit * (1f + wave * 0.35f), fakeLimit.toInt())
                testHandler?.postDelayed(this, 50L)
            }
        })
    }
}

// =============================================================================
// ViolationGradient view
// =============================================================================
/**
 * Thermometer-style bar: blue outline at full height, yellow→red fill growing upward.
 * progress = 0..1 where 1 = 30% overspeed.
 */
class ViolationView(context: Context) : View(context) {

    var progress: Float = 0f
        set(value) { field = value; invalidate() }

    private val strokePx = 2f * resources.displayMetrics.density

    private val fillPaint    = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val outlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; color = Color.rgb(0, 0, 255)
        strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND
    }
    private val tickPaint    = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; color = Color.rgb(0, 0, 255); strokeCap = Paint.Cap.ROUND
    }

    override fun onDraw(canvas: Canvas) {
        if (progress <= 0f) return

        val w          = width.toFloat()
        val h          = height.toFloat()
        val halfStroke = strokePx / 2f
        val radius     = w * 0.25f
        val rect       = RectF(halfStroke, halfStroke, w - halfStroke, h - halfStroke)

        // Gradient fill — grows from bottom upward
        val barTop = h - progress * (h - halfStroke * 2f) - halfStroke
        fillPaint.shader = LinearGradient(
            0f, halfStroke, 0f, h - halfStroke,
            Color.RED, Color.YELLOW, Shader.TileMode.CLAMP
        )
        canvas.save()
        canvas.clipPath(Path().apply { addRoundRect(rect, radius, radius, Path.Direction.CW) })
        canvas.drawRect(halfStroke, barTop, w - halfStroke, h - halfStroke, fillPaint)
        canvas.restore()

        // Tick marks — long at 10%/20%, short at 5%/15%/25%
        outlinePaint.strokeWidth = strokePx
        tickPaint.strokeWidth    = strokePx
        val barHeight = h - halfStroke * 2f
        listOf(5f/30f to false, 10f/30f to true, 15f/30f to false, 20f/30f to true, 25f/30f to false)
            .forEach { (fraction, isLong) ->
                val tickY = h - halfStroke - fraction * barHeight
                canvas.drawLine(
                    if (isLong) w * 0.12f else w * 0.25f, tickY,
                    if (isLong) w * 0.88f else w * 0.75f, tickY,
                    tickPaint
                )
            }

        // Outline drawn last so it sits on top of fill and ticks
        canvas.drawRoundRect(rect, radius, radius, outlinePaint)
    }
}

// =============================================================================
// Road info view
// =============================================================================
/**
 * Shows road name (top line) and road type (bottom line), centred, at the
 * bottom of the screen. Each line is independently toggled via showName / showType.
 * Font size = half of talLim, set externally via fontSizePx.
 */
class RoadInfoView(context: Context) : View(context) {

    var roadName: String = ""
        set(value) { field = value; invalidate() }
    var roadType: String = ""
        set(value) { field = value; invalidate() }
    var showName: Boolean = true
        set(value) { field = value; invalidate() }
    var showType: Boolean = true
        set(value) { field = value; invalidate() }
    var fillColor: Int = Color.WHITE
        set(value) { field = value; invalidate() }
    var strokeColor: Int = Color.BLACK
        set(value) { field = value; invalidate() }
    var textAlpha: Int = 255
        set(value) { field = value; invalidate() }
    var strokeWidthFactor: Float = 3f
        set(value) { field = value; invalidate() }
    var fontSizePx: Float = 40f
        set(value) { field = value; invalidate() }

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.DEFAULT_BOLD; textAlign = Paint.Align.CENTER; style = Paint.Style.FILL
    }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.DEFAULT_BOLD; textAlign = Paint.Align.CENTER; style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
    }

    override fun onDraw(canvas: Canvas) {
        val lines = mutableListOf<String>()
        if (showName && roadName.isNotEmpty()) lines.add(roadName)
        if (showType && roadType.isNotEmpty()) lines.add(roadType)
        if (lines.isEmpty()) return

        val cx    = width / 2f
        val lineH = fontSizePx * 1.35f
        val totalH = lines.size * lineH
        var y = (height - totalH) / 2f + fontSizePx * 0.85f

        fillPaint.apply   { textSize = fontSizePx; color = fillColor;   alpha = textAlpha }
        strokePaint.apply { textSize = fontSizePx; color = strokeColor; alpha = textAlpha; strokeWidth = strokeWidthFactor * 0.4f }

        for (line in lines) {
            canvas.drawText(line, cx, y, strokePaint)
            canvas.drawText(line, cx, y, fillPaint)
            y += lineH
        }
    }
}