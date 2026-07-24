// SpeedOverlayView.kt
package com.example.speedover

import android.content.Context
import android.graphics.*
import android.view.MotionEvent
import android.view.View

/**
 * SpeedOver Safety Awareness
 * Holle TechNolle, 2026
 *
 * Draws speed number, speed limit (hidden when 0) and direction arrow.
 * ViolationGradient lives entirely in OverlayService / ViolationView.
 * Road name and road type live in RoadInfoView / OverlayService.
 *
 * Personal use only. Untested. Built for Xiaomi T10 — may work on other devices.
 */
class SpeedOverlayView(context: Context) : View(context) {

    var speedKmh: Float = 0f
        set(value) { field = value; invalidate() }

    var bearing: Float? = null
        set(value) { field = value; invalidate() }

    // 0 = no data — talLim hidden entirely
    var speedLimitKmh: Int = 0
        set(value) { field = value; invalidate() }

    var fillColor: Int = Color.WHITE
        set(value) { field = value; invalidate() }

    var strokeColor: Int = Color.BLACK
        set(value) { field = value; invalidate() }

    var textAlpha: Int = 255
        set(value) { field = value; invalidate() }

    var strokeWidthFactor: Float = 3f
        set(value) { field = value; invalidate() }

    var textSizePx: Float = 200f
        set(value) { field = value.coerceIn(40f, 800f); invalidate() }

    var settingsMode: Boolean = false
        set(value) {
            field = value
            setBackgroundColor(if (value) Color.BLACK else Color.TRANSPARENT)
            invalidate()
        }

    var onDrag: ((dx: Float, dy: Float) -> Unit)? = null
    var onLongPress: (() -> Unit)? = null

    // --- Speed number paints ---
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textAlign = Paint.Align.RIGHT; style = Paint.Style.FILL
    }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textAlign = Paint.Align.RIGHT; style = Paint.Style.STROKE
    }

    // --- Speed limit paints ---
    private val limitFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textAlign = Paint.Align.CENTER; style = Paint.Style.FILL
    }
    private val limitStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textAlign = Paint.Align.CENTER; style = Paint.Style.STROKE
    }

    // --- Direction arrow paints ---
    private val arrowFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val arrowStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND
    }

    private var pointerDownX = 0f
    private var pointerDownY = 0f
    private var isDragging   = false
    private val longPressRunnable = Runnable { onLongPress?.invoke() }

    override fun onDraw(canvas: Canvas) {

        // --- Speed number ---
        val text = speedKmh.toInt().toString()
        fillPaint.apply   { textSize = textSizePx; color = fillColor;   alpha = textAlpha }
        strokePaint.apply { textSize = textSizePx; color = strokeColor; alpha = textAlpha; strokeWidth = strokeWidthFactor }
        val x  = width.toFloat()
        val fm = fillPaint.fontMetrics
        // Font metrics for stable vertical position regardless of which digits are shown
        val numberY = -fm.ascent + textSizePx * 0.05f
        canvas.drawText(text, x, numberY, strokePaint)
        canvas.drawText(text, x, numberY, fillPaint)

        // --- Shared bottom-row geometry ---
        val arrowH  = textSizePx * 0.38f
        val indentD = arrowH * 0.28f
        val inset   = arrowH * 0.75f
        val right   = width.toFloat() - inset
        val left    = right - arrowH
        val top     = numberY + (-fm.ascent + fm.descent) * 0.05f + arrowH * 0.3f
        val bottom  = top + arrowH
        val cx      = (left + right) / 2f
        val cy      = (top + bottom) / 2f

        // --- Speed limit — hidden entirely when speedLimitKmh == 0 ---
        if (speedLimitKmh > 0) {
            val limitText     = speedLimitKmh.toString()
            val limitFontSize = arrowH * 0.75f
            // Centre mirrors arrow centre relative to right edge
            val limitCenterX  = arrowH * 1.25f

            limitFillPaint.apply {
                textSize = limitFontSize; color = fillColor; alpha = textAlpha
            }
            limitStrokePaint.apply {
                textSize = limitFontSize; color = strokeColor; alpha = textAlpha
                strokeWidth = strokeWidthFactor * 0.5f
            }
            val limitBounds = Rect()
            limitFillPaint.getTextBounds(limitText, 0, limitText.length, limitBounds)
            val limitY = cy + limitBounds.height() / 2f - limitBounds.bottom
            canvas.drawText(limitText, limitCenterX, limitY, limitStrokePaint)
            canvas.drawText(limitText, limitCenterX, limitY, limitFillPaint)
        }

        // --- Direction arrow ---
        bearing?.let { b ->
            val path = Path().apply {
                moveTo(cx, top)
                lineTo(right, bottom)
                lineTo(cx, bottom - indentD)
                lineTo(left, bottom)
                close()
            }
            arrowFillPaint.apply   { color = fillColor;   alpha = textAlpha }
            arrowStrokePaint.apply { color = strokeColor; alpha = textAlpha; strokeWidth = strokeWidthFactor * 0.5f }
            canvas.save()
            canvas.rotate(-b, cx, cy)
            canvas.drawPath(path, arrowFillPaint)
            canvas.drawPath(path, arrowStrokePaint)
            canvas.restore()
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!settingsMode) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                pointerDownX = event.rawX; pointerDownY = event.rawY
                isDragging = false; postDelayed(longPressRunnable, 600L)
            }
            MotionEvent.ACTION_MOVE -> {
                if (event.pointerCount == 1) {
                    val dx = event.rawX - pointerDownX
                    val dy = event.rawY - pointerDownY
                    if (!isDragging && (Math.abs(dx) > 10 || Math.abs(dy) > 10)) {
                        isDragging = true; removeCallbacks(longPressRunnable)
                    }
                    if (isDragging) {
                        onDrag?.invoke(dx, dy)
                        pointerDownX = event.rawX; pointerDownY = event.rawY
                    }
                } else removeCallbacks(longPressRunnable)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(longPressRunnable); isDragging = false
            }
        }
        return true
    }
}