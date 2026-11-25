package com.grafizum.lucid

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * The app's own words, in the language the person chose to read them in.
 *
 * This is the other half of the language setting. The lens's target language is
 * what a read comes out as and needs nothing on the phone — the engine answers
 * it online. This one is the app itself: "Swipe to start the lens", "Leave the
 * tour?", every button on the way. Shipping those in 240 languages would mean
 * 240 translated page sets inside the APK, so they are not shipped. The English
 * side is the source, and a language is made by translating that list once over
 * the network and keeping the answer as one small file on the phone — the same
 * trade the offline packs make, and the reason the row has a download arrow
 * instead of a switch.
 *
 * Nothing here invents a translation: a word list with no pack falls back to the
 * English it was written in, so the app reads correctly before a download and
 * after a failed one. The endpoint is the one the lens already uses — Google's
 * public translate, no key, a phrase at a time — which is why a download can
 * happen with nothing but the phone and Wi-Fi.
 */
object UiText {

    /**
     * The English side, and the list that gets translated.
     *
     * Bumping [VERSION] retires every pack on the phone: the strings changed, so
     * a pack translated from the old ones would be missing whatever is new. The
     * arrow comes back and the language is fetched again.
     */
    internal const val VERSION = 2

    private val english = linkedMapOf(
        "tour.1.title" to "Swipe to start the lens",
        "tour.1.body" to "While the lens is off, the well rests at the left of the slide. Drag it across: the knob lands, and the lens comes up on the screen you are already on.",
        "tour.2.title" to "Point it at the other language",
        "tour.2.body" to "Anna writes in Japanese, you write in English. Rest the lens on one of her lines: it reads it where it stands, and the translation opens underneath with her own words kept below.",
        "tour.3.title" to "Tap for languages",
        "tour.3.body" to "One tap opens the language list, and the language you pick is remembered.",
        "tour.4.title" to "Hold to pause the lens",
        "tour.4.body" to "Press and hold the lens: it fades back to a third of itself and stops reading, so it is out of the way without leaving the screen. Hold it once more to wake it.",
        "tour.5.title" to "Drag it down to delete",
        "tour.5.body" to "The lens always ends at the bottom edge. Carry it down, the edge opens Delete, and dropping it there takes the lens off the screen.",
        "tour.6.title" to "Add it to quick settings",
        "tour.6.body" to "One tap in the shade turns the lens on, and one more turns it off again.",
        "tour.7.title" to "Let the lens read the screen",
        "tour.7.body" to "Text access is what lets Lucid read what the lens rests on, and it lives in Android's Accessibility settings, under the list of installed apps or services. Set it up now, or go in without it and grant it later from the setup card.",
        "tour.skip" to "Skip",
        "tour.back" to "Back",
        "tour.next" to "Next",
        "tour.setup" to "Set up",
        "tour.start" to "Start the lens",

        // Lines that are built with a value in them. The line is the source and
        // the pack keeps it whole, so a value is dropped in after translation
        // rather than translated with the sentence.
        "appLang.onPhone" to "%s is on this phone — the app reads in it.",
        "appLang.notOnPhone" to "Not on this phone — tap the arrow to fetch %s.",
        "appLang.fetching" to "Fetching %s…",
        "appLang.progress" to "Fetching %s… %d/%d",
        "appLang.done" to "%d lines in %s, and the app reads in it now.",
        "appLang.failed" to "Couldn't reach the translation service. Try again on Wi-Fi.",
    )

    /**
     * Every other word the app says, written as the app says it.
     *
     * These are the pages themselves — home, Settings, the history, the sheets —
     * copied out of the layouts and the code that fills them. The English IS the
     * key: a pack answers this list line for line, so a page that changes keeps
     * reading in English until its new line is added here, and nothing ever has
     * to keep a second name for a sentence.
     */
    private val lines = listOf(
        "Translation history",
        "Clear all",
        "What the lens has read, newest first. Kept on this phone, twenty at most. Swipe a read left to delete it.",
        "Nothing translated yet.\nPoint the lens at some text and every read lands here.",
        "Reads whatever the lens sits over — WhatsApp, a browser, any app — and translates it in place.",
        "THE LENS",
        "Auto-translate",
        "Reads when the lens stops",
        "On",
        "Engine",
        "Best quality — sent online to translate",
        "Online",
        "Offline only",
        "No read ever leaves the phone",
        "Off",
        "PAGES",
        "Offline models",
        "Downloaded language packs",
        "What the lens has read, kept on this phone",
        "Shipped with Grafizum.com",
        "Languages on this phone",
        "Add a language once and the lens reads it with nothing leaving the phone.",
        "ON THIS PHONE",
        "MORE LANGUAGES",
        "No language matches that.",
        "Settings",
        "APP LANGUAGE",
        "App language",
        "See the tour in this language",
        "TRANSLATE INTO",
        "Translate into",
        "What every read comes out as — answered online, nothing to download",
        "APPEARANCE",
        "System",
        "Light",
        "Dark",
        "Follows your phone's setting",
        "WHAT THE LENS NEEDS",
        "Translation can't start yet — the first two below are what it runs on.",
        "ALL OF THESE ARE NEEDED TO TRANSLATE",
        "Float over other apps",
        "Off by default — lets the lens sit on top",
        "Allow",
        "Read text on screen",
        "Off by default — lets the lens read text",
        "Screen capture",
        "Off by default — lets the lens read photos and video",
        "OPTIONAL — THE LENS WORKS WITHOUT IT",
        "Quick button",
        "Adds Lucid to the shade's quick buttons",
        "Add",
        "Open the lens when the app opens",
        "The lens needs Float over other apps and Read text on screen before a switch here can do anything. Both are just above.",
        "Search apps",
        "No app matches that name.",
        "Ten apps at most — type a name to reach any other app.",
        "Tap the lens to begin",
        "Welcome to Lucid",
        "Point it at anything on your screen and it reads it where it stands.",
        "What Text access does",
        "Lucid reads the words that sit under its lens so it can translate them. It reads only what is under the lens, never the rest of the screen, and keeps no copy.",
        "With ENGINE set to Online, those words are sent to an online translation service, with the rest of that screen as context, to be translated. With On-device, nothing leaves the phone.",
        "I agree",
        "Not now",
        "Clear the reads?",
        "The reads kept on this phone go — the lines, the answers, where and when. Nothing was uploaded, so there is no other copy and this cannot be undone.",
        "Keep them",
        "Text access",
        "Before the switch, what it does",
        "Leave the tour?",
        "The two switches can still be set up from the home page — the card there says what is missing. The tour will not come back on its own.",
        "Stay",
        "Leave",
        "Got it",
        "Permission needed",
        "The lens needs Text access",
        "Without it the lens still opens, but it reads nothing.",
        "Display over other apps",
        "Tap this to reach its switch",
        "Start now",
        "Settings › Accessibility › Lucid",
        "Greyed out? Open App info › ⋮ › Allow restricted settings first.",
        "Open settings",
        "READ",
        "TRANSLATED",
        "WHAT THE LENS READ",
        "One-time setup",
        "Allow, swipe, read",
        "Two permissions, one swipe, and the lens is on. Nothing is recorded and no frame is saved.",
        "Lets the lens sit on top of any app",
        "Reads the text under the lens",
        "Later",
        "READY",
        "Detected",
        "Read the whole sentence",
        "Copy",
        "READING",
        "ORIGINAL",
        "Speak",

        // Lines a page writes into a view after it is built. They are here so
        // the same walk over the views answers them, and where a line carries a
        // value it is asked for with [line].
        "Reads the moment the lens stops moving",
        "Only reads when you let go of the lens",
        "On-device",
        "Offline models — nothing leaves the phone",
        "On — nothing leaves the phone, whatever the engine",
        "Stop the lens",
        "1 read kept on this phone",
        "%d reads kept on this phone",
        "No packs downloaded yet",
        "1 pack downloaded",
        "%d packs downloaded",
        "Light, wherever the phone goes",
        "Dark, wherever the phone goes",
        "Allowed",
        "On — the lens can sit over any app",
        "Ready — steps 1 and 2 are on, so the lens can translate anywhere it can read.",
        "One step is on and one is off. Translation can't start until both are on.",
        "Translation can't start yet — steps 1 and 2 are what it runs on.",
        "Read and agree",
        "On — the lens can read the text under it",
        "On — the lens can read photos, video and hidden text",
        "Kept on — Android asks to share the screen each session",
        "Needed on this phone — images are read through the screen share",
        "Added",
        "In the shade — one tap turns the lens on",
        "Screen capture is on — the whole screen, every app.",
        "Screen access declined. Text access alone still works everywhere it can read.",
        "Appearance",
        "What the lens needs",
        "EVERY LANGUAGE IS ON THIS PHONE",
        "Adding…",
        "Adding %s",
        "Add %s",
        "Couldn't add %s — check the connection.",
        "Remove %s?",
        "The pack is downloaded again the next time an offline read needs it.",
        "Keep",
        "Remove",
        "Couldn't remove %s.",
        "%s removed",
        "Cleared — nothing is left on this phone",
        "Copied the original",
        "Read removed",
        "TODAY",
        "YESTERDAY",
        "The one read kept on this phone goes — the line, the answer, where and when. Nothing was uploaded, so there is no other copy and this cannot be undone.",
        "All %d reads kept on this phone go — the lines, the answers, where and when. Nothing was uploaded, so there is no other copy and this cannot be undone.",
        "Translating into %s",
        "This is the default for every app. If you pick a language on the lens while it sits over an app, that app keeps its own choice — a chat read into German, a browser into English — and every other app still uses this one.",
        "Two languages, two jobs. This one is the app itself — its tour, its buttons, its own words. English is written in; every other language is translated once by the same online engine the lens uses and then kept on this phone, which is what the arrow downloads. The card below it is the other question: the language a read comes out as, answered online every time, so nothing is ever downloaded for it.",
        "The page's own light. System follows the phone, so the app changes when the phone does; Light and Dark hold the app where you put it. The lens, its card and the sheets it opens keep their own dressing either way — they have to read over whatever is underneath them.",
        "These live in Android's own screens; Lucid can open them but never flips them for you. Steps 1 and 2 are the ground translation stands on, and without both the lens cannot start: float over other apps lets it sit on top of what you are reading, and read text on screen is what lets it see the words under it. A step 3 appears only where the phone will not give a screenshot any other way, and lasts one session. Anything past the optional line can stay off — the quick button is only the tile in the shade.",
        "Turn one on and the lens comes up over that app whenever it reaches the front — a chat, a game, a page you read every morning. Off is the app left alone. A switch only brings the lens up: nothing is read until you drag it onto something, and it still closes when you clear it. An app you have read into its own language shows a small cross on its row — tap it to forget that app's language and take the page's default back. Nothing else moves.",

        // The first-run guide: the sheet that sends someone to Android's own
        // switch, and says out loud which row to look for once they are there.
        "The lens needs to sit on top",
        "One screen to pick",
        "Android keeps \u201CDisplay over other apps\u201D off. Without it the lens cannot float over a chat or a browser, so it does nothing at all.",
        "Android keeps Text access off. Without it the lens still opens, but it reads nothing — every read comes back empty.",
        "Android asks which part of the screen to share. The lens needs the whole screen to read anything sitting on top of other apps.",
        "Accessibility",
        "Start recording or casting",
        "Entire screen",
        "%s · Off",
        "So the lens sees every app, not one",
        "\u2191 tap Lucid, then switch it on",
        "\u2191 tap it, then turn it on",
        "then this one \u2191",
        "Settings \u203A Apps \u203A Special access \u203A Display over other apps",
        "Settings \u203A Accessibility \u203A %s \u203A %s",
        "Pick Entire screen, then Start now — a single app will not do.",
        "Continue",
        "Couldn't open Accessibility settings — find Lucid in Settings › Accessibility.",
        "On phone",
        "%s is on the phone. Tap to remove it.",

        // What the lens says back while it is on screen.
        "Screen access was denied.",
        "Couldn't start screen capture.",
        "Lens paused — long-press to wake it",
        "Lens on",
        "Lucid needs the draw-over-other-apps permission.",
        "Lens is paused — long-press to wake it",
        "Lens off",
        "Translating %s into %s",
        "Copied",
        "Pinned — press ✕ to close",
        "Unpinned",
        "Voice is still starting — try again in a moment.",
        "No %s voice on this phone. Add one in Settings › Text-to-speech.",
        "The voice could not read this one.",
    )

    /**
     * Everything a pack has to carry: the tour's lines by name, and the pages'
     * lines by the words they are.
     */
    private val source: Map<String, String> = english + lines.associateWith { it }

    /** Packs already read from disk, so a page of words does not read the file twice. */
    private val cache = HashMap<String, Map<String, String>>()

    /** How many lines are in the list a language has to translate. */
    val size: Int get() = source.size

    /**
     * One line of the app, in the language in force. English is the floor: a
     * pack that is missing, half written or out of date reads as the words it
     * was written in, never as a blank or a key.
     */
    fun t(context: Context, key: String): String =
        pack(context, Prefs.uiLanguage(context))[key] ?: english[key] ?: key

    /** [t], with the values the line was written to hold. */
    fun t(context: Context, key: String, vararg values: Any): String =
        String.format(t(context, key), *values)

    /**
     * A sentence the page built itself, in the language in force: news about a
     * download, a count of packs, the word on a button that changes as it is
     * pressed. Written in English where it is used, answered by the pack when
     * the language has one, and left alone otherwise.
     */
    fun line(context: Context, english: String): String =
        pack(context, Prefs.uiLanguage(context))[english] ?: english

    /** [line], with the values the sentence was written to hold. */
    fun line(context: Context, english: String, vararg values: Any): String =
        String.format(line(context, english), *values)

    /** The English side of one line, for the picker's previews and for tests. */
    fun plain(key: String): String = english[key] ?: key

    /** Whether every line the pages are written in is unique, so a pack can be keyed by it. */
    internal fun linesAreUnique(): Boolean = lines.size == lines.distinct().size

    /** Whether a pack would carry [line] — the source of truth for one sentence. */
    internal fun carries(line: String): Boolean = source.containsKey(line)

    /**
     * Puts the app's own words on the screen into the language in force.
     *
     * The pages are written in English — in the layouts, and in the code that
     * fills them — and a pack is keyed by that English, so this walks the views
     * once and swaps every line it has a translation for. A line the pack does
     * not know is left exactly as written: English is the floor, and a page
     * added tomorrow reads in English until its words are added to [lines].
     * Called after a page is filled, and again after anything that rewrites it.
     */
    fun apply(context: Context, root: View) {
        val pack = pack(context, Prefs.uiLanguage(context))
        if (pack.isEmpty()) return
        translate(root, pack)
    }

    private fun translate(view: View, pack: Map<String, String>) {
        if (view is TextView) {
            view.text?.toString()?.let { pack[it]?.let { words -> view.text = words } }
            view.hint?.toString()?.let { pack[it]?.let { words -> view.hint = words } }
        }
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) translate(view.getChildAt(index), pack)
        }
    }

    /** Whether this language is already on the phone. English is built in. */
    fun has(context: Context, code: String): Boolean =
        code == "en" || pack(context, code).isNotEmpty()

    /**
     * Settles every line into [code] and keeps the answer, or says why not.
     *
     * A line per request, on purpose: the engine merges sentences sent together
     * and the list would come back with its lines joined. Three of those requests
     * are in flight at once, which is the difference between a page set arriving
     * in twenty seconds and in three minutes; a beat between each keeps the
     * public endpoint from reading the download as a burst. A language that
     * cannot be answered stops the whole thing — half a pack is worse than none,
     * because the app would read in two languages at once.
     */
    suspend fun download(
        context: Context,
        code: String,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): Result<Int> = withContext(Dispatchers.IO) {
        if (code == "en") return@withContext Result.success(0)
        val entries = source.entries.toList()
        val out = ConcurrentHashMap<String, String>()
        val next = AtomicInteger(0)
        val done = AtomicInteger(0)
        val lost = AtomicReference<Throwable?>(null)
        coroutineScope {
            repeat(WORKERS) {
                launch {
                    while (true) {
                        if (lost.get() != null) break
                        val index = next.getAndIncrement()
                        if (index >= entries.size) break
                        val (key, line) = entries[index]
                        val translated = ask(line, code)
                        if (translated == null) {
                            lost.compareAndSet(null, IllegalStateException("no answer for $key"))
                            break
                        }
                        out[key] = translated
                        withContext(Dispatchers.Main) { onProgress(done.incrementAndGet(), entries.size) }
                        delay(GAP_MS)
                    }
                }
            }
        }
        lost.get()?.let { return@withContext Result.failure(it) }
        withContext(Dispatchers.Main) { onProgress(done.get(), entries.size) }
        val saved = runCatching { write(context, code, out) }
        if (saved.isFailure) return@withContext Result.failure(saved.exceptionOrNull()!!)
        cache.remove(code)
        Result.success(out.size)
    }

    /** Requests in flight at once, and the breath taken between two of them. */
    private const val WORKERS = 3
    private const val GAP_MS = 100L

    /** Forgets a pack — the arrow's way back after a bad download. */
    fun remove(context: Context, code: String) {
        cache.remove(code)
        runCatching { file(context, code).delete() }
    }

    /**
     * Deletes the packs this version can no longer read.
     *
     * Uninstalling Lucid already takes every pack with it: these files and the
     * ML Kit models both live in the app's own storage, which Android deletes
     * with the package. What uninstall cannot reach is a pack the app retires
     * itself — bumping [VERSION] makes every pack on the phone unreadable, and
     * without this the files would sit there, counted against the person's
     * storage, for as long as the app stays installed. A `.part` left behind by
     * a download the phone cut short is the same dead weight. Runs as the app
     * starts, before any screen can read a pack.
     */
    fun sweep(context: Context) {
        for (name in sweep(dir(context))) cache.remove(name.removeSuffix(".json"))
    }

    /**
     * Deletes every file in [dir] that cannot be read, and answers the names it
     * took. Split from the context so a plain JVM test can hand it a folder.
     */
    internal fun sweep(dir: File): List<String> {
        val files = dir.listFiles() ?: return emptyList()
        val taken = ArrayList<String>()
        for (file in files) {
            val readable = file.name.endsWith(".json") && runCatching {
                JSONObject(file.readText()).optInt("v", 0) == VERSION
            }.getOrDefault(false)
            if (!readable && file.delete()) taken.add(file.name)
        }
        return taken
    }

    /* ── the file ────────────────────────────────────────────────────── */

    private fun file(context: Context, code: String) = File(dir(context), "$code.json")

    private fun dir(context: Context) =
        File(context.filesDir, "ui").apply { runCatching { mkdirs() } }

    /**
     * The pack on the phone, read once and kept. A file written by an older
     * version of the list counts as absent, which is what brings the arrow back.
     */
    private fun pack(context: Context, code: String): Map<String, String> {
        if (code == "en" || code.isBlank()) return emptyMap()
        cache[code]?.let { return it }
        val file = file(context, code)
        if (!file.exists()) return emptyMap()
        val loaded = runCatching {
            val root = JSONObject(file.readText())
            if (root.optInt("v", 0) != VERSION) return@runCatching emptyMap<String, String>()
            val strings = root.optJSONObject("strings") ?: return@runCatching emptyMap<String, String>()
            val map = HashMap<String, String>()
            strings.keys().forEach { key -> map[key] = strings.optString(key) }
            map
        }.getOrDefault(emptyMap())
        cache[code] = loaded
        return loaded
    }

    private fun write(context: Context, code: String, strings: Map<String, String>) {
        val root = JSONObject().apply {
            put("v", VERSION)
            put("code", code)
            put("strings", JSONObject().apply { strings.forEach { (k, v) -> put(k, v) } })
        }
        val file = file(context, code)
        // Written beside and moved into place, so a download cut short by the
        // phone sleeping cannot leave half a pack where a whole one belongs.
        val temp = File(file.parentFile, "${file.name}.part")
        temp.writeText(root.toString())
        if (!temp.renameTo(file)) {
            file.writeText(root.toString())
            temp.delete()
        }
    }

    /* ── the request ─────────────────────────────────────────────────── */

    /**
     * One line, translated. The engine's first row of segments is the answer;
     * the source is said out loud as English so a short line is never mistaken
     * for something to detect.
     *
     * The host is the one that answers a bare request without a key. Its older
     * sibling names the same service but answers a plain GET with Google's
     * "Sorry…" page from some networks, which is a download that fails for a
     * reason no user could act on.
     */
    private fun ask(line: String, code: String): String? = runCatching {
        val url = "https://clients5.google.com/translate_a/single" +
            "?client=dict-chrome-ex&dt=t&sl=en&tl=" + URLEncoder.encode(code, "UTF-8") +
            "&q=" + URLEncoder.encode(line, "UTF-8")
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            setRequestProperty(
                "User-Agent",
                "Mozilla/5.0 (Linux; Android) AppleWebKit/537.36 Chrome/130.0 Mobile Safari/537.36",
            )
            connectTimeout = TimeUnit.SECONDS.toMillis(6).toInt()
            readTimeout = TimeUnit.SECONDS.toMillis(10).toInt()
        }
        try {
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            val segments = JSONArray(body).optJSONArray(0) ?: return@runCatching null
            val text = StringBuilder()
            for (index in 0 until segments.length()) {
                val segment = segments.optJSONArray(index) ?: continue
                if (segment.isNull(0)) continue
                text.append(segment.optString(0))
            }
            text.toString().trim().ifEmpty { null }
        } finally {
            connection.disconnect()
        }
    }.getOrNull()
}
