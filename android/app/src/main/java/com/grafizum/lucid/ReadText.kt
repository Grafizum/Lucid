package com.grafizum.lucid

import java.util.Locale

/**
 * The words a frame really holds, with the phone's own chrome taken out.
 *
 * Chat screens carry timestamps, delivery ticks, retry rows and edge artifacts
 * that read as noise once translated, so they are worth dropping. A caption is
 * not that: a time inside a sentence belongs to the sentence, and a word that
 * happens to be "Retry" is still a word. So the rule is split by what the mark
 * carries —
 *
 *  - marks that are not words at all (delivery ticks, citation numbers, stray
 *    separators) come out wherever they sit, because removing them cannot lose
 *    anything anyone wrote;
 *  - chrome that is made of words (a clock, a retry label) is taken only when
 *    it is the whole row. "Meet me at 10:30" keeps its time and the words
 *    before it; a row that is nothing but "10:30" goes.
 *
 * This used to cut the trailing time off every line, which is how a caption
 * ending in a time came back missing the end of its sentence.
 */
fun tidyRead(raw: String): String {
    val cleaned = raw.lines()
        .map { line ->
            var out = line
                .replace(CITATION, "")
                .replace(DELIVERY_TICKS, "")
                .replace(STRAY_SEPARATORS, "")
                .trim()
                .replace(SPACES, " ")

            // Only when the row is the chrome and nothing else. A row with no
            // letter or digit in it is a rule, a bullet or an artifact — there
            // is nothing on it to translate.
            val bare = out.replace(CLOCK, "").replace(RETRY, "").replace(SPACES, " ").trim()
            if (bare.isEmpty() || bare.none { it.isLetterOrDigit() }) out = ""
            out
        }
        .filter { it.isNotEmpty() }
        .joinToString("\n")
        .trim()

    // A line of pure chrome leaves nothing; the raw line is the better answer
    // only when cleaning has taken everything away.
    return if (cleaned.length >= 2) cleaned else raw.trim()
}

/** A clock, with or without am/pm: "9:41", "10.30 pm", "23:59". */
private val CLOCK = Regex("""\b\d{1,2}[:.]\d{2}(?:[\s\u00a0]*[AaPp]\.?[Mm]\.?)?""")

/** The delivery ticks on a sent message. They carry no words. */
private val DELIVERY_TICKS = Regex("""[\u2713\u2714]{1,3}""")

/** Chat bubbles sit beside a retry row that is not part of the message. */
private val RETRY = Regex("""(?:Tap to retry|Retry)""", RegexOption.IGNORE_CASE)

/** A row of separators, the artifact of a table or a cracked layout. */
private val STRAY_SEPARATORS = Regex("""[/\\|·]+""")

/** Web pages carry citation markers such as [1] or [12] inline. */
private val CITATION = Regex("""\[\d{1,3}]""")

private val SPACES = Regex("""[\s\u00a0]{2,}""")

/**
 * What one frame of a moving picture is worth as a vote, next to another.
 *
 * Two frames of the same caption almost never agree to the letter: a pan, a
 * fade or a player's own smoothing catches a glyph differently, so one read is
 * "The world is" and the next "he world is". Comparing them for equality threw
 * that away and settled for the longest read, which is how a caption came back
 * with the start of its first word missing. A frame backs a read when either
 * holds the other — the same words, with more or less of the line visible.
 */
fun sameWords(one: String, other: String): Boolean {
    val a = signatureOf(one)
    val b = signatureOf(other)
    if (a.isEmpty() || b.isEmpty()) return false
    // A stray "e" or "1" the recogniser found in the picture is contained in
    // half of everything, and would otherwise outvote the caption it came
    // from. Something shorter than a word is not evidence of a line.
    val shorter = if (a.length <= b.length) a else b
    if (shorter.length < 3) return false
    return a.contains(b) || b.contains(a)
}

/** The letters and digits of a read, for comparing one frame with another. */
fun signatureOf(text: String): String =
    text.lowercase(Locale.ROOT).filter { it.isLetterOrDigit() }
