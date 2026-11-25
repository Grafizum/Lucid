package com.grafizum.lucid

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatDelegate
import androidx.appcompat.view.ContextThemeWrapper
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Where the surface can be changed, and what the one button says.
 *
 * The theme is a setting, so it belongs with the settings: the appearance card
 * inside Settings carries all three answers. The one other place is the tour,
 * because Settings is not reachable from a fresh install — and there it is a
 * single button wearing the surface the page is not, so what a tap does is on
 * the button rather than implied by a pair of icons.
 */
@RunWith(AndroidJUnit4::class)
class ThemeButtonTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    /** Whatever the phone was set to before a test moved it. */
    private val before = Prefs.themeMode(context)
    private val beforeSeen = Prefs.onboardingSeen(context)

    @After
    fun putTheSurfaceBack() {
        Prefs.setThemeMode(context, before)
        Prefs.setOnboardingSeen(context, beforeSeen)
        AppCompatDelegate.setDefaultNightMode(ThemeChoice.nightMode(context))
    }

    /** Whether the welcome is the screen the person would be looking at. */
    private fun welcomeIsOnScreen(): Boolean {
        var onScreen = false
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            onScreen = ActivityLifecycleMonitorRegistry.getInstance()
                .getActivitiesInStage(Stage.RESUMED)
                .any { it is WelcomeActivity }
        }
        return onScreen
    }

    /* ── where it lives ───────────────────────────────────────────────── */

    /**
     * A page's layout, measured at phone size. Measured rather than opened as an
     * activity: the welcome runs a loop that never ends — the mark breathing —
     * and a page that never goes idle is a minute of waiting for a fact about two
     * dp values.
     */
    private fun inflate(layout: Int): View {
        val themed = ContextThemeWrapper(context, R.style.Theme_Lucid)
        val holder = FrameLayout(themed)
        val page = LayoutInflater.from(themed).inflate(layout, holder, false)
        holder.addView(page)

        val metrics = context.resources.displayMetrics
        holder.measure(
            View.MeasureSpec.makeMeasureSpec(metrics.widthPixels, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(metrics.heightPixels, View.MeasureSpec.EXACTLY),
        )
        holder.layout(0, 0, metrics.widthPixels, metrics.heightPixels)
        return page
    }

    /** Every view on a page whose TalkBack name starts with [prefix]. */
    private fun named(
        view: View,
        prefix: String,
        into: MutableList<View> = mutableListOf(),
    ): List<View> {
        if (view.contentDescription?.toString()?.startsWith(prefix) == true) into.add(view)
        if (view is ViewGroup) {
            (0 until view.childCount).forEach { named(view.getChildAt(it), prefix, into) }
        }
        return into
    }

    @Test
    fun theTourCarriesOneSurfaceButtonInItsCorner() {
        val page = inflate(R.layout.activity_onboarding)
        val density = context.resources.displayMetrics.density

        val button = page.findViewById<View>(R.id.obTheme)
        assertNotNull("the tour has no surface button", button)
        assertEquals(
            "the button is not named for TalkBack",
            "Switch the theme",
            button.contentDescription?.toString(),
        )
        assertTrue("the surface button is not tappable", button.isClickable)

        // One button, not a pair and not the old card: nothing else on the tour
        // offers the theme.
        assertEquals("the tour carries more than one surface control", 1, named(page, "Switch").size)
        assertTrue("the tour still carries an appearance sheet", named(page, "Appearance").isEmpty())

        // In the top bar, on the right, where the gear sits on home.
        val row = button.parent as ViewGroup
        assertTrue("the tour's top bar is not at the top", row.top < (140 * density).toInt())
        assertTrue(
            "the button is ${row.width - button.right}px from the right edge",
            row.width - button.right <= (70 * density).toInt(),
        )
    }

    @Test
    fun theHomeAndTheWelcomeCarryNoSurfaceSwitch() {
        val pages = listOf(
            "the home page" to R.layout.activity_main,
            "the welcome" to R.layout.activity_welcome,
        )
        for ((name, layout) in pages) {
            val page = inflate(layout)
            assertTrue(
                "$name still carries an appearance control — the theme lives in Settings and the tour",
                named(page, "Switch").isEmpty() && named(page, "Appearance").isEmpty(),
            )
        }
    }

    /* ── what the button says ─────────────────────────────────────────── */

    /**
     * The button wears the surface the page is not, and a tap goes there: paper
     * offers a moon, charcoal offers a sun, and either way the write lands in the
     * one record Settings writes.
     */
    @Test
    fun theButtonShowsTheOtherSurfaceAndGoesThere() {
        Prefs.setOnboardingSeen(context, false)

        Prefs.setThemeMode(context, ThemeChoice.LIGHT)
        AppCompatDelegate.setDefaultNightMode(ThemeChoice.nightMode(context))
        ActivityScenario.launch(OnboardingActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val button = activity.findViewById<View>(R.id.obTheme)
                assertEquals(
                    "a paper page should offer the moon",
                    "Switch to dark",
                    button.contentDescription?.toString(),
                )
                button.performClick()
            }
        }
        assertEquals(
            "tapping the tour's button did not turn the page",
            ThemeChoice.DARK,
            Prefs.themeMode(context),
        )

        Prefs.setThemeMode(context, ThemeChoice.DARK)
        AppCompatDelegate.setDefaultNightMode(ThemeChoice.nightMode(context))
        ActivityScenario.launch(OnboardingActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val button = activity.findViewById<View>(R.id.obTheme)
                assertEquals(
                    "a charcoal page should offer the sun",
                    "Switch to light",
                    button.contentDescription?.toString(),
                )
            }
        }
    }

    /* ── the page behind it ───────────────────────────────────────────── */

    /**
     * A page rebuilt for the theme is not the app being opened for the first
     * time. The page behind the welcome and the tour is rebuilt whenever the
     * choice is made from the tour, and it used to launch the welcome again on
     * the way — the first screen flashing up over the one being read.
     */
    @Test
    fun aRebuiltHomeDoesNotOpenTheWelcomeAgain() {
        Prefs.setOnboardingSeen(context, false)

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            assertTrue("the first launch never opened the welcome", welcomeIsOnScreen())

            // Get the welcome out of the way, so what is rebuilt is the page
            // behind it — the one a theme change rebuilds.
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                ActivityLifecycleMonitorRegistry.getInstance()
                    .getActivitiesInStage(Stage.RESUMED)
                    .filterIsInstance<WelcomeActivity>()
                    .forEach { it.finish() }
            }
            Thread.sleep(400)
            assertFalse("the welcome could not be closed", welcomeIsOnScreen())

            scenario.recreate()
            Thread.sleep(600)

            assertFalse(
                "a rebuilt page opened the welcome again",
                welcomeIsOnScreen(),
            )
        }
    }

    /** The pick is written down once, wherever it was made. */
    @Test
    fun pickingASurfaceWritesItWhereSettingsWritesIt() {
        Prefs.setThemeMode(context, ThemeChoice.SYSTEM)
        Prefs.setOnboardingSeen(context, true)

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                ThemePicker.choose(activity, ThemeChoice.DARK)
            }
        }

        // The write happens before AppCompat rebuilds the page, so it is already
        // true when the next screen is drawn.
        assertEquals(ThemeChoice.DARK, Prefs.themeMode(context))
        assertTrue("the app is not dark after choosing Dark", ThemeChoice.isDark(context))
    }
}
