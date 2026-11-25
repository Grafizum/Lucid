package com.grafizum.lucid

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable

/**
 * The drawer lip: an outline that runs up the left side, over the top on two
 * rounded corners and back down the right, with no line along the bottom. The
 * screen's own edge is the bottom of the shape, so the lip reads as a pipe going
 * into the phone rather than as a panel floating on it.
 *
 * Drawn from its own bounds rather than a fixed corner radius in dp, because the
 * bar is resized while it grows: the radius is hugged to whatever height it is
 * given, which keeps the ends round at every size instead of turning square on
 * the way up or over-round at rest.
 */
class LipDrawable(
    private val fill: Int,
    private val edge: Int,
    private val strokePx: Float,
    private val radiusPx: Float,
) : Drawable() {

    private val body = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = strokePx
    }
    /** The filled shape: rounded top, square bottom, ending at the screen edge. */
    private val bodyPath = Path()

    /** The drawn outline: left side, top with its two corners, right side. */
    private val outline = Path()
    private val corner = RectF()
    private val rect = RectF()
    private var drawAlpha = 255

    /**
     * Scales the fill alone, not the rim: 1 is the lip's own surface, 0 leaves
     * the outline standing on its own. The held lip is grown by its border and
     * almost no surface, so the jump reads as the edge drawing a bigger line
     * rather than as a slab of colour appearing.
     */
    var fillScale = 1f
        set(value) {
            field = value.coerceIn(0f, 1f)
            invalidateSelf()
        }

    /**
     * The size of the bar drawn inside its bounds: 1 fills them, and anything
     * less draws a smaller bar inset towards the bottom centre, still flat on the
     * screen's edge.
     *
     * The window is given the grown lip's box for its whole life and the bar is
     * drawn at whichever size the cue is in, so the way the lip gives itself back
     * is one size on one centre — it only rises and falls — instead of a window
     * that shrinks and slides at the same time.
     */
    var bodyScaleX = 1f
        set(value) {
            field = value.coerceIn(0f, 1f)
            invalidateSelf()
        }

    var bodyScaleY = 1f
        set(value) {
            field = value.coerceIn(0f, 1f)
            invalidateSelf()
        }

    override fun draw(canvas: Canvas) {
        val bounds = bounds
        if (bounds.isEmpty) return

        val bodyW = bounds.width() * bodyScaleX
        val bodyH = bounds.height() * bodyScaleY
        if (bodyW <= 0f || bodyH <= 0f) return
        // Centred across and sitting on the bottom: the sides move equally, and
        // the only edge that travels is the top one.
        val left = bounds.left + (bounds.width() - bodyW) / 2f
        val radius = minOf(radiusPx, bodyH / 2f)
        rect.set(left, bounds.bottom - bodyH, left + bodyW, bounds.bottom.toFloat())
        // Inset by half the hairline so the stroke sits inside the window, not
        // half outside it where it would be clipped away.
        rect.inset(strokePx / 2f, strokePx / 2f)

        bodyPath.reset()
        bodyPath.addRoundRect(
            rect,
            floatArrayOf(radius, radius, radius, radius, 0f, 0f, 0f, 0f),
            Path.Direction.CW,
        )

        // The bottom line is left open on purpose: the shape starts at the very
        // bottom of the left side, climbs, turns the two top corners and comes
        // back down to the screen edge.
        val reach = minOf(radius, minOf(rect.width() / 2f, rect.height()))
        outline.reset()
        outline.moveTo(rect.left, rect.bottom)
        outline.lineTo(rect.left, rect.top + reach)
        corner.set(rect.left, rect.top, rect.left + 2f * reach, rect.top + 2f * reach)
        outline.arcTo(corner, 180f, 90f, false)
        outline.lineTo(rect.right - reach, rect.top)
        corner.set(rect.right - 2f * reach, rect.top, rect.right, rect.top + 2f * reach)
        outline.arcTo(corner, 270f, 90f, false)
        outline.lineTo(rect.right, rect.bottom)

        body.color = fill
        body.alpha = (drawAlpha * fillScale).toInt().coerceIn(0, 255)
        rim.color = edge
        rim.alpha = drawAlpha
        canvas.drawPath(bodyPath, body)
        canvas.drawPath(outline, rim)
    }

    override fun setAlpha(alpha: Int) {
        drawAlpha = alpha.coerceIn(0, 255)
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        body.colorFilter = colorFilter
        rim.colorFilter = colorFilter
    }

    @Deprecated("Kept for the platform's sake; this drawable is drawn per pixel.")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
