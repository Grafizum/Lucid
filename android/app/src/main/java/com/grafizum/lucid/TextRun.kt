package com.grafizum.lucid

import android.graphics.Rect
import android.graphics.RectF
import kotlin.math.abs

/**
 * Mixed-language narrowing: a sentence can hold two languages at once
 * ("I'm learning 日本語を勉強しています"), and the accessibility tree hands that
 * over as one string. The lens, though, is physically over *one* of them — so
 * this cuts the string down to what the lens is actually asking for.
 *
 * Which cut that is depends on how much of the message the script under the
 * lens holds. Most messages are in one language with the odd token from
 * another in the middle (`明日8pmに来る`, `I go to 日本語 today`): those tokens
 * belong to the message and travel with it, so the whole message is read.
 * When the lens is instead resting on a genuine minority — one foreign word in
 * someone else's sentence — only that word's stretch is taken, so hovering it
 * translates it alone.
 *
 * The character under the lens comes from mapping the lens x onto the type
 * size TextLines recovered (or the app's own character boxes when it publishes
 * them); punctuation, spaces and digits never decide the language, they only
 * bridge the letters either side of them.
 */
object TextRun {

    /** [start] and [end] are character offsets into the node's string. */
    data class Focus(val text: String, val start: Int, val end: Int, val narrowed: Boolean)

    private const val NEUTRAL = 0
    private const val LATIN = 1
    private const val JAPANESE = 2
    private const val KOREAN = 3
    private const val CYRILLIC = 4
    private const val GREEK = 5
    private const val ARABIC = 6
    private const val HEBREW = 7
    private const val THAI = 8
    private const val DEVANAGARI = 9
    private const val OTHER = 10

    fun at(
        text: String,
        bounds: Rect,
        x: Int,
        y: Int,
        density: Float,
        chars: Array<RectF?>? = null,
        roomySpacing: Float = TextLines.LINE_HEIGHT,
    ): Focus {
        val clean = text.replace('\u00A0', ' ')
        val whole = Focus(clean, 0, clean.length, narrowed = false)
        if (clean.isBlank()) return whole

        // Which character the lens is over: read straight off the app's own
        // character boxes when it reported them, otherwise off the fitted
        // layout. The boxes make the hover exact — no glyph is guessed.
        val index: Int
        val search: IntRange
        if (chars != null && chars.size == clean.length) {
            index = indexFromBoxes(chars, x, y) ?: return whole
            search = lineAround(chars, index)
        } else {
            if (bounds.width() <= 0 || bounds.height() <= 0) return whole
            val layout = TextLines.layout(clean, bounds, density, roomySpacing)
            val line = layout.lines.minByOrNull { verticalDistance(it, y) } ?: return whole
            index = indexAt(clean, line, x)
            search = line.start until line.end
        }

        return at(clean, index, search)
    }

    /**
     * The narrowing itself: which character the lens sits on, and what the read
     * becomes. Pure string logic — no geometry — so the rule can be checked on
     * the JVM without a device.
     */
    fun at(clean: String, index: Int, search: IntRange): Focus {
        val whole = Focus(clean, 0, clean.length, narrowed = false)
        if (clean.isBlank() || index !in clean.indices) return whole

        val group = groupAt(clean, index, search)
        if (group == NEUTRAL) return whole

        var start = index
        var end = index + 1

        if (majorityOf(clean, group)) {
            // This really is a message in one language, so the whole message is
            // the run: a small intrusion in the middle rides along instead of
            // cutting it in two (`明日8pmに来る`, `I go to 日本語 today`).
            start = 0
            end = clean.length
        } else {
            // The lens is on a minority of the text — a lone foreign word in
            // someone else's sentence — so only this script's own stretch is
            // taken. A short neutral stretch (space, comma, dash) is part of
            // the same run; a letter of another script ends it.
            var i = index - 1
            while (i >= 0) {
                val g = groupOf(clean[i])
                if (g == group) {
                    start = i
                } else if (g != NEUTRAL) {
                    break
                }
                i--
            }

            i = index + 1
            while (i < clean.length) {
                val g = groupOf(clean[i])
                if (g == group) {
                    end = i + 1
                } else if (g != NEUTRAL) {
                    break
                }
                i++
            }
        }

        if (end <= start) return whole

        // The run stops at the last letter, but a sentence is not finished
        // there: ？ is what makes a Japanese line a question, and a read that
        // dropped it handed the engine a statement (本当？ came back as
        // "Really", not "Really?"). The marks touching the run belong to it.
        var tail = end
        while (tail < clean.length && tail - end < 3 && isMark(clean[tail])) tail++
        end = tail

        var head = start
        while (head > 0 && start - head < 2 && isMark(clean[head - 1])) head--
        start = head

        val cut = clean.substring(start, end)
        val trimmed = cut.trim()
        if (trimmed.length < 2) return whole

        val shift = cut.indexOf(trimmed)
        val from = start + shift
        val narrowed = !(from == 0 && from + trimmed.length == clean.length)
        return Focus(trimmed, from, from + trimmed.length, narrowed)
    }

    /** The marks that finish a sentence, Latin and CJK alike. */
    private const val ENDERS = ".!?。！？…"

    /** Quotes and brackets that close a sentence after its full stop. */
    private const val CLOSERS = "”’\"')」』）]"

    /**
     * The whole sentence the run at [start, end) sits in.
     *
     * The lens is one gesture whichever path answers it, so the tree path must
     * not read less than the pixel path does: an OCR line under the lens comes
     * back as a sentence, and a node read by the accessibility tree has to be
     * cut the same way. Putting the lens on one word therefore reads the
     * sentence around it — the unit a person means by "put it on the text".
     *
     * A full stop between digits ("3.5", "v1.0.0") is not the end of anything,
     * and a newline always is: two lines of a card are two things to read, not
     * one run-on sentence across the gap between them.
     */
    fun sentence(text: String, start: Int, end: Int): Focus {
        val clean = text.replace('\u00A0', ' ')
        if (clean.isBlank()) return Focus(clean, 0, clean.length, narrowed = false)

        val from0 = start.coerceIn(0, clean.length)
        val to0 = end.coerceIn(from0, clean.length)

        var from = from0
        while (from > 0 && !endsAt(clean, from - 1) && clean[from - 1] != '\n') from--

        var to = to0
        while (to < clean.length && !endsAt(clean, to) && clean[to] != '\n') to++

        // The stop that ended the search belongs to the sentence, and so do the
        // quotes or brackets that follow it.
        if (to < clean.length && clean[to] != '\n') {
            to++
            while (to < clean.length && clean[to] in CLOSERS) to++
        }

        while (from < to && clean[from].isWhitespace()) from++
        while (to > from && clean[to - 1].isWhitespace()) to--
        if (to <= from) return Focus(clean, 0, clean.length, narrowed = false)

        val cut = clean.substring(from, to)
        val narrowed = !(from == 0 && to == clean.length)
        return Focus(cut, from, to, narrowed)
    }

    /**
     * The whole block the lens is on — every line of the node, not the one
     * sentence around the glyph.
     *
     * One gesture means "read this", and this is that: a paragraph comes whole,
     * a line that is one bullet of a list comes with the lines beside it, and
     * the card can then say what kind of thing was read instead of handing back
     * a single clause with its neighbours missing. [max] keeps a whole article
     * — one node with a page of text in it — from being read as one gesture: past
     * that length the sentence around the lens is the honest unit again. The
     * budget is a couple of screens, so a paragraph read whole is still a
     * paragraph, and the end of it does not quietly go untranslated.
     */
    fun block(text: String, start: Int, end: Int, max: Int = MAX_BLOCK_CHARS): Focus {
        val clean = text.replace('\u00A0', ' ')
        if (clean.isBlank()) return Focus(clean, 0, clean.length, narrowed = false)

        val trimmed = clean.trim()
        if (trimmed.length > max) return sentence(clean, start, end)

        val from = clean.indexOf(trimmed).coerceAtLeast(0)
        return Focus(trimmed, from, from + trimmed.length, narrowed = false)
    }

    /**
     * True when a block is written as a list: two or more of its lines start
     * with a bullet, a dash or a star. It is what the card reads to know the
     * answer should be given back as bullets rather than as one paragraph.
     */
    fun looksLikeList(text: String): Boolean {
        var bullets = 0
        for (line in text.lineSequence()) {
            if (startsWithBullet(line) && ++bullets >= 2) return true
        }
        return false
    }

    /**
     * True when a line opens with a bullet or a number: the marks a list uses.
     *
     * A list of settings rows, a chat and a feed are stack of rows too, and
     * reading eight of them because the lens touched one would hand back a
     * screenful. These marks are what tells a list that was written as a list
     * from a screen that merely happens to be a column.
     */
    fun startsWithBullet(line: String): Boolean {
        val t = line.trimStart()
        if (t.length < 2) return false
        when (t[0]) {
            '\u2022', '\u00B7', '\u25E6', '\u2023', '\u25AA', '\u25CF' -> return true
            '-', '*', '\u2013', '\u2014' -> return t.length > 1 && t[1] == ' '
        }
        // "1. something" or "2) something": a numbered list.
        var digits = 0
        while (digits < t.length && t[digits].isDigit()) digits++
        return digits in 1..2 && digits < t.length - 1 &&
            (t[digits] == '.' || t[digits] == ')') && t[digits + 1] == ' '
    }

    /**
     * The answer given back as a list: one bullet per row read, with the marks
     * the engine kept stripped first so none of them are doubled.
     */
    fun asBullets(text: String): String {
        val lines = text.split('\n')
            .map { line -> line.trim().trimStart('\u2022', '\u00B7', '-', '*', '\u2013').trim() }
            .filter { it.isNotEmpty() }
        if (lines.isEmpty()) return text
        return lines.joinToString("\n") { "\u2022  $it" }
    }

    /**
     * How much text one gesture takes in. A screen of text is 600–900
     * characters and a paragraph in a document can run to two screens; past
     * that the node holds an article, and the sentence under the lens is the
     * honest unit again.
     */
    const val MAX_BLOCK_CHARS = 2000

    /** True when the character at [i] finishes a sentence. */
    private fun endsAt(clean: String, i: Int): Boolean {
        val ch = clean[i]
        if (ch !in ENDERS) return false
        // "3.5" and "v1.0.0" are numbers, not two sentences and a stray one.
        return !(ch == '.' && i > 0 && i + 1 < clean.length &&
            clean[i - 1].isDigit() && clean[i + 1].isDigit())
    }

    /** Character offset of the glyph under [x] on [line]. */
    private fun indexAt(clean: String, line: TextLines.Line, x: Int): Int {
        val target = (x - line.left).toFloat()
        if (target <= 0f) return line.start

        for (i in line.start until line.end) {
            val width = TextLines.measure(clean.substring(line.start, i + 1))
            if (width >= target) return i
        }
        return line.end - 1
    }

    /**
     * The character whose own box is under the lens. A point in the gap between
     * boxes — between words, or between two lines — takes the nearest one.
     */
    private fun indexFromBoxes(chars: Array<RectF?>, x: Int, y: Int): Int? {
        var best = -1
        var bestScore = Float.MAX_VALUE

        for (i in chars.indices) {
            val box = chars[i] ?: continue
            if (box.contains(x.toFloat(), y.toFloat())) return i

            val dx = maxOf(box.left - x, 0f, x - box.right)
            val dy = maxOf(box.top - y, 0f, y - box.bottom)
            val score = dx * dx + dy * dy
            if (score < bestScore) {
                bestScore = score
                best = i
            }
        }

        return best.takeIf { it >= 0 }
    }

    /** The stretch of characters sitting on the same visual line as [index]. */
    private fun lineAround(chars: Array<RectF?>, index: Int): IntRange {
        val anchor = chars[index] ?: return 0 until chars.size
        val reach = anchor.height().coerceAtLeast(1f) * 0.7f

        fun onLine(i: Int): Boolean =
            chars[i]?.let { abs(it.centerY() - anchor.centerY()) < reach } == true

        var start = index
        while (start > 0 && onLine(start - 1)) start--
        var end = index + 1
        while (end < chars.size && onLine(end)) end++
        return start until end
    }

    /**
     * The script under the lens. Landing on punctuation or a space looks either
     * side for the nearest letter on the same line first, so a cursor in the gap
     * between words still picks the language it is sitting in.
     */
    private fun groupAt(clean: String, index: Int, search: IntRange): Int {
        groupOf(clean[index]).let { if (it != NEUTRAL) return it }

        for (step in 1..24) {
            val left = index - step
            if (left >= search.first) {
                groupOf(clean[left]).let { if (it != NEUTRAL) return it }
            }
            val right = index + step
            if (right <= search.last) {
                groupOf(clean[right]).let { if (it != NEUTRAL) return it }
            }
            if (left < search.first && right > search.last) break
        }
        return NEUTRAL
    }

    /**
     * Does the script under the lens hold most of the message?
     *
     * Counted in letters, so digits and punctuation never tip it either way:
     * `明日8pmに来る` is 4 Japanese letters to 2 Latin ones, and the message is
     * Japanese. A tie goes to the strict majority check — the run narrows
     * rather than swallowing a sentence that is half of another language.
     */
    private fun majorityOf(clean: String, group: Int): Boolean {
        if (group == NEUTRAL) return false
        var own = 0
        var other = 0
        for (ch in clean) {
            val g = groupOf(ch)
            if (g == group) own++
            else if (g != NEUTRAL) other++
        }
        return own > other
    }

    private fun verticalDistance(line: TextLines.Line, y: Int): Int = when {
        y < line.top -> line.top - y
        y > line.bottom -> y - line.bottom
        else -> 0
    }

    /** Punctuation and symbols: they belong to the run they touch, not to a language. */
    private fun isMark(ch: Char): Boolean = !ch.isLetter() && !ch.isDigit() && !ch.isWhitespace()

    private fun groupOf(ch: Char): Int {
        if (ch.isWhitespace()) return NEUTRAL
        if (!ch.isLetter()) return NEUTRAL

        val block = runCatching { Character.UnicodeBlock.of(ch) }.getOrNull()
            ?: return if (ch.code < 0x250) LATIN else OTHER

        return when (block) {
            Character.UnicodeBlock.HIRAGANA,
            Character.UnicodeBlock.KATAKANA,
            Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS,
            Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A,
            Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS,
            -> JAPANESE

            Character.UnicodeBlock.HANGUL_SYLLABLES,
            Character.UnicodeBlock.HANGUL_JAMO,
            Character.UnicodeBlock.HANGUL_COMPATIBILITY_JAMO,
            -> KOREAN

            Character.UnicodeBlock.CYRILLIC, Character.UnicodeBlock.CYRILLIC_SUPPLEMENTARY -> CYRILLIC
            Character.UnicodeBlock.GREEK, Character.UnicodeBlock.GREEK_EXTENDED -> GREEK
            Character.UnicodeBlock.ARABIC -> ARABIC
            Character.UnicodeBlock.HEBREW -> HEBREW
            Character.UnicodeBlock.THAI -> THAI
            Character.UnicodeBlock.DEVANAGARI -> DEVANAGARI

            Character.UnicodeBlock.BASIC_LATIN,
            Character.UnicodeBlock.LATIN_1_SUPPLEMENT,
            Character.UnicodeBlock.LATIN_EXTENDED_A,
            Character.UnicodeBlock.LATIN_EXTENDED_B,
            -> LATIN

            else -> OTHER
        }
    }
}
