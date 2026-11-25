package com.grafizum.lucid

import android.content.Context
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.widget.SwitchCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The settings page on a real device.
 *
 * The page is the app's whole configuration now: the switches Android owns, the
 * language every read is answered in, and one switch per app that brings the
 * lens up by itself. What it shows has to be what the phone will honour — a
 * switch that lies is worse than no switch.
 */
@RunWith(AndroidJUnit4::class)
class SettingsTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun clearRules() {
        Prefs.autoApps(context).forEach { Prefs.setAutoApp(context, it, false) }
    }

    private fun switchesIn(view: View, into: MutableList<SwitchCompat> = mutableListOf()): List<SwitchCompat> {
        when (view) {
            is SwitchCompat -> into.add(view)
            is ViewGroup -> (0 until view.childCount).forEach { switchesIn(view.getChildAt(it), into) }
        }
        return into
    }

    /** The small crosses that take an app's own language back. */
    private fun crossesIn(view: View, into: MutableList<View> = mutableListOf()): List<View> {
        when (view) {
            is ViewGroup -> (0 until view.childCount).forEach { crossesIn(view.getChildAt(it), into) }
            else -> {
                val said = view.contentDescription?.toString().orEmpty()
                if (view.isClickable && said.startsWith("Clear ")) into.add(view)
            }
        }
        return into
    }

    /** The app list is read off the main thread, so give it a moment to arrive. */
    private fun <T> wait(scenario: ActivityScenario<SettingsActivity>, ready: (SettingsActivity) -> T?): T? {
        val end = System.currentTimeMillis() + 4_000
        var found: T? = null
        while (System.currentTimeMillis() < end) {
            scenario.onActivity { activity -> found = ready(activity) }
            if (found != null) return found
            Thread.sleep(100)
        }
        return found
    }

    @Test
    fun aSwitchOnAnAppIsWhatBringsTheLensUpOverIt() {
        clearRules()

        ActivityScenario.launch(SettingsActivity::class.java).use { scenario ->
            val list = scenario.onActivity { it.findViewById<LinearLayout>(R.id.settingsAppsList) }
            // The list is the phone's own apps, minus Lucid itself.
            val toggle = wait(scenario) { activity ->
                switchesIn(activity.findViewById<LinearLayout>(R.id.settingsAppsList)).firstOrNull()
            }
            assertTrue("the page lists the phone's apps", toggle != null)
            assertTrue("a list was drawn", list != null)

            val pkg = toggle!!.tag as String
            assertFalse("nothing starts switched on", Prefs.autoOpenIn(context, pkg))

            scenario.onActivity { toggle.performClick() }
            assertTrue(
                "the switch is the rule: on means the lens comes up over that app",
                Prefs.autoOpenIn(context, pkg),
            )

            scenario.onActivity { toggle.performClick() }
            assertFalse(
                "switching it back off is the whole removal",
                Prefs.autoOpenIn(context, pkg),
            )
        }

        clearRules()
    }

    /**
     * The page draws a short list, not the phone: every launcher entry used to
     * get a row and an icon of its own, and that is what made it lag. What is
     * left out is a search away, which is why the cap can be this blunt.
     */
    @Test
    fun theAppListStopsAtTen() {
        ActivityScenario.launch(SettingsActivity::class.java).use { scenario ->
            val toggles = wait(scenario) { activity ->
                switchesIn(activity.findViewById<LinearLayout>(R.id.settingsAppsList))
                    .takeIf { it.isNotEmpty() }
            }
            assertTrue("the page lists apps", toggles != null)
            assertTrue(
                "ten rows at most, whatever the phone has installed (drew ${toggles!!.size})",
                toggles.size <= 10,
            )
        }
    }

    /**
     * The log and the build string are not here any more: home's own Translation
     * history row is the way into the reads, and a build number is a diagnostic,
     * not a setting. Neither should creep back onto this page.
     */
    @Test
    fun thePageLeavesTheReadsAndTheBuildLineToHome() {
        ActivityScenario.launch(SettingsActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                listOf("historyToggle", "historyBox", "settingsVersion").forEach { name ->
                    assertEquals(
                        "$name is not on the settings page",
                        0,
                        activity.resources.getIdentifier(name, "id", activity.packageName),
                    )
                }
            }
        }
    }

    /**
     * The appearance section's promise in storage: a phone that never chose
     * follows its own light, an old two-state choice stays where it was put, and
     * a three-way pick is kept as itself.
     */
    @Test
    fun aPhoneThatNeverChoseFollowsItsOwnLight() {
        val prefs = context.getSharedPreferences("lucid_prefs", Context.MODE_PRIVATE)
        val keptMode = if (prefs.contains("themeMode")) prefs.getInt("themeMode", 0) else null
        val keptOld = if (prefs.contains("darkTheme")) prefs.getBoolean("darkTheme", false) else null

        try {
            // A fresh install: no choice of either shape.
            prefs.edit().remove("themeMode").remove("darkTheme").commit()
            assertEquals(
                "a phone that never chose follows the phone",
                ThemeChoice.SYSTEM,
                Prefs.themeMode(context),
            )

            // The old two-state button's flag is an explicit light or dark, and
            // must not be swallowed by the new default.
            prefs.edit().putBoolean("darkTheme", true).commit()
            assertEquals(
                "an old dark choice stays dark",
                ThemeChoice.DARK,
                Prefs.themeMode(context),
            )
            prefs.edit().putBoolean("darkTheme", false).commit()
            assertEquals(
                "an old light choice stays light",
                ThemeChoice.LIGHT,
                Prefs.themeMode(context),
            )

            // And the new choice is written as itself, clearing the old flag.
            Prefs.setThemeMode(context, ThemeChoice.LIGHT)
            assertEquals(ThemeChoice.LIGHT, Prefs.themeMode(context))
            assertFalse("the old flag is retired", prefs.contains("darkTheme"))
        } finally {
            prefs.edit().apply {
                if (keptMode == null) remove("themeMode") else putInt("themeMode", keptMode)
                if (keptOld == null) remove("darkTheme") else putBoolean("darkTheme", keptOld)
            }.commit()
        }
    }

    /**
     * A language picked while the lens sat over an app belongs to that app, and
     * this page is where it is taken back: the cross on the app's row, and
     * nothing else on the page moves.
     */
    @Test
    fun anAppsOwnLanguageIsClearedByItsCross() {
        clearRules()

        val list = R.id.settingsAppsList
        var pkg = ""
        ActivityScenario.launch(SettingsActivity::class.java).use { scenario ->
            val toggle = wait(scenario) { activity ->
                switchesIn(activity.findViewById<LinearLayout>(list)).firstOrNull()
            }
            assertTrue("the page lists the phone's apps", toggle != null)
            pkg = toggle!!.tag as String
        }

        Prefs.setTargetFor(context, pkg, "de")
        try {
            ActivityScenario.launch(SettingsActivity::class.java).use { scenario ->
                val cross = wait(scenario) { activity ->
                    crossesIn(activity.findViewById<LinearLayout>(list)).firstOrNull()
                }
                assertTrue("an app with its own language carries the cross", cross != null)
                assertTrue(
                    "the cross says what it would forget",
                    cross!!.contentDescription.toString().contains("German"),
                )

                scenario.onActivity { cross.performClick() }
                assertNull(
                    "the cross takes the app's own language back",
                    Prefs.ownTargetFor(context, pkg),
                )
                assertEquals(
                    "the page's own language is untouched",
                    Prefs.targetLanguage(context),
                    Prefs.targetFor(context, pkg),
                )
            }
        } finally {
            Prefs.clearTargetFor(context, pkg)
        }
    }

    @Test
    fun thePermissionRowsSayWhatAndroidSays() {
        ActivityScenario.launch(SettingsActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val allowed = Settings.canDrawOverlays(context)
                val button = activity.findViewById<TextView>(R.id.btn1)
                assertEquals(
                    if (allowed) "Allowed" else "Allow",
                    button.text.toString(),
                )
                assertFalse(
                    "an allowed switch is an answer, not a question",
                    allowed && button.isEnabled,
                )
            }
        }
    }
}
