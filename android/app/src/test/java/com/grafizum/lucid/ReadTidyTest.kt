package com.grafizum.lucid

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What is taken out of a frame's read before it is translated, and what is
 * kept. The rule is the one that decides whether a caption comes back whole:
 * the phone's own chrome goes, the words stay.
 */
class ReadTidyTest {

    @Test
    fun aTimeInsideACaptionKeepsItsWordsAndItsTime() {
        // This line used to lose "10:30" and the space before it, so a caption
        // ending in a time came back as a sentence cut in half.
        assertEquals(
            "Meet me at the pier 10:30",
            tidyRead("Meet me at the pier 10:30"),
        )
    }

    @Test
    fun aRowThatIsOnlyATimeGoes() {
        // A chat screen's timestamp is a row of its own, and nothing is lost.
        assertEquals("Hello there", tidyRead("10:30\nHello there"))
        assertEquals("Hello there", tidyRead("Hello there\n9.41 pm"))
    }

    @Test
    fun deliveryTicksGoAndTheMessageStays() {
        assertEquals("See you tonight", tidyRead("See you tonight ✓✓"))
    }

    @Test
    fun citationMarkersGo() {
        assertEquals(
            "The study says so",
            tidyRead("The study [1] says so"),
        )
    }

    @Test
    fun aRowOfSeparatorsGoes() {
        assertEquals("Price list", tidyRead("Price list\n— / |"))
    }

    @Test
    fun aRetryRowGoesButTheWordRetryInACaptionStays() {
        assertEquals("Send the files", tidyRead("Send the files\nRetry"))
        assertEquals("Never retry a payment twice", tidyRead("Never retry a payment twice"))
    }

    @Test
    fun nothingThatWasWrittenIsThrownAwayWhenCleaningTakesEverything() {
        // The raw line is the better answer when the clean one is empty.
        assertTrue(tidyRead("✓✓").isNotEmpty())
    }

    @Test
    fun twoLooksAtOneCaptionAgreeEvenWhenOneClippedItsFirstLetter() {
        assertTrue(sameWords("The world is", "he world is"))
        assertTrue(sameWords("he world is", "The world is"))
        // Different captions are not the same caption.
        assertFalse(sameWords("The world is", "Rent is due"))
        // A stray glyph is not evidence of a line.
        assertFalse(sameWords("e", "The world is changing"))
    }
}
