package com.grafizum.lucid

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rule that picks the read several frames of a moving picture agree on.
 *
 * A reel is read by taking more than one frame and believing the words that
 * stayed on screen. Which of the reads that is, is decided here, so the rule
 * can be checked without a device, a video or a recogniser.
 */
class AgreedReadTest {

    private fun read(text: String, empty: Boolean = false, error: String? = null) =
        Outcome(
            source = text,
            translated = if (empty || error != null) null else "translated",
            empty = empty,
            error = error,
        )

    @Test
    fun theWordsTwoFramesSawAreTheAnswer() {
        val agreed = agreedOutcome(
            listOf(
                read("Half a capti"),
                read("The whole caption"),
                read("The whole caption"),
            ),
        )
        assertEquals("the read two frames agreed on", "The whole caption", agreed.source)
    }

    @Test
    fun aFrameThatFoundNothingIsNotAVote() {
        // Two blank frames must not outvote the one frame that had words on it:
        // an empty frame is the absence of an answer, not an answer of its own.
        val agreed = agreedOutcome(
            listOf(
                read("", empty = true),
                read("Rent is due on Friday"),
                read("", empty = true),
            ),
        )
        assertEquals("Rent is due on Friday", agreed.source)
    }

    @Test
    fun whenNoTwoFramesAgreeTheLongestReadWins() {
        // Word-by-word captions genuinely differ between frames. Nothing agrees,
        // so the most of it any frame showed is the best answer available.
        val agreed = agreedOutcome(
            listOf(
                read("due on"),
                read("rent is due on friday"),
                read("friday"),
            ),
        )
        assertEquals("rent is due on friday", agreed.source)
    }

    @Test
    fun aFrameThatClippedTheStartOfAWordDoesNotBecomeTheAnswer() {
        // Two frames of one caption rarely match to the letter: the pan, the
        // fade, the player's own smoothing catch a glyph differently. The read
        // that held the whole line is the answer, not the one that lost a
        // letter off its first word.
        val agreed = agreedOutcome(
            listOf(
                read("he world is watching"),
                read("The world is watching"),
                read("The world is watching"),
            ),
        )
        assertEquals("The world is watching", agreed.source)
    }

    @Test
    fun aSingleReadIsItsOwnAnswer() {
        assertEquals("one line", agreedOutcome(listOf(read("one line"))).source)
    }

    @Test
    fun aRefusalSurvivesOnlyWhenNothingWasRead() {
        val refused = agreedOutcome(
            listOf(
                read("", empty = true),
                read("", error = "Couldn't read that area."),
            ),
        )
        assertTrue("the refusal is kept when no frame had words", refused.error != null)

        val rescued = agreedOutcome(
            listOf(
                read("", error = "Couldn't read that area."),
                read("Rent is due on Friday"),
            ),
        )
        assertEquals("Rent is due on Friday", rescued.source)
        assertEquals(null, rescued.error)
    }
}
