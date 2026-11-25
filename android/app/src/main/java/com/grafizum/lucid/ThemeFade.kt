package com.grafizum.lucid

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.animation.PathInterpolator
import android.widget.ImageView

/**
 * Carries the page across a light/dark change.
 *
 * A theme switch rebuilds the screen, so by itself it is a cut: the old page
 * vanishes and a new one is already there. This keeps a picture of the page as
 * it stood, lays it over the rebuilt one, and dissolves it — the change then
 * reads as one page becoming the other.
 *
 * Only the picture moves. The rebuilt page is complete from its first frame and
 * never changes opacity: two layers fading against each other let the window
 * background show through at the halfway point, which is exactly the grey blink
 * this is here to avoid. With one layer moving, every frame is an honest blend
 * of the two pages.
 *
 * The curve is an ease in and out — it leaves the old page gently, crosses the
 * middle at speed and settles on the new one — and it runs long enough (well
 * past a fifth of a second) to be seen as motion rather than a flash.
 *
 * The picture is held only between the tap and the rebuilt screen drawing it,
 * and dropped in every other path, so nothing is kept of what the person read.
 */
object ThemeFade {

    /**
     * Long enough to read as a dissolve rather than a flash, short enough that
     * the tap still feels answered. The page underneath is already drawn, so
     * this is the whole length of the change.
     */
    private const val FADE_MS = 420L

    /** Ease out of the old page, ease into the new one. */
    private val EASE = PathInterpolator(0.38f, 0f, 0.26f, 1f)

    private var picture: Bitmap? = null

    /** Called before the theme is rebuilt: keeps what is on screen right now. */
    fun capture(activity: Activity) {
        discard()
        val view = activity.window?.decorView ?: return
        if (view.width <= 0 || view.height <= 0) return

        picture = runCatching {
            Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888).also {
                view.draw(Canvas(it))
            }
        }.getOrNull()
    }

    /**
     * Lays the held picture over the rebuilt page and dissolves it away. Answers
     * false when there is nothing to show, so the screen can fade in instead.
     */
    fun reveal(activity: Activity): Boolean {
        val bitmap = picture ?: return false
        picture = null

        val decor = activity.window?.decorView as? ViewGroup ?: return false
        val overlay = ImageView(activity).apply {
            scaleType = ImageView.ScaleType.FIT_XY
            setImageBitmap(bitmap)
            isClickable = false
            importantForAccessibility = ImageView.IMPORTANT_FOR_ACCESSIBILITY_NO
        }

        decor.addView(
            overlay,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )

        // One layer, one curve, one alpha ramp: the old page goes, the new one
        // is already there to be uncovered.
        //
        // It starts on the frame this page is first drawn, not here. The page
        // is rebuilt in a new window and the old one stays on screen until that
        // frame arrives, so a ramp started on this line is spent before anyone
        // can see it: the change lands as a cut, with whatever is left of the
        // fade showing as a ghost behind it. Anchoring the first frame to the
        // old page's picture means the cut is never visible at all — the page
        // holds still, then dissolves.
        val start = object : ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                val live = decor.viewTreeObserver
                if (live.isAlive) live.removeOnPreDrawListener(this)
                overlay.animate()
                    .alpha(0f)
                    .setDuration(FADE_MS)
                    .setInterpolator(EASE)
                    .withEndAction { (overlay.parent as? ViewGroup)?.removeView(overlay) }
                    .start()
                return true
            }
        }
        decor.viewTreeObserver.addOnPreDrawListener(start)
        return true
    }

    /** Nothing was rebuilt after all: drop the held picture. */
    fun discard() {
        picture?.recycle()
        picture = null
    }
}
