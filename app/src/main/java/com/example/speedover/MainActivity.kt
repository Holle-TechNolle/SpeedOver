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

/**
 * SpeedOver Safety Awareness
 * Holle TechNolle, 2026
 *
 * Entry point. Handles permissions, starts the overlay service,
 * and shows the settings UI when brought to the foreground from recents.
 *
 * The settings screen contains a live preview that cycles between a light and
 * a dark mock-up background every 3 seconds, so colour and opacity choices can
 * be evaluated against both light and dark app themes before locking them in.
 *
 * Personal use only. Untested. Built for Xiaomi T10 — may work on other devices.
 */
class MainActivity : AppCompatActivity() {

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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        if (!hasLocationPermission()) requestLocationPermission() else checkAndStart()
    }

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

    private fun showSettingsUI() {
        val dm      = resources.displayMetrics
        val screenW = dm.widthPixels
        val screenH = dm.heightPixels

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
        }

        // --- PREVIEW ---
        val preview = OverlayPreviewView(this, prefs, screenW, screenH) { newX, newY ->
            prefs.overlayX = newX
            prefs.overlayY = newY
            sendBroadcast(
                Intent(OverlayService.ACTION_MOVE).setPackage(packageName)
                    .putExtra("x", newX).putExtra("y", newY)
            )
        }
        previewView = preview
        preview.startThemeCycle()

        root.addView(preview, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0
        ).apply { weight = 1f })

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

        fun labelRow(labelText: String, value: String): Triple<LinearLayout, TextView, TextView> {
            val lbl = TextView(this).apply {
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

        // Colour buttons — side by side
        controls.addView(label("Colour"))
        val colourRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }

        val fillBtn = Button(this).apply {
            text = "Fill"; setBackgroundColor(prefs.fillColor)
            setTextColor(if (Color.luminance(prefs.fillColor) > 0.5f) Color.BLACK else Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                .apply { setMargins(0, 0, 8, 0) }
            setOnClickListener {
                showColorPicker("Fill colour", prefs.fillColor) { color ->
                    prefs.fillColor = color
                    setBackgroundColor(color)
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
                    prefs.strokeColor = color
                    setBackgroundColor(color)
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

        // Opacity
        val (alphaRow, _, alphaVal) = labelRow("Opacity", "${(prefs.textAlpha / 255f * 100).toInt()}%")
        controls.addView(alphaRow)
        controls.addView(seekBar(prefs.textAlpha, 0, 255) { v ->
            prefs.textAlpha = v; alphaVal.text = "${(v / 255f * 100).toInt()}%"
            sendBroadcast(Intent(OverlayService.ACTION_UPDATE_PREFS).setPackage(packageName))
            preview.invalidate()
        })

        // GPS keepalive
        val (gpsRow, _, gpsVal) = labelRow("GPS keepalive", "${prefs.gpsKeepaliveSeconds} sec")
        controls.addView(gpsRow)
        controls.addView(seekBar(prefs.gpsKeepaliveSeconds, 5, 60) { v ->
            prefs.gpsKeepaliveSeconds = v; gpsVal.text = "$v sec"
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

        val scrollView = ScrollView(this).apply {
            addView(controls)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        root.addView(scrollView)

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

    private fun showColorPicker(title: String, current: Int, onPick: (Int) -> Unit) {
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(40, 20, 40, 20) }
        val rSeek = SeekBar(this).apply { max = 255; progress = Color.red(current) }
        val gSeek = SeekBar(this).apply { max = 255; progress = Color.green(current) }
        val bSeek = SeekBar(this).apply { max = 255; progress = Color.blue(current) }
        val preview = TextView(this).apply {
            text = "  #${Integer.toHexString(current).uppercase().padStart(6, '0')}  "
            setBackgroundColor(current)
            setTextColor(if (Color.luminance(current) > 0.5f) Color.BLACK else Color.WHITE)
            textSize = 16f; setPadding(10, 10, 10, 10)
        }
        fun updatePreview() {
            val c = Color.rgb(rSeek.progress, gSeek.progress, bSeek.progress)
            preview.setBackgroundColor(c)
            preview.text = "  #${Integer.toHexString(c).uppercase().padStart(6, '0')}  "
            preview.setTextColor(if (Color.luminance(c) > 0.5f) Color.BLACK else Color.WHITE)
        }
        val presets = listOf(
            "White" to Color.WHITE, "Black" to Color.BLACK,
            "Red" to Color.RED,     "Lime" to Color.rgb(50, 255, 0),
            "Blue" to Color.BLUE,   "Yellow" to Color.YELLOW,
            "Cyan" to Color.CYAN,   "Orange" to Color.rgb(255, 165, 0)
        )
        val presetRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        presets.forEach { (name, color) ->
            presetRow.addView(Button(this).apply {
                text = name; textSize = 10f; setBackgroundColor(color)
                setTextColor(if (color == Color.WHITE || color == Color.YELLOW) Color.BLACK else Color.WHITE)
                layoutParams = LinearLayout.LayoutParams(0, 90, 1f).apply { setMargins(2, 2, 2, 2) }
                setOnClickListener {
                    rSeek.progress = Color.red(color); gSeek.progress = Color.green(color)
                    bSeek.progress = Color.blue(color); updatePreview()
                }
            })
        }
        layout.addView(presetRow)
        val cl = object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, v: Int, user: Boolean) { if (user) updatePreview() }
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {}
        }
        rSeek.setOnSeekBarChangeListener(cl); gSeek.setOnSeekBarChangeListener(cl); bSeek.setOnSeekBarChangeListener(cl)
        layout.addView(TextView(this).apply { text = "R"; setTextColor(Color.RED) })
        layout.addView(rSeek)
        layout.addView(TextView(this).apply { text = "G"; setTextColor(Color.GREEN) })
        layout.addView(gSeek)
        layout.addView(TextView(this).apply { text = "B"; setTextColor(Color.BLUE) })
        layout.addView(bSeek)
        layout.addView(preview)
        AlertDialog.Builder(this).setTitle(title).setView(layout)
            .setPositiveButton("OK") { _, _ -> onPick(Color.rgb(rSeek.progress, gSeek.progress, bSeek.progress)) }
            .setNegativeButton("Cancel", null).show()
    }

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

    private fun startOverlayService() {
        if (!OverlayService.isRunning) startForegroundService(Intent(this, OverlayService::class.java))
        moveTaskToBack(true)
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

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (::scaleDetector.isInitialized) scaleDetector.onTouchEvent(event)
        return super.dispatchTouchEvent(event)
    }
}

/**
 * Preview view — mock-up of the phone screen at correct scale.
 *
 * Cycles between a light (news/social-media) and a dark background every 3 seconds
 * so overlay colours can be validated against both typical app themes.
 * The overlay number and direction arrow are drawn at their current prefs-defined
 * size and position, with a fixed NW demonstration bearing.
 *
 * Drag the number to reposition the actual overlay.
 */
class OverlayPreviewView(
    context: Context,
    private val prefs: Prefs,
    private val screenW: Int,
    private val screenH: Int,
    private val onPositionChanged: (Int, Int) -> Unit
) : View(context) {

    private var scale      = 1f
    private var isDarkTheme = false

    private val themeHandler  = Handler(Looper.getMainLooper())
    private val themeRunnable = object : Runnable {
        override fun run() {
            isDarkTheme = !isDarkTheme
            invalidate()
            themeHandler.postDelayed(this, 3000)
        }
    }

    fun startThemeCycle() { themeHandler.post(themeRunnable) }
    fun stopThemeCycle()  { themeHandler.removeCallbacks(themeRunnable) }

    private val p          = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint  = Paint(Paint.ANTI_ALIAS_FLAG)
    private val strokePaint= Paint(Paint.ANTI_ALIAS_FLAG)
    private val borderPaint= Paint().apply {
        color = Color.rgb(60, 60, 60); style = Paint.Style.STROKE; strokeWidth = 2f
    }
    private val arrowFill  = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val arrowStroke= Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND
    }

    private var isDragging = false
    private var lastRawX   = 0f
    private var lastRawY   = 0f

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

        drawOverlayNumber(canvas, pw, ph)
        canvas.drawRect(0f, 0f, pw, ph, borderPaint)
        canvas.restore()
    }

    // -----------------------------------------------------------------------
    // Light theme — simulates a white news / social-media app
    // -----------------------------------------------------------------------
    private fun drawLightBackground(canvas: Canvas, pw: Float, ph: Float) {
        // Page background
        p.color = Color.rgb(242, 242, 247); p.style = Paint.Style.FILL
        canvas.drawRect(0f, 0f, pw, ph, p)

        // Top navigation bar
        val navH = ph * 0.08f
        p.color = Color.rgb(20, 90, 180)
        canvas.drawRect(0f, 0f, pw, navH, p)
        // "BREAKING NEWS" brand text approximated as white rects
        p.color = Color.WHITE
        canvas.drawRect(pw * 0.06f, navH * 0.3f, pw * 0.35f, navH * 0.55f, p)
        canvas.drawRect(pw * 0.06f, navH * 0.62f, pw * 0.28f, navH * 0.75f, p)
        // Menu icon — three lines on right
        val mi = pw * 0.82f
        for (i in 0..2) canvas.drawRect(mi, navH * (0.25f + i * 0.22f), pw * 0.92f, navH * (0.33f + i * 0.22f), p)

        // Hero article card
        val cardTop  = navH + ph * 0.01f
        val cardBot  = ph * 0.48f
        val cardPad  = pw * 0.03f
        p.color = Color.WHITE
        canvas.drawRect(cardPad, cardTop, pw - cardPad, cardBot, p)

        // Category tag
        p.color = Color.rgb(200, 30, 30)
        canvas.drawRect(cardPad * 2, cardTop + ph * 0.01f, pw * 0.28f, cardTop + ph * 0.04f, p)

        // Headline — two dark lines
        p.color = Color.rgb(20, 20, 20)
        canvas.drawRect(cardPad * 2, cardTop + ph * 0.05f, pw * 0.92f, cardTop + ph * 0.085f, p)
        canvas.drawRect(cardPad * 2, cardTop + ph * 0.095f, pw * 0.78f, cardTop + ph * 0.128f, p)

        // Byline
        p.color = Color.rgb(130, 130, 130)
        canvas.drawRect(cardPad * 2, cardTop + ph * 0.14f, pw * 0.55f, cardTop + ph * 0.162f, p)

        // Hero image placeholder
        p.color = Color.rgb(195, 215, 240)
        canvas.drawRect(cardPad * 2, cardTop + ph * 0.175f, pw - cardPad * 2, cardBot - ph * 0.01f, p)
        // Tiny camera icon suggestion
        p.color = Color.rgb(160, 185, 210)
        canvas.drawCircle(pw / 2f, (cardTop + ph * 0.175f + cardBot) / 2f, pw * 0.04f, p)

        // Social card
        val sc1 = ph * 0.495f
        val sc2 = ph * 0.80f
        p.color = Color.WHITE
        canvas.drawRect(cardPad, sc1, pw - cardPad, sc2, p)
        // Avatar
        p.color = Color.rgb(20, 90, 180)
        canvas.drawCircle(cardPad * 2 + pw * 0.04f, sc1 + ph * 0.04f, pw * 0.038f, p)
        // Name and timestamp
        p.color = Color.rgb(20, 20, 20)
        canvas.drawRect(pw * 0.18f, sc1 + ph * 0.018f, pw * 0.58f, sc1 + ph * 0.042f, p)
        p.color = Color.rgb(150, 150, 150)
        canvas.drawRect(pw * 0.18f, sc1 + ph * 0.05f, pw * 0.38f, sc1 + ph * 0.068f, p)
        // Post text lines
        p.color = Color.rgb(40, 40, 40)
        canvas.drawRect(cardPad * 2, sc1 + ph * 0.095f, pw * 0.90f, sc1 + ph * 0.116f, p)
        canvas.drawRect(cardPad * 2, sc1 + ph * 0.124f, pw * 0.95f, sc1 + ph * 0.145f, p)
        canvas.drawRect(cardPad * 2, sc1 + ph * 0.153f, pw * 0.72f, sc1 + ph * 0.174f, p)
        // Reaction bar
        p.color = Color.rgb(220, 220, 225)
        canvas.drawRect(cardPad * 2, sc1 + ph * 0.195f, pw - cardPad * 2, sc1 + ph * 0.197f, p)
        val rColors = listOf(Color.rgb(20,90,180), Color.rgb(220,50,50), Color.rgb(255,160,0))
        rColors.forEachIndexed { i, c ->
            p.color = c
            canvas.drawCircle(cardPad * 2 + pw * 0.05f + i * pw * 0.06f, sc1 + ph * 0.225f, pw * 0.022f, p)
        }
        p.color = Color.rgb(130, 130, 130)
        canvas.drawRect(pw * 0.40f, sc1 + ph * 0.213f, pw * 0.68f, sc1 + ph * 0.237f, p)

        // Bottom navigation bar
        p.color = Color.WHITE
        canvas.drawRect(0f, ph * 0.92f, pw, ph, p)
        p.color = Color.rgb(220, 220, 225)
        canvas.drawRect(0f, ph * 0.92f, pw, ph * 0.922f, p)
        val icons = listOf(0.15f, 0.38f, 0.62f, 0.85f)
        icons.forEach { x ->
            p.color = if (x == 0.15f) Color.rgb(20, 90, 180) else Color.rgb(170, 170, 175)
            canvas.drawRect(pw * x - pw * 0.04f, ph * 0.935f, pw * x + pw * 0.04f, ph * 0.96f, p)
        }
    }

    // -----------------------------------------------------------------------
    // Dark theme — simulates a dark-mode news / social-media app
    // -----------------------------------------------------------------------
    private fun drawDarkBackground(canvas: Canvas, pw: Float, ph: Float) {
        p.color = Color.rgb(15, 15, 18); p.style = Paint.Style.FILL
        canvas.drawRect(0f, 0f, pw, ph, p)

        val navH = ph * 0.08f
        p.color = Color.rgb(10, 10, 14)
        canvas.drawRect(0f, 0f, pw, navH, p)
        p.color = Color.rgb(230, 230, 240)
        canvas.drawRect(pw * 0.06f, navH * 0.3f, pw * 0.35f, navH * 0.55f, p)
        canvas.drawRect(pw * 0.06f, navH * 0.62f, pw * 0.28f, navH * 0.75f, p)
        for (i in 0..2) canvas.drawRect(pw * 0.82f, navH * (0.25f + i * 0.22f), pw * 0.92f, navH * (0.33f + i * 0.22f), p)

        val cardTop = navH + ph * 0.01f
        val cardBot = ph * 0.48f
        val cardPad = pw * 0.03f
        p.color = Color.rgb(28, 28, 34)
        canvas.drawRect(cardPad, cardTop, pw - cardPad, cardBot, p)

        p.color = Color.rgb(220, 80, 20)
        canvas.drawRect(cardPad * 2, cardTop + ph * 0.01f, pw * 0.28f, cardTop + ph * 0.04f, p)

        p.color = Color.rgb(230, 230, 240)
        canvas.drawRect(cardPad * 2, cardTop + ph * 0.05f, pw * 0.92f, cardTop + ph * 0.085f, p)
        canvas.drawRect(cardPad * 2, cardTop + ph * 0.095f, pw * 0.78f, cardTop + ph * 0.128f, p)

        p.color = Color.rgb(140, 140, 155)
        canvas.drawRect(cardPad * 2, cardTop + ph * 0.14f, pw * 0.55f, cardTop + ph * 0.162f, p)

        p.color = Color.rgb(38, 38, 52)
        canvas.drawRect(cardPad * 2, cardTop + ph * 0.175f, pw - cardPad * 2, cardBot - ph * 0.01f, p)
        p.color = Color.rgb(55, 55, 72)
        canvas.drawCircle(pw / 2f, (cardTop + ph * 0.175f + cardBot) / 2f, pw * 0.04f, p)

        val sc1 = ph * 0.495f
        val sc2 = ph * 0.80f
        p.color = Color.rgb(28, 28, 34)
        canvas.drawRect(cardPad, sc1, pw - cardPad, sc2, p)

        p.color = Color.rgb(220, 80, 20)
        canvas.drawCircle(cardPad * 2 + pw * 0.04f, sc1 + ph * 0.04f, pw * 0.038f, p)

        p.color = Color.rgb(220, 220, 235)
        canvas.drawRect(pw * 0.18f, sc1 + ph * 0.018f, pw * 0.58f, sc1 + ph * 0.042f, p)
        p.color = Color.rgb(120, 120, 138)
        canvas.drawRect(pw * 0.18f, sc1 + ph * 0.05f, pw * 0.38f, sc1 + ph * 0.068f, p)

        p.color = Color.rgb(200, 200, 215)
        canvas.drawRect(cardPad * 2, sc1 + ph * 0.095f, pw * 0.90f, sc1 + ph * 0.116f, p)
        canvas.drawRect(cardPad * 2, sc1 + ph * 0.124f, pw * 0.95f, sc1 + ph * 0.145f, p)
        canvas.drawRect(cardPad * 2, sc1 + ph * 0.153f, pw * 0.72f, sc1 + ph * 0.174f, p)

        p.color = Color.rgb(50, 50, 62)
        canvas.drawRect(cardPad * 2, sc1 + ph * 0.195f, pw - cardPad * 2, sc1 + ph * 0.197f, p)
        val rColors = listOf(Color.rgb(80,130,255), Color.rgb(255,80,80), Color.rgb(255,180,30))
        rColors.forEachIndexed { i, c ->
            p.color = c
            canvas.drawCircle(cardPad * 2 + pw * 0.05f + i * pw * 0.06f, sc1 + ph * 0.225f, pw * 0.022f, p)
        }
        p.color = Color.rgb(110, 110, 130)
        canvas.drawRect(pw * 0.40f, sc1 + ph * 0.213f, pw * 0.68f, sc1 + ph * 0.237f, p)

        p.color = Color.rgb(10, 10, 14)
        canvas.drawRect(0f, ph * 0.92f, pw, ph, p)
        p.color = Color.rgb(40, 40, 50)
        canvas.drawRect(0f, ph * 0.92f, pw, ph * 0.922f, p)
        val icons = listOf(0.15f, 0.38f, 0.62f, 0.85f)
        icons.forEach { x ->
            p.color = if (x == 0.15f) Color.rgb(80, 130, 255) else Color.rgb(90, 90, 108)
            canvas.drawRect(pw * x - pw * 0.04f, ph * 0.935f, pw * x + pw * 0.04f, ph * 0.96f, p)
        }
    }

    // -----------------------------------------------------------------------
    // Overlay number + direction arrow at current prefs size and position
    // -----------------------------------------------------------------------
    private fun drawOverlayNumber(canvas: Canvas, pw: Float, ph: Float) {
        val scaledSize = prefs.textSizePx * scale
        val nx = (prefs.overlayX + prefs.overlayWidth) * scale
        val ny = (prefs.overlayY + prefs.textSizePx * 0.85f) * scale

        textPaint.apply {
            textSize = scaledSize; color = prefs.fillColor; alpha = prefs.textAlpha
            style = Paint.Style.FILL; typeface = Typeface.DEFAULT_BOLD; textAlign = Paint.Align.RIGHT
        }
        strokePaint.apply {
            textSize = scaledSize; color = prefs.strokeColor; alpha = prefs.textAlpha
            style = Paint.Style.STROKE; strokeWidth = prefs.strokeWidth * scale
            typeface = Typeface.DEFAULT_BOLD; textAlign = Paint.Align.RIGHT
        }
        canvas.drawText("73", nx, ny, strokePaint)
        canvas.drawText("73", nx, ny, textPaint)

        // Direction arrow — fixed NW bearing for demonstration
        val arrowH   = scaledSize * 0.38f
        val indentD  = arrowH * 0.28f
        val inset    = arrowH * 0.75f
        val right    = nx - inset
        val left     = right - arrowH
        val top      = ny + arrowH * 0.3f
        val bottom   = top + arrowH
        val cx       = (left + right) / 2f
        val cy       = (top + bottom) / 2f

        val path = Path().apply {
            moveTo(cx, top); lineTo(right, bottom)
            lineTo(cx, bottom - indentD); lineTo(left, bottom); close()
        }
        arrowFill.apply   { color = prefs.fillColor;   alpha = prefs.textAlpha }
        arrowStroke.apply { color = prefs.strokeColor; alpha = prefs.textAlpha
            strokeWidth = prefs.strokeWidth * scale * 0.5f }

        canvas.save()
        canvas.rotate(-315f, cx, cy)   // NW demonstration bearing
        canvas.drawPath(path, arrowFill)
        canvas.drawPath(path, arrowStroke)
        canvas.restore()
    }

    // -----------------------------------------------------------------------
    // Touch — drag number to reposition overlay
    // -----------------------------------------------------------------------
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val pw      = screenW * scale
        val ph      = screenH * scale
        val offsetX = (width  - pw) / 2f
        val offsetY = (height - ph) / 2f
        val px      = event.x - offsetX
        val py      = event.y - offsetY

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val nx   = (prefs.overlayX + prefs.overlayWidth) * scale
                val ny   = prefs.overlayY * scale
                val hitW = prefs.overlayWidth  * scale
                val hitH = prefs.textSizePx * 1.6f * scale   // tall enough to include arrow
                if (px >= nx - hitW && px <= nx + hitW * 0.2f && py >= ny && py <= ny + hitH) {
                    isDragging = true; lastRawX = event.rawX; lastRawY = event.rawY
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (isDragging) {
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