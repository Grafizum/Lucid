package com.grafizum.lucid

import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The offline languages page on a real device.
 *
 * The list is the on-device half of [Languages.targets] filtered by what is
 * typed, so the page can be checked without a single pack being fetched: the
 * search is what decides which rows exist, and the two groups together must
 * always be every language that has a pack.
 */
@RunWith(AndroidJUnit4::class)
class ModelsTest {

    private fun names(list: LinearLayout): List<String> =
        (0 until list.childCount).map { index ->
            val card = list.getChildAt(index) as LinearLayout
            val words = card.getChildAt(1) as LinearLayout
            (words.getChildAt(0) as TextView).text.toString()
        }

    private fun offered(activity: ModelsActivity): List<String> =
        names(activity.findViewById(R.id.modelsOnList)) +
            names(activity.findViewById(R.id.modelsAddList))

    @Test
    fun everyLanguageIsOfferedUntilSomethingIsTyped() {
        ActivityScenario.launch(ModelsActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                assertEquals(
                    "the two groups together are every language with a pack",
                    Languages.targets.count { it.onDevice },
                    offered(activity).size,
                )
                assertEquals(
                    View.GONE,
                    activity.findViewById<TextView>(R.id.modelsEmpty).visibility,
                )
            }
        }
    }

    @Test
    fun searchNarrowsToTheMatchingLanguageAndIgnoresAccents() {
        ActivityScenario.launch(ModelsActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.findViewById<EditText>(R.id.modelsSearch).setText("espanol")

                val shown = offered(activity)
                assertTrue("an unaccented query still finds Español", shown.contains("Spanish"))
                assertFalse("the rest of the list is gone", shown.contains("Afrikaans"))
                assertEquals(1, shown.size)
            }
        }
    }

    @Test
    fun aNativeNameIsSearchable() {
        ActivityScenario.launch(ModelsActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.findViewById<EditText>(R.id.modelsSearch).setText("日本語")
                assertTrue(offered(activity).contains("Japanese"))
            }
        }
    }

    @Test
    fun nothingMatchingSaysSoInsteadOfAnEmptyPage() {
        ActivityScenario.launch(ModelsActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.findViewById<EditText>(R.id.modelsSearch).setText("zzzzzz")

                assertEquals(
                    View.VISIBLE,
                    activity.findViewById<TextView>(R.id.modelsEmpty).visibility,
                )
                assertEquals(0, offered(activity).size)
            }
        }
    }
}
