package com.grafizum.lucid

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

/**
 * A choice in the appearance card, drawn as the thing it chooses.
 *
 * The three words — System, Light, Dark — are all true and none of them is a
 * picture, so the card asked a person to imagine the result. This is the result,
 * at 46 dp: a page with three lines of text on it, in the light it will have.
 * System is both at once — the light page on the left, the charcoal one on the
 * right — because that is exactly what it means.
 *
 * The colours here are literal rather than theme roles on purpose. A preview of
 * dark must stay dark while the app is light, or it stops being a preview; it is
 * the one place in the app where a colour is not allowed to follow the theme.
 */
class ThemeSwatch @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    /** Which of the three answers this swatch is a picture of. */
    var mode: Int = ThemeChoice.SYSTEM
        set(value) {
            field = value
            invalidate()
        }

    /** The answer in force: its page is outlined in the accent. */
    var chosen: Boolean = false
        set(value) {
            field = value
            invalidate()
        }

    private val density = resources.displayMetrics.density

    private val paper = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = LIGHT_PAGE }
    private val charcoal = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = DARK_PAGE }
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = density
    }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * density
    }

    private val page = RectF()
    private val ringPath = RectF()

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width <= 0 || height <= 0) return

        val radius = 9f * density
        page.set(0.5f * density, 0.5f * density, width - 0.5f * density, height - 0.5f * density)

        // The page itself: one light, one dark, or the two halves of a phone
        // that has not decided yet.
        when (mode) {
            ThemeChoice.LIGHT -> canvas.drawRoundRect(page, radius, radius, paper)
            ThemeChoice.DARK -> canvas.drawRoundRect(page, radius, radius, charcoal)
            else -> {
                canvas.drawRoundRect(page, radius, radius, paper)
                canvas.save()
                canvas.clipRect(page.centerX(), page.top, page.right, page.bottom)
                canvas.drawRoundRect(page, radius, radius, charcoal)
                canvas.restore()
            }
        }

        // Three bars of text, at the size a paragraph has in a list: the page
        // reads as a page rather than a colour chip.
        val barHeight = 2.4f * density
        val barRadius = barHeight / 2f
        val left = page.left + 7f * density
        val widths = floatArrayOf(0.62f, 0.42f, 0.52f)
        val tops = floatArrayOf(0.30f, 0.48f, 0.66f)

        for (i in widths.indices) {
            val w = (page.width() - 14f * density) * widths[i]
            val top = page.top + page.height() * tops[i]
            val bar = RectF(left, top, left + w, top + barHeight)

            if (mode == ThemeChoice.LIGHT) {
                line.color = DARK_INK
                canvas.drawRoundRect(bar, barRadius, barRadius, line)
            } else if (mode == ThemeChoice.DARK) {
                line.color = LIGHT_INK
                canvas.drawRoundRect(bar, barRadius, barRadius, line)
            } else {
                // Each half of the split page carries its own ink.
                canvas.save()
                canvas.clipRect(page.left, page.top, page.centerX(), page.bottom)
                line.color = DARK_INK
                canvas.drawRoundRect(bar, barRadius, barRadius, line)
                canvas.restore()

                canvas.save()
                canvas.clipRect(page.centerX(), page.top, page.right, page.bottom)
                line.color = LIGHT_INK
                canvas.drawRoundRect(bar, barRadius, barRadius, line)
                canvas.restore()
            }
        }

        // The page's own hairline, then the accent ring when this is the answer
        // in force — the same "this one" the card's pill says in colour.
        edge.color = EDGE
        canvas.drawRoundRect(page, radius, radius, edge)
        if (chosen) {
            // The ring has to show against the page it surrounds, and the two
            // pages are opposite ends of the scale: clay on paper, cream clay on
            // charcoal. On the split page each half is outlined in the colour
            // that clears its own half, cut at the seam like the ink is.
            val inset = 0.75f * density
            ringPath.set(inset, inset, width - inset, height - inset)

            if (mode == ThemeChoice.SYSTEM) {
                canvas.save()
                canvas.clipRect(0f, 0f, page.centerX(), height.toFloat())
                ring.color = ACCENT_ON_LIGHT
                canvas.drawRoundRect(ringPath, radius, radius, ring)
                canvas.restore()

                canvas.save()
                canvas.clipRect(page.centerX(), 0f, width.toFloat(), height.toFloat())
                ring.color = ACCENT_ON_DARK
                canvas.drawRoundRect(ringPath, radius, radius, ring)
                canvas.restore()
            } else {
                ring.color = if (mode == ThemeChoice.DARK) ACCENT_ON_DARK else ACCENT_ON_LIGHT
                canvas.drawRoundRect(ringPath, radius, radius, ring)
            }
        }
    }

    private companion object {
        /** The two pages a preview may show, whatever theme is in force. */
        const val LIGHT_PAGE = 0xFFFCF8F1.toInt()
        const val DARK_PAGE = 0xFF23221E.toInt()

        /** Bars: ink on paper, paper on charcoal — measured, not guessed. */
        const val DARK_INK = 0x4D191814
        const val LIGHT_INK = 0x8CF7F1E5.toInt()

        /** The preview's own hairline, and the accent of a chosen answer. */
        const val EDGE = 0x24191814
        const val ACCENT_ON_LIGHT = 0xFF8A5A3C.toInt()
        const val ACCENT_ON_DARK = 0xFFE9BC96.toInt()
    }
}
