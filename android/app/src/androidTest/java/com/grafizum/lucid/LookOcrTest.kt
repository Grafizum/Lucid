package com.grafizum.lucid

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import androidx.test.ext.junit.runners.AndroidJUnit4
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
 * Look mode on a real picture, on a real device.
 *
 * The JVM tests check the grouping rule against boxes written by hand. This one
 * checks the whole path — ML Kit reading a bitmap of text, the sections being
 * cut out of the lines it returns, the lens finding the block it is on — because
 * the failure the app actually has to survive is a photo with more than one
 * block of text in it: a menu, a poster, a slide.
 *
 * The image is drawn here rather than shipped as a PNG so the expected position
 * of every block is known exactly and the assertions are about the picture, not
 * about a fixture nobody can re-derive.
 */
@RunWith(AndroidJUnit4::class)
class LookOcrTest {

    private companion object {
        const val WIDTH = 1080
        const val HEIGHT = 1350

        /** Where each block is drawn, so the boxes the OCR reports can be checked. */
        val TITLE = 60..160
        val PARAGRAPH = 180..330
        val MENU = 690..880
        val TOTAL = 1140..1230

        const val SLACK = 20
    }

    private var lastLines: List<Sections.Line> = emptyList()

    private fun draw(): Bitmap {
        val bitmap = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)

        val black = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            typeface = Typeface.DEFAULT
        }

        // One block with a heading and a two-line paragraph.
        black.textSize = 64f
        canvas.drawText("Screen translator", 60f, 130f, black)
        black.textSize = 44f
        canvas.drawText("Drag the lens over the text", 60f, 240f, black)
        canvas.drawText("and it translates in place.", 60f, 296f, black)

        // A second block, far enough below to be its own section.
        black.textSize = 56f
        canvas.drawText("Photo menu tonight", 60f, 760f, black)
        black.textSize = 44f
        canvas.drawText("Soup and salad", 60f, 850f, black)

        // A third block on its own.
        black.textSize = 44f
        canvas.drawText("Total twelve euros", 60f, 1200f, black)

        return bitmap
    }

    /** The frame read the way Look mode reads it: OCR lines, then sections. */
    private fun sectionsOf(bitmap: Bitmap): List<Sections.Section> = runBlocking {
        val result = withTimeout(60_000) {
            TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
                .process(InputImage.fromBitmap(bitmap, 0))
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

        // The same first step Look mode takes, so the test covers the shipped path.
        Sections.of(lines)
    }

    @Test
    fun lookModeCutsThePictureIntoBlocks() {
        val sections = sectionsOf(draw())
        val all = sections.joinToString(" | ") { it.text } +
            "\nsections=" + sections.size +
            "\nlines:\n" + lastLines.joinToString("\n") { "  '${it.text}' ${it.left}..${it.right} x ${it.top}..${it.bottom} (h${it.height})" }
        assertTrue("OCR read nothing at all", all.isNotBlank())

        // 1. The letters came back.
        assertTrue("no title in: $all", all.contains("translator", ignoreCase = true))
        assertTrue("no body in: $all", all.contains("translates", ignoreCase = true))
        assertTrue("no menu in: $all", all.contains("menu", ignoreCase = true))
        assertTrue("no total in: $all", all.contains("euros", ignoreCase = true))

        // 2. Four blocks were drawn and four come back: a title set well clear of
        //    its text is its own block, the paragraph under it is one block, the
        //    menu heading and its dish are one block, the total is one block.
        assertEquals("sections for:\n$all", 4, sections.size)

        val title = sections[0]
        val paragraph = sections[1]
        val menu = sections[2]
        val total = sections[3]

        // 3. Each block holds its own words, and only its own.
        assertEquals("Screen translator", title.text.trim())
        assertTrue("paragraph was: ${paragraph.text}", paragraph.text.contains("translates", true))
        assertTrue("menu was: ${menu.text}", menu.text.contains("menu", true))
        assertTrue("menu lost its dish: ${menu.text}", menu.text.contains("salad", true))
        assertTrue("total was: ${total.text}", total.text.contains("euros", true))

        assertTrue("title ate the body: ${title.text}", !title.text.contains("translates", true))
        assertTrue("paragraph ate the title: ${paragraph.text}", !paragraph.text.contains("translator", true))
        assertTrue("menu ate the total: ${menu.text}", !menu.text.contains("euros", true))

        // 4. The sections sit where the words were drawn.
        assertWithin(title.top, title.bottom, TITLE, "title")
        assertWithin(paragraph.top, paragraph.bottom, PARAGRAPH, "paragraph")
        assertWithin(menu.top, menu.bottom, MENU, "menu")
        assertWithin(total.top, total.bottom, TOTAL, "total")

        // 5. Reading order runs down the page.
        assertTrue(
            "sections out of order: ${sections.map { it.top }}",
            title.top < paragraph.top && paragraph.top < menu.top && menu.top < total.top,
        )
    }

    @Test
    fun lensAnswersForTheBlockItRestsOn() {
        val sections = sectionsOf(draw())

        val onTotal = Sections.under(sections, x = 300, y = 1190)
        assertNotNull("nothing under the lens on the total", onTotal)
        assertTrue("lens on the total read: ${onTotal?.text}", onTotal!!.text.contains("euros", true))

        val onMenu = Sections.under(sections, x = 300, y = 770)
        assertNotNull("nothing under the lens on the menu", onMenu)
        assertTrue("lens on the menu read: ${onMenu?.text}", onMenu!!.text.contains("menu", true))
        assertTrue("menu answer spilled into the total: ${onMenu.text}", !onMenu.text.contains("euros", true))

        // A lens on the title answers with the title, not with the paragraph
        // sitting under it — the whole point of cutting the picture into blocks.
        val onTitle = Sections.under(sections, x = 300, y = 100)
        assertNotNull("nothing under the lens on the title", onTitle)
        assertTrue("lens on the title read: ${onTitle?.text}", onTitle!!.text.contains("translator", true))
        assertTrue("title answer dragged the body in: ${onTitle.text}", !onTitle.text.contains("translates", true))

        // 6. Paper between two blocks is still nothing.
        assertNull("blank space answered with text", Sections.under(sections, x = 540, y = 520))
    }

    /** A poster of names: three rows, set well apart, as a card or a credit list is. */
    private fun names(): Bitmap {
        val bitmap = Bitmap.createBitmap(WIDTH, 700, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)

        val black = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            typeface = Typeface.DEFAULT
            textSize = 56f
        }
        canvas.drawText("Mia Chen", 60f, 140f, black)
        canvas.drawText("Arjun Patel", 60f, 330f, black)
        canvas.drawText("Sofia Rossi", 60f, 520f, black)
        return bitmap
    }

    /**
     * The list case, read the way the lens reads it now: the lens rests on one
     * name and the block it asks for holds the names around it, not only the row
     * under the glass.
     */
    @Test
    fun aListOfNamesIsReadWholeNotOneName() {
        val sections = sectionsOf(names())
        val lines = lastLines
        val all = sections.joinToString(" | ") { it.text }
        assertTrue("OCR found no names in: $all", all.contains("Mia", true) && all.contains("Rossi", true))

        val middle = lines.firstOrNull { it.text.contains("Arjun", true) }
            ?: throw AssertionError("no middle name in:\n$all")
        val x = (middle.left + middle.right) / 2
        val y = (middle.top + middle.bottom) / 2

        val section = Sections.under(sections, x = x, y = y)
        assertNotNull("nothing under the lens on the middle name", section)
        // The old read: one section, which is one name.
        assertTrue(
            "one section already held every name, so this proves nothing: $all",
            !section!!.text.contains("Rossi", true) || !section.text.contains("Mia", true),
        )

        val reach = section.lines.minOf { it.height } * 3
        val block = Sections.blockOf(sections, section, reach)
        val read = block.joinToString("\n") { it.text }

        assertTrue("block lost a name above: $read", read.contains("Mia", true))
        assertTrue("block lost the name under the lens: $read", read.contains("Arjun", true))
        assertTrue("block lost a name below: $read", read.contains("Rossi", true))
    }

    private fun assertWithin(top: Int, bottom: Int, band: IntRange, what: String) {
        assertTrue(
            "$what came back at $top..$bottom, drawn at ${band.first}..${band.last}",
            top >= band.first - SLACK && bottom <= band.last + SLACK,
        )
    }
}
