package com.grafizum.lucid

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The button in the shade, put there by hand.
 *
 * A tile dropped in from the panel's edit screen never reaches Lucid: its
 * callback goes to a process that is not running, and the list the shade keeps
 * its buttons in has been closed to apps since Android 14. What is left is the
 * platform's own question — bind the tile if it is there — so this checks that
 * asking it finds a button the app was never told about.
 *
 * Run with Lucid's button in the shade:
 *   adb shell cmd statusbar add-tile com.grafizum.lucid/com.grafizum.lucid.LensTileService
 */
@RunWith(AndroidJUnit4::class)
class QuickTileFoundTest {

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun askingTheShadeFindsAButtonAddedByHand() {
        // Nothing was written down: this is the state the row was stuck in.
        Prefs.setTileAdded(context, false)

        LensTileService.ask(context)

        val end = System.currentTimeMillis() + 4_000
        while (System.currentTimeMillis() < end && !Prefs.tileAdded(context)) {
            Thread.sleep(100)
        }

        assertTrue(
            "the shade never handed the tile over, so a hand-added button stays invisible",
            Prefs.tileAdded(context),
        )
    }
}
