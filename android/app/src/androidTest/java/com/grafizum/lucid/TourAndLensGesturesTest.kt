package com.grafizum.lucid

import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.view.ContextThemeWrapper
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The two things on screen that can break without a single line of logic
 * changing: the welcome tour, which is hand-drawn loops and page state, and the
 * slide that starts the lens, which only exists as a gesture.
 *
 * The slide is exercised in a real window, because it hands its answer back
 * through a posted message — a view that is never attached would queue that
 * message forever and prove nothing. Hosting it in the demo chat keeps the test
 * about the gesture rather than about the home screen's permissions.
 */
@RunWith(AndroidJUnit4::class)
class TourAndLensGesturesTest {

    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    /** Views animate, so anything built outside a scenario is made on the looper. */
    private fun onMain(block: () -> Unit) = instrumentation.runOnMainSync(block)

    private fun themed(): Context = ContextThemeWrapper(
        ApplicationProvider.getApplicationContext<Context>(),
        R.style.Theme_Lucid,
    )

    /* ── the tour ─────────────────────────────────────────────────────── */

    /** Seven loops, starting on the first, each describing itself for TalkBack. */
    @Test
    fun theTourHasSevenPagesAndStartsOnTheFirst() {
        lateinit var scene: OnboardingScene
        onMain { scene = OnboardingScene(themed()) }

        assertEquals("the tour lost a page", 7, OnboardingScene.LAST + 1)
        val first = scene.contentDescription?.toString().orEmpty()
        assertTrue("the first page describes nothing", first.isNotBlank())

        onMain { scene.page = OnboardingScene.LAST }
        val last = scene.contentDescription?.toString().orEmpty()
        assertTrue("the last page describes nothing", last.isNotBlank())
        assertTrue("the tour never changed page", last != first)
    }

    /* ── the slide ────────────────────────────────────────────────────── */

    @Test
    fun draggingTheWellAllTheWayFiresOnce() {
        var fired: SwipeToStart.Mode? = null
        var count = 0

        withSlide { view ->
            view.onComplete = { mode ->
                fired = mode
                count++
            }
        }

        assertEquals("a full drag must fire once, not $count times", 1, count)
        assertEquals("a slide from rest starts the lens", SwipeToStart.Mode.START, fired)
    }

    @Test
    fun aNudgeIsNotADrag() {
        var count = 0

        // A couple of percent of travel: still a press, nowhere near the
        // commit line the slide draws.
        withSlide(fromFraction = 0.08f, toFraction = 0.10f) { view ->
            view.onComplete = { count++ }
        }

        assertEquals("a short nudge must not start anything", 0, count)
    }

    /* ── the page's new rows ──────────────────────────────────────────── */

    /** The model list inflates, and the engine row is the only privacy switch. */
    @Test
    fun thePageCarriesTheNewPrivacyRows() {
        lateinit var page: View
        onMain {
            page = LayoutInflater.from(themed()).inflate(R.layout.activity_main, null)
        }

        // Offline only used to sit under Engine and turn it off. It is the
        // On-device state of Engine itself now, so the second row is gone.
        val offlineRow = page.resources.getIdentifier("offlineBtn", "id", page.context.packageName)
        assertEquals("a second offline switch came back", 0, offlineRow)
        assertNotNull("no offline models row", page.findViewById<View>(R.id.modelsRow))
        assertNotNull("the engine row lost its hint", page.findViewById<View>(R.id.engHint))

        // The sent panel is gone: the page keeps Recent reads and nothing else
        // that looks back at what the engine was handed.
        val sentRow = page.resources.getIdentifier("sentRow", "id", page.context.packageName)
        assertEquals("the sent panel is still on the page", 0, sentRow)
    }

    /* ── the two per-read answers ─────────────────────────────────────── */

    /** A language picked over one app is kept for that app, and only it. */
    @Test
    fun aLanguageIsRememberedPerApp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val chat = "com.example.test.chat"
        val browser = "com.example.test.browser"
        val global = Prefs.targetLanguage(context)

        try {
            Prefs.setTargetFor(context, chat, "de")
            Prefs.setTargetFor(context, browser, "es")

            assertEquals("de", Prefs.targetFor(context, chat))
            assertEquals("es", Prefs.targetFor(context, browser))
            assertEquals(
                "an app with no pick keeps the app's own language",
                global,
                Prefs.targetFor(context, "com.example.test.never.seen"),
            )
            assertEquals("the global answer moved", global, Prefs.targetLanguage(context))
        } finally {
            Prefs.setTargetFor(context, chat, global)
            Prefs.setTargetFor(context, browser, global)
        }
    }

    /**
     * A script Android can spell out gets a reading; one that is already Latin,
     * and a Japanese line with kanji, do not.
     */
    @Test
    fun romanisationOnlyAnswersWhereItCan() {
        assertNull("Latin needs no reading", Romanisation.of("Hola", "es"))
        assertNull("nothing to spell", Romanisation.of("   ", "ja"))

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            // Android 10 is where ICU's transliterators start.
            assertNull(Romanisation.of("こんにちは", "ja"))
            return
        }

        val japanese = Romanisation.of("こんにちは", "ja")
        assertNotNull("kana should be spellable from Android 10", japanese)
        assertTrue("not Latin: $japanese", japanese!!.all { it.code < 128 })

        assertNull("kanji were romanised as if they were Chinese", Romanisation.of("日本語", "ja"))
    }

    /* ── plumbing ─────────────────────────────────────────────────────── */

    /**
     * Puts a slide on a real window, drags it all the way, and waits for the
     * completion that follows the words crossing. [configure] runs before the
     * drag so the callback is in place.
     */
    private fun withSlide(
        fromFraction: Float = 0.08f,
        toFraction: Float = 0.92f,
        configure: (SwipeToStart) -> Unit,
    ) {
        lateinit var slide: SwipeToStart

        ActivityScenario.launch(DemoChatActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val context = ContextThemeWrapper(activity, R.style.Theme_Lucid)
                val density = context.resources.displayMetrics.density
                slide = SwipeToStart(context).apply {
                    startLabel = "Start the lens"
                    stopLabel = "Stop the lens"
                }
                configure(slide)
                activity.addContentView(
                    slide,
                    ViewGroup.LayoutParams(
                        (320 * density).toInt(),
                        (60 * density).toInt(),
                    ),
                )
            }

            // One frame to lay the slide out and let it settle.
            SystemClock.sleep(400)

            scenario.onActivity { drag(slide, fromFraction, toFraction) }

            // Complete() lands its words (250 ms), stands the check still
            // (210 ms) and then hands over: nothing is posted before that.
            SystemClock.sleep(1500)
        }
    }

    /** A real finger path on the looper: down, a run of moves, up. */
    private fun drag(view: SwipeToStart, fromFraction: Float, toFraction: Float) {
        val y = view.height / 2f
        val from = view.width * fromFraction
        val to = view.width * toFraction
        val steps = 8
        val downAt = SystemClock.uptimeMillis()

        fun event(action: Int, x: Float, at: Long) {
            val event = MotionEvent.obtain(downAt, at, action, x, y, 0)
            view.dispatchTouchEvent(event)
            event.recycle()
        }

        event(MotionEvent.ACTION_DOWN, from, downAt)
        for (step in 1..steps) {
            val x = from + (to - from) * step / steps
            event(MotionEvent.ACTION_MOVE, x, downAt + step * 16L)
        }
        event(MotionEvent.ACTION_UP, to, downAt + (steps + 1) * 16L)
    }
}
