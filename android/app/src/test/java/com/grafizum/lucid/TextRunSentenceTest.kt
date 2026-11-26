package com.grafizum.lucid

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The tree path and the pixel path must read the same thing. An OCR line under
 * the lens is a sentence; live text under the lens is a node's whole string,
 * which is often several sentences or a title above a paragraph. This cuts the
 * node down to the sentence the lens is on, so one gesture reads one sentence
 * whichever path answered it.
 */
class TextRunSentenceTest {

    private fun sentenceAt(text: String, needle: String): String {
        val from = text.indexOf(needle).also { require(it >= 0) { "'$needle' is not in: $text" } }
        return TextRun.sentence(text, from, from + needle.length).text
    }

    @Test
    fun `a word under the lens reads its whole sentence`() {
        val text = "The train leaves at nine. Meet me on the platform."

        assertEquals("The train leaves at nine.", sentenceAt(text, "nine"))
        assertEquals("Meet me on the platform.", sentenceAt(text, "platform"))
    }

    @Test
    fun `the mark that ends the sentence stays in it`() {
        assertEquals("¿Llegaste bien a casa?", sentenceAt("¿Llegaste bien a casa? Sí, sin problema.", "casa"))
        assertEquals("本当？", sentenceAt("本当？今日は行けない。", "本当"))
        assertEquals("「そうだ。」", sentenceAt("「そうだ。」と彼は言った。", "そうだ"))
    }

    @Test
    fun `a title above a paragraph is its own sentence`() {
        val text = "Lucid\nReads whatever the lens sits over."

        assertEquals("Lucid", sentenceAt(text, "Lucid"))
        assertEquals("Reads whatever the lens sits over.", sentenceAt(text, "whatever"))
    }

    @Test
    fun `a number is not the end of a sentence`() {
        assertEquals("It costs 3.5 euros.", sentenceAt("It costs 3.5 euros. Pay at the desk.", "euros"))
        assertEquals("Build v1.0.0 is out", sentenceAt("Build v1.0.0 is out", "1.0.0"))
    }

    @Test
    fun `a string with no stop at all is the sentence`() {
        val text = "Photo menu tonight"

        assertEquals(text, sentenceAt(text, "menu"))
    }

    @Test
    fun `an empty node has nothing to cut`() {
        val empty = TextRun.sentence("", 0, 0)
        assertEquals("", empty.text)
        assertFalse(empty.narrowed)

        val blank = TextRun.sentence("   \n  ", 1, 2)
        assertEquals("   \n  ", blank.text)
        assertFalse(blank.narrowed)
    }

    @Test
    fun `a node that is one sentence is not marked as cut`() {
        val whole = TextRun.sentence("Grilled sea bass with lemon", 0, 10)

        assertEquals("Grilled sea bass with lemon", whole.text)
        assertFalse(whole.narrowed)
        assertEquals(0, whole.start)

        // A line with no stop in it is one sentence, wherever the lens landed
        // on it: there is nothing to cut it with.
        val part = TextRun.sentence("Grilled sea bass with lemon", 20, 25)
        assertEquals("Grilled sea bass with lemon", part.text)
        assertEquals(0, part.start)
        assertFalse(part.narrowed)
    }
}
