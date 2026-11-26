package com.grafizum.lucid

import android.view.MotionEvent
import android.view.View
import android.view.animation.PathInterpolator
import android.widget.TextView
import androidx.core.content.ContextCompat

/**
 * The press a half of the pair card answers with, and the beat it holds before
 * its picker arrives.
 *
 * The press used to be a wash laid over the half: a rectangle rounded on all
 * four corners with a hairline of its own, animated on a view of the half's size
 * while the half itself was scaled down by a thirty-fifth. Three things came out
 * of that — the ring drew a second edge inside the card's own, the half shrank
 * away from the card's border so a rim of paper was left around the feedback,
 * and both rounded corners at the seam curled away from the split. What the eye
 * read was a smaller box sitting on the box you pressed, a little off-centre.
 *
 * So the half now gives none of that away. The wash is the shape of the half
 * (see half_press_from) and simply comes up under the thumb while the medallion
 * pinches and the name takes the accent ink; the half itself does not move, so
 * the feedback is the surface of the control and nothing else. The one
 * movement left is in the small parts, which is where a key's travel is read
 * anyway.
 *
 * Staying is the point. A picker that opens the instant the finger lands never
 * lets the eye see that it landed at all; held for [HOLD_MS], the press is read
 * first and the sheet second, the way a key bottoms out before the letter
 * reaches the page. The release is the slower leg, so the half comes back up
 * while the picker is already on its way.
 */
object PairPress {

    /** The medallion pinches in with the press, as a key's own surface does. */
    private const val DISC = 0.94f

    /** The tint is a step, the release is a breath; the same asymmetry a key has. */
    private const val DOWN_MS = 120L
    private const val UP_MS = 400L

    /** The press is seen before the picker: a fifth of a second of it. */
    const val HOLD_MS = 220L

    /** cubic-bezier(.2, .8, .3, 1) — the press; cubic-bezier(.32, .72, 0, 1) — the rise. */
    private val DOWN = PathInterpolator(0.2f, 0.8f, 0.3f, 1f)
    private val UP = PathInterpolator(0.32f, 0.72f, 0f, 1f)

    /**
     * Gives [half] the press. [wash] is the half's own tint coming up under the
     * thumb, [disc] the medallion that pinches with it, [name] the word that
     * takes the accent while it is held, and [onOpen] runs once the press has
     * been held long enough to be seen.
     */
    fun attach(half: View, wash: View, disc: View, name: TextView, onOpen: () -> Unit) {
        if (half.getTag(R.id.press_tag) != null) return
        half.setTag(R.id.press_tag, true)

        val openSoon = Runnable {
            release(wash, disc, name)
            onOpen()
        }

        half.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> press(wash, disc, name)
                // A gesture taken away by the system (a scroll, another window)
                // is not a tap: the half comes straight back up.
                MotionEvent.ACTION_CANCEL -> {
                    half.removeCallbacks(openSoon)
                    release(wash, disc, name)
                }
            }
            false
        }

        half.setOnClickListener {
            half.removeCallbacks(openSoon)
            // A finger that lifted early still keeps the press until the beat is
            // over: the press belongs to the tap, not to the contact.
            press(wash, disc, name)
            half.postDelayed(openSoon, HOLD_MS)
        }
    }

    private fun press(wash: View, disc: View, name: TextView) {
        wash.animate().cancel()
        disc.animate().cancel()
        wash.animate().alpha(1f).setDuration(DOWN_MS).setInterpolator(DOWN).start()
        disc.animate()
            .scaleX(DISC)
            .scaleY(DISC)
            .setDuration(DOWN_MS)
            .setInterpolator(DOWN)
            .start()
        name.setTextColor(accentInk(name))
    }

    private fun release(wash: View, disc: View, name: TextView) {
        wash.animate().alpha(0f).setDuration(UP_MS).setInterpolator(UP).start()
        disc.animate()
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(UP_MS)
            .setInterpolator(UP)
            .start()
        name.setTextColor(ink(name))
    }

    private fun accentInk(view: View): Int =
        ContextCompat.getColor(view.context, R.color.screen_accent_ink)

    private fun ink(view: View): Int =
        ContextCompat.getColor(view.context, R.color.screen_ink)
}
