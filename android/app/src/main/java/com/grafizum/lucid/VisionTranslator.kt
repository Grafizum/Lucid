package com.grafizum.lucid

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Point
import android.graphics.Rect
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.devanagari.DevanagariTextRecognizerOptions
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray

data class Outcome(
    val source: String = "",
    val translated: String? = null,
    val detected: String? = null,
    val provider: String = "",
    /** How the source is pronounced, as the engine spells it (romaji for Japanese). */
    val reading: String? = null,
    val empty: Boolean = false,
    val sameLanguage: Boolean = false,
    val error: String? = null,
    /** Where the chosen text sat in the captured frame (only when read from pixels). */
    val box: Rect? = null,
    val blocks: Int = 0,
)

/**
 * The read a handful of frames agree on.
 *
 * A reel never stands still: one glimpse can catch a pan halfway through, or a
 * caption between two words, and the card then answers with a fragment or with
 * nothing. Where several frames of the same spot were read, the answer is the
 * one more than one of them found — the words that stayed on screen. Frames
 * that found nothing are not votes: they are the absence of an answer, not an
 * answer of their own. Where no two frames agree the longest read wins, which
 * is the closest thing to "the most of it that any frame showed".
 */
fun agreedOutcome(readings: List<Outcome>): Outcome {
    val usable = readings.filter { !it.empty && it.error == null && it.source.isNotBlank() }
    if (usable.isEmpty()) {
        // Nothing was written on the spot in any frame. The refusal is the more
        // useful answer when the recogniser could not run at all.
        return readings.firstOrNull { it.error != null } ?: readings.firstOrNull() ?: Outcome(empty = true)
    }

    var best = usable.first()
    var bestVotes = -1
    for ((index, reading) in usable.withIndex()) {
        // A frame backs a read when either one holds the other's words: the
        // same caption seen with a glyph more or less of it on screen. Asking
        // for an exact match treated two looks at one line as two lines.
        val votes = usable.count { other ->
            other === reading || sameWords(other.source, reading.source)
        }
        val longer = reading.source.count { it.isLetterOrDigit() } >
            best.source.count { it.isLetterOrDigit() }
        if (votes > bestVotes || (votes == bestVotes && longer)) {
            best = reading
            bestVotes = votes
        }
    }
    return best
}

/**
 * Everything runs on the device: ML Kit language identification and
 * translation, plus ML Kit text recognition for the look-mode fallback.
 * A network fallback only kicks in if an on-device model cannot be downloaded.
 */
class VisionTranslator(private val context: Context) {

    // One recogniser per script. The Latin one is bundled with the app; the
    // other four are separate models that Play services downloads the first
    // time one is asked for, so they are built on first use and a phone reading
    // English never pays for a script it will not look at.
    private val latinRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private val japaneseRecognizer by lazy {
        TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())
    }
    private val chineseRecognizer by lazy {
        TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
    }
    private val koreanRecognizer by lazy {
        TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())
    }
    private val devanagariRecognizer by lazy {
        TextRecognition.getClient(DevanagariTextRecognizerOptions.Builder().build())
    }

    private val languageId = LanguageIdentification.getClient()

    /**
     * Translate a string we already have (text access mode).
     *
     * [sourceHint] pins the source language (a language code) instead of
     * auto-detecting — used when the card's language chips are changed live.
     * Pass null to detect.
     */
    suspend fun translate(
        source: String,
        target: String,
        onStatus: (String) -> Unit = {},
        sourceHint: String? = null,
        /** The rest of the screen: sent with the line, never shown. */
        screenContext: String = "",
    ): Outcome {
        val text = tidy(source.trim())
        if (text.length < 2) return Outcome(source = text, empty = true)

        onStatus("Detecting")

        // Bounded: a detector that never answers must not leave the card
        // spinning forever — an unknown language stays unknown.
        val pin = Languages.canonical(sourceHint)
        val tag = pin ?: detect(text)
        // Never call an unnamed language English. Doing that labelled Spanish
        // lines "English" and, when English was also the target, handed the
        // original back as its own translation.
        // Everything below speaks Lucid's own codes: the detector may answer
        // "iw" where the picker said "he", and the two are one language.
        val from = Languages.canonical(tag)
        val to = Languages.canonical(target) ?: target

        if (from != null && from == to) {
            return Outcome(
                source = text,
                translated = text,
                detected = from,
                provider = "on-device",
                sameLanguage = true,
            )
        }

        onStatus("Translating")

        // The offline models are fast but weak on languages like Japanese, so
        // the online engine leads when the user allows it — and never when the
        // engine is set to On-device, which is a promise rather than a
        // preference.
        if (onlineAllowed()) {
            // With neighbours in the query the engine would detect on the whole
            // screen instead of the line, so the line's own detected language
            // pins the source whenever context is travelling with it.
            val sl = pin ?: if (screenContext.isNotBlank() && from != null) from else "auto"
            google(text, to, sl, screenContext)?.let { result ->
                return Outcome(
                    source = text,
                    translated = result.text,
                    detected = pin ?: Languages.canonical(result.detected) ?: from,
                    provider = "online",
                    reading = result.reading,
                )
            }
        }

        // Without a source language the offline models have nothing to work
        // from; only the online engine can still have a go. The same goes for a
        // pair where either side is online-only (Uzbek, Kazakh, Traditional
        // Chinese): no model exists, so none is asked for.
        val fromModel = from?.let { Languages.forModel(it) }
        val toModel = Languages.forModel(to)
        val onDevice = if (fromModel == null || toModel == null) {
            null
        } else {
            try {
                translateOnDevice(text, fromModel, toModel)
            } catch (err: Exception) {
                null
            }
        }

        if (onDevice != null) {
            return Outcome(source = text, translated = onDevice, detected = from, provider = "on-device")
        }

        // No engine left. Say so instead of showing a rough machine guess.
        // The order matters: a read that had the online engine and did not get
        // an answer is a network or service failure, not an unreadable line.
        // Saying "couldn't tell which language" there sent people looking for
        // the wrong thing on their phone.
        return Outcome(
            source = text,
            detected = from,
            error = when {
                Prefs.onlineEngine(context) ->
                    "The translation service didn't answer. Check the connection and try again in a moment, or switch ENGINE to On-device in Lucid."
                from == null ->
                    "The engine is on-device and there is no offline model for this pair yet. Tap the left chip and pick the language, or switch ENGINE to Online in Lucid."
                else ->
                    "No offline model for this language pair yet. Switch ENGINE to Online in Lucid."
            },
        )
    }

    /**
     * What this line is written in, or null when the detector will not commit.
     * A few words often come back undecided, so the ranked answer is taken
     * only when it is confident; an unknown line is never assumed English.
     */
    /** Whether a read may leave the phone at all: ENGINE is the one answer. */
    private fun onlineAllowed(): Boolean = Prefs.onlineEngine(context)

    private suspend fun detect(text: String): String? {
        val primary = runCatching {
            withTimeoutOrNull(2500) { languageId.identifyLanguage(text).await() }
        }.getOrNull()
        if (!primary.isNullOrBlank() && primary != "und") return primary

        val ranked = runCatching {
            withTimeoutOrNull(1200) { languageId.identifyPossibleLanguages(text).await() }
        }.getOrNull().orEmpty()
        val best = ranked.firstOrNull() ?: return null
        return best.languageTag.takeIf {
            best.confidence >= 0.5f && it.isNotBlank() && it != "und"
        }
    }

    /**
     * The pixel path: read the frame, then keep the one line of text the lens point
     * is resting on — a line, not the paragraph around it, and never the
     * nearest one somewhere else in the frame.
     *
     * [slack] widens each line by that many pixels before the test, because the
     * lens may be aimed at the gap between two lines and a line's own box is
     * cut tight to its glyphs.
     */
    suspend fun read(
        bitmap: Bitmap,
        point: Point,
        target: String,
        slack: Int = 0,
        onStatus: (String) -> Unit = {},
    ): Outcome {
        onStatus("Reading")

        // Lines rather than blocks: a paragraph is one block, and the lens is
        // over one of its lines. Reading the block would answer for the whole
        // paragraph whatever the lens touched.
        val read = readLines(bitmap, onStatus)
        val lines = read.lines
        if (lines.isEmpty()) {
            // Nothing on this patch, or the recogniser could not run at all —
            // "nothing here" and "couldn't read" are different answers.
            return if (read.failed) Outcome(error = "Couldn't read that area.") else Outcome(empty = true)
        }

        // Sections first: a picture can hold a menu, a headline and a caption,
        // and the answer has to come from the block the lens is on rather than
        // from whichever line happens to be nearest across the whole frame.
        val sections = Sections.of(
            lines.map { Sections.Line(it.text, it.box.left, it.box.top, it.box.right, it.box.bottom) },
        )
        val section = Sections.under(sections, point.x, point.y, slack)
        val sectionLines = section?.let { s -> lines.filter { s.holds(sectionLine(it)) } }.orEmpty()

        // A photograph of a list — a poster's names, a menu's rows, a card's
        // credits — gives each row its own section, because the space between
        // two rows is wider than normal leading. The section under the lens is
        // therefore joined with the rows above and below it in the same column,
        // out to about two empty lines, which is the block the hand asked for.
        val reach = sectionLines.minOfOrNull { it.box.height() }?.times(3) ?: 0
        val blockSections = section?.let { Sections.blockOf(sections, it, reach) }.orEmpty()
        val candidates = blockSections.flatMap { s -> lines.filter { s.holds(sectionLine(it)) } }

        // The section narrows the answer; if the lens is on the section but not
        // squarely on one of its lines, fall back to the plain line rule so the
        // narrower answer can never lose a line the old one found.
        val chosen = pick(candidates, point, slack)
            ?: pick(lines, point, slack)
            ?: return Outcome(empty = true)

        // The block, not only its line: the rows beside it are read with it, so
        // a bulleted list, a wrapped paragraph or a column of names comes whole.
        // Past a screen's worth of text the section is the unit again, and past
        // that the line, so one gesture never translates a page.
        val block = listOf(candidates, sectionLines)
            .firstNotNullOfOrNull { rows -> fit(rows, chosen) }
            ?: chosen
        val outcome = translate(block.text, target, onStatus)
        return outcome.copy(box = block.box, blocks = sections.size)
    }

    /**
     * Every line the frame holds, read by the script it is written in.
     *
     * The lens used to read every frame with the Latin model, whatever the
     * picture said. A Japanese poster came back with the handful of strokes the
     * Latin model could pretend to find ("AEA 1200F"), or with nothing at all;
     * a Korean or Hindi frame the same. The frame is now read by the script it
     * is written in: Latin first, then — only while nothing has been found — the
     * scripts a poster, a menu or a sign is otherwise in. An English screen
     * still costs one pass.
     */
    private suspend fun readLines(bitmap: Bitmap, onStatus: (String) -> Unit): Reading {
        var failed = false

        /** One script's read of the frame, or null when it found nothing. */
        suspend fun scan(script: Script): List<Block>? {
            val reading = recognise(bitmap, script)
            failed = failed || reading.failed
            return reading.lines.ifEmpty { null }
        }

        var best = scan(Script.LATIN)
        // A Latin read of a decent length is the answer: most frames are Latin,
        // and asking every script would cost the read its speed.
        if (best != null && glyphs(best) >= SURE_READ_GLYPHS) return Reading(best)

        // Japanese and Chinese next: they are what a poster or a menu is most
        // often in, and they are the pair that can both claim the same frame.
        onStatus("Reading Japanese")
        val japanese = scan(Script.JAPANESE)
        // Kana settle it: only Japanese writes them, so a read holding kana is
        // Japanese however many kanji it shares with Chinese.
        if (japanese != null && japanese.any { line -> line.text.any { it.isKana() } }) {
            return Reading(japanese)
        }

        onStatus("Reading Chinese")
        val chinese = scan(Script.CHINESE)

        // A thin Latin read is not proof of Latin: it may be the few strokes the
        // Latin model found in a frame that is not Latin at all. When the reads
        // are this short they are weighed against each other, and the one that
        // held more of the frame wins.
        best = listOfNotNull(best, japanese, chinese).maxByOrNull { glyphs(it) }
        if (best != null && glyphs(best) >= SURE_READ_GLYPHS) return Reading(best)

        for (script in listOf(Script.KOREAN, Script.DEVANAGARI)) {
            onStatus("Reading ${script.label}")
            val other = scan(script) ?: continue
            if (glyphs(other) > glyphs(best.orEmpty())) best = other
        }

        return Reading(best.orEmpty(), failed)
    }

    /**
     * One script's read of a frame. [Reading.failed] says the recogniser itself
     * could not run — a model that has not downloaded, usually — as opposed to a
     * frame with nothing written on it.
     */
    private suspend fun recognise(bitmap: Bitmap, script: Script): Reading {
        val recognizer = runCatching { recogniserFor(script) }.getOrNull()
            ?: return Reading(failed = true)

        val result = try {
            // Bounded, because a recogniser that never calls back is a phone
            // where the lens stops answering for good: the card waits on this
            // and nothing behind it can start. A script that cannot read a
            // frame in this long has failed at reading it.
            withTimeoutOrNull(OCR_TIMEOUT_MS) {
                recognizer.process(InputImage.fromBitmap(bitmap, 0)).await()
            }
        } catch (err: Exception) {
            null
        }

        if (result == null) return Reading(failed = true)

        val lines = result.textBlocks
            .flatMap { block -> block.lines }
            .mapNotNull { line ->
                val box = line.boundingBox ?: return@mapNotNull null
                val text = tidy(line.text)
                // A one-glyph name is still a name — a short surname, an initial
                // — while a rule, a bullet or a stray mark is not. Only the
                // second kind is dropped.
                if (text.isBlank() || text.none { it.isLetterOrDigit() }) null else Block(text, box)
            }
        return Reading(lines)
    }

    private fun recogniserFor(script: Script): TextRecognizer = when (script) {
        Script.LATIN -> latinRecognizer
        Script.JAPANESE -> japaneseRecognizer
        Script.CHINESE -> chineseRecognizer
        Script.KOREAN -> koreanRecognizer
        Script.DEVANAGARI -> devanagariRecognizer
    }

    private fun glyphs(lines: List<Block>): Int =
        lines.sumOf { line -> line.text.count { it.isLetterOrDigit() } }

    /** Hiragana and katakana, the two scripts Chinese does not write. */
    private fun Char.isKana(): Boolean = this in '\u3040'..'\u30FF'

    /**
     * The scripts the lens can read. Latin is bundled with the app; the rest
     * are models Play services fetches the first time one is asked for.
     */
    private enum class Script(val label: String) {
        LATIN("Latin"),
        JAPANESE("Japanese"),
        CHINESE("Chinese"),
        KOREAN("Korean"),
        DEVANAGARI("Hindi"),
    }

    /** One script's read: the lines it found, or why it could not try. */
    private class Reading(val lines: List<Block> = emptyList(), val failed: Boolean = false)

    /**
     * The lines read together, top to bottom, and the box they fill — or null
     * when they are more than one request can carry.
     */
    private fun fit(lines: List<Block>, chosen: Block): Block? {
        if (lines.size < 2) return chosen
        val ordered = lines.sortedWith(compareBy({ it.box.top }, { it.box.left }))
        val text = ordered.joinToString("\n") { it.text }
        if (text.length > MAX_BLOCK_CHARS) return null

        val box = Rect(ordered.first().box)
        ordered.forEach { box.union(it.box) }
        return Block(text, box)
    }

    private fun sectionLine(block: Block) =
        Sections.Line(block.text, block.box.left, block.box.top, block.box.right, block.box.bottom)

    private data class Block(val text: String, val box: Rect)

    /** How much text one block read will take in before it falls back to a line. */
    private companion object {
        /** The same gesture budget the text-tree path reads by. */
        const val MAX_BLOCK_CHARS = TextRun.MAX_BLOCK_CHARS

        /**
         * How much text one request carries. The engine answers a request this
         * size in full and shorter is slower for no gain; longer is the size at
         * which it starts dropping the tail of the read.
         */
        const val MAX_REQUEST_CHARS = 1200

        /** The marks that end a sentence, Latin and CJK alike. */
        const val SENTENCE_ENDS = ".!?。！？…"

        /**
         * A read holding this many letters, digits or CJK characters is taken
         * as the answer without asking the other scripts. Below it, the read
         * is a fragment at best, so the other scripts are weighed against it.
         */
        const val SURE_READ_GLYPHS = 12

        /** How long one script's recogniser gets before the frame is given up on. */
        const val OCR_TIMEOUT_MS = 6_000L

        /** How long the offline model gets, download included, on the fallback. */
        const val OFFLINE_TIMEOUT_MS = 20_000L
    }

    /** The line the point is on — or nothing at all. */
    private fun pick(lines: List<Block>, point: Point, slack: Int): Block? {
        val hit = UnderLens.innermost(lines.map { box(it.box) }, point.x, point.y, slack)
            ?: return null
        // The innermost box decides, so a caption nested inside a bigger text
        // frame still answers for itself.
        return lines.firstOrNull { box(it.box) == hit }
    }

    private fun box(rect: Rect) = UnderLens.Box(rect.left, rect.top, rect.right, rect.bottom)

    /**
     * The phone's own chrome — timestamps, delivery ticks, retry rows — read as
     * noise once translated. The rule for taking it out without taking a word
     * with it lives in [tidyRead].
     */
    fun tidy(raw: String): String = tidyRead(raw)

    /**
     * The offline pair, or null when it is unavailable or did not finish in
     * time. A first use downloads a model — thirty-odd megabytes on some pairs
     * — and a download over a network that drops halfway can hang for minutes
     * without ever failing. The bound is the same reason the recogniser has
     * one: a read must always come back, even if the answer is "not now".
     * ML Kit keeps what it has already fetched, so the next read carries on.
     */
    private suspend fun translateOnDevice(text: String, source: String, target: String): String? =
        withContext(Dispatchers.Default) {
            val options = TranslatorOptions.Builder()
                .setSourceLanguage(source)
                .setTargetLanguage(target)
                .build()

            val translator = Translation.getClient(options)
            try {
                withTimeoutOrNull(OFFLINE_TIMEOUT_MS) {
                    translator.downloadModelIfNeeded(DownloadConditions.Builder().build()).await()
                    translator.translate(text).await()
                }
            } finally {
                runCatching { translator.close() }
            }
        }

    /** What the online engine answered: the translation, and what it noticed. */
    private data class Online(val text: String, val detected: String?, val reading: String?)

    /** One round trip: the answer, the language the engine saw, and its readings. */
    private data class Fetched(val text: String, val detected: String?, val readings: List<String>)

    /**
     * Google's public translate endpoint: no key, no signup, and far better on
     * Japanese than the on-device models. Returns translated text, the detected
     * code, and — asked for with dt=rm — how the source is pronounced.
     *
     * With [context] the neighbours travel too: the line under the lens first,
     * the rest of the screen behind it, one per line. The answer is used only
     * when the engine kept those lines apart — otherwise the line is asked for
     * on its own — so the card can never be shown a neighbour's sentence.
     */
    private suspend fun google(
        text: String,
        target: String,
        sl: String,
        context: String = "",
    ): Online? =
        withContext(Dispatchers.IO) {
            // A read longer than one request goes in pieces. The engine takes
            // only so much text at a time and drops the rest without saying so,
            // which read as a translation that simply stopped halfway down the
            // page. Neighbours are not sent with a long read: the whole screen
            // is a hint, and it would only spend room the read itself needs.
            if (text.length > MAX_REQUEST_CHARS) return@withContext readInPieces(text, target, sl)

            val lines = Contexts.query(context, text)
            val joined = lines.joinToString("\n")

            if (lines.size <= 1) {
                // Nothing else to send: one request asks for the translation
                // and the reading together, exactly as it always has.
                val answer = request(joined, target, sl, reading = true)
                    ?: return@withContext null
                if (answer.text.isBlank()) return@withContext null
                return@withContext Online(
                    answer.text,
                    answer.detected,
                    answer.readings.joinToString(" ").ifBlank { null },
                )
            }

            val answer = request(joined, target, sl, reading = true) ?: return@withContext null
            val picked = Contexts.resolve(lines, answer.text)
            if (picked != null) {
                // A reading row describes one line. They are trusted over the
                // kanji only when there is exactly one row per line sent; if the
                // engine mixed them, the line's reading is asked for again on
                // its own rather than a neighbour's being drawn over this text.
                val reading = answer.readings.takeIf { it.size == lines.size }?.firstOrNull()
                    ?: request(text, target, sl, reading = true)?.readings?.firstOrNull()
                return@withContext Online(picked, answer.detected, reading?.trim()?.ifBlank { null })
            }

            // The engine did not keep the lines apart. Ask again for the line on
            // its own: a wrong sentence shown is worse than a second request.
            val plain = request(text, target, sl, reading = true) ?: return@withContext null
            if (plain.text.isBlank()) return@withContext null
            return@withContext Online(
                plain.text,
                plain.detected,
                plain.readings.joinToString(" ").ifBlank { null },
            )
        }

    /**
     * A long read, in pieces that each end where a meaning already ends.
     *
     * Each piece is under the size the engine answers fully for, and the
     * answers are put back together with the break they were cut at — a
     * paragraph break stays a paragraph break, a space stays a space. A
     * reading is not asked for: one row describing a page of text is noise.
     */
    private fun readInPieces(text: String, target: String, sl: String): Online? {
        val pieces = pieces(text)
        if (pieces.isEmpty()) return null

        val out = StringBuilder(text.length * 2)
        var detected: String? = null

        for (piece in pieces) {
            // A piece is retried once before the read is given up on: one lost
            // request must not hand a page back untranslated.
            val answer = request(piece.text, target, sl, reading = false)
                ?: request(piece.text, target, sl, reading = false)
                ?: return null
            val translated = answer.text.trim()
            if (translated.isEmpty()) continue
            if (detected == null) detected = answer.detected
            if (out.isNotEmpty()) out.append(piece.glue(target))
            out.append(translated)
        }

        if (out.isEmpty()) return null
        return Online(out.toString(), detected, null)
    }

    /** One piece of a long read, and how it was separated from the next. */
    private data class Piece(val text: String, val after: Char) {
        fun glue(target: String): String = when {
            after == '\n' -> "\n"
            Words.crowded(target) -> ""
            else -> " "
        }
    }

    /**
     * The read cut into requests of at most [MAX_REQUEST_CHARS], never in the
     * middle of a word where there is a line break, a full stop or a space to
     * end at. A run with none of those — a column of kanji — is cut at the
     * limit, which is the only place left.
     */
    private fun pieces(text: String): List<Piece> {
        val out = ArrayList<Piece>(4)
        var i = 0

        while (i < text.length) {
            if (text.length - i <= MAX_REQUEST_CHARS) {
                val tail = text.substring(i).trim()
                if (tail.isNotEmpty()) out += Piece(tail, '\n')
                break
            }

            val limit = i + MAX_REQUEST_CHARS
            val floor = i + MAX_REQUEST_CHARS / 3
            var cut = -1
            var after = ' '

            for (k in limit downTo floor) {
                if (text[k] == '\n') {
                    cut = k
                    after = '\n'
                    break
                }
            }
            if (cut < 0) {
                for (k in limit downTo floor) {
                    if (text[k] in SENTENCE_ENDS) {
                        cut = k + 1
                        after = ' '
                        break
                    }
                }
            }
            if (cut < 0) {
                for (k in limit downTo floor) {
                    if (text[k] == ' ') {
                        cut = k
                        after = ' '
                        break
                    }
                }
            }
            if (cut < 0) cut = limit

            val piece = text.substring(i, cut).trim()
            if (piece.isNotEmpty()) out += Piece(piece, after)
            i = cut
        }

        return out
    }

    /**
     * One request for a block of lines, whatever it holds.
     *
     * The request itself lives in [TranslateNet]: a POST to the endpoint that
     * still answers it. The old GET went out with a hand-written user agent and
     * four seconds to connect, and on a phone network it came back as Google's
     * "Sorry…" page — which is the bug behind "translation does not work".
     */
    private fun request(
        query: String,
        target: String,
        sl: String,
        reading: Boolean,
    ): Fetched? =
        runCatching {
            val body = TranslateNet.ask(
                TranslateNet.params(query, target, sl, reading),
            ) ?: return@runCatching null

            val root = JSONArray(body)
            val segments = root.optJSONArray(0) ?: return@runCatching null

            val out = StringBuilder()
            val readings = ArrayList<String>()
            for (index in 0 until segments.length()) {
                val segment = segments.optJSONArray(index) ?: continue
                // optString answers "null" for a JSON null, so the null
                // has to be checked for — otherwise it lands in the
                // translation as the word "null".
                if (!segment.isNull(0)) {
                    val piece = segment.optString(0)
                    if (piece.isNotEmpty()) {
                        out.append(piece)
                        continue
                    }
                }
                // A reading row translates nothing: index 0 is empty and
                // index 3 holds the source spelled out in Latin letters.
                val spoken = if (segment.length() > 3 && !segment.isNull(3)) segment.optString(3) else ""
                if (spoken.isNotBlank()) readings += spoken
            }

            val detected = if (root.length() > 2 && !root.isNull(2)) {
                root.getString(2).substringBefore('-').ifBlank { null }
            } else {
                null
            }

            Fetched(out.toString().trim(), detected, readings)
        }.getOrNull()

    /**
     * Word by word: the meaning of each word of a language written without
     * spaces. All the words travel in one request; anything that does not come
     * back lined up is dropped rather than paired with the wrong word.
     */
    suspend fun gloss(
        source: String,
        lang: String?,
        target: String,
    ): List<Words.Pair> = withContext(Dispatchers.IO) {
        // The word list is a second request, so On-device refuses it too: the
        // promise covers every word on the card, not just the line above them.
        if (!Prefs.onlineEngine(context)) return@withContext emptyList()
        val terms = Words.terms(source, lang)
        if (terms.size < 2) return@withContext emptyList()

        runCatching {
            // The same POST as a read: this is the second request of a card, so
            // it cannot be the one that makes a phone look broken.
            val body = TranslateNet.ask(
                TranslateNet.params(terms.joinToString("\n"), target, Words.sourceOf(lang), reading = false),
            ) ?: return@runCatching emptyList()
            val meanings = Words.parse(body, terms.size) ?: return@runCatching emptyList()
            Words.pairs(terms, meanings)
        }.getOrDefault(emptyList())
    }

    fun close() {
        runCatching { latinRecognizer.close() }
        // Only the scripts a frame actually needed were ever built.
        runCatching { japaneseRecognizer.close() }
        runCatching { chineseRecognizer.close() }
        runCatching { koreanRecognizer.close() }
        runCatching { devanagariRecognizer.close() }
        runCatching { languageId.close() }
    }
}
