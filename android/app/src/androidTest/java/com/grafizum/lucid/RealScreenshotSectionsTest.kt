package com.grafizum.lucid

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The grouping rule against a real picture, not a bitmap drawn by a test.
 *
 * [LookOcrTest] draws its own image so it knows exactly where every block sits.
 * That proves the rule does what it says, but not that the 1.2x gap threshold
 * survives the boxes ML Kit actually returns from a screenshot: line heights
 * come back a few pixels off, text wraps, some rows carry a trailing timestamp.
 * This one reads assets/home_screen.png — a 1080x2400 capture of the Lucid home
 * screen from a Pixel 7a — and checks that the blocks a person can see on that
 * screen are the blocks the lens gets.
 *
 * The fixture is a real screenshot (copied from design/phone-home.png) rather
 * than something drawn here, so the spacing is the spacing a real renderer
 * produced: title over a paragraph, four permission rows, a target language
 * row, two setting rows and a footer, each separated by different amounts of
 * empty paper.
 */
@RunWith(AndroidJUnit4::class)
class RealScreenshotSectionsTest {

    private companion object {
        const val ASSET = "home_screen.png"

        /** 1.2x, spelled out here so the test fails if the rule ever widens. */
        const val LEADING = 1.2f
    }

    private var lastLines: List<Sections.Line> = emptyList()

    private fun screenshot(): Bitmap {
        val assets = InstrumentationRegistry.getInstrumentation().context.assets
        return assets.open(ASSET).use { BitmapFactory.decodeStream(it) }
            ?: throw AssertionError("could not decode $ASSET")
    }

    /** Exactly what Look mode does with a frame: OCR lines, then sections. */
    private fun readScreenshot(): List<Sections.Section> = runBlocking {
        val result = withTimeout(60_000) {
            TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
                .process(InputImage.fromBitmap(screenshot(), 0))
                .await()
        }

        val lines = result.textBlocks
            .flatMap { block -> block.lines }
            .mapNotNull { line ->
                val box = line.boundingBox ?: return@mapNotNull null
                val text = line.text.trim()
                if (text.length < 2) null else Sections.Line(text, box.left, box.top, box.right, box.bottom)
            }

        lastLines = lines
        Sections.of(lines, LEADING)
    }

    private fun dump(sections: List<Sections.Section>): String =
        sections.mapIndexed { index, section ->
            "[$index] ${section.top}..${section.bottom} :: " + section.text.replace("\n", " ⏎ ")
        }.joinToString("\n") +
            "\nlines:\n" + lastLines.joinToString("\n") {
                "  '${it.text}' ${it.left}..${it.right} x ${it.top}..${it.bottom} (h${it.height})"
            }

    /** The section holding a line whose text contains [needle]. */
    private fun sectionWith(sections: List<Sections.Section>, needle: String): Sections.Section? =
        sections.firstOrNull { it.lines.any { line -> line.text.contains(needle, true) } }

    @Test
    fun realScreenshotBreaksIntoTheBlocksYouCanSee() {
        val sections = readScreenshot()
        val all = dump(sections)

        // 1. A real screenshot reads as text at all.
        assertTrue("OCR read nothing from $ASSET", lastLines.isNotEmpty())
        assertTrue("nothing grouped from $ASSET", sections.isNotEmpty())

        // 2. The words a person can see came back.
        for (word in listOf("Lucid", "Float over other apps", "Text access", "Look mode",
            "Quick button", "English", "Auto", "Engine", "Start the lens", "grafizum.com")) {
            assertTrue("OCR missed '$word' in:\n$all", lastLines.any { it.text.contains(word, true) })
        }

        // 3. Each permission row is one block: its title and its explanation —
        //    the gap between them is smaller than a line, the divider between
        //    rows is much bigger, so the threshold has to sit between the two.
        val float = sectionWith(sections, "Float over other apps")
        assertNotNull("no section for the float row in:\n$all", float)
        assertTrue("float row lost its explanation: ${float!!.text}", float.text.contains("lens sit on top", true))
        assertTrue("float row ate the next row: ${float.text}", !float.text.contains("Text access", true))

        val textAccess = sectionWith(sections, "Text access")
        assertNotNull("no section for the text access row in:\n$all", textAccess)
        assertTrue("text access lost its explanation: ${textAccess!!.text}",
            textAccess.text.contains("read text", true))
        assertTrue("text access ate the float row: ${textAccess.text}",
            !textAccess.text.contains("Float over other apps", true))

        // 4. A wrapped line stays with its own row, not with the row below it.
        val look = sectionWith(sections, "Look mode")
        assertNotNull("no section for the look mode row in:\n$all", look)
        assertTrue("look mode lost its wrapped explanation: ${look!!.text}",
            look.text.contains("hidden text", true))
        assertTrue("look mode ate the quick button row: ${look.text}",
            !look.text.contains("Quick button", true))

        // 5. The slider is its own block, and so is each footer item: the
        //    version and the address sit at opposite edges of the page, which
        //    is the same case as two columns of a menu. OCR read the 1 as an l
        //    here ("vl.0.0"), which is exactly the sort of noise a real
        //    screenshot brings and the grouping must not care about.
        val slider = sectionWith(sections, "Start the lens")
        assertNotNull("no section for the slider in:\n$all", slider)
        assertTrue("slider swallowed the footer: ${slider!!.text}",
            !slider.text.contains("grafizum.com", true))

        val version = sectionWith(sections, ".0.0")
        assertNotNull("no section for the footer in:\n$all", version)
        assertTrue("footer swallowed the slider: ${version!!.text}",
            !version.text.contains("Start the lens", true))

        val address = sectionWith(sections, "grafizum.com")
        assertNotNull("no section for the address in:\n$all", address)
        assertTrue("address swallowed the version: ${address!!.text}",
            !address.text.contains(".0.0", true))
        assertTrue("the two footer items share a column, so they read as one row",
            version.right < address.left || address.right < version.left)

        // 6. The title and the paragraph under it are separate thoughts, but
        //    the screenshot sets them close — one block is correct here, and it
        //    must not run into the card below.
        val title = sectionWith(sections, "Lucid")
        assertNotNull("no section for the title in:\n$all", title)
        assertTrue("title lost its paragraph: ${title!!.text}", title.text.contains("translates it in place", true))
        assertTrue("title ate the float row: ${title.text}", !title.text.contains("Float over other apps", true))

        // 7. The threshold is what is doing the work: every break between two
        //    blocks on real OCR geometry is wider than 1.2x the line above it,
        //    and every line kept inside a block is within it.
        assertThresholdIsWhereTheBreaksAre(sections)
    }

    @Test
    fun lensOnARealScreenshotAnswersForItsOwnBlock() {
        val sections = readScreenshot()
        val all = dump(sections)

        // Aim at the middle of a line the OCR reported, so the test follows the
        // picture's own geometry instead of coordinates guessed by hand.
        fun centreOf(needle: String): Pair<Sections.Section, Sections.Line> {
            val line = lastLines.firstOrNull { it.text.contains(needle, true) }
                ?: throw AssertionError("OCR never saw '$needle' in:\n$all")
            val section = Sections.under(sections, (line.left + line.right) / 2, (line.top + line.bottom) / 2)
                ?: throw AssertionError("nothing under the lens on '$needle' in:\n$all")
            return section to line
        }

        // On the engine row: the answer is the engine row, not the row above it.
        val (engine, engineLine) = centreOf("Engine")
        assertTrue("engine answer was: ${engine.text}", engine.text.contains("Engine", true))
        assertTrue("engine answer was: ${engine.text}", engine.text.contains("Google Translate", true))
        assertTrue("engine answer dragged in auto-translate: ${engine.text}",
            !engine.text.contains("Auto", true))
        assertEquals("lens did not land on the engine row",
            Sections.under(sections, (engineLine.left + engineLine.right) / 2,
                (engineLine.top + engineLine.bottom) / 2), engine)

        // On the footer: the footer only.
        val (footer, _) = centreOf("grafizum.com")
        assertTrue("footer answer was: ${footer.text}", footer.text.contains("grafizum.com", true))
        assertTrue("footer answer dragged in the slider: ${footer.text}",
            !footer.text.contains("Start the lens", true))

        // On the paragraph: the paragraph and its title, never the card below.
        val (paragraph, _) = centreOf("translates it in place")
        assertTrue("paragraph answer was: ${paragraph.text}", paragraph.text.contains("WhatsApp", true))
        assertTrue("paragraph answer dragged in the rows: ${paragraph.text}",
            !paragraph.text.contains("Float over other apps", true))

        // Empty paper between the title block and the first card answers nothing.
        val rowTop = sectionWith(sections, "Float over other apps")!!.top
        val titleBottom = sectionWith(sections, "Lucid")!!.bottom
        val gapY = (titleBottom + rowTop) / 2
        assertTrue("no empty band between '$titleBottom' and '$rowTop' to test",
            rowTop - titleBottom > 8)
        assertNull("empty paper answered with text", Sections.under(sections, 60, gapY))
    }

    /**
     * The rule's whole claim is that 1.2x a line's height separates blocks.
     * Checking it against the boxes the OCR really returned is the point of
     * this test: inside a block the gap is within the threshold, across a break
     * it is not.
     */
    private fun assertThresholdIsWhereTheBreaksAre(sections: List<Sections.Section>) {
        for (section in sections) {
            val lines = section.lines
            for (index in 1 until lines.size) {
                val above = lines[index - 1]
                val gap = lines[index].top - above.bottom
                val tolerance = maxOf(4f, above.height * LEADING)
                assertTrue(
                    "line '${lines[index].text}' is ${gap}px under '${above.text}' " +
                        "(h${above.height}, tolerance ${tolerance.toInt()}) but was kept in the same block:\n" +
                        dump(sections),
                    gap <= tolerance,
                )
            }
        }

        // And a break really is a break: the lowest line of one block and the
        // highest line of the next, when they sit one under the other and share
        // a column, are further apart than the threshold allows.
        //
        // Blocks that overlap vertically are skipped on purpose. A real
        // screenshot nests text: this one has a tiny logo glyph OCR read as
        // "At" inside the title block's own width but level with its top, and
        // blocks side by side (the two footer items, LENS OFF beside SCREEN
        // LENS) are separated by the column rule, not by a gap.
        for (index in 1 until sections.size) {
            val above = sections[index - 1]
            val below = sections[index]
            val aboveLine = above.lines.maxByOrNull { it.bottom }!!
            val belowLine = below.lines.minByOrNull { it.top }!!
            if (belowLine.top < aboveLine.bottom) continue
            val overlap = minOf(above.right, below.right) - maxOf(above.left, below.left)
            if (overlap <= 0) continue
            val gap = belowLine.top - aboveLine.bottom
            val tolerance = maxOf(4f, aboveLine.height * LEADING)
            assertTrue(
                "two blocks sit ${gap}px apart (tolerance ${tolerance.toInt()}) yet were split:\n" +
                    dump(sections),
                gap > tolerance,
            )
        }
    }
}
