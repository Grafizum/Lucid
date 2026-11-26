package com.grafizum.lucid

import android.content.Context
import android.view.MotionEvent
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Recent reads on a real device.
 *
 * The page is built from [Prefs] and nothing else, so what it shows is exactly
 * what the phone stored: a read the lens made, in the order it was made.
 */
@RunWith(AndroidJUnit4::class)
class HistoryTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun read(source: String, translated: String, ago: Long = 0) = Prefs.Read(
        source = source,
        translated = translated,
        detected = "es",
        target = "en",
        packageName = "com.whatsapp",
        at = System.currentTimeMillis() - ago,
    )

    @Test
    fun storedReadsRenderAndOpen() {
        Prefs.clearHistory(context)
        // Older first, then the newer: the list is newest first, so the station
        // ends up at the top.
        Prefs.addRead(context, read("本日の会議は中止です", "Today's meeting is cancelled.", ago = 600_000))
        Prefs.addRead(context, read("¿Dónde está la estación?", "Where is the station?", ago = 120_000))

        ActivityScenario.launch(HistoryActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val list = activity.findViewById<LinearLayout>(R.id.historyList)
                val rows = (0 until list.childCount).map { list.getChildAt(it) }.filter { it.isClickable }

                assertEquals("one row per stored read", 2, rows.size)
                assertEquals(
                    "the empty notice is not shown when there are reads",
                    View.GONE,
                    activity.findViewById<TextView>(R.id.historyEmpty).visibility,
                )

                // The row shows the line the lens saw and opens the whole read
                // as a card over the page: the answer, the original, the pair of
                // languages and where and when it was read.
                val row = rows.first()
                assertTrue("the row opens the read", row.hasOnClickListeners())

                val detail = activity.detailView(Prefs.history(context).first())
                assertEquals(
                    "the answer is shown, not the original",
                    "Where is the station?",
                    detail.findViewById<TextView>(R.id.detailTranslation).text.toString(),
                )
                assertEquals(
                    "the original travels with it",
                    "¿Dónde está la estación?",
                    detail.findViewById<TextView>(R.id.detailSource).text.toString(),
                )

                val langs = detail.findViewById<LinearLayout>(R.id.detailLangs)
                assertEquals(
                    Languages.short("es").uppercase(),
                    (langs.getChildAt(0) as TextView).text.toString(),
                )
                assertEquals(
                    Languages.short("en").uppercase(),
                    (langs.getChildAt(2) as TextView).text.toString(),
                )
                assertTrue(
                    "the app and the time are named",
                    detail.findViewById<TextView>(R.id.detailMeta).text.isNotBlank(),
                )
            }
        }
    }

    @Test
    fun historyKeepsTwentyAndIgnoresAnImmediateRepeat() {
        Prefs.clearHistory(context)
        repeat(60) { index ->
            Prefs.addRead(context, read("line $index", "answer $index", ago = index.toLong()))
        }

        val kept = Prefs.history(context)
        assertEquals("the list is capped at twenty", 20, kept.size)
        assertEquals("the newest read is first", "line 59", kept.first().source)
        // Sixty went in, so the forty oldest left: twenty lines is enough to go
        // back through a screen you were reading, and small enough to be a log.
        assertEquals("the oldest read falls off", "line 40", kept.last().source)

        // Asking twice for the same line is one read, not two.
        Prefs.addRead(context, read("line 59", "answer 59"))
        assertEquals(20, Prefs.history(context).size)
        assertEquals(1, Prefs.history(context).count { it.source == "line 59" })
    }

    /** A gesture on the row, as a finger would make it: down, travel, up. */
    private fun drag(view: View, dx: Float) {
        val x = view.width / 2f
        val y = view.height / 2f
        val now = android.os.SystemClock.uptimeMillis()
        view.dispatchTouchEvent(MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, x, y, 0))
        view.dispatchTouchEvent(MotionEvent.obtain(now, now + 40, MotionEvent.ACTION_MOVE, x + dx, y, 0))
        view.dispatchTouchEvent(MotionEvent.obtain(now, now + 80, MotionEvent.ACTION_UP, x + dx, y, 0))
    }

    /**
     * A leftward swipe is the delete, and it deletes the row it was made on:
     * a short drag springs back and keeps the read, a long one takes exactly
     * that one — never the log.
     */
    @Test
    fun aLeftSwipeDeletesOneRead() {
        Prefs.clearHistory(context)
        Prefs.addRead(context, read("older", "older answer", ago = 3_000))
        Prefs.addRead(context, read("newest", "newest answer", ago = 1_000))

        ActivityScenario.launch(HistoryActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val list = activity.findViewById<LinearLayout>(R.id.historyList)
                fun firstRow(): View =
                    (0 until list.childCount).map { list.getChildAt(it) }.first { it.isClickable }

                val density = activity.resources.displayMetrics.density

                drag(firstRow(), -20f * density)
                assertEquals(
                    "a short drag is not a delete",
                    2,
                    Prefs.history(context).size,
                )

                drag(firstRow(), -120f * density)
                assertEquals(
                    "the swipe takes one read",
                    listOf("older"),
                    Prefs.history(context).map { it.source },
                )
            }
        }

        Prefs.clearHistory(context)
    }

    /**
     * A swipe deletes the row it was made on and no other: the reads either
     * side keep their place, and the count drops by exactly one.
     */
    @Test
    fun deletingOneReadLeavesTheRest() {
        Prefs.clearHistory(context)
        Prefs.addRead(context, read("older", "older answer", ago = 3_000))
        Prefs.addRead(context, read("middle", "middle answer", ago = 2_000))
        Prefs.addRead(context, read("newest", "newest answer", ago = 1_000))

        val middle = Prefs.history(context).first { it.source == "middle" }
        Prefs.removeRead(context, middle)

        val left = Prefs.history(context)
        assertEquals("one row goes, not the log", 2, left.size)
        assertEquals("the reads either side are untouched", listOf("newest", "older"), left.map { it.source })

        Prefs.clearHistory(context)
    }

    @Test
    fun clearingIsOfferedOnlyWhenThereIsSomethingToClear() {
        Prefs.clearHistory(context)

        ActivityScenario.launch(HistoryActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                assertEquals(
                    "nothing to clear is no button",
                    View.GONE,
                    activity.findViewById<View>(R.id.historyClear).visibility,
                )
            }
        }

        Prefs.addRead(context, read("¿Dónde está la estación?", "Where is the station?"))
        ActivityScenario.launch(HistoryActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                assertEquals(
                    "a read on the phone can be wiped",
                    View.VISIBLE,
                    activity.findViewById<View>(R.id.historyClear).visibility,
                )
            }
        }

        Prefs.clearHistory(context)
    }

    @Test
    fun emptyStateExplainsItself() {
        Prefs.clearHistory(context)

        ActivityScenario.launch(HistoryActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                assertEquals(
                    View.VISIBLE,
                    activity.findViewById<TextView>(R.id.historyEmpty).visibility,
                )
                assertTrue((activity.findViewById<LinearLayout>(R.id.historyList)).childCount == 0)
            }
        }
    }
}
