// OverlayService.kt
package com.example.speedover

import android.Manifest
import android.annotation.SuppressLint
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
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
 * Foreground service owning all three overlay windows and the OSM data pipeline.
 *
 * Windows:
 *   textView       — speed number, speed limit, direction arrow.
 *                    MIUI click-through via alpha = 0.5f.
 *                    Locked to alpha = 1.0f when violation > 10%.
 *   violationView  — ViolationGradient: yellow→red thermometer bar, left side.
 *                    Fixed position and size. Fully opaque when visible.
 *   roadInfoView   — Road ref (top), name (middle), type (bottom), bottom-centred.
 *
 * Threading model:
 *   - Views are only ever touched on the UI thread. Worker threads post back via
 *     Handler(Looper.getMainLooper()).
 *   - Network calls (fetchRoadInfo, checkInstanceHealth) run on their own threads.
 *   - Debug log writes run on a dedicated single-thread executor, never on the UI
 *     thread — file I/O there caused visible stutter in the overlay.
 *
 * Hard limit: when prefs.hardLimit > 0, the displayed limit is min(hardLimit, osmLimit)
 * when OSM has a value, or hardLimit alone when OSM has none. Hard limit acts as a
 * ceiling: genuine lower zone limits (e.g. 50 in town) still show through, but the
 * displayed value never exceeds it. The superscript asterisk appears only when hard
 * limit is the value actually binding the display.
 *
 * Stickiness — the central rule of this file:
 *   OSM-derived values (limit, name, type, ref) are only ever replaced by a NEW
 *   valid value from a successful query. Nothing else clears them. Not an empty
 *   result, not a timeout, not an HTTP error code. An HTTP 429 means "you are
 *   asking too often" — it says nothing about the road you are on, so it must not
 *   wipe the display. Data survives until we genuinely learn we are somewhere else.
 *
 * Road data from OpenStreetMap via Overpass API — free, no key required.
 * Query: way['highway'] — returns maxspeed, name and ref when the way carries them.
 * Speed limit: explicit maxspeed tag preferred; falls back to per-type prefs values.
 * Road priority: motorway > trunk > primary > secondary > tertiary > unclassified > residential.
 *
 * Overpass instance rotation: three genuinely independent instances (separate
 * infrastructure, separate rate limiting). Never the same instance twice in a row
 * unless it is the only one alive. Any non-200 HTTP response marks that instance
 * dead until the next health check; a network timeout does NOT, since a timeout
 * says nothing about whether the server is up. Health is checked at startup and
 * every 20 minutes (all three instances). When every instance is currently dead,
 * fetchRoadInfo does not probe all three again — that would triple our request
 * volume during an outage that is already in progress. Instead it probes exactly
 * one instance (round-robin) and backs off on an escalating schedule (20s, 40s,
 * 60s, 2m, capping at 5m) until something answers.
 *
 * Instance cooldown: five consecutive non-200 responses from the same instance,
 * with no success in between, parks it for two hours — no real queries, no health
 * probes, nothing. Timeouts do not count towards this; only real HTTP refusals do,
 * since a timeout says nothing about whether the server wants our traffic. Any 200
 * clears both the streak and the parking immediately. This exists because two of
 * the three instances answered 429 to every single request for an entire day while
 * still being re-probed on every sweep and every backoff tick.
 *
 * Timeout budget: the Overpass server-side [timeout:N] and the client-side
 * connect/read timeouts are deliberately NOT equal. If both are 5s, a server that
 * takes its full allotted 5s to compute an answer always loses the race against a
 * client that gives up at exactly 5s, before network latency is even accounted
 * for — this was the single largest source of OVERPASS_EXCEPTION entries in the
 * field log. Server timeout is now 3s (which also reduces load on a busy public
 * server) and the client waits up to 10s for the response body, leaving comfortable
 * margin for mobile network latency on top of the server's own budget.
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

        // A GPS fix older than this is considered unusable for an Overpass query —
        // without it, a stalled GPS (tunnel, MIUI throttling) would leave speedKmh
        // frozen above the fetch threshold and we would hammer Overpass with the
        // same stale coordinates indefinitely.
        const val MAX_FIX_AGE_MS = 15_000L

        // Wake lock safety net: released explicitly in onDestroy, but a timeout
        // guarantees it cannot leak if the process is killed in an unusual way.
        const val WAKE_LOCK_TIMEOUT_MS = 4L * 60L * 60L * 1000L  // 4 hours

        // Debug log is trimmed only every N writes — trimming reads and rewrites
        // the whole file, which is far too expensive to do on every line.
        const val LOG_MAX_LINES     = 2000
        const val LOG_TRIM_INTERVAL = 100

        // An instance that answers with a non-200 status this many times in a row,
        // with no success in between, is not having a bad minute — it is refusing
        // us. The field log showed kumi.systems and private.coffee returning 429 to
        // every single request across an entire day, yet still being re-probed every
        // 20 minutes and on every backoff tick. Parking them for a long stretch cuts
        // that noise without giving up on them permanently.
        const val HTTP_FAIL_COOLDOWN_THRESHOLD = 5
        const val INSTANCE_COOLDOWN_MS = 2L * 60L * 60L * 1000L  // 2 hours

        // Road type priority — lower number = higher priority
        val HIGHWAY_PRIORITY = mapOf(
            "motorway"       to 0,  "motorway_link"  to 1,
            "trunk"          to 2,  "trunk_link"     to 3,
            "primary"        to 4,  "primary_link"   to 5,
            "secondary"      to 6,  "secondary_link" to 7,
            "tertiary"       to 8,  "tertiary_link"  to 9,
            "unclassified"   to 10, "residential"    to 11,
            "living_street"  to 12
        )

        var isRunning = false
    }

    private data class RoadInfo(
        val highway: String,
        val name: String,
        val ref: String,
        val speedLimit: Int
    )

    // --- Overpass instance rotation ---
    private data class OverpassInstance(val name: String, val url: String)

    private val overpassInstances = listOf(
        OverpassInstance("overpass-api.de",  "https://overpass-api.de/api/interpreter"),
        OverpassInstance("kumi.systems",     "https://overpass.kumi.systems/api/interpreter"),
        OverpassInstance("private.coffee",   "https://overpass.private.coffee/api/interpreter")
    )

    // Optimistically alive until proven otherwise — avoids blocking the first real
    // queries behind the startup health check. Written from worker threads, read
    // from the UI thread, hence ConcurrentHashMap.
    private val instanceAlive = java.util.concurrent.ConcurrentHashMap<String, Boolean>().apply {
        overpassInstances.forEach { put(it.url, true) }
    }
    private var lastUsedInstanceUrl: String? = null

    // --- Per-instance cooldown after repeated refusals ---
    // Counts consecutive non-200 HTTP responses per instance. Deliberately counts
    // only real HTTP answers: a timeout tells us nothing about whether the server
    // wants our traffic, so it neither increments nor resets this. Any 200 clears it.
    private val consecutiveHttpFailures = java.util.concurrent.ConcurrentHashMap<String, Int>()

    // elapsedRealtime after which a parked instance may be tried again.
    // Absent or in the past = not parked.
    private val cooldownUntilMs = java.util.concurrent.ConcurrentHashMap<String, Long>()

    // Runs on: any thread
    private fun isInCooldown(url: String): Boolean =
        SystemClock.elapsedRealtime() < (cooldownUntilMs[url] ?: 0L)

    // Records a real HTTP refusal and parks the instance once the streak is long
    // enough to be a pattern rather than a blip.
    // Runs on: fetch worker thread or probe worker thread
    private fun noteHttpFailure(instance: OverpassInstance, code: Int) {
        val streak = (consecutiveHttpFailures[instance.url] ?: 0) + 1
        consecutiveHttpFailures[instance.url] = streak
        if (streak >= HTTP_FAIL_COOLDOWN_THRESHOLD && !isInCooldown(instance.url)) {
            cooldownUntilMs[instance.url] = SystemClock.elapsedRealtime() + INSTANCE_COOLDOWN_MS
            logDebug("COOLDOWN ${instance.name} parked for ${INSTANCE_COOLDOWN_MS / 60_000}min after $streak consecutive http=$code")
        }
    }

    // Runs on: fetch worker thread or probe worker thread
    private fun noteHttpSuccess(instance: OverpassInstance) {
        consecutiveHttpFailures[instance.url] = 0
        cooldownUntilMs.remove(instance.url)
    }

    // Guards against overlapping health check runs — the periodic 20-minute sweep
    // and the ad-hoc single-instance probe (triggered when everything is dead) both
    // call in here from different threads, so a plain Volatile check-then-set is not
    // enough: AtomicBoolean.compareAndSet makes the guard itself race-free.
    private val healthCheckInProgress = java.util.concurrent.atomic.AtomicBoolean(false)

    // Guards against overlapping road-info fetches. Overpass rate-limits on
    // CONCURRENT connections per IP, so two in-flight queries can trigger the very
    // 429s we are trying to avoid. At a 5 s interval with a 5 s timeout this is a
    // real possibility, not a theoretical one.
    @Volatile private var fetchInProgress = false

    // --- Backoff for the "every instance is dead" case ---
    // Escalating delay between ad-hoc probe attempts. Without this, a genuine
    // outage affecting all three instances turns into a fourth source of request
    // volume stacked on top of the outage — the field log showed ALL_DEAD firing
    // every ~20 s (one fetch-loop tick) for minutes at a stretch, each time probing
    // all three instances at once. The ad-hoc path now probes exactly one instance
    // (round-robin) and only as often as this schedule allows.
    private val ALL_DEAD_BACKOFF_MS = longArrayOf(20_000L, 40_000L, 60_000L, 120_000L, 300_000L)
    private var allDeadBackoffIndex = 0
    private var nextAllDeadProbeAtMs = 0L
    private var allDeadProbeRotationIndex = 0

    // Runs on: whichever thread discovers a live instance (UI thread via the
    // periodic sweep, or a probe worker thread via the ad-hoc path)
    private fun resetAllDeadBackoff() {
        allDeadBackoffIndex = 0
        nextAllDeadProbeAtMs = 0L
    }

    private val instanceHealthHandler  = Handler(Looper.getMainLooper())
    private val instanceHealthRunnable = object : Runnable {
        // Runs on: UI thread (schedules work onto a worker thread)
        override fun run() {
            checkInstanceHealth()
            instanceHealthHandler.postDelayed(this, 20 * 60 * 1000L) // 20 minutes
        }
    }

    private lateinit var windowManager: WindowManager
    private lateinit var prefs: Prefs
    private lateinit var locationManager: LocationManager
    private var wakeLock: PowerManager.WakeLock? = null

    private lateinit var textView: SpeedOverlayView
    private lateinit var textParams: WindowManager.LayoutParams

    private lateinit var violationView: ViolationView
    private lateinit var violationParams: WindowManager.LayoutParams

    private lateinit var roadInfoView: RoadInfoView
    private lateinit var roadInfoParams: WindowManager.LayoutParams

    private var locationPendingIntent: PendingIntent? = null
    private var currentLat = 0.0
    private var currentLon = 0.0

    // elapsedRealtime of the last GPS fix — used to reject stale positions before
    // spending an Overpass query on them. 0 = no fix received yet.
    private var lastFixElapsedMs = 0L

    // Last OSM-derived limit. Sticky: only overwritten by a new non-zero value.
    private var lastOsmLimit = 0

    // --- Auto-hide state ---
    private var isAutoHidden     = false
    private var hideScheduled    = false
    private var isInSettingsMode = false

    private val autoHideHandler  = Handler(Looper.getMainLooper())
    private val autoHideRunnable = Runnable {
        // Runs on: UI thread. Fired 15 s after speed last dropped below 10 km/h.
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

    // --- GPS keepalive ---
    private val gpsKeepaliveHandler  = Handler(Looper.getMainLooper())
    private val gpsKeepaliveRunnable = object : Runnable {
        // Runs on: UI thread. Re-registers GPS periodically as a defence against
        // MIUI silently dropping our location updates.
        override fun run() {
            stopGps(); startGps()
            gpsKeepaliveHandler.postDelayed(this, prefs.gpsKeepaliveSeconds * 1000L)
        }
    }

    // --- Debug logging ---
    // All log writes are funnelled onto a single background thread. Writing from the
    // UI thread (which is where the fetch callbacks land) caused frame drops in the
    // overlay: appendText + readLines + writeText on a 2000-line file is roughly
    // 200 KB of I/O per call. The executor also serialises writes, so no locking needed.
    private val logExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()
    private val logDateFormat = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US)
    private var logWriteCount = 0   // only touched on the log executor thread

    // Callable from any thread. Timestamp is taken at call time so ordering reflects
    // when the event happened, not when the write was flushed.
    private fun logDebug(line: String) {
        if (!prefs.enableDebugLog) return
        val timestamp = logDateFormat.format(java.util.Date())
        logExecutor.execute {
            // Runs on: log executor thread
            try {
                val file = java.io.File(filesDir, "speedover_debug.log")
                file.appendText("$timestamp | $line\n")
                // Trim rarely — reading the whole file back on every line was the
                // expensive part. Between trims the file may exceed LOG_MAX_LINES
                // by up to LOG_TRIM_INTERVAL lines, which is harmless.
                if (++logWriteCount >= LOG_TRIM_INTERVAL) {
                    logWriteCount = 0
                    val lines = file.readLines()
                    if (lines.size > LOG_MAX_LINES) {
                        file.writeText(lines.takeLast(LOG_MAX_LINES).joinToString("\n") + "\n")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "logDebug exception: ${e.message}")
            }
        }
    }

    // One HTTP round-trip to one instance, shaped like a real query (same tiny
    // radius trick as before) so the result is representative of what real traffic
    // would get. Returns the reason alongside the boolean — "alive=false" alone
    // does not distinguish a 429 from a timeout from a TLS failure, and that
    // distinction is exactly what explained why kumi.systems and private.coffee
    // never recovered: they were being rejected, not merely slow.
    // Runs on: caller's thread (this makes a blocking network call — always invoke
    // it from inside a worker Thread, never from the UI thread)
    private fun probeInstance(instance: OverpassInstance, lat: Double, lon: Double): Pair<Boolean, String> {
        var conn: java.net.HttpURLConnection? = null
        return try {
            val query   = "[out:json][timeout:3];way['highway'](around:5,$lat,$lon);out tags;"
            val encoded = java.net.URLEncoder.encode(query, "UTF-8")
            conn = (java.net.URL(instance.url).openConnection() as java.net.HttpURLConnection).apply {
                requestMethod  = "POST"
                doOutput       = true
                connectTimeout = 5000
                readTimeout    = 10000
                setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            }
            conn.outputStream.bufferedWriter().use { it.write("data=$encoded") }
            val code = conn.responseCode
            (code == 200) to "http=$code"
        } catch (e: Exception) {
            false to "exception=${e.message ?: "unknown"}"
        } finally {
            try { conn?.disconnect() } catch (_: Exception) {}
        }
    }

    // Sweeps every candidate instance that is not parked. Runs at startup and every
    // 20 minutes. A parked instance is skipped entirely — no request at all, which
    // is the whole point of parking it.
    // Runs on: its own worker thread (one thread for all probes, sequentially)
    private fun checkInstanceHealth() {
        if (!healthCheckInProgress.compareAndSet(false, true)) return
        val lat = currentLat
        val lon = currentLon
        Thread {
            overpassInstances.forEach { instance ->
                if (isInCooldown(instance.url)) {
                    val remainMin = ((cooldownUntilMs[instance.url] ?: 0L) - SystemClock.elapsedRealtime()) / 60_000
                    logDebug("HEALTHCHECK ${instance.name} skipped, cooldown ${remainMin}min remaining")
                    return@forEach
                }
                val (alive, reason) = probeInstance(instance, lat, lon)
                instanceAlive[instance.url] = alive
                if (alive) {
                    noteHttpSuccess(instance)
                    resetAllDeadBackoff()
                } else if (reason.startsWith("http=")) {
                    val code = reason.removePrefix("http=").toIntOrNull() ?: 0
                    noteHttpFailure(instance, code)
                }
                logDebug("HEALTHCHECK ${instance.name} alive=$alive $reason")
            }
            healthCheckInProgress.set(false)
        }.start()
    }

    // Probes exactly ONE instance (round-robin across calls), used only from the
    // ALL_DEAD path in fetchRoadInfo. Deliberately not the same as checkInstanceHealth
    // above, which probes all three — during an outage that is already in progress,
    // tripling the request volume to "check if it's over yet" makes the outage worse
    // for everyone, us included.
    // Runs on: its own worker thread
    private fun probeSingleInstanceAdHoc() {
        if (!healthCheckInProgress.compareAndSet(false, true)) return

        // Only rotate through instances that are not parked. If every instance is
        // parked there is nothing useful to probe, so release the guard and wait for
        // a cooldown to expire.
        val candidates = overpassInstances.filter { !isInCooldown(it.url) }
        if (candidates.isEmpty()) {
            logDebug("HEALTHCHECK_ADHOC skipped, all instances in cooldown")
            healthCheckInProgress.set(false)
            return
        }
        val instance = candidates[allDeadProbeRotationIndex % candidates.size]
        allDeadProbeRotationIndex++
        val lat = currentLat
        val lon = currentLon
        Thread {
            val (alive, reason) = probeInstance(instance, lat, lon)
            instanceAlive[instance.url] = alive
            if (alive) {
                noteHttpSuccess(instance)
                resetAllDeadBackoff()
            } else if (reason.startsWith("http=")) {
                val code = reason.removePrefix("http=").toIntOrNull() ?: 0
                noteHttpFailure(instance, code)
            }
            logDebug("HEALTHCHECK_ADHOC ${instance.name} alive=$alive $reason")
            healthCheckInProgress.set(false)
        }.start()
    }

    // Picks the next instance — never the same as last time unless it is the only
    // one alive. Returns null when nothing is alive; the caller then skips the fetch
    // instead of hammering instances already known to be refusing us.
    // Runs on: UI thread (called from fetchRoadInfo before the worker starts).
    private fun pickNextInstance(): OverpassInstance? {
        val alive = overpassInstances.filter { instanceAlive[it.url] == true && !isInCooldown(it.url) }
        if (alive.isEmpty()) return null
        val candidates = if (alive.size > 1) alive.filter { it.url != lastUsedInstanceUrl } else alive
        val chosen = if (candidates.isNotEmpty()) candidates.random() else alive.first()
        lastUsedInstanceUrl = chosen.url
        return chosen
    }

    // --- Road info fetch loop ---
    private val speedLimitHandler  = Handler(Looper.getMainLooper())
    private val speedLimitRunnable = object : Runnable {
        // Runs on: UI thread. Decides whether this tick warrants a network call.
        override fun run() {
            val fixAgeMs = if (lastFixElapsedMs == 0L) Long.MAX_VALUE
            else SystemClock.elapsedRealtime() - lastFixElapsedMs

            // Three conditions must all hold before spending a query:
            // moving fast enough to care, a recent fix, and no fetch already running.
            if (textView.speedKmh >= 20f && fixAgeMs <= MAX_FIX_AGE_MS && !fetchInProgress) {
                fetchRoadInfo(currentLat, currentLon)
            }
            updateViolationView(textView.speedKmh, textView.speedLimitKmh)
            speedLimitHandler.postDelayed(this, prefs.speedLimitIntervalSeconds * 1000L)
        }
    }

    // --- Internal broadcast receiver ---
    private val receiver = object : BroadcastReceiver() {
        // Runs on: UI thread
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

    // --- Location receiver (EXPORTED — delivered by the system LocationManager) ---
    private val locationReceiver = object : BroadcastReceiver() {
        // Runs on: UI thread. Fires roughly twice a second while GPS is healthy.
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
                lastFixElapsedMs = SystemClock.elapsedRealtime()
                if (it.hasBearing()) textView.bearing = it.bearing

                if (kmh >= AUTO_HIDE_THRESHOLD_KMH) {
                    if (hideScheduled) {
                        autoHideHandler.removeCallbacks(autoHideRunnable)
                        hideScheduled = false
                    }
                    if (isAutoHidden) {
                        isAutoHidden = false
                        if (!isInSettingsMode) {
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
    // Runs on: UI thread
    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)

        createNotificationChannel()

        // Location permission must hold at the moment startForeground runs for a
        // location-typed service on Android 14+, or the system throws. The normal
        // path via MainActivity guarantees this, but START_STICKY means the system
        // can restart us later — possibly after the user revoked the permission.
        if (!hasLocationPermission()) {
            Log.e(TAG, "Location permission missing at service start — stopping")
            stopSelf()
            return
        }

        startForegroundCompat()
        isRunning = true

        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.SCREEN_DIM_WAKE_LOCK or PowerManager.ON_AFTER_RELEASE,
            "SpeedOver::ScreenWakeLock"
        ).also { it.acquire(WAKE_LOCK_TIMEOUT_MS) }

        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        buildTextWindow()
        buildViolationWindow()
        buildRoadInfoWindow()

        // NW arrow so something sensible is drawn before the first bearing arrives
        textView.bearing = BEARING_FALLBACK

        applyEffectiveLimit()

        autoHideHandler.postDelayed(autoHideRunnable, AUTO_HIDE_DELAY_MS)
        hideScheduled = true

        // startTestLoop()   // uncomment to animate vioGrad for visual verification
        startGps()
        gpsKeepaliveHandler.postDelayed(gpsKeepaliveRunnable, prefs.gpsKeepaliveSeconds * 1000L)
        speedLimitHandler.postDelayed(speedLimitRunnable, 5_000)
        checkInstanceHealth()
        instanceHealthHandler.postDelayed(instanceHealthRunnable, 20 * 60 * 1000L)

        val filter = IntentFilter().apply {
            addAction(ACTION_ENTER_SETTINGS); addAction(ACTION_EXIT_SETTINGS)
            addAction(ACTION_UPDATE_PREFS);   addAction(ACTION_RESIZE)
            addAction(ACTION_MOVE)
        }
        registerReceiver(receiver, filter, RECEIVER_NOT_EXPORTED)
        registerReceiver(locationReceiver, IntentFilter(ACTION_LOCATION_UPDATE), RECEIVER_EXPORTED)

        Log.d(TAG, "OverlayService started")
    }

    // Declaring foregroundServiceType in the manifest is sufficient on older APIs,
    // but from Android 14 the type must also be passed here.
    private fun startForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(1, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } else {
            startForeground(1, buildNotification())
        }
    }

    private fun hasLocationPermission() =
        checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    override fun onTaskRemoved(rootIntent: Intent?) { super.onTaskRemoved(rootIntent); stopSelf() }

    // Runs on: UI thread
    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        wakeLock?.let { if (it.isHeld) it.release() }
        stopGps()
        gpsKeepaliveHandler.removeCallbacks(gpsKeepaliveRunnable)
        autoHideHandler.removeCallbacks(autoHideRunnable)
        speedLimitHandler.removeCallbacks(speedLimitRunnable)
        instanceHealthHandler.removeCallbacks(instanceHealthRunnable)
        testHandler?.removeCallbacksAndMessages(null)
        logExecutor.shutdown()
        try { unregisterReceiver(receiver) }         catch (_: Exception) {}
        try { unregisterReceiver(locationReceiver) } catch (_: Exception) {}
        // Views may never have been built if onCreate bailed on a missing permission
        if (::windowManager.isInitialized) {
            if (::textView.isInitialized)      try { windowManager.removeView(textView) }      catch (_: Exception) {}
            if (::violationView.isInitialized) try { windowManager.removeView(violationView) } catch (_: Exception) {}
            if (::roadInfoView.isInitialized)  try { windowManager.removeView(roadInfoView) }  catch (_: Exception) {}
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // --- Text window ---
    // Runs on: UI thread
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
        // 0.5f is the MIUI threshold below which touches pass through the overlay
        textParams.alpha = 0.5f
        windowManager.updateViewLayout(textView, textParams)
    }

    // --- Violation gradient window ---
    // Fixed geometry, independent of the user's text-size setting: this is a safety
    // element and must not shrink because someone made the speed number small.
    // Runs on: UI thread
    private fun buildViolationWindow() {
        val dm = resources.displayMetrics
        violationView = ViolationView(this)
        violationParams = WindowManager.LayoutParams(
            (dm.widthPixels * 0.10f).toInt(), (dm.heightPixels * 0.50f).toInt(),
            (dm.widthPixels * 0.02f).toInt(), (dm.heightPixels * 0.25f).toInt(),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START }
        windowManager.addView(violationView, violationParams)
        violationParams.alpha = 0f
        windowManager.updateViewLayout(violationView, violationParams)
    }

    // --- Road info window ---
    // Runs on: UI thread
    private fun buildRoadInfoWindow() {
        val dm = resources.displayMetrics
        val rh = (dm.heightPixels * 0.16f).toInt()
        roadInfoView = RoadInfoView(this)
        applyRoadInfoPrefs()
        roadInfoParams = WindowManager.LayoutParams(
            dm.widthPixels, rh, 0, dm.heightPixels - rh,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START }
        windowManager.addView(roadInfoView, roadInfoParams)
        roadInfoParams.alpha = 0.5f
        windowManager.updateViewLayout(roadInfoView, roadInfoParams)
    }

    // --- Hard limit / effective limit ---
    // Displayed limit = min(hardLimit, osmLimit) when both known, hardLimit alone
    // when OSM has none, plain osmLimit when hard limit is off. The asterisk marks
    // only the case where hard limit is actually lowering what would otherwise show.
    // Runs on: UI thread
    private fun applyEffectiveLimit() {
        val hard = prefs.hardLimit
        if (hard > 0) {
            if (lastOsmLimit > 0) {
                textView.speedLimitKmh   = minOf(hard, lastOsmLimit)
                textView.hardLimitActive = lastOsmLimit > hard
            } else {
                textView.speedLimitKmh   = hard
                textView.hardLimitActive = true
            }
        } else {
            textView.speedLimitKmh   = lastOsmLimit
            textView.hardLimitActive = false
        }
    }

    // --- ViolationGradient ---
    // Runs on: UI thread. Called from the location receiver and the fetch loop tick.
    private fun updateViolationView(speedKmh: Float, limitKmh: Int) {
        if (isAutoHidden || isInSettingsMode) return

        // 3 km/h tolerance mirrors the margin applied in Danish speed enforcement
        if (limitKmh <= 0 || speedKmh <= limitKmh + 3f) {
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

        // Above 10% over, the overlay goes fully opaque — deliberately sacrificing
        // click-through, since at that point looking at the speed matters more.
        val targetAlpha = if (violation > 0.10f) 1.0f else 0.5f
        textParams.alpha     = targetAlpha
        roadInfoParams.alpha = targetAlpha
        windowManager.updateViewLayout(textView, textParams)
        windowManager.updateViewLayout(roadInfoView, roadInfoParams)
    }

    // Runs on: UI thread
    private fun applyPrefs() {
        textView.fillColor         = prefs.fillColor
        textView.strokeColor       = prefs.strokeColor
        textView.textAlpha         = prefs.textAlpha
        textView.textSizePx        = prefs.textSizePx
        textView.strokeWidthFactor = prefs.strokeWidth
        applyEffectiveLimit()
        // roadInfoView does not exist yet during the first call from buildTextWindow
        if (::roadInfoView.isInitialized) applyRoadInfoPrefs()
    }

    // Runs on: UI thread
    private fun applyRoadInfoPrefs() {
        roadInfoView.fillColor         = prefs.fillColor
        roadInfoView.strokeColor       = prefs.strokeColor
        roadInfoView.textAlpha         = prefs.textAlpha
        roadInfoView.strokeWidthFactor = prefs.strokeWidth
        roadInfoView.fontSizePx        = prefs.textSizePx * 0.38f * 0.75f * 0.5f
        roadInfoView.showRef           = prefs.showRoadRef
        roadInfoView.showName          = prefs.showRoadName
        roadInfoView.showType          = prefs.showRoadType
    }

    // Runs on: UI thread (pinch gesture forwarded from MainActivity)
    private fun resizeOverlay(scaleFactor: Float) {
        val dm = resources.displayMetrics
        val testPaint      = Paint().apply { textSize = 800f }
        val testArrowH     = 800f * 0.38f
        val testLimitPaint = Paint().apply { textSize = testArrowH * 0.75f }
        val testBottom     = testArrowH * 1.25f + testLimitPaint.measureText("199") / 2f + testArrowH * 1.75f
        val testNumber     = testPaint.measureText("1") + testPaint.measureText("00") + 800f * 0.08f
        val maxSizeByWidth = dm.widthPixels / (maxOf(testNumber, testBottom) / 800f)

        val newSize = (prefs.textSizePx * scaleFactor).coerceIn(40f, maxSizeByWidth)
        prefs.textSizePx        = newSize
        textView.textSizePx     = newSize
        roadInfoView.fontSizePx = newSize * 0.38f * 0.75f * 0.5f

        val (w, h) = calcOverlaySize(newSize)
        textParams.width  = w;  textParams.height = h
        prefs.overlayWidth = w; prefs.overlayHeight = h
        textParams.x = textParams.x.coerceIn(0, (dm.widthPixels  - w).coerceAtLeast(0))
        textParams.y = textParams.y.coerceIn(0, (dm.heightPixels - h).coerceAtLeast(0))
        prefs.overlayX = textParams.x; prefs.overlayY = textParams.y
        windowManager.updateViewLayout(textView, textParams)
    }

    // Runs on: UI thread
    private fun enterSettingsMode() {
        isInSettingsMode = true
        textParams.alpha      = 0f
        violationParams.alpha = 0f
        roadInfoParams.alpha  = 0f
        windowManager.updateViewLayout(textView, textParams)
        windowManager.updateViewLayout(violationView, violationParams)
        windowManager.updateViewLayout(roadInfoView, roadInfoParams)
    }

    // Runs on: UI thread
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

    // Mirrors the layout maths in SpeedOverlayView.onDraw — talLim centred at
    // arrowH*1.25f from the left, arrow zone arrowH*1.75f from the right, plus
    // room for the hard-limit asterisk.
    // Runs on: UI thread
    private fun calcOverlaySize(sizePx: Float): Pair<Int, Int> {
        val paint = Paint().apply { textSize = sizePx }
        val numberWidth = paint.measureText("1") + paint.measureText("00") + sizePx * 0.08f
        val arrowH      = sizePx * 0.38f
        val arrowGap    = arrowH * 0.3f
        val limitPaint  = Paint().apply { textSize = arrowH * 0.75f }
        val asteriskRoom = arrowH * 0.75f * 0.35f
        val bottomWidth  = arrowH * 1.25f + limitPaint.measureText("199") / 2f + asteriskRoom + arrowH * 1.75f
        val w  = maxOf(numberWidth, bottomWidth).toInt()
        val fm = paint.fontMetrics
        val h  = (fm.bottom - fm.top + sizePx * 0.05f + arrowGap + arrowH + sizePx * 0.05f).toInt()
        return Pair(w, h)
    }

    // --- Overpass road info fetch ---
    // Entry runs on: UI thread. The network call itself runs on a worker thread and
    // posts results back to the UI thread.
    //
    // Failure handling, deliberately asymmetric:
    //   200 + data     → update the sticky fields
    //   200 + empty    → keep everything (we simply learned nothing new here)
    //   non-200 HTTP   → keep everything, but mark the instance dead. A 429 tells us
    //                    about our request rate, not about the road under the wheels.
    //   timeout/error  → keep everything, keep the instance alive. A timeout says
    //                    nothing at all about the server's state.
    private fun fetchRoadInfo(lat: Double, lon: Double) {
        val radius   = prefs.overpassRadiusMeters
        val gpsTag   = "lat=${"%.5f".format(lat)} lon=${"%.5f".format(lon)}"
        val instance = pickNextInstance()

        if (instance == null) {
            // Everything is marked dead. Retrying all three blindly (as before) just
            // collects more rejections during an outage that is already happening —
            // back off on an escalating schedule and probe only one instance per
            // attempt instead.
            val now = SystemClock.elapsedRealtime()
            if (now < nextAllDeadProbeAtMs) {
                val waitSec = (nextAllDeadProbeAtMs - now) / 1000
                logDebug("ALL_DEAD backoff active, ${waitSec}s remaining | $gpsTag")
                return
            }
            val backoffMs = ALL_DEAD_BACKOFF_MS[allDeadBackoffIndex.coerceAtMost(ALL_DEAD_BACKOFF_MS.lastIndex)]
            nextAllDeadProbeAtMs = now + backoffMs
            allDeadBackoffIndex  = (allDeadBackoffIndex + 1).coerceAtMost(ALL_DEAD_BACKOFF_MS.lastIndex)
            logDebug("ALL_DEAD probing one instance, next attempt in ${backoffMs / 1000}s | $gpsTag")
            probeSingleInstanceAdHoc()
            return
        }

        fetchInProgress = true
        Thread {
            // Runs on: fetch worker thread
            var conn: java.net.HttpURLConnection? = null
            try {
                // Server-side timeout kept comfortably below the client-side read
                // timeout — see the class doc for why the two were equal before and
                // why that made every slow-but-working response look like a failure.
                val query   = "[out:json][timeout:3];way['highway'](around:$radius,$lat,$lon);out tags;"
                val encoded = java.net.URLEncoder.encode(query, "UTF-8")
                conn = (java.net.URL(instance.url).openConnection() as java.net.HttpURLConnection).apply {
                    requestMethod  = "POST"
                    doOutput       = true
                    connectTimeout = 5000
                    readTimeout    = 10000
                    setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                }
                conn.outputStream.bufferedWriter().use { it.write("data=$encoded") }

                val code = conn.responseCode
                if (code == 200) {
                    noteHttpSuccess(instance)
                    val body = conn.inputStream.bufferedReader().readText()
                    val info = parseRoadInfo(body)
                    Handler(Looper.getMainLooper()).post {
                        // Runs on: UI thread
                        if (info != null) {
                            // Each field independently sticky: a way that carries a
                            // name but no ref must not wipe a ref we already knew.
                            if (info.speedLimit > 0)       lastOsmLimit = info.speedLimit
                            applyEffectiveLimit()
                            if (info.name.isNotEmpty())    roadInfoView.roadName = info.name
                            if (info.highway.isNotEmpty()) roadInfoView.roadType = info.highway
                            if (info.ref.isNotEmpty())     roadInfoView.roadRef  = info.ref
                            logDebug("OVERPASS_OK ${instance.name} http=200 | #${info.name}#${info.highway}#${info.ref}# | limit=${info.speedLimit} | $gpsTag")
                        } else {
                            logDebug("OVERPASS_OK ${instance.name} http=200 | EMPTY (no elements) | $gpsTag")
                        }
                    }
                } else {
                    // Server answered, but refused. Take it out of rotation until the
                    // next health check — but leave the displayed data alone. Enough
                    // refusals in a row and noteHttpFailure parks it for hours.
                    instanceAlive[instance.url] = false
                    noteHttpFailure(instance, code)
                    Log.e(TAG, "Overpass HTTP error ($code) on ${instance.name}")
                    logDebug("OVERPASS_ERR ${instance.name} http=$code | $gpsTag")
                }
            } catch (e: Exception) {
                val msg = e.message ?: "unknown"
                Log.e(TAG, "fetchRoadInfo exception on ${instance.name}: $msg")
                logDebug("OVERPASS_EXCEPTION ${instance.name} msg=$msg | $gpsTag")
            } finally {
                try { conn?.disconnect() } catch (_: Exception) {}
                fetchInProgress = false
            }
        }.start()
    }

    // Wipes all OSM-derived state. Currently only reachable from a future explicit
    // reset — no failure path calls this, by design: a failed request never proves
    // that the previously known road data has become wrong.
    // Runs on: UI thread
    private fun clearOsmData() {
        lastOsmLimit = 0
        applyEffectiveLimit()
        roadInfoView.roadName = ""
        roadInfoView.roadType = ""
        roadInfoView.roadRef  = ""
    }

    // Picks the highest-priority way from everything Overpass returned within the
    // radius — a junction can easily return six ways, and the biggest road present
    // is the best guess at which one we are actually driving on.
    // Runs on: fetch worker thread
    private fun parseRoadInfo(json: String): RoadInfo? {
        return try {
            val elements = JSONObject(json).getJSONArray("elements")
            if (elements.length() == 0) return null

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
            val ref     = tags.optString("ref", "")
            val rawMax  = tags.optString("maxspeed", "").trim()

            val speedLimit = if (rawMax.isNotEmpty()) {
                parseMaxspeedString(rawMax)
            } else {
                speedLimitForHighway(highway)
            }

            RoadInfo(highway, name, ref, speedLimit)
        } catch (e: Exception) {
            Log.e(TAG, "parseRoadInfo exception: ${e.message}")
            null
        }
    }

    // OSM maxspeed values are free-form strings: plain numbers, "50 mph", or
    // country-coded implicit zones. Anything unrecognised yields 0 (= hide).
    // Runs on: fetch worker thread
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

    // Fallback limit by highway type, straight from Prefs. 0 = off for that type,
    // uniformly — no special-casing for unclassified here, it just defaults to 0.
    // Runs on: fetch worker thread
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

    // GPS is delivered via PendingIntent rather than a LocationListener — MIUI
    // throttles listeners in background far more aggressively than broadcasts.
    // Runs on: UI thread
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

    // Runs on: UI thread
    private fun stopGps() {
        try { locationPendingIntent?.let { locationManager.removeUpdates(it) } } catch (_: Exception) {}
    }

    // Runs on: UI thread
    private fun createNotificationChannel() {
        val channel = NotificationChannel(CHANNEL_ID, "SpeedOver", NotificationManager.IMPORTANCE_LOW)
            .apply { description = "GPS speedometer overlay" }
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
    }

    // Runs on: UI thread
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

    // Development aid: sweeps vioGrad up and down so its rendering can be checked
    // while parked. Enable by uncommenting the call in onCreate.
    // Runs on: UI thread
    private fun startTestLoop() {
        val startTime = System.currentTimeMillis()
        testHandler = Handler(Looper.getMainLooper())
        testHandler?.post(object : Runnable {
            override fun run() {
                val phase = ((System.currentTimeMillis() - startTime) % 5000L).toFloat() / 5000f
                val wave  = if (phase < 0.5f) phase * 2f else (1f - phase) * 2f
                updateViolationView(50f * (1f + wave * 0.35f), 50)
                testHandler?.postDelayed(this, 50L)
            }
        })
    }
}

// =============================================================================
// ViolationGradient view
// =============================================================================
/**
 * Thermometer bar: blue outline always at full height when visible, yellow→red
 * fill growing from the bottom. progress = 0..1, where 1 means 30% over the limit.
 * Tick marks read as a scale: long at 10% and 20%, short at 5%, 15% and 25%.
 *
 * Drawn on: UI thread, via invalidate() from the progress setter.
 */
class ViolationView(context: Context) : View(context) {

    var progress: Float = 0f
        set(value) { field = value; invalidate() }

    // 2 dp in device pixels — a physical width, deliberately not tied to text size
    private val strokePx     = 2f * resources.displayMetrics.density
    private val fillPaint    = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val outlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; color = Color.rgb(0, 0, 255)
        strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND
    }
    private val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; color = Color.rgb(0, 0, 255); strokeCap = Paint.Cap.ROUND
    }

    override fun onDraw(canvas: Canvas) {
        if (progress <= 0f) return
        val w = width.toFloat(); val h = height.toFloat()
        val halfStroke = strokePx / 2f; val radius = w * 0.25f
        val rect = RectF(halfStroke, halfStroke, w - halfStroke, h - halfStroke)
        val barTop = h - progress * (h - halfStroke * 2f) - halfStroke

        // Gradient spans the full window height regardless of fill level, so a given
        // colour always means the same overspeed
        fillPaint.shader = LinearGradient(0f, halfStroke, 0f, h - halfStroke, Color.RED, Color.YELLOW, Shader.TileMode.CLAMP)
        canvas.save()
        canvas.clipPath(Path().apply { addRoundRect(rect, radius, radius, Path.Direction.CW) })
        canvas.drawRect(halfStroke, barTop, w - halfStroke, h - halfStroke, fillPaint)
        canvas.restore()

        outlinePaint.strokeWidth = strokePx; tickPaint.strokeWidth = strokePx
        val barHeight = h - halfStroke * 2f
        listOf(5f/30f to false, 10f/30f to true, 15f/30f to false, 20f/30f to true, 25f/30f to false)
            .forEach { (fraction, isLong) ->
                val tickY = h - halfStroke - fraction * barHeight
                canvas.drawLine(if (isLong) w*0.12f else w*0.25f, tickY, if (isLong) w*0.88f else w*0.75f, tickY, tickPaint)
            }
        // Outline last so it sits on top of both fill and ticks
        canvas.drawRoundRect(rect, radius, radius, outlinePaint)
    }
}

// =============================================================================
// Road info view
// =============================================================================
/**
 * Road ref (top), name (middle), type (bottom), centred at the bottom of the
 * screen. Only enabled, non-empty lines are drawn, and the resulting block is
 * centred as a unit — switching one off closes the gap rather than leaving a hole.
 *
 * Drawn on: UI thread, via invalidate() from each setter.
 */
class RoadInfoView(context: Context) : View(context) {

    var roadName: String = ""
        set(value) { field = value; invalidate() }
    var roadType: String = ""
        set(value) { field = value; invalidate() }
    var roadRef: String = ""
        set(value) { field = value; invalidate() }
    var showName: Boolean = true
        set(value) { field = value; invalidate() }
    var showType: Boolean = true
        set(value) { field = value; invalidate() }
    var showRef: Boolean = true
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
        typeface = Typeface.DEFAULT_BOLD; textAlign = Paint.Align.CENTER
        style = Paint.Style.STROKE; strokeJoin = Paint.Join.ROUND
    }

    override fun onDraw(canvas: Canvas) {
        // Order top-to-bottom: ref, name, type
        val lines = mutableListOf<String>()
        if (showRef  && roadRef.isNotEmpty())  lines.add(roadRef)
        if (showName && roadName.isNotEmpty()) lines.add(roadName)
        if (showType && roadType.isNotEmpty()) lines.add(roadType)
        if (lines.isEmpty()) return

        val cx = width / 2f; val lineH = fontSizePx * 1.35f
        var y = (height - lines.size * lineH) / 2f + fontSizePx * 0.85f

        fillPaint.apply   { textSize = fontSizePx; color = fillColor;   alpha = textAlpha }
        strokePaint.apply { textSize = fontSizePx; color = strokeColor; alpha = textAlpha; strokeWidth = strokeWidthFactor * 0.4f }

        for (line in lines) {
            canvas.drawText(line, cx, y, strokePaint)
            canvas.drawText(line, cx, y, fillPaint)
            y += lineH
        }
    }
}