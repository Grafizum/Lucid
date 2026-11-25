package com.grafizum.lucid

import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * The way back from Android's accessibility screen.
 *
 * Turning the switch on is the end of the ask, so it should also be the end of
 * the errand: waiting to be pressed back makes the person carry the app home
 * themselves. Android will not let an ordinary page in the background pull
 * itself to the front, but the service that was just granted is bound by the
 * system and may — so the page that sends someone there writes down which page
 * it was, and [LensAccessibilityService] brings it back on connect.
 */
object AccessReturn {

    /** Remember who asked, right before Android's own screen is opened. */
    fun arm(context: Context, screen: Class<*>) =
        Prefs.armAccessReturn(context, screen.name)

    /**
     * Come back to the armed page, if there is one. Called once per grant, so
     * the note is taken rather than read: a switch flipped twice is one return,
     * and a phone rebooted later does not open Lucid out of nowhere.
     */
    fun come(context: Context) {
        val screen = Prefs.takeAccessReturn(context) ?: return
        val intent = Intent()
            .setClassName(context.packageName, screen)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        runCatching { context.startActivity(intent) }
            .onFailure { Log.i("LucidA11y", "return refused: ${it.javaClass.simpleName}") }
    }
}
