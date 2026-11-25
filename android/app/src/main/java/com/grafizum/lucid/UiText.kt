package com.grafizum.lucid

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
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
    internal const val VERSION = 1

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
        "exit.title" to "Leave the tour?",
        "exit.body" to "The two switches can still be set up from the home page — the card there says what is missing. The tour will not come back on its own.",
        "exit.stay" to "Stay",
        "exit.leave" to "Leave",
    )

    /** Packs already read from disk, so a page of words does not read the file twice. */
    private val cache = HashMap<String, Map<String, String>>()

    /** How many lines are in the list a language has to translate. */
    val size: Int get() = english.size

    /**
     * One line of the app, in the language in force. English is the floor: a
     * pack that is missing, half written or out of date reads as the words it
     * was written in, never as a blank or a key.
     */
    fun t(context: Context, key: String): String =
        pack(context, Prefs.uiLanguage(context))[key] ?: english[key] ?: key

    /** The English side of one line, for the picker's previews and for tests. */
    fun plain(key: String): String = english[key] ?: key

    /** Whether this language is already on the phone. English is built in. */
    fun has(context: Context, code: String): Boolean =
        code == "en" || pack(context, code).isNotEmpty()

    /**
     * Translates the whole list into [code] and keeps it, or answers why not.
     *
     * One line at a time, on purpose: the engine merges sentences sent together
     * and the list would come back with its lines joined. A language that cannot
     * be answered stops the download — half a pack is worse than none, because
     * the app would read in two languages at once.
     */
    suspend fun download(
        context: Context,
        code: String,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): Result<Int> = withContext(Dispatchers.IO) {
        if (code == "en") return@withContext Result.success(0)
        val out = LinkedHashMap<String, String>()
        var done = 0
        for ((key, line) in english) {
            withContext(Dispatchers.Main) { onProgress(done, english.size) }
            val translated = ask(line, code)
                ?: return@withContext Result.failure(IllegalStateException("no answer for $key"))
            out[key] = translated
            done++
            // A beat between lines: the endpoint is a public one and a burst of
            // requests is how a download starts answering 429.
            delay(120)
        }
        withContext(Dispatchers.Main) { onProgress(done, english.size) }
        val saved = runCatching { write(context, code, out) }
        if (saved.isFailure) return@withContext Result.failure(saved.exceptionOrNull()!!)
        cache.remove(code)
        Result.success(out.size)
    }

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
