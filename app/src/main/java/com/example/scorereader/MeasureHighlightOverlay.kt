package com.example.scorereader

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

/**
 * Translucent yellow rectangle drawn on top of the score image to mark
 * the measure currently being played back.
 *
 * Both the measure bbox and the SVG viewBox are passed in *viewBox*
 * coordinates. We apply xMidYMid-meet ourselves using the overlay's
 * current pixel dimensions, which guarantees the rectangle lines up with
 * whatever the underlying ImageView's `fitCenter` is actually drawing
 * even when the bitmap's intrinsic size differs from the ImageView's
 * visible bounds (e.g. status bar / cutout / navigation insets).
 */
class MeasureHighlightOverlay @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var rectVb: RectF? = null
    private var viewBox: RectF? = null

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.argb(70, 255, 215, 0) // soft amber
    }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.argb(170, 255, 165, 0)
        strokeWidth = 3f
    }

    init {
        // We just paint; no need to intercept touch.
        isClickable = false
        isFocusable = false
    }

    /** Set the active measure. Both rects use SVG viewBox coordinates. */
    fun setMeasure(boxInViewBox: RectF?, vb: RectF?) {
        val sameBox = (rectVb == null && boxInViewBox == null) ||
            (rectVb != null && boxInViewBox != null && rectVb == boxInViewBox)
        val sameVb = (viewBox == null && vb == null) ||
            (viewBox != null && vb != null && viewBox == vb)
        if (sameBox && sameVb) return
        rectVb = boxInViewBox?.let { RectF(it) }
        viewBox = vb?.let { RectF(it) }
        invalidate()
    }

    fun clear() = setMeasure(null, null)

    override fun onDraw(canvas: Canvas) {
        val r = rectVb ?: return
        val vb = viewBox ?: return
        val w = width.toFloat()
        val h = height.toFloat()
        val vbW = vb.width()
        val vbH = vb.height()
        if (w <= 0f || h <= 0f || vbW <= 0f || vbH <= 0f) return
        // xMidYMid-meet from viewBox → this view's pixel space.
        val scale = minOf(w / vbW, h / vbH)
        val offX = (w - vbW * scale) / 2f
        val offY = (h - vbH * scale) / 2f
        val left   = (r.left   - vb.left) * scale + offX
        val top    = (r.top    - vb.top)  * scale + offY
        val right  = (r.right  - vb.left) * scale + offX
        val bottom = (r.bottom - vb.top)  * scale + offY
        canvas.drawRect(left, top, right, bottom, fillPaint)
        canvas.drawRect(left, top, right, bottom, strokePaint)
    }
}
