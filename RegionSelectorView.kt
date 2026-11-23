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
    initialNormalizedRect: RectF,
    private val onRegionSelected: (RectF) -> Unit
) : View(context) {

    private var startX = 0f
    private var startY = 0f
    private var currentRect = RectF()
    private var initialized = false
    private val initialNorm = RectF(initialNormalizedRect)

    private val dimPaint = Paint().apply {
        color = Color.parseColor("#88000000")
        style = Paint.Style.FILL
    }

    private val borderPaint = Paint().apply {
        color = Color.parseColor("#00E5FF")
        style = Paint.Style.STROKE
        strokeWidth = 6f
    }

    private val textPaint = Paint().apply {
        color = Color.WHITE
        textSize = 42f
        textAlign = Paint.Align.CENTER
        isAntiAlias = true
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (!initialized && w > 0 && h > 0) {
            currentRect.set(
                initialNorm.left * w,
                initialNorm.top * h,
                initialNorm.right * w,
                initialNorm.bottom * h
            )
            initialized = true
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), dimPaint)
        canvas.drawRect(currentRect, borderPaint)
        canvas.drawText(
            "Kéo tay để chọn vùng quét chữ (Thả tay để lưu)",
            width / 2f,
            90f,
            textPaint
        )
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                startX = event.x
                startY = event.y
                currentRect.set(startX, startY, startX, startY)
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                currentRect.set(
                    min(startX, event.x),
                    min(startY, event.y),
                    max(startX, event.x),
                    max(startY, event.y)
                )
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (width > 0 && height > 0 && abs(currentRect.width()) > 40f && abs(currentRect.height()) > 40f) {
                    val norm = RectF(
                        currentRect.left / width,
                        currentRect.top / height,
                        currentRect.right / width,
                        currentRect.bottom / height
                    )
                    onRegionSelected(norm)
                } else {
                    // Tap without dragging -> reset to default center 3-augments region
                    onRegionSelected(RectF(0.12f, 0.18f, 0.88f, 0.84f))
                }
                return true
            }
        }
        return super.onTouchEvent(event)
    }
}
