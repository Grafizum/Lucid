package com.grafizum.lucid

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.widget.LinearLayout
import androidx.core.content.ContextCompat

/**
 * The dotted band that heads the language sheet.
 *
 * A title row on bare paper read as another line of the sheet. This is a
 * surface instead: one step of beige deeper than the card, a field of dots at
 * 9 dp, and a hairline, so the top of the sheet is a place rather than a
 * sentence. The alphabet rail sits inside it — the dots are the paper's, not a
 * decoration that means anything, which is why nothing is drawn on them.
 *
 * Children stack as in a vertical [LinearLayout]; the dots are painted under
 * them and clipped to the band's own corner.
 */
class DottedBand @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : LinearLayout(context, attrs) {

    private val density = resources.displayMetrics.density

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.card_band)
    }

    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.card_dot)
    }

    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = density
        color = ContextCompat.getColor(context, R.color.card_edge)
    }

    private val shape = Path()

    /** One dot every 9 dp, the spacing the design studies draw it at. */
    private val gap = 9f * density
    private val dotRadius = 0.75f * density
    private val radius = 18f * density

    init {
        orientation = VERTICAL
        setWillNotDraw(false)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        shape.reset()
        shape.addRoundRect(0f, 0f, w, h, radius, radius, Path.Direction.CW)
        canvas.drawPath(shape, fill)

        // The field: a dot at the centre of every cell, so the pattern starts and
        // ends half a step in from the edge instead of being cut through a dot.
        canvas.save()
        canvas.clipPath(shape)
        var y = gap / 2f
        while (y < h) {
            var x = gap / 2f
            while (x < w) {
                canvas.drawCircle(x, y, dotRadius, dotPaint)
                x += gap
            }
            y += gap
        }
        canvas.restore()

        canvas.drawPath(shape, edge)
    }
}
