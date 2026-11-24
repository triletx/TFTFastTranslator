package com.tft.fasttranslator

import android.content.Context
import android.graphics.Color
import android.graphics.RectF
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import kotlin.math.max
import kotlin.math.min

data class OverlayBlock(val rect: RectF, val text: String)

class InPlaceOverlayView(
    context: Context,
    private val screenW: Int,
    private val screenH: Int,
    blocks: List<OverlayBlock>,
    fontSizeSp: Float,
    private val onDismiss: () -> Unit
) : FrameLayout(context) {

    private val density = context.resources.displayMetrics.density

    private fun dp(v: Int): Int = (v * density).toInt()

    init {
        setBackgroundColor(Color.parseColor("#38040A14"))
        setOnClickListener { onDismiss() }

        val margin = dp(6)
        val placedRects = mutableListOf<RectF>()

        val sorted = blocks.sortedWith(compareBy<OverlayBlock> { it.rect.top }.thenBy { it.rect.left })

        for (item in sorted) {
            if (item.text.isBlank()) continue

            val origW = item.rect.width()
            val maxAllowedW = if (origW < screenW * 0.42f) {
                max((origW * 1.28f).toInt(), dp(125)).coerceAtMost((screenW * 0.39f).toInt())
            } else {
                max((origW * 1.08f).toInt(), dp(200)).coerceAtMost(screenW - margin * 2)
            }

            val tv = TextView(context).apply {
                text = item.text
                setTextColor(Color.parseColor("#F0E6D2"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, fontSizeSp)
                setBackgroundResource(R.drawable.bg_inplace_tag)
                setPadding(dp(8), dp(5), dp(8), dp(5))
                setLineSpacing(dp(2).toFloat(), 1.0f)
                elevation = dp(4).toFloat()
                maxWidth = maxAllowedW
                setOnClickListener { onDismiss() }
            }

            val wSpec = MeasureSpec.makeMeasureSpec(maxAllowedW, MeasureSpec.AT_MOST)
            val hSpec = MeasureSpec.makeMeasureSpec(screenH - margin * 2, MeasureSpec.AT_MOST)
            tv.measure(wSpec, hSpec)

            val mw = max(tv.measuredWidth, min(origW.toInt(), maxAllowedW))
            val mh = tv.measuredHeight

            val centerX = item.rect.centerX()
            var left = (centerX - mw / 2f).toInt().coerceIn(margin, max(margin, screenW - mw - margin))
            var top = item.rect.top.toInt().coerceIn(margin, max(margin, screenH - mh - margin))

            // Avoid overlapping with previously placed cards in same column
            var candidate = RectF(left.toFloat(), top.toFloat(), (left + mw).toFloat(), (top + mh).toFloat())
            for (prev in placedRects) {
                val horizOverlap = min(candidate.right, prev.right) - max(candidate.left, prev.left)
                val vertOverlap = min(candidate.bottom, prev.bottom) - max(candidate.top, prev.top)
                if (horizOverlap > dp(8) && vertOverlap > dp(2)) {
                    top = (prev.bottom + dp(4)).toInt()
                    candidate = RectF(left.toFloat(), top.toFloat(), (left + mw).toFloat(), (top + mh).toFloat())
                }
            }

            if (top + mh > screenH - margin) {
                top = max(margin, screenH - mh - margin)
                candidate = RectF(left.toFloat(), top.toFloat(), (left + mw).toFloat(), (top + mh).toFloat())
            }

            placedRects.add(candidate)

            val lp = LayoutParams(mw, LayoutParams.WRAP_CONTENT).apply {
                leftMargin = left
                topMargin = top
            }
            addView(tv, lp)
        }

        // Subtle Hextech dismiss pill at bottom center
        val hintPill = TextView(context).apply {
            text = "Chạm bất kỳ đâu để đóng"
            setTextColor(Color.parseColor("#C8AA6E"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setBackgroundResource(R.drawable.bg_inplace_tag)
            setPadding(dp(14), dp(5), dp(14), dp(5))
            alpha = 0.85f
            setOnClickListener { onDismiss() }
        }
        val hintLp = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            bottomMargin = dp(16)
        }
        addView(hintPill, hintLp)

        alpha = 0f
        animate().alpha(1f).setDuration(140).start()
    }
}
