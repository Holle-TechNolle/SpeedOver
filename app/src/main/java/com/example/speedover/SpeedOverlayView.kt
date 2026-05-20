package com.example.speedover

import android.content.Context
import android.graphics.*
import android.view.MotionEvent
import android.view.View

/**
 * SpeedOver Safety Awareness
 * Holle TechNolle, 2026
 *
 * Custom view that draws the speed number and a north-pointing arrow below it.
 * The arrow is inset from the right edge so it remains fully visible at all
 * rotation angles. Gap between number and arrow is likewise calculated from
 * the arrow's diagonal radius so the tail never overlaps the number.
 * Pinch-to-resize is handled at Activity level (MainActivity).
 *
 * Personal use only. Untested. Built for Xiaomi T10 — may work on other devices.
 */
class SpeedOverlayView(context: Context) : View(context) {

    var speedKmh: Float = 0f
        set(value) { field = value; invalidate() }

    // Null = no bearing available yet; keeps last known value once set
    var bearing: Float? = null
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
        val text = speedKmh.toInt().toString()

        fillPaint.apply {
            textSize = textSizePx
            color = fillColor
            alpha = textAlpha
        }
        strokePaint.apply {
            textSize = textSizePx
            color = strokeColor
            alpha = textAlpha
            strokeWidth = strokeWidthFactor
        }

        val x = width.toFloat()
        val bounds = Rect()
        fillPaint.getTextBounds(text, 0, text.length, bounds)
        val numberY = bounds.height().toFloat() + textSizePx * 0.05f

        canvas.drawText(text, x, numberY, strokePaint)
        canvas.drawText(text, x, numberY, fillPaint)

        // --- Direction arrow ---
        bearing?.let { b ->
            val arrowH   = textSizePx * 0.38f
            val indentD  = arrowH * 0.28f

            // Inset from right edge: arrow diagonal radius = arrowH * 0.707 (√2/2).
            // Using 0.75 gives a small safety margin so the tail never clips the edge.
            val inset = arrowH * 0.75f
            val right  = width.toFloat() - inset
            val left   = right - arrowH   // width equals height for equal diagonal radius
            val top    = numberY + arrowH * 0.3f   // gap = arrow diagonal radius minus half-height
            val bottom = top + arrowH
            val cx     = (left + right) / 2f
            val cy     = (top + bottom) / 2f

            // Dart shape: tip at top, concave indent at back centre
            val path = Path().apply {
                moveTo(cx, top)               // tip — points north at bearing 0°
                lineTo(right, bottom)          // back-right
                lineTo(cx, bottom - indentD)   // back indent
                lineTo(left, bottom)           // back-left
                close()
            }

            arrowFillPaint.apply {
                color = fillColor
                alpha = textAlpha
            }
            arrowStrokePaint.apply {
                color = strokeColor
                alpha = textAlpha
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
                pointerDownX = event.rawX
                pointerDownY = event.rawY
                isDragging = false
                postDelayed(longPressRunnable, 600L)
            }
            MotionEvent.ACTION_MOVE -> {
                if (event.pointerCount == 1) {
                    val dx = event.rawX - pointerDownX
                    val dy = event.rawY - pointerDownY
                    if (!isDragging && (Math.abs(dx) > 10 || Math.abs(dy) > 10)) {
                        isDragging = true
                        removeCallbacks(longPressRunnable)
                    }
                    if (isDragging) {
                        onDrag?.invoke(dx, dy)
                        pointerDownX = event.rawX
                        pointerDownY = event.rawY
                    }
                } else {
                    removeCallbacks(longPressRunnable)
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(longPressRunnable)
                isDragging = false
            }
        }
        return true
    }
}