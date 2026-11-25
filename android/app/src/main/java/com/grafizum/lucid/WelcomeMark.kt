package com.grafizum.lucid

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import androidx.core.content.ContextCompat
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.sin

/**
 * The app's mark, drawn from the launcher's own drawing rather than pasted: the
 * magnifier of `design/icon/lens-mark.svg`, without its plate, so it sits on the
 * page's own paper.
 *
 * Every part is in the icon's own units — the frame's outer radius 260.35, its
 * glass 184.86, the handle out to 383.76 from the centre, the glyph strokes
 * 25.86 wide — so the proportions are the icon's, not an approximation of it.
 * [MARK_EXTENT] is the far end of the handle including half its width, and
 * [REACH] is how much of the circle's radius that far end is allowed to take.
 *
 * The circle breathes as it always has, and a tap plays a short launch: the ring
 * lets go and widens, the mark blooms a little and fades into the tour, then
 * [onLaunch] fires.
 */
class WelcomeMark @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    companion object {
        /**
         * How much of the circle's radius the whole mark reaches, handle tip
         * included. 0.72 leaves clear air all round; 0.58 is small, 0.86 large.
         */
        private const val REACH = 0.72f

        /** Handle tip plus half its stroke, in the icon's units. */
        private const val MARK_EXTENT = 598.2f

        private const val LAUNCH_MS = 380L
    }

    private val density = resources.displayMetrics.density
    private fun dp(value: Float) = value * density

    private fun color(id: Int) = ContextCompat.getColor(context, id)

    private val creamHi = color(R.color.logo_cream_hi)
    private val creamMid = color(R.color.logo_cream_mid)
    private val creamLo = color(R.color.logo_cream_lo)
    private val handleHi = color(R.color.logo_handle_hi)
    private val handleLo = color(R.color.logo_handle_lo)
    private val glassHi = color(R.color.logo_glass_hi)
    private val glassMid = color(R.color.logo_glass_mid)
    private val glassLo = color(R.color.logo_glass_lo)
    private val glyphA = color(R.color.logo_glyph_a)
    private val glyphB = color(R.color.logo_glyph_b)

    private val shade = color(R.color.screen_shade)
    private val accentSoft = color(R.color.screen_accent_soft)

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val glyphPath = Path()

    private var t = 0f
    private var pressed = false
    private var launching = 0f
    private var animator: ValueAnimator? = null
    private var launchAnimator: ValueAnimator? = null

    /** Called when the launch animation has played out and the tour is due. */
    var onLaunch: (() -> Unit)? = null

    private var markCx = 0f
    private var markCy = 0f
    private var markR = 1f

    init {
        isClickable = true
        isFocusable = true
        contentDescription = "Lucid — tap to begin"
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        setWillNotDraw(false)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (!Capabilities.animationsOn()) {
            t = 0.5f
            invalidate()
            return
        }
        animator?.cancel()
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 4400L
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener {
                t = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    override fun onDetachedFromWindow() {
        animator?.cancel()
        animator = null
        launchAnimator?.cancel()
        launchAnimator = null
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val s = min(width, height).toFloat()
        if (s <= 0f) return

        val circle = s * 0.40f
        val cx = width / 2f
        val cy = height / 2f
        markCx = cx
        markCy = cy
        markR = circle

        // Pixels per unit of the icon's drawing.
        val u = circle * REACH / MARK_EXTENT

        // Nothing stands on the page: there is no frame around the mark at all,
        // only two rings that leave it and let go. Half a cycle apart, so one
        // is always travelling and neither is ever a circle you can see resting.
        val breath = sin((t * PI).toFloat())
        val press = if (pressed) 0.96f else 1f

        canvas.save()
        canvas.scale(press, press, cx, cy)

        if (launching <= 0f) {
            ripple(canvas, cx, cy, circle, t)
            ripple(canvas, cx, cy, circle, (t + 0.5f) % 1f)
        }

        if (launching > 0f) {
            val p = launching
            // A shader is moderated by the paint's own alpha, which the last
            // setColor left low; it has to be brought back before it is used.
            fill.alpha = 255
            fill.shader = RadialGradient(
                cx, cy, circle * (0.55f + 0.55f * p),
                intArrayOf(
                    withAlpha(accentSoft, 0.16f * (1f - p)),
                    withAlpha(accentSoft, 0f),
                ),
                null, Shader.TileMode.CLAMP,
            )
            canvas.drawCircle(cx, cy, circle * (0.55f + 0.55f * p), fill)
            fill.shader = null

            line.strokeWidth = dp(1.3f) + dp(1.4f) * p
            line.color = withAlpha(accentSoft, 0.42f * (1f - p))
            canvas.drawCircle(cx, cy, circle * (1f + 0.62f * p), line)
        }

        drawMark(
            canvas,
            cx,
            cy,
            u * (1f + 0.016f * breath) * (1f + 0.10f * launching),
            (1f - 0.06f * breath) * (1f - launching * 0.92f),
        )

        canvas.restore()
    }

    /**
     * One ring of the ripple: it leaves the mark, widens to a little over
     * four-fifths of the circle and gives its ink back as it goes, so it is
     * gone before the other one is half out. [phase] runs 0..1 over a cycle.
     */
    private fun ripple(canvas: Canvas, cx: Float, cy: Float, circle: Float, phase: Float) {
        val ink = sin((phase * PI).toFloat()).coerceIn(0f, 1f)
        if (ink <= 0.01f) return

        // Wide at first and slower further out, the way a drop spreads.
        val grow = phase * (2f - phase)
        val radius = circle * (0.44f + 0.46f * grow)

        line.strokeWidth = dp(1.1f) + dp(0.5f) * phase
        line.color = withAlpha(accentSoft, 0.20f * ink)
        canvas.drawCircle(cx, cy, radius, line)
        fill.color = withAlpha(accentSoft, 0.035f * ink)
        canvas.drawCircle(cx, cy, radius, fill)
    }

    /** The launcher's magnifier at [cx]/[cy], one icon unit being [u] pixels. */
    private fun drawMark(canvas: Canvas, cx: Float, cy: Float, u: Float, alpha: Float) {
        if (alpha <= 0.01f || u <= 0f) return

        // Its lift off the page: the same silhouette, dropped a few pixels and
        // softened by drawing it twice, since the view is not a layered bitmap.
        canvas.save()
        canvas.translate(0f, dp(4f))
        line.strokeWidth = 111.3f * u
        line.color = withAlpha(shade, 0.07f * alpha)
        canvas.drawLine(cx + 178.39f * u, cy + 178.39f * u, cx + 383.76f * u, cy + 383.76f * u, line)
        fill.color = withAlpha(shade, 0.07f * alpha)
        canvas.drawCircle(cx, cy, 260.35f * u, fill)
        canvas.restore()

        canvas.save()
        canvas.translate(0f, dp(7f))
        line.strokeWidth = 111.3f * u + dp(2f)
        line.color = withAlpha(shade, 0.04f * alpha)
        canvas.drawLine(cx + 178.39f * u, cy + 178.39f * u, cx + 383.76f * u, cy + 383.76f * u, line)
        fill.color = withAlpha(shade, 0.04f * alpha)
        canvas.drawCircle(cx, cy, 260.35f * u + dp(1f), fill)
        canvas.restore()

        // The gradients below are drawn through paints whose alpha was left at
        // a few percent by the shadow above; a shader is multiplied by that
        // alpha, so both have to be brought back to full first.
        fill.alpha = 255
        line.alpha = 255

        // the handle, at the foot of the frame, pointing down-right
        line.strokeWidth = 111.3f * u
        line.shader = LinearGradient(
            cx + 178.39f * u, cy + 178.39f * u,
            cx + 383.76f * u, cy + 383.76f * u,
            intArrayOf(withAlpha(handleHi, alpha), withAlpha(handleLo, alpha)),
            null, Shader.TileMode.CLAMP,
        )
        canvas.drawLine(cx + 178.39f * u, cy + 178.39f * u, cx + 383.76f * u, cy + 383.76f * u, line)
        line.shader = null

        // the frame: a cream ring, lit at its crown and tan at its foot
        line.strokeWidth = 75.49f * u
        line.shader = LinearGradient(
            cx - 234.3f * u, cy - 250f * u,
            cx + 187.6f * u, cy + 250f * u,
            intArrayOf(
                withAlpha(creamHi, alpha),
                withAlpha(creamMid, alpha),
                withAlpha(creamLo, alpha),
            ),
            floatArrayOf(0f, 0.42f, 1f), Shader.TileMode.CLAMP,
        )
        canvas.drawCircle(cx, cy, 222.6f * u, line)
        line.shader = null

        // the glass: warm charcoal, lighter where the light comes from
        fill.shader = RadialGradient(
            cx - 51.7f * u, cy - 73.9f * u, 325.4f * u,
            intArrayOf(
                withAlpha(glassHi, alpha),
                withAlpha(glassMid, alpha),
                withAlpha(glassLo, alpha),
            ),
            floatArrayOf(0f, 0.66f, 1f), Shader.TileMode.CLAMP,
        )
        canvas.drawCircle(cx, cy, 184.86f * u, fill)
        fill.shader = null

        // the glass's own light and shade, as the icon draws them
        arc(canvas, cx, cy, 176f * u, -174f, 82f, 13.14f * u, 0x191712, 0.55f * alpha)
        arc(canvas, cx, cy, 175.1f * u, 12f, 66f, 8.48f * u, 0xFFF8E9.toInt(), 0.22f * alpha)
        arc(canvas, cx, cy, 149.46f * u, -158f, 40f, 11.66f * u, 0xFBF4E4.toInt(), 0.11f * alpha)
        arc(canvas, cx, cy, 164.3f * u, -154f, 28f, 6.78f * u, 0xFBF4E4.toInt(), 0.28f * alpha)

        // A in the frame's cream, 文 in its tan: the icon's own strokes, not a
        // typeface's idea of them
        line.strokeWidth = 25.86f * u
        line.color = withAlpha(glyphA, alpha)
        glyphPath.reset()
        glyphPath.moveTo(cx - 75.472f * u, cy - 80.56f * u)
        glyphPath.lineTo(cx - 124.444f * u, cy + 48.76f * u)
        glyphPath.moveTo(cx - 75.472f * u, cy - 80.56f * u)
        glyphPath.lineTo(cx - 21.836f * u, cy + 48.76f * u)
        glyphPath.moveTo(cx - 109.18f * u, cy + 4.24f * u)
        glyphPath.lineTo(cx - 39.22f * u, cy + 4.24f * u)
        canvas.drawPath(glyphPath, line)

        line.color = withAlpha(glyphB, alpha)
        glyphPath.reset()
        glyphPath.moveTo(cx + 64.66f * u, cy - 69.536f * u)
        glyphPath.lineTo(cx + 64.66f * u, cy - 38.584f * u)
        glyphPath.moveTo(cx + 93.28f * u, cy - 13.78f * u)
        glyphPath.lineTo(cx + 8.48f * u, cy + 82.68f * u)
        glyphPath.moveTo(cx + 36.04f * u, cy - 13.78f * u)
        glyphPath.lineTo(cx + 119.78f * u, cy + 81.62f * u)
        glyphPath.moveTo(cx, cy - 24.804f * u)
        glyphPath.lineTo(cx + 129.32f * u, cy - 24.804f * u)
        canvas.drawPath(glyphPath, line)
    }

    private fun arc(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        r: Float,
        start: Float,
        sweep: Float,
        width: Float,
        rgb: Int,
        alpha: Float,
    ) {
        line.strokeWidth = width
        line.color = withAlpha(rgb, alpha)
        canvas.drawArc(cx - r, cy - r, cx + r, cy + r, start, sweep, false, line)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (launching > 0f) return true
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                pressed = true
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP -> {
                pressed = false
                invalidate()
                val dx = event.x - markCx
                val dy = event.y - markCy
                if (dx * dx + dy * dy <= (markR * 1.15f) * (markR * 1.15f)) performClick()
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                pressed = false
                invalidate()
                return true
            }
        }
        return false
    }

    override fun performClick(): Boolean {
        super.performClick()
        launch()
        return true
    }

    /**
     * The last thing the welcome does: the ring opens, the mark blooms and
     * fades, and then the tour is asked for. With animations off it asks at once.
     */
    private fun launch() {
        if (launching > 0f || launchAnimator != null) return
        if (!Capabilities.animationsOn() || !isAttachedToWindow) {
            onLaunch?.invoke()
            return
        }
        launchAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = LAUNCH_MS
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                launching = it.animatedValue as Float
                invalidate()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    launchAnimator = null
                    onLaunch?.invoke()
                }
            })
            start()
        }
    }

    private fun withAlpha(color: Int, fraction: Float): Int {
        val a = (fraction.coerceIn(0f, 1f) * 255f).toInt()
        return (a shl 24) or (color and 0x00FFFFFF)
    }

}
