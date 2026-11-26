package com.grafizum.lucid

import android.animation.ValueAnimator
import android.os.Build

/**
 * What this phone can do, worked out from the version it runs rather than
 * asked of the person holding it.
 *
 * Nothing here changes the app on a modern phone: every path it already took
 * is still the one it takes. What the flags are for is the older phone, where
 * an API the lens relies on does not exist — there the same features are
 * reached the other way (a screen share instead of a silent screenshot, a
 * dragged-in tile instead of a system sheet) so that reading text, photos and
 * video keeps working instead of quietly disappearing.
 */
object Capabilities {

    /**
     * Android 11 added a screenshot API to the accessibility service itself, so
     * a frame can be taken with the permission the lens already has: no dialog,
     * nothing recorded. Below that there is no such call and MediaProjection —
     * the screen-share dialog Android shows — is the only way to pixels.
     */
    val silentFrames: Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R

    /**
     * Android 14 shoots the app window under the lens, so the lens, the card
     * and the Delete key are never in the frame. Below that the whole display
     * comes back and they have to step out of it for the grab.
     */
    val framesLeaveOverlaysOut: Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE

    /**
     * Whether motion is welcome. Android 8 can be asked — the user may have
     * switched animations off in Developer options — and before it there is no
     * such setting to read, so the loops run as they always did.
     */
    fun animationsOn(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return true
        return ValueAnimator.areAnimatorsEnabled()
    }

    /** Android 13 made notifications something the user grants. */
    val asksForNotifications: Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

    /** Android 13 lets the app offer its quick-settings button; before it, it is dragged in. */
    val offersQuickButton: Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

    /** Android 10 draws a tile's subtitle; older shades have the label alone. */
    val tileSubtitles: Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

    /** Android 11 has a confirm haptic; older phones get the long press instead. */
    val confirmHaptic: Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R

    /**
     * True where the lens cannot take its own stills and has to be handed the
     * screen instead. Reading is not lost there — the share is the way — so the
     * app asks for it as part of setup rather than leaving it as an optional
     * row that would look like a feature this phone does not have.
     */
    val needsScreenShare: Boolean = !silentFrames
}
