package com.grafizum.lucid

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import kotlin.math.min

/**
 * The alphabet, laid across the dotted band at the top of the language sheet.
 *
 * Two hundred names are one long scroll; the rail turns that scroll into a
 * gesture — press a letter, the list jumps to the first language it begins, and
 * the letter under the finger takes the accent while the one the list is
 * actually showing keeps it afterwards. Letters with no language behind them are
 * not drawn, so the rail is never a key that does nothing.
 *
 * It is a single strip rather than a column down the edge because the band that
 * holds it is the sheet's title: the rail is one more line of that title, and it
 * stays clear of the rows and of the keyboard.
 */
class AlphabetRail @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val density = resources.displayMetrics.density

    /** One letter per slot, in the order the list runs. */
    var letters: List<Char> = emptyList()
        set(value) {
            field = value
            invalidate()
        }

    /** The letter the list is showing — the one that stays lit. */
    var active: Char? = null
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    /** Called on every letter the finger slides over, not only on release. */
    var onLetter: ((Char) -> Unit)? = null

    private val accent = ContextCompat.getColor(context, R.color.card_accent)
    private val muted = ContextCompat.getColor(context, R.color.card_faint)

    private val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = 8.5f * density
        letterSpacing = 0.04f
        typeface = ResourcesCompat.getFont(context, R.font.lucid_sans) ?: Typeface.DEFAULT
    }

    private val halo = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = accent
        alpha = 34
    }

    /** The letter under the finger, lit in addition to [active]. */
    private var pressed: Char? = null

    private val slot: Float
        get() = if (letters.isEmpty()) 0f else width.toFloat() / letters.size

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val wanted = (22f * density).toInt()
        setMeasuredDimension(
            resolveSize(suggestedMinimumWidth, widthMeasureSpec),
            resolveSize(maxOf(wanted, suggestedMinimumHeight), heightMeasureSpec),
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (letters.isEmpty() || width <= 0) return

        val step = slot
        val middle = height / 2f
        // Vertically centred caps: the baseline sits below the middle by half the
        // text's own height, so a letter is never nudged by its descender.
        val baseline = middle - (ink.ascent() + ink.descent()) / 2f
        val haloRadius = min(step * 0.46f, 11f * density)

        letters.forEachIndexed { index, letter ->
            val cx = step * (index + 0.5f)
            val lit = letter == pressed || letter == active
            if (letter == pressed) canvas.drawCircle(cx, middle, haloRadius, halo)
            ink.color = if (lit) accent else muted
            canvas.drawText(letter.toString(), cx, baseline, ink)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (letters.isEmpty()) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // The strip is a scrub, not a row of buttons: the whole band takes
                // the touch, and the nearest letter is the one meant.
                isPressed = true
                return pick(event.x)
            }

            MotionEvent.ACTION_MOVE -> return pick(event.x)

            MotionEvent.ACTION_UP -> {
                pick(event.x)
                pressed = null
                invalidate()
                isPressed = false
                performClick()
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                pressed = null
                invalidate()
                isPressed = false
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    /** Lights the letter nearest [x] and tells the list to go there. */
    private fun pick(x: Float): Boolean {
        val step = slot
        if (step <= 0f) return false
        val index = (x / step).toInt().coerceIn(0, letters.lastIndex)
        val letter = letters[index]
        if (letter != pressed) {
            pressed = letter
            invalidate()
            onLetter?.invoke(letter)
        }
        return true
    }
}
