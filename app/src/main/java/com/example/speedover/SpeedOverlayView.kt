package com.example.speedover

import android.content.Context
import android.graphics.*
import android.view.MotionEvent
import android.view.View

/**
 * SpeedOver Safety Awareness
 * Holle TechNolle, 2026
 *
 * Custom view that draws:
 *   - Speed number (top, right-aligned)
 *   - Speed limit (bottom-left, same colours and opacity as speed)
 *   - Direction arrow (bottom-right, inset so it never clips when rotated)
 *
 * Speed limit shows "00" when no data is available or speed is below threshold.
 * Arrow is hidden until GPS delivers a bearing; shows NW fallback after init.
 * Pinch-to-resize is handled at Activity level (MainActivity).
 *
 * Personal use only. Untested. Built for Xiaomi T10 — may work on other devices.
 */
class SpeedOverlayView(context: Context) : View(context) {

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    var speedKmh: Float = 0f
        set(value) { field = value; invalidate() }

    // Null = no bearing yet; keeps last known value once GPS delivers one
    var bearing: Float? = null
        set(value) { field = value; invalidate() }

    // 0 = show "00" (no data / speed below threshold)
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
        textAlign = Paint.Align.RIGHT
        style = Paint.Style.FILL
    }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textAlign = Paint.Align.RIGHT
        style = Paint.Style.STROKE
    }

    // --- Speed limit paints ---
    private val limitFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
        style = Paint.Style.FILL
    }
    private val limitStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
        style = Paint.Style.STROKE
    }

    // --- Arrow paints ---
    private val arrowFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val arrowStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }

    private var pointerDownX = 0f
    private var pointerDownY = 0f
    private var isDragging = false
    private val longPressRunnable = Runnable { onLongPress?.invoke() }

    override fun onDraw(canvas: Canvas) {
        // --- Speed number ---
        val text = speedKmh.toInt().toString()
        fillPaint.apply {
            textSize = textSizePx; color = fillColor; alpha = textAlpha
        }
        strokePaint.apply {
            textSize = textSizePx; color = strokeColor; alpha = textAlpha
            strokeWidth = strokeWidthFactor
        }
        val x = width.toFloat()
        val bounds = Rect()
        fillPaint.getTextBounds(text, 0, text.length, bounds)
        // Use font metrics instead of actual glyph bounds so position never shifts between digits
        val fm = fillPaint.fontMetrics
        val numberY = -fm.ascent + textSizePx * 0.05f
        canvas.drawText(text, x, numberY, strokePaint)
        canvas.drawText(text, x, numberY, fillPaint)

        // --- Shared geometry for bottom row ---
        val arrowH   = textSizePx * 0.38f
        val indentD  = arrowH * 0.28f
        val inset    = arrowH * 0.75f   // keeps rotated corners inside the view
        val right    = width.toFloat() - inset
        val left     = right - arrowH
        val top = numberY + (-fm.ascent + fm.descent) * 0.05f + arrowH * 0.3f
        val bottom   = top + arrowH
        val cx       = (left + right) / 2f
        val cy       = (top + bottom) / 2f

        // --- Speed limit (always drawn, left of arrow zone) ---
        val limitText      = if (speedLimitKmh > 0) speedLimitKmh.toString() else "00"
        val limitFontSize  = arrowH * 0.75f
        val limitGap       = arrowH * 0.15f
        // Mirror arrow centre relative to right edge:
        // xArrowCentre = width - arrowH*1.25f  →  xLimitCentre = width - arrowH*2.5f
        val limitCenterX = width.toFloat() - arrowH * 2.5f

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

        // Underline — shown when speed exceeds limit + 2, grows left-to-right up to limit*1.3 + 2
        if (speedLimitKmh > 0) {
            val lowerBound = speedLimitKmh + 2f
            val upperBound = speedLimitKmh * 1.3f + 2f

            if (speedKmh > lowerBound) {
                val progress    = ((speedKmh - lowerBound) / (upperBound - lowerBound)).coerceIn(0f, 1f)
                val boxWidth    = limitFillPaint.measureText("199")
                val lineLeft    = limitCenterX - boxWidth / 2f
                val lineRight   = lineLeft + progress * boxWidth
                val lineY       = limitY + limitFontSize * 0.18f

                linePaint.apply {
                    color       = strokeColor
                    alpha       = textAlpha
                    strokeWidth = strokeWidthFactor * 0.5f
                }
                canvas.drawLine(lineLeft, lineY, lineRight, lineY, linePaint)
            }
        }

        // --- Direction arrow (only when bearing is available) ---
        bearing?.let { b ->
            val path = Path().apply {
                moveTo(cx, top)               // tip — points north at bearing 0°
                lineTo(right, bottom)          // back-right
                lineTo(cx, bottom - indentD)   // back indent
                lineTo(left, bottom)           // back-left
                close()
            }
            arrowFillPaint.apply   { color = fillColor;   alpha = textAlpha }
            arrowStrokePaint.apply {
                color = strokeColor; alpha = textAlpha
                strokeWidth = strokeWidthFactor * 0.5f
            }
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