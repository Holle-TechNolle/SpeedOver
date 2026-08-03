// MainActivity.kt
package com.example.speedover

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.*
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.*
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import java.io.File

/**
 * SpeedOver Safety Awareness
 * Holle TechNolle, 2026
 *
 * Entry point. Handles permissions, starts the overlay service, and shows the
 * settings UI when brought to the foreground from recents.
 *
 * Everything in this file runs on the UI thread. The activity holds no state of
 * its own beyond the preview view — all settings are written straight to Prefs and
 * broadcast to OverlayService, which owns the live overlay.
 *
 * Personal use only. Untested. Built for Xiaomi T10 — may work on other devices.
 */
class MainActivity : AppCompatActivity() {

    companion object {
        // Upper bound of the hard-limit slider. Independent of limitMotorway on
        // purpose: hard limit is a standalone user setting, and deriving its range
        // from another preference meant turning motorway off capped it at 10.
        const val HARD_LIMIT_MAX = 160
    }

    private lateinit var prefs: Prefs
    private lateinit var scaleDetector: ScaleGestureDetector
    private var previewView: OverlayPreviewView? = null

    private val overlayPermLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (Settings.canDrawOverlays(this)) startOverlayService()
        else { Toast.makeText(this, "Overlay permission required", Toast.LENGTH_LONG).show(); finish() }
    }

    private val locationPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        if (grants.values.all { it }) checkAndStart()
        else { Toast.makeText(this, "GPS permission required", Toast.LENGTH_LONG).show(); finish() }
    }

    // Returning from the share sheet gives no indication of success, so we ask
    // rather than delete — a failed mail share should not cost you the log.
    private val shareLogLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { confirmDeleteDebugLog() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        if (!hasLocationPermission()) requestLocationPermission() else checkAndStart()
    }

    // Coming to the foreground means the user wants settings: hide the live overlay
    // so it does not sit on top of the settings screen, then build the UI.
    override fun onResume() {
        super.onResume()
        if (OverlayService.isRunning) {
            sendBroadcast(Intent(OverlayService.ACTION_ENTER_SETTINGS).setPackage(packageName))
            showSettingsUI()
        }
    }

    override fun onPause() {
        super.onPause()
        previewView?.stopThemeCycle()
        if (OverlayService.isRunning) {
            sendBroadcast(Intent(OverlayService.ACTION_EXIT_SETTINGS).setPackage(packageName))
        }
    }

    // Builds the whole settings screen programmatically. Each control writes to
    // Prefs immediately and broadcasts to OverlayService where a live update matters.
    private fun showSettingsUI() {
        val dm      = resources.displayMetrics
        val screenW = dm.widthPixels
        val screenH = dm.heightPixels

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
        }

        // --- PREVIEW ---
        // Both preview and the scroll view below use height=0 with a weight. If the
        // scroll view were WRAP_CONTENT it would measure its full content height —
        // several screens' worth — and squeeze the preview to nothing.
        val preview = OverlayPreviewView(this, prefs, screenW, screenH) { newX, newY ->
            prefs.overlayX = newX; prefs.overlayY = newY
            sendBroadcast(
                Intent(OverlayService.ACTION_MOVE).setPackage(packageName)
                    .putExtra("x", newX).putExtra("y", newY)
            )
        }
        previewView = preview
        preview.startThemeCycle()
        root.addView(preview, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0
        ).apply { weight = 0.35f })

        root.addView(View(this).apply {
            setBackgroundColor(Color.rgb(40, 40, 40))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1)
        })

        // --- CONTROLS ---
        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 48)
        }

        fun label(text: String) = TextView(this).apply {
            this.text = text; setTextColor(Color.WHITE)
            textSize = 11f; setPadding(0, 16, 0, 4)
            isAllCaps = true; letterSpacing = 0.08f
        }

        // Returns the row plus its value TextView so callers can update the readout
        fun labelRow(labelText: String, value: String): Triple<LinearLayout, TextView, TextView> {
            val lbl   = TextView(this).apply {
                text = labelText; setTextColor(Color.WHITE)
                textSize = 11f; isAllCaps = true; letterSpacing = 0.08f
            }
            val valTv = TextView(this).apply {
                text = value; setTextColor(Color.WHITE)
                textSize = 11f; textAlignment = View.TEXT_ALIGNMENT_TEXT_END
            }
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; setPadding(0, 16, 0, 4)
                addView(lbl,   LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                addView(valTv, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            }
            return Triple(row, lbl, valTv)
        }

        fun switchRow(labelText: String, checked: Boolean, onChange: (Boolean) -> Unit): LinearLayout {
            val lbl = TextView(this).apply {
                text = labelText; setTextColor(Color.WHITE)
                textSize = 11f; isAllCaps = true; letterSpacing = 0.08f
            }
            val sw = Switch(this).apply {
                isChecked = checked
                setOnCheckedChangeListener { _, v -> onChange(v) }
            }
            return LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; setPadding(0, 12, 0, 4)
                addView(lbl, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                addView(sw,  LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            }
        }

        // Colour — each button shows its own colour and opens the RGB picker
        controls.addView(label("Colour"))
        val colourRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val fillBtn = Button(this).apply {
            text = "Fill"; setBackgroundColor(prefs.fillColor)
            setTextColor(if (Color.luminance(prefs.fillColor) > 0.5f) Color.BLACK else Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                .apply { setMargins(0, 0, 8, 0) }
            setOnClickListener {
                showColorPicker("Fill colour", prefs.fillColor) { color ->
                    prefs.fillColor = color; setBackgroundColor(color)
                    setTextColor(if (Color.luminance(color) > 0.5f) Color.BLACK else Color.WHITE)
                    sendBroadcast(Intent(OverlayService.ACTION_UPDATE_PREFS).setPackage(packageName))
                    preview.invalidate()
                }
            }
        }
        val strokeBtn = Button(this).apply {
            text = "Stroke"; setBackgroundColor(prefs.strokeColor)
            setTextColor(if (Color.luminance(prefs.strokeColor) > 0.5f) Color.BLACK else Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                .apply { setMargins(8, 0, 0, 0) }
            setOnClickListener {
                showColorPicker("Stroke colour", prefs.strokeColor) { color ->
                    prefs.strokeColor = color; setBackgroundColor(color)
                    setTextColor(if (Color.luminance(color) > 0.5f) Color.BLACK else Color.WHITE)
                    sendBroadcast(Intent(OverlayService.ACTION_UPDATE_PREFS).setPackage(packageName))
                    preview.invalidate()
                }
            }
        }
        colourRow.addView(fillBtn); colourRow.addView(strokeBtn)
        controls.addView(colourRow)

        // Stroke weight
        val (strokeRow, _, strokeVal) = labelRow("Stroke weight", "${prefs.strokeWidth.toInt()} px")
        controls.addView(strokeRow)
        controls.addView(seekBar(prefs.strokeWidth.toInt(), 0, 40) { v ->
            prefs.strokeWidth = v.toFloat(); strokeVal.text = "$v px"
            sendBroadcast(Intent(OverlayService.ACTION_UPDATE_PREFS).setPackage(packageName))
            preview.invalidate()
        })

        // Opacity — this is paint alpha, multiplied on top of the window alpha that
        // OverlayService controls for MIUI click-through
        val (alphaRow, _, alphaVal) = labelRow("Opacity", "${(prefs.textAlpha / 255f * 100).toInt()}%")
        controls.addView(alphaRow)
        controls.addView(seekBar(prefs.textAlpha, 0, 255) { v ->
            prefs.textAlpha = v; alphaVal.text = "${(v / 255f * 100).toInt()}%"
            sendBroadcast(Intent(OverlayService.ACTION_UPDATE_PREFS).setPackage(packageName))
            preview.invalidate()
        })

        // GPS keepalive — read by the service on its next timer tick, no broadcast needed
        val (gpsRow, _, gpsVal) = labelRow("GPS keepalive", "${prefs.gpsKeepaliveSeconds} sec")
        controls.addView(gpsRow)
        controls.addView(seekBar(prefs.gpsKeepaliveSeconds, 5, 60) { v ->
            prefs.gpsKeepaliveSeconds = v; gpsVal.text = "$v sec"
        })

        // Speed limit interval — takes effect from the next fetch cycle
        val (limRow, _, limVal) = labelRow("Speed limit interval", "${prefs.speedLimitIntervalSeconds} sec")
        controls.addView(limRow)
        controls.addView(seekBar(prefs.speedLimitIntervalSeconds, 5, 60) { v ->
            prefs.speedLimitIntervalSeconds = v; limVal.text = "$v sec"
        })

        // Overpass search radius around the current GPS position
        val (radRow, _, radVal) = labelRow("Speed limit radius", "${prefs.overpassRadiusMeters} m")
        controls.addView(radRow)
        controls.addView(seekBar(prefs.overpassRadiusMeters, 10, 150) { v ->
            prefs.overpassRadiusMeters = v; radVal.text = "$v m"
        })

        // Road info toggles — listed in the same order they appear on the overlay:
        // ref on top, then name, then type at the bottom
        controls.addView(label("Road info"))
        controls.addView(switchRow("Show road ref", prefs.showRoadRef) { v ->
            prefs.showRoadRef = v
            sendBroadcast(Intent(OverlayService.ACTION_UPDATE_PREFS).setPackage(packageName))
        })
        controls.addView(switchRow("Show road name", prefs.showRoadName) { v ->
            prefs.showRoadName = v
            sendBroadcast(Intent(OverlayService.ACTION_UPDATE_PREFS).setPackage(packageName))
        })
        controls.addView(switchRow("Show road type", prefs.showRoadType) { v ->
            prefs.showRoadType = v
            sendBroadcast(Intent(OverlayService.ACTION_UPDATE_PREFS).setPackage(packageName))
        })

        // Hard limit — 0 = off. Acts as a ceiling on the OSM limit; the asterisk on
        // the overlay appears only when it is actually the binding value.
        val hardLimitDisplay = if (prefs.hardLimit == 0) "off" else "${prefs.hardLimit}"
        val (hardRow, _, hardVal) = labelRow("Hard limit  (* = binding)", hardLimitDisplay)
        controls.addView(hardRow)
        controls.addView(seekBar(prefs.hardLimit, 0, HARD_LIMIT_MAX) { v ->
            prefs.hardLimit = v
            hardVal.text = if (v == 0) "off" else "$v"
            sendBroadcast(Intent(OverlayService.ACTION_UPDATE_PREFS).setPackage(packageName))
            preview.invalidate()
        })

        // Per-road-type fallbacks, used only when OSM has no explicit maxspeed.
        // Labels are the raw OSM names so they translate to any country's own terms.
        controls.addView(label("Speed limits by road type  (0 = off)"))

        fun speedLimitRow(osmName: String, current: Int, onSave: (Int) -> Unit) {
            val (row, _, valTv) = labelRow(osmName, if (current == 0) "off" else "$current")
            controls.addView(row)
            controls.addView(seekBar(current, 0, 160) { v ->
                valTv.text = if (v == 0) "off" else "$v"
                onSave(v)
            })
        }

        speedLimitRow("motorway",      prefs.limitMotorway)      { prefs.limitMotorway      = it }
        speedLimitRow("motorway_link", prefs.limitMotorwayLink)  { prefs.limitMotorwayLink  = it }
        speedLimitRow("trunk",         prefs.limitTrunk)         { prefs.limitTrunk         = it }
        speedLimitRow("trunk_link",    prefs.limitTrunkLink)     { prefs.limitTrunkLink     = it }
        speedLimitRow("primary",       prefs.limitPrimary)       { prefs.limitPrimary       = it }
        speedLimitRow("secondary",     prefs.limitSecondary)     { prefs.limitSecondary     = it }
        speedLimitRow("tertiary",      prefs.limitTertiary)      { prefs.limitTertiary      = it }
        speedLimitRow("unclassified",  prefs.limitUnclassified)  { prefs.limitUnclassified  = it }
        speedLimitRow("residential",   prefs.limitResidential)   { prefs.limitResidential   = it }
        speedLimitRow("living_street", prefs.limitLivingStreet)  { prefs.limitLivingStreet  = it }

        // Debug logging — off by default, writes Overpass outcomes with GPS position
        controls.addView(label("Debugging"))
        controls.addView(switchRow("Enable debug log", prefs.enableDebugLog) { v ->
            prefs.enableDebugLog = v
            sendBroadcast(Intent(OverlayService.ACTION_UPDATE_PREFS).setPackage(packageName))
        })
        controls.addView(Button(this).apply {
            text = "Share log"
            setBackgroundColor(Color.rgb(40, 40, 40)); setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 8 }
            setOnClickListener { shareDebugLog() }
        })

        controls.addView(label("Pinch anywhere to resize · Drag number in preview to reposition"))

        controls.addView(Button(this).apply {
            text = "Stop overlay"
            setBackgroundColor(Color.rgb(160, 30, 30)); setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 16 }
            setOnClickListener {
                stopService(Intent(this@MainActivity, OverlayService::class.java)); finish()
            }
        })

        // Weighted like the preview above so both get a fixed share of the screen
        val scrollView = ScrollView(this).apply {
            addView(controls)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0
            ).apply { weight = 0.65f }
        }
        root.addView(scrollView)

        // Pinch anywhere on the settings screen resizes the live overlay
        scaleDetector = ScaleGestureDetector(this,
            object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScale(detector: ScaleGestureDetector): Boolean {
                    sendBroadcast(
                        Intent(OverlayService.ACTION_RESIZE).setPackage(packageName)
                            .putExtra("scaleFactor", detector.scaleFactor)
                    )
                    preview.invalidate()
                    return true
                }
            })

        setContentView(root)
    }

    // RGB picker with presets. onPick fires only on OK, so cancelling leaves the
    // stored colour untouched.
    private fun showColorPicker(title: String, current: Int, onPick: (Int) -> Unit) {
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(40, 20, 40, 20) }
        val rSeek  = SeekBar(this).apply { max = 255; progress = Color.red(current) }
        val gSeek  = SeekBar(this).apply { max = 255; progress = Color.green(current) }
        val bSeek  = SeekBar(this).apply { max = 255; progress = Color.blue(current) }
        val prev   = TextView(this).apply {
            text = "  #${Integer.toHexString(current).uppercase().padStart(6,'0')}  "
            setBackgroundColor(current)
            setTextColor(if (Color.luminance(current) > 0.5f) Color.BLACK else Color.WHITE)
            textSize = 16f; setPadding(10, 10, 10, 10)
        }
        fun update() {
            val c = Color.rgb(rSeek.progress, gSeek.progress, bSeek.progress)
            prev.setBackgroundColor(c)
            prev.text = "  #${Integer.toHexString(c).uppercase().padStart(6,'0')}  "
            prev.setTextColor(if (Color.luminance(c) > 0.5f) Color.BLACK else Color.WHITE)
        }
        val presets = listOf(
            "Cyan"  to Color.CYAN,  "Orange" to Color.rgb(255,165,0),
            "White" to Color.WHITE, "Black"  to Color.BLACK,
            "Red"   to Color.RED,   "Lime"   to Color.rgb(50,255,0),
            "Blue"  to Color.BLUE,  "Yellow" to Color.YELLOW,
        )
        val presetRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        presets.forEach { (name, color) ->
            presetRow.addView(Button(this).apply {
                text = name; textSize = 10f; setBackgroundColor(color)
                setTextColor(if (color == Color.WHITE || color == Color.YELLOW) Color.BLACK else Color.WHITE)
                layoutParams = LinearLayout.LayoutParams(0, 90, 1f).apply { setMargins(2,2,2,2) }
                setOnClickListener {
                    rSeek.progress = Color.red(color); gSeek.progress = Color.green(color)
                    bSeek.progress = Color.blue(color); update()
                }
            })
        }
        layout.addView(presetRow)
        val cl = object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, v: Int, user: Boolean) { if (user) update() }
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {}
        }
        rSeek.setOnSeekBarChangeListener(cl); gSeek.setOnSeekBarChangeListener(cl); bSeek.setOnSeekBarChangeListener(cl)
        layout.addView(TextView(this).apply { text = "R"; setTextColor(Color.RED) }); layout.addView(rSeek)
        layout.addView(TextView(this).apply { text = "G"; setTextColor(Color.GREEN) }); layout.addView(gSeek)
        layout.addView(TextView(this).apply { text = "B"; setTextColor(Color.BLUE) }); layout.addView(bSeek)
        layout.addView(prev)
        AlertDialog.Builder(this).setTitle(title).setView(layout)
            .setPositiveButton("OK") { _, _ -> onPick(Color.rgb(rSeek.progress, gSeek.progress, bSeek.progress)) }
            .setNegativeButton("Cancel", null).show()
    }

    // SeekBar with an arbitrary minimum — Android's SeekBar always starts at 0,
    // so the offset is applied on the way in and out.
    private fun seekBar(initial: Int, min: Int, max: Int, onChange: (Int) -> Unit): SeekBar {
        return SeekBar(this).apply {
            this.max = max - min; progress = initial - min
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar, v: Int, user: Boolean) { if (user) onChange(v + min) }
                override fun onStartTrackingTouch(sb: SeekBar) {}
                override fun onStopTrackingTouch(sb: SeekBar) {}
            })
        }
    }

    private fun checkAndStart() {
        if (!Settings.canDrawOverlays(this))
            overlayPermLauncher.launch(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        else startOverlayService()
    }

    // Starts the service then drops to the background — the overlay is the product,
    // this activity only exists for settings.
    private fun startOverlayService() {
        if (!OverlayService.isRunning) startForegroundService(Intent(this, OverlayService::class.java))
        moveTaskToBack(true)
    }

    // Shares the debug log through the system share sheet. A FileProvider URI is
    // required — a raw file:// path would throw FileUriExposedException.
    private fun shareDebugLog() {
        val file = File(filesDir, "speedover_debug.log")
        if (!file.exists() || file.length() == 0L) {
            Toast.makeText(this, "No log data yet", Toast.LENGTH_SHORT).show()
            return
        }
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        shareLogLauncher.launch(Intent.createChooser(intent, "Share SpeedOver debug log"))
    }

    private fun deleteDebugLog() {
        val file = File(filesDir, "speedover_debug.log")
        if (file.exists()) file.delete()
    }

    // Asked after the share sheet closes. Android gives no success signal, so the
    // decision is left to the person who can actually see whether the mail sent.
    private fun confirmDeleteDebugLog() {
        val file = File(filesDir, "speedover_debug.log")
        if (!file.exists()) return
        AlertDialog.Builder(this)
            .setTitle("Delete debug log?")
            .setMessage("Only delete if the share succeeded. If something went wrong, keep the log and try again.")
            .setPositiveButton("Delete") { _, _ -> deleteDebugLog() }
            .setNegativeButton("Keep", null)
            .show()
    }

    private fun hasLocationPermission() =
        checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED

    private fun requestLocationPermission() {
        locationPermLauncher.launch(arrayOf(
            android.Manifest.permission.ACCESS_FINE_LOCATION,
            android.Manifest.permission.ACCESS_COARSE_LOCATION
        ))
    }

    // Intercepted at the window level so pinch works anywhere on the settings screen,
    // not just over the preview.
    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (::scaleDetector.isInitialized) scaleDetector.onTouchEvent(event)
        return super.dispatchTouchEvent(event)
    }
}

/**
 * Preview view — a scale mock-up of the phone screen showing where the overlay
 * will sit and how it will look. Cycles light/dark every 3 seconds so colour and
 * opacity choices can be judged against both.
 *
 * Demo values: speed 123, limit 80 (or the hard limit when set), NW arrow, and
 * road info at the bottom. Drag the speed number to reposition the real overlay.
 *
 * All drawing runs on the UI thread.
 */
class OverlayPreviewView(
    context: Context,
    private val prefs: Prefs,
    private val screenW: Int,
    private val screenH: Int,
    private val onPositionChanged: (Int, Int) -> Unit
) : View(context) {

    private var scale       = 1f
    private var isDarkTheme = false

    private val themeHandler  = Handler(Looper.getMainLooper())
    private val themeRunnable = object : Runnable {
        override fun run() { isDarkTheme = !isDarkTheme; invalidate(); themeHandler.postDelayed(this, 3000) }
    }

    fun startThemeCycle() { themeHandler.post(themeRunnable) }
    fun stopThemeCycle()  { themeHandler.removeCallbacks(themeRunnable) }

    private val p           = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint   = Paint(Paint.ANTI_ALIAS_FLAG)
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val borderPaint = Paint().apply {
        color = Color.rgb(60,60,60); style = Paint.Style.STROKE; strokeWidth = 2f
    }
    private val arrowFill   = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val arrowStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND
    }
    private val limitFill   = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL; typeface = Typeface.DEFAULT_BOLD; textAlign = Paint.Align.CENTER
    }
    private val limitStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; typeface = Typeface.DEFAULT_BOLD; textAlign = Paint.Align.CENTER
        strokeJoin = Paint.Join.ROUND
    }
    private val asteriskFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL; typeface = Typeface.DEFAULT_BOLD; textAlign = Paint.Align.LEFT
    }
    private val asteriskStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; typeface = Typeface.DEFAULT_BOLD; textAlign = Paint.Align.LEFT
        strokeJoin = Paint.Join.ROUND
    }
    private val infoFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL; typeface = Typeface.DEFAULT_BOLD; textAlign = Paint.Align.CENTER
    }
    private val infoStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; typeface = Typeface.DEFAULT_BOLD; textAlign = Paint.Align.CENTER
        strokeJoin = Paint.Join.ROUND
    }

    private var isDragging = false
    private var lastRawX   = 0f
    private var lastRawY   = 0f

    // Scale factor from real screen pixels down to preview pixels
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        scale = minOf(w.toFloat() / screenW, h.toFloat() / screenH)
    }

    override fun onDetachedFromWindow() { super.onDetachedFromWindow(); stopThemeCycle() }

    override fun onDraw(canvas: Canvas) {
        val pw      = screenW * scale
        val ph      = screenH * scale
        val offsetX = (width  - pw) / 2f
        val offsetY = (height - ph) / 2f
        canvas.save()
        canvas.translate(offsetX, offsetY)
        if (isDarkTheme) drawDarkBackground(canvas, pw, ph)
        else             drawLightBackground(canvas, pw, ph)
        drawOverlay(canvas, pw, ph)
        canvas.drawRect(0f, 0f, pw, ph, borderPaint)
        canvas.restore()
    }

    // Abstract stand-in for a light-themed app: nav bar, cards, text blocks.
    // Purely decorative — its only job is to be a realistic backdrop.
    private fun drawLightBackground(canvas: Canvas, pw: Float, ph: Float) {
        p.style = Paint.Style.FILL
        p.color = Color.rgb(242, 242, 247); canvas.drawRect(0f, 0f, pw, ph, p)
        val navH = ph * 0.08f
        p.color = Color.rgb(20, 90, 180); canvas.drawRect(0f, 0f, pw, navH, p)
        p.color = Color.WHITE
        canvas.drawRect(pw*0.06f, navH*0.3f, pw*0.35f, navH*0.55f, p)
        canvas.drawRect(pw*0.06f, navH*0.62f, pw*0.28f, navH*0.75f, p)
        for (i in 0..2) canvas.drawRect(pw*0.82f, navH*(0.25f+i*0.22f), pw*0.92f, navH*(0.33f+i*0.22f), p)
        val ct = navH + ph*0.01f; val cb = ph*0.48f; val cp = pw*0.03f
        p.color = Color.WHITE; canvas.drawRect(cp, ct, pw-cp, cb, p)
        p.color = Color.rgb(200,30,30); canvas.drawRect(cp*2, ct+ph*0.01f, pw*0.28f, ct+ph*0.04f, p)
        p.color = Color.rgb(20,20,20)
        canvas.drawRect(cp*2, ct+ph*0.05f, pw*0.92f, ct+ph*0.085f, p)
        canvas.drawRect(cp*2, ct+ph*0.095f, pw*0.78f, ct+ph*0.128f, p)
        p.color = Color.rgb(130,130,130); canvas.drawRect(cp*2, ct+ph*0.14f, pw*0.55f, ct+ph*0.162f, p)
        p.color = Color.rgb(195,215,240); canvas.drawRect(cp*2, ct+ph*0.175f, pw-cp*2, cb-ph*0.01f, p)
        p.color = Color.rgb(160,185,210); canvas.drawCircle(pw/2f, (ct+ph*0.175f+cb)/2f, pw*0.04f, p)
        val s1 = ph*0.495f; val s2 = ph*0.80f
        p.color = Color.WHITE; canvas.drawRect(cp, s1, pw-cp, s2, p)
        p.color = Color.rgb(20,90,180); canvas.drawCircle(cp*2+pw*0.04f, s1+ph*0.04f, pw*0.038f, p)
        p.color = Color.rgb(20,20,20)
        canvas.drawRect(pw*0.18f, s1+ph*0.018f, pw*0.58f, s1+ph*0.042f, p)
        p.color = Color.rgb(150,150,150); canvas.drawRect(pw*0.18f, s1+ph*0.05f, pw*0.38f, s1+ph*0.068f, p)
        p.color = Color.rgb(40,40,40)
        canvas.drawRect(cp*2, s1+ph*0.095f, pw*0.90f, s1+ph*0.116f, p)
        canvas.drawRect(cp*2, s1+ph*0.124f, pw*0.95f, s1+ph*0.145f, p)
        canvas.drawRect(cp*2, s1+ph*0.153f, pw*0.72f, s1+ph*0.174f, p)
        p.color = Color.rgb(220,220,225); canvas.drawRect(cp*2, s1+ph*0.195f, pw-cp*2, s1+ph*0.197f, p)
        listOf(Color.rgb(20,90,180), Color.rgb(220,50,50), Color.rgb(255,160,0)).forEachIndexed { i,c ->
            p.color = c; canvas.drawCircle(cp*2+pw*0.05f+i*pw*0.06f, s1+ph*0.225f, pw*0.022f, p)
        }
        p.color = Color.rgb(130,130,130); canvas.drawRect(pw*0.40f, s1+ph*0.213f, pw*0.68f, s1+ph*0.237f, p)
        p.color = Color.WHITE; canvas.drawRect(0f, ph*0.92f, pw, ph, p)
        p.color = Color.rgb(220,220,225); canvas.drawRect(0f, ph*0.92f, pw, ph*0.922f, p)
        listOf(0.15f,0.38f,0.62f,0.85f).forEach { x ->
            p.color = if (x==0.15f) Color.rgb(20,90,180) else Color.rgb(170,170,175)
            canvas.drawRect(pw*x-pw*0.04f, ph*0.935f, pw*x+pw*0.04f, ph*0.96f, p)
        }
    }

    // Same layout as the light mock-up, dark palette
    private fun drawDarkBackground(canvas: Canvas, pw: Float, ph: Float) {
        p.style = Paint.Style.FILL
        p.color = Color.rgb(15,15,18); canvas.drawRect(0f, 0f, pw, ph, p)
        val navH = ph*0.08f
        p.color = Color.rgb(10,10,14); canvas.drawRect(0f, 0f, pw, navH, p)
        p.color = Color.rgb(230,230,240)
        canvas.drawRect(pw*0.06f, navH*0.3f, pw*0.35f, navH*0.55f, p)
        canvas.drawRect(pw*0.06f, navH*0.62f, pw*0.28f, navH*0.75f, p)
        for (i in 0..2) canvas.drawRect(pw*0.82f, navH*(0.25f+i*0.22f), pw*0.92f, navH*(0.33f+i*0.22f), p)
        val ct = navH+ph*0.01f; val cb = ph*0.48f; val cp = pw*0.03f
        p.color = Color.rgb(28,28,34); canvas.drawRect(cp, ct, pw-cp, cb, p)
        p.color = Color.rgb(220,80,20); canvas.drawRect(cp*2, ct+ph*0.01f, pw*0.28f, ct+ph*0.04f, p)
        p.color = Color.rgb(230,230,240)
        canvas.drawRect(cp*2, ct+ph*0.05f, pw*0.92f, ct+ph*0.085f, p)
        canvas.drawRect(cp*2, ct+ph*0.095f, pw*0.78f, ct+ph*0.128f, p)
        p.color = Color.rgb(140,140,155); canvas.drawRect(cp*2, ct+ph*0.14f, pw*0.55f, ct+ph*0.162f, p)
        p.color = Color.rgb(38,38,52); canvas.drawRect(cp*2, ct+ph*0.175f, pw-cp*2, cb-ph*0.01f, p)
        p.color = Color.rgb(55,55,72); canvas.drawCircle(pw/2f, (ct+ph*0.175f+cb)/2f, pw*0.04f, p)
        val s1 = ph*0.495f; val s2 = ph*0.80f
        p.color = Color.rgb(28,28,34); canvas.drawRect(cp, s1, pw-cp, s2, p)
        p.color = Color.rgb(220,80,20); canvas.drawCircle(cp*2+pw*0.04f, s1+ph*0.04f, pw*0.038f, p)
        p.color = Color.rgb(220,220,235)
        canvas.drawRect(pw*0.18f, s1+ph*0.018f, pw*0.58f, s1+ph*0.042f, p)
        p.color = Color.rgb(120,120,138); canvas.drawRect(pw*0.18f, s1+ph*0.05f, pw*0.38f, s1+ph*0.068f, p)
        p.color = Color.rgb(200,200,215)
        canvas.drawRect(cp*2, s1+ph*0.095f, pw*0.90f, s1+ph*0.116f, p)
        canvas.drawRect(cp*2, s1+ph*0.124f, pw*0.95f, s1+ph*0.145f, p)
        canvas.drawRect(cp*2, s1+ph*0.153f, pw*0.72f, s1+ph*0.174f, p)
        p.color = Color.rgb(50,50,62); canvas.drawRect(cp*2, s1+ph*0.195f, pw-cp*2, s1+ph*0.197f, p)
        listOf(Color.rgb(80,130,255), Color.rgb(255,80,80), Color.rgb(255,180,30)).forEachIndexed { i,c ->
            p.color = c; canvas.drawCircle(cp*2+pw*0.05f+i*pw*0.06f, s1+ph*0.225f, pw*0.022f, p)
        }
        p.color = Color.rgb(110,110,130); canvas.drawRect(pw*0.40f, s1+ph*0.213f, pw*0.68f, s1+ph*0.237f, p)
        p.color = Color.rgb(10,10,14); canvas.drawRect(0f, ph*0.92f, pw, ph, p)
        p.color = Color.rgb(40,40,50); canvas.drawRect(0f, ph*0.92f, pw, ph*0.922f, p)
        listOf(0.15f,0.38f,0.62f,0.85f).forEach { x ->
            p.color = if (x==0.15f) Color.rgb(80,130,255) else Color.rgb(90,90,108)
            canvas.drawRect(pw*x-pw*0.04f, ph*0.935f, pw*x+pw*0.04f, ph*0.96f, p)
        }
    }

    // Mirrors SpeedOverlayView.onDraw at preview scale. The geometry factors
    // (0.38, 0.75, 1.25) must match that file or the preview lies about placement.
    private fun drawOverlay(canvas: Canvas, pw: Float, ph: Float) {
        val scaledSize = prefs.textSizePx * scale
        val nx = (prefs.overlayX + prefs.overlayWidth) * scale
        val ny = (prefs.overlayY + prefs.textSizePx * 0.85f) * scale

        // Speed number
        textPaint.apply {
            textSize = scaledSize; color = prefs.fillColor; alpha = prefs.textAlpha
            style = Paint.Style.FILL; typeface = Typeface.DEFAULT_BOLD; textAlign = Paint.Align.RIGHT
        }
        strokePaint.apply {
            textSize = scaledSize; color = prefs.strokeColor; alpha = prefs.textAlpha
            style = Paint.Style.STROKE; strokeWidth = prefs.strokeWidth * scale
            typeface = Typeface.DEFAULT_BOLD; textAlign = Paint.Align.RIGHT
        }
        canvas.drawText("123", nx, ny, strokePaint)
        canvas.drawText("123", nx, ny, textPaint)

        val arrowH  = scaledSize * 0.38f
        val indentD = arrowH * 0.28f
        val inset   = arrowH * 0.75f
        val right   = nx - inset
        val left    = right - arrowH
        val top     = ny + arrowH * 0.3f
        val bottom  = top + arrowH
        val cx      = (left + right) / 2f
        val cy      = (top + bottom) / 2f

        // Speed limit — shows the hard limit when one is set, otherwise a demo 80
        val limitFontSize = arrowH * 0.75f
        val overlayLeftX  = prefs.overlayX * scale
        val limitCenterX  = overlayLeftX + arrowH * 1.25f
        val limitText     = if (prefs.hardLimit > 0) prefs.hardLimit.toString() else "80"
        limitFill.apply {
            textSize = limitFontSize; color = prefs.fillColor; alpha = prefs.textAlpha
        }
        limitStroke.apply {
            textSize = limitFontSize; color = prefs.strokeColor; alpha = prefs.textAlpha
            strokeWidth = prefs.strokeWidth * scale * 0.5f
        }
        val lb = Rect()
        limitFill.getTextBounds(limitText, 0, limitText.length, lb)
        val limitY = cy + lb.height() / 2f - lb.bottom
        canvas.drawText(limitText, limitCenterX, limitY, limitStroke)
        canvas.drawText(limitText, limitCenterX, limitY, limitFill)

        // Asterisk shown here whenever hard limit is set, so its placement can be
        // judged. On the real overlay it appears only when hard limit is binding.
        if (prefs.hardLimit > 0) {
            val asteriskSize = limitFontSize * 0.6f
            val asteriskX    = limitCenterX + lb.width() / 2f + limitFontSize * 0.05f
            val asteriskY    = limitY - limitFontSize * 0.35f
            asteriskFill.apply   { textSize = asteriskSize; color = prefs.fillColor;   alpha = prefs.textAlpha }
            asteriskStroke.apply { textSize = asteriskSize; color = prefs.strokeColor; alpha = prefs.textAlpha; strokeWidth = prefs.strokeWidth * scale * 0.4f }
            canvas.drawText("*", asteriskX, asteriskY, asteriskStroke)
            canvas.drawText("*", asteriskX, asteriskY, asteriskFill)
        }

        // Direction arrow — fixed NW so its shape and rotation can be assessed
        val path = Path().apply {
            moveTo(cx, top); lineTo(right, bottom)
            lineTo(cx, bottom - indentD); lineTo(left, bottom); close()
        }
        arrowFill.apply   { color = prefs.fillColor;   alpha = prefs.textAlpha }
        arrowStroke.apply { color = prefs.strokeColor; alpha = prefs.textAlpha; strokeWidth = prefs.strokeWidth * scale * 0.5f }
        canvas.save()
        canvas.rotate(-315f, cx, cy)
        canvas.drawPath(path, arrowFill)
        canvas.drawPath(path, arrowStroke)
        canvas.restore()

        // Road info at the bottom, same order as the real overlay: ref, name, type
        val infoFontSize = scaledSize * 0.38f * 0.75f * 0.5f
        val infoCx       = pw / 2f
        val lineH        = infoFontSize * 1.35f
        val infoBaseY    = ph - lineH * 0.3f
        infoFill.apply   { textSize = infoFontSize; color = prefs.fillColor;   alpha = prefs.textAlpha }
        infoStroke.apply { textSize = infoFontSize; color = prefs.strokeColor; alpha = prefs.textAlpha; strokeWidth = prefs.strokeWidth * scale * 0.4f }

        val infoLines = mutableListOf<String>()
        if (prefs.showRoadRef)  infoLines.add("21")
        if (prefs.showRoadName) infoLines.add("Køge Bugt Motorvej")
        if (prefs.showRoadType) infoLines.add("motorway")
        infoLines.forEachIndexed { i, line ->
            val y = infoBaseY - (infoLines.size - 1 - i) * lineH
            canvas.drawText(line, infoCx, y, infoStroke)
            canvas.drawText(line, infoCx, y, infoFill)
        }
    }

    // Dragging the speed number moves the real overlay. Hit area is generous —
    // precision is not the point here.
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val pw      = screenW * scale
        val offsetX = (width  - pw) / 2f
        val offsetY = (height - screenH * scale) / 2f
        val px      = event.x - offsetX
        val py      = event.y - offsetY
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val nx   = (prefs.overlayX + prefs.overlayWidth) * scale
                val ny   = prefs.overlayY * scale
                val hitW = prefs.overlayWidth  * scale
                val hitH = prefs.textSizePx * 1.6f * scale
                if (px >= nx - hitW && px <= nx + hitW*0.2f && py >= ny && py <= ny + hitH) {
                    isDragging = true; lastRawX = event.rawX; lastRawY = event.rawY
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (isDragging) {
                    // Deltas are converted back to real screen pixels before saving
                    val dx   = ((event.rawX - lastRawX) / scale).toInt()
                    val dy   = ((event.rawY - lastRawY) / scale).toInt()
                    val maxX = (screenW - prefs.overlayWidth ).coerceAtLeast(0)
                    val maxY = (screenH - prefs.overlayHeight).coerceAtLeast(0)
                    onPositionChanged(
                        (prefs.overlayX + dx).coerceIn(0, maxX),
                        (prefs.overlayY + dy).coerceIn(0, maxY)
                    )
                    lastRawX = event.rawX; lastRawY = event.rawY; invalidate()
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> isDragging = false
        }
        return true
    }
}