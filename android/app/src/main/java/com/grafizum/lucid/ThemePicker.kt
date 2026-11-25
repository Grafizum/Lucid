package com.grafizum.lucid

import android.app.Activity
import androidx.appcompat.app.AppCompatDelegate

/**
 * The one way a surface is changed from a page.
 *
 * The choice lives in exactly two places: the appearance card in Settings,
 * which carries all three answers — follow the phone, always light, always
 * dark — and the tour, which asks it with a sun and a moon. Both write through
 * [ThemeChoice], so there is still one record of the decision wherever it was
 * made; what is left here is the way the page turns when it is made.
 */
object ThemePicker {

    /**
     * The choice is written first, then put on: AppCompat rebuilds the page when
     * the light actually changes, and the picture kept before the call dissolves
     * over the rebuilt one. When the light does not change — picking Light on a
     * phone that is already light — nothing rebuilds, so the held picture is
     * dropped and only the page is left behind.
     */
    internal fun choose(activity: Activity, mode: Int) {
        if (ThemeChoice.mode(activity) == mode) return
        ThemeFade.capture(activity)
        ThemeChoice.set(activity, mode)
        AppCompatDelegate.setDefaultNightMode(ThemeChoice.nightMode(activity))
        activity.window?.decorView?.postDelayed({
            if (!activity.isFinishing && !activity.isDestroyed) ThemeFade.discard()
        }, 450)
    }
}
