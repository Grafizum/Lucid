package com.grafizum.lucid

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat

/**
 * The hairline where the pair's two ends meet.
 *
 * It is not a rule from edge to edge. The mock draws it as the edge of a second
 * plate laid beside the first: it leaves the card's top edge a little right of
 * the centre line, turns through a fillet into the vertical, runs down behind
 * the swap ring, and turns back out at the bottom edge. A straight line made the
 * card read as one card sliced in two — the two fillets are what make the ends
 * read as two plates meeting.
 *
 * The colour stays the page's own divider, so at night it is a cream hairline
 * and in daylight a warm taupe one, without a second copy of the drawing.
 */
class SeamView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val density = resources.displayMetrics.density

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = density
        color = ContextCompat.getColor(context, R.color.screen_divider)
    }

    /** The radius the second plate is cut with; about a tenth of the card. */
    private val fillet = 12f * density

    private val line = Path()

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val h = height.toFloat()
        if (h <= 0f || width <= 0) return
        val cx = width / 2f
        // On a card too short for two fillets the corner is simply tightened,
        // so the drawing never folds back over itself.
        val r = fillet.coerceAtMost(h / 4f)

        line.reset()
        line.moveTo(cx + r, 0f)
        line.quadTo(cx, 0f, cx, r)
        line.lineTo(cx, h - r)
        line.quadTo(cx, h, cx + r, h)
        canvas.drawPath(line, paint)
    }
}
