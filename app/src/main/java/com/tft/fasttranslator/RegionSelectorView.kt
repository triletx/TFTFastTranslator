package com.tft.fasttranslator

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class RegionSelectorView(
    context: Context,
    initialNorm: RectF,
    private val onRegionSelected: (RectF) -> Unit
) : View(context) {

    private val dimPaint = Paint().apply {
        color = Color.parseColor("#99050B14")
        style = Paint.Style.FILL
    }
    private val borderPaint = Paint().apply {
        color = Color.parseColor("#C8AA6E")
        style = Paint.Style.STROKE
        strokeWidth = 6f
        isAntiAlias = true
    }
    private val cornerPaint = Paint().apply {
        color = Color.parseColor("#0AC8B9")
        style = Paint.Style.STROKE
        strokeWidth = 10f
        isAntiAlias = true
    }
    private val hintBgPaint = Paint().apply {
        color = Color.parseColor("#E6091428")
        style = Paint.Style.FILL
        isAntiAlias = true
    }
    private val hintTextPaint = Paint().apply {
        color = Color.parseColor("#F0E6D2")
        textSize = 36f
        textAlign = Paint.Align.CENTER
        isAntiAlias = true
    }

    private var normRect = RectF(initialNorm)
    private var startX = 0f
    private var startY = 0f
    private var curX = 0f
    private var curY = 0f
    private var isDragging = false

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()

        val rect = if (isDragging) {
            RectF(min(startX, curX), min(startY, curY), max(startX, curX), max(startY, curY))
        } else {
            RectF(normRect.left * w, normRect.top * h, normRect.right * w, normRect.bottom * h)
        }

        // Draw 4 dim areas around selection (hardware-safe)
        canvas.drawRect(0f, 0f, w, rect.top, dimPaint)
        canvas.drawRect(0f, rect.bottom, w, h, dimPaint)
        canvas.drawRect(0f, rect.top, rect.left, rect.bottom, dimPaint)
        canvas.drawRect(rect.right, rect.top, w, rect.bottom, dimPaint)

        canvas.drawRect(rect, borderPaint)

        val c = 32f
        // 4 Cyan Hextech corners
        canvas.drawLine(rect.left, rect.top, rect.left + c, rect.top, cornerPaint)
        canvas.drawLine(rect.left, rect.top, rect.left, rect.top + c, cornerPaint)
        canvas.drawLine(rect.right, rect.top, rect.right - c, rect.top, cornerPaint)
        canvas.drawLine(rect.right, rect.top, rect.right, rect.top + c, cornerPaint)
        canvas.drawLine(rect.left, rect.bottom, rect.left + c, rect.bottom, cornerPaint)
        canvas.drawLine(rect.left, rect.bottom, rect.left, rect.bottom - c, cornerPaint)
        canvas.drawLine(rect.right, rect.bottom, rect.right - c, rect.bottom, cornerPaint)
        canvas.drawLine(rect.right, rect.bottom, rect.right, rect.bottom - c, cornerPaint)

        val hint = "Kéo vẽ vùng quét  •  Chạm 1 cái để quét Full màn hình"
        val tw = hintTextPaint.measureText(hint)
        val pill = RectF(w / 2f - tw / 2f - 36f, 44f, w / 2f + tw / 2f + 36f, 114f)
        canvas.drawRoundRect(pill, 24f, 24f, hintBgPaint)
        canvas.drawRoundRect(pill, 24f, 24f, borderPaint.apply { strokeWidth = 3f })
        borderPaint.strokeWidth = 6f
        canvas.drawText(hint, w / 2f, 92f, hintTextPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                startX = event.x
                startY = event.y
                curX = event.x
                curY = event.y
                isDragging = true
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                curX = event.x
                curY = event.y
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP -> {
                isDragging = false
                val dx = abs(curX - startX)
                val dy = abs(curY - startY)
                if (dx < 45f || dy < 45f) {
                    onRegionSelected(RectF(0f, 0f, 1f, 1f))
                } else {
                    val w = width.toFloat().coerceAtLeast(1f)
                    val h = height.toFloat().coerceAtLeast(1f)
                    val l = (min(startX, curX) / w).coerceIn(0f, 1f)
                    val t = (min(startY, curY) / h).coerceIn(0f, 1f)
                    val r = (max(startX, curX) / w).coerceIn(0f, 1f)
                    val b = (max(startY, curY) / h).coerceIn(0f, 1f)
                    onRegionSelected(RectF(l, t, r, b))
                }
                return true
            }
        }
        return super.onTouchEvent(event)
    }
}
