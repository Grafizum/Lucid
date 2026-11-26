package com.grafizum.lucid

import android.content.Context
import android.os.Build

/**
 * Where Lucid's switch lives in this phone's own Settings.
 *
 * Accessibility's list is guarded so that no app may open the per-service page
 * itself; the best anything can do is open the list of services. On that list
 * the row is somewhere under a section whose name is the phone's own:
 * "Installed apps" on most Galaxies, "Installed services" on their tablets,
 * "Downloaded apps" on Pixel and most others, and whatever the next skin
 * decides. None of that can be navigated to — so it is written down instead,
 * in the guide card that stands in front of the list, in the words the list
 * will actually be using.
 *
 * There used to be a floating card for this, drawn over Settings at the top of
 * the screen. It could not be made opaque: Android composites application
 * overlays at reduced alpha on this build — WindowManager reported alpha 0.8 on
 * a window whose own LayoutParams said 1.0 — so Settings' title read straight
 * through the words of the card, and the card sat over the back arrow and the
 * menu while it did it. The sentence belongs in our own surface, which has no
 * such treatment, so it lives in the guide now.
 */
object SetupNudge {

    /**
     * The words the list actually uses on this phone, quoted for a sentence.
     *
     * Only the section's name is taken from the phone, never a path: a path is
     * right until the next skin renames a menu, while the section is what the
     * eye is looking for once the page is open.
     */
    fun sectionPhrase(context: Context): String = when {
        galaxy(context) && tablet(context) -> "“Installed services”"
        galaxy(context) -> "“Installed apps”"
        stock(context) -> "“Downloaded apps”"
        // A phone whose skin is not known here; both names, so whichever the
        // list is using is on the card as well.
        else -> "“Installed apps” or “Downloaded apps”"
    }

    /** The same name bare, for a drawn path or a row of a drawing. */
    fun sectionName(context: Context): String = when {
        galaxy(context) -> if (tablet(context)) "Installed services" else "Installed apps"
        stock(context) -> "Downloaded apps"
        else -> "Installed apps"
    }

    private fun galaxy(context: Context): Boolean =
        Build.MANUFACTURER.contains("samsung", true) || Build.BRAND.contains("samsung", true)

    /** AOSP and Pixel, the list whose section is named "Downloaded apps". */
    private fun stock(context: Context): Boolean =
        Build.MANUFACTURER.equals("Google", true) || Build.BRAND.equals("google", true)

    private fun tablet(context: Context): Boolean =
        context.resources.configuration.smallestScreenWidthDp >= 600
}
