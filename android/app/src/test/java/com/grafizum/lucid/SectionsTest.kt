package com.grafizum.lucid

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rule that decides what counts as one block of text. Every case here is a
 * picture the lens has to get right: a menu with two columns, a card with a
 * heading and a paragraph, a caption floating under a photo.
 */
class SectionsTest {

    private fun line(text: String, left: Int, top: Int, right: Int, bottom: Int) =
        Sections.Line(text, left, top, right, bottom)

    /** A 42px-tall line at [top], which is 20px of glyph plus its leading. */
    private fun row(text: String, left: Int, top: Int, right: Int) =
        line(text, left, top, right, top + 42)

    @Test
    fun `a paragraph of stacked lines is one section`() {
        val sections = Sections.of(
            listOf(
                row("Drag the lens over any text", 60, 100, 900),
                row("and it translates in place.", 60, 148, 860),
                row("No app switching.", 60, 196, 500),
            ),
        )

        assertEquals(1, sections.size)
        assertEquals(
            "Drag the lens over any text\nand it translates in place.\nNo app switching.",
            sections[0].text,
        )
    }

    @Test
    fun `a blank gap starts a new section`() {
        val sections = Sections.of(
            listOf(
                row("Photo menu tonight", 60, 100, 700),
                row("Soup and salad", 60, 148, 600),
                // three lines of empty space, then the price line
                row("Total twelve euros", 60, 400, 620),
            ),
        )

        assertEquals(2, sections.size)
        assertEquals("Photo menu tonight\nSoup and salad", sections[0].text)
        assertEquals("Total twelve euros", sections[1].text)
    }

    @Test
    fun `two columns of a menu stay apart`() {
        val sections = Sections.of(
            listOf(
                row("Soup", 60, 100, 300),
                row("Salad", 560, 100, 800),
                row("Bread", 60, 148, 320),
                row("Olives", 560, 148, 820),
            ),
        )

        assertEquals(2, sections.size)
        assertEquals("Soup\nBread", sections[0].text)
        assertEquals("Salad\nOlives", sections[1].text)
        // The columns hold the same rows, so they are ordered left to right.
        assertTrue(sections[0].left < sections[1].left)
    }

    @Test
    fun `a heading set tight above its paragraph stays in the block`() {
        val sections = Sections.of(
            listOf(
                row("Lucid", 60, 100, 260),
                row("Reads whatever the lens sits over,", 60, 190, 940),
                row("then translates it in place.", 60, 238, 900),
            ),
        )

        assertEquals(1, sections.size)
        assertTrue(sections[0].text.startsWith("Lucid"))
    }

    @Test
    fun `a title set clear of its text is its own section`() {
        val sections = Sections.of(
            listOf(
                row("Lucid", 60, 100, 260),
                // a whole heading's height of empty space under the title
                row("Reads whatever the lens sits over,", 60, 240, 940),
                row("then translates it in place.", 60, 288, 900),
            ),
        )

        assertEquals(2, sections.size)
        assertEquals("Lucid", sections[0].text)
        assertTrue(sections[1].text.startsWith("Reads whatever"))
    }

    @Test
    fun `a caption off to the side is its own section`() {
        val sections = Sections.of(
            listOf(
                row("Grilled sea bass with lemon", 60, 400, 880),
                row("Fig. 2", 60, 520, 200),
            ),
        )

        assertEquals(2, sections.size)
        assertEquals("Fig. 2", sections[1].text)
    }

    @Test
    fun `the lens finds the section it is resting on`() {
        val sections = Sections.of(
            listOf(
                row("Photo menu tonight", 60, 100, 700),
                row("Soup and salad", 60, 148, 600),
                row("Total twelve euros", 60, 400, 620),
            ),
        )

        val underPrice = Sections.under(sections, x = 300, y = 415)
        assertEquals("Total twelve euros", underPrice?.text)

        val underHeading = Sections.under(sections, x = 300, y = 115)
        assertEquals("Photo menu tonight\nSoup and salad", underHeading?.text)
    }

    @Test
    fun `a column of names set apart reads as one block`() {
        // A poster's names: each row is its own section, because the space
        // between two of them is wider than the leading of a paragraph.
        val sections = Sections.of(
            listOf(
                row("Mia Chen", 60, 100, 300),
                row("Arjun Patel", 60, 200, 360),
                row("Sofia Rossi", 60, 300, 340),
            ),
        )
        assertEquals(3, sections.size)

        val underSecond = Sections.under(sections, x = 200, y = 210)!!
        val block = Sections.blockOf(sections, underSecond, reach = 126)

        // All three names, top to bottom, not just the one under the lens.
        assertEquals(listOf("Mia Chen", "Arjun Patel", "Sofia Rossi"), block.map { it.text })
    }

    @Test
    fun `the other column of a list stays out of the block`() {
        val sections = Sections.of(
            listOf(
                row("Mia Chen", 60, 100, 300),
                row("Arjun Patel", 60, 200, 360),
                row("Sofia Rossi", 560, 100, 800),
                row("Noah Kim", 560, 200, 760),
            ),
        )

        val underName = Sections.under(sections, x = 200, y = 110)!!
        val block = Sections.blockOf(sections, underName, reach = 126)

        assertEquals(listOf("Mia Chen", "Arjun Patel"), block.map { it.text })
    }

    @Test
    fun `a block stops at the empty space below it`() {
        val sections = Sections.of(
            listOf(
                row("Mia Chen", 60, 100, 300),
                row("Arjun Patel", 60, 200, 360),
                // far below: a caption, not another row of the list
                row("Photograph by the studio", 60, 600, 620),
            ),
        )

        val underName = Sections.under(sections, x = 200, y = 110)!!
        val block = Sections.blockOf(sections, underName, reach = 126)

        assertEquals(listOf("Mia Chen", "Arjun Patel"), block.map { it.text })
    }

    @Test
    fun `empty paper answers with nothing`() {
        val sections = Sections.of(
            listOf(
                row("Photo menu tonight", 60, 100, 700),
                row("Total twelve euros", 60, 400, 620),
            ),
        )

        assertNull(Sections.under(sections, x = 540, y = 280))
    }

    @Test
    fun `nothing on the page is no sections at all`() {
        assertTrue(Sections.of(emptyList()).isEmpty())
        assertTrue(Sections.of(listOf(line("", 0, 0, 0, 0))).isEmpty())
    }
}
