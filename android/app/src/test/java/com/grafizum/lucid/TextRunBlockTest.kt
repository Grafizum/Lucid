package com.grafizum.lucid

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * One gesture reads the block, not the clause: a paragraph comes whole, and a
 * block written as bullets is answered as bullets. These are the two pieces of
 * that rule that can be checked without a screen.
 */
class TextRunBlockTest {

    @Test
    fun `a whole node is the block`() {
        val text = "Line one of the post\nLine two, under it\nLine three."
        val from = text.indexOf("two")

        assertEquals(text, TextRun.block(text, from, from + 3).text)
    }

    @Test
    fun `the block is trimmed at both ends`() {
        val focus = TextRun.block("  padded block  ", 3, 6)

        assertEquals("padded block", focus.text)
        assertEquals(2, focus.start)
        assertEquals(2 + "padded block".length, focus.end)
    }

    @Test
    fun `a long paragraph comes whole`() {
        // Past the length of a screen: a paragraph of a document is still one
        // thing to read, and cutting it to its sentence left the rest untranslated.
        val body = (1..180).joinToString(" ") { "word$it" }
        val text = "$body. The last sentence of the paragraph."
        val from = text.indexOf("word90")
        assertTrue(text.length > 600)

        val focus = TextRun.block(text, from, from + 6)

        assertEquals(text, focus.text)
    }

    @Test
    fun `a page of text is not one gesture`() {
        // A node holding an article: the sentence around the lens is the unit.
        val text = (1..500).joinToString(" ") { "word$it" } + ". Another sentence here."
        val from = text.indexOf("Another")

        val focus = TextRun.block(text, from, from + 7)

        assertEquals("Another sentence here.", focus.text)
    }

    @Test
    fun `bullets make a list`() {
        assertTrue(TextRun.looksLikeList("• first\n• second"))
        assertTrue(TextRun.looksLikeList("- first\n- second"))
        assertTrue(TextRun.looksLikeList("Some heading\n• first\n• second"))
    }

    @Test
    fun `a paragraph with a hyphen is not a list`() {
        assertFalse(TextRun.looksLikeList("A well-known place, and a long-winded one."))
        assertFalse(TextRun.looksLikeList("• only one bullet"))
    }

    @Test
    fun `numbered rows count as a list`() {
        assertTrue(TextRun.looksLikeList("1. Open the app\n2. Tap the lens"))
        assertTrue(TextRun.startsWithBullet("3) Third step"))
        assertFalse(TextRun.startsWithBullet("2023 was a year"))
    }

    @Test
    fun `a list is given back one bullet per row`() {
        assertEquals("•  First line\n•  Second line", TextRun.asBullets("• First line\n- Second line"))
    }

    @Test
    fun `a plain paragraph is not given bullets`() {
        assertFalse(TextRun.looksLikeList("Settings\nDisplay\nSound\nBattery"))
    }
}
