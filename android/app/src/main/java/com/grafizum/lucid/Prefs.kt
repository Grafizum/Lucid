package com.grafizum.lucid

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

object Prefs {
    private const val FILE = "lucid_prefs"

    /** How many reads are kept; the oldest falls off the end. A log, not an archive. */
    private const val HISTORY_LIMIT = 20

    /** How long an armed return from the accessibility switch stays good. */
    private const val ACCESS_RETURN_WINDOW = 10 * 60 * 1000L

    private fun sp(context: Context) =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /**
     * What the lens translates into. Nothing stored yet means the phone's own
     * language, so a German phone opens already translating into German
     * instead of asking the person to find themselves in a list of sixty.
     */
    fun targetLanguage(context: Context): String =
        sp(context).getString("target", null)?.takeIf { it.isNotBlank() }
            ?: Languages.defaultTarget()

    /**
     * The language the app speaks — its own words, not what a read comes out as.
     *
     * Unset means the phone's own language, because that is the language its
     * owner already reads; a phone whose language Lucid does not carry opens in
     * English. The pack that makes it true is downloaded rather than shipped —
     * [UiText] owns that — and until it is, the app's words stay English.
     */
    fun uiLanguage(context: Context): String =
        storedUiLanguage(context) ?: defaultUiLanguage()

    /**
     * The language picked in the app, or null while the person has never picked
     * one — in which case the app follows the phone rather than taking a choice
     * that was never made.
     */
    fun storedUiLanguage(context: Context): String? =
        sp(context).getString("uiLang", null)?.takeIf { it.isNotBlank() }

    fun setUiLanguage(context: Context, code: String) =
        sp(context).edit().putString("uiLang", code).apply()

    private fun defaultUiLanguage(): String {
        val phone = Languages.canonical(java.util.Locale.getDefault().language) ?: return "en"
        return if (Languages.targets.any { it.code == phone }) phone else "en"
    }

    /** Read the text under the lens as soon as it stops moving. */
    fun autoTranslate(context: Context): Boolean = sp(context).getBoolean("autoTranslate", true)

    fun setAutoTranslate(context: Context, enabled: Boolean) =
        sp(context).edit().putBoolean("autoTranslate", enabled).apply()

    /** Use the online engine for accuracy, not just the offline models. */
    fun onlineEngine(context: Context): Boolean = sp(context).getBoolean("onlineEngine", true)

    fun setOnlineEngine(context: Context, enabled: Boolean) =
        sp(context).edit().putBoolean("onlineEngine", enabled).apply()

    fun setTargetLanguage(context: Context, code: String) =
        sp(context).edit().putString("target", code).apply()

    /**
     * What one app is translated into, when it has its own answer: a chat read
     * into German and a browser read into English, without changing the app's
     * own TRANSLATE INTO row every time the person switches between them.
     *
     * Nothing remembered for an app means [targetLanguage], so a phone with no
     * per-app picks behaves exactly as it always did.
     */
    fun targetFor(context: Context, pkg: String?): String = ownTargetFor(context, pkg) ?: targetLanguage(context)

    /**
     * The language this app was given, or null when it simply follows the page.
     * Null is not "the same as the default": it is the absence of a choice, which
     * is what lets the settings page show a chip only on the apps that have one.
     */
    fun ownTargetFor(context: Context, pkg: String?): String? =
        pkg?.takeIf { it.isNotBlank() }
            ?.let { sp(context).getString("target.$it", null) }
            ?.takeIf { it.isNotBlank() }

    /** Keep [code] for [pkg] alone; the global answer is left where it was. */
    fun setTargetFor(context: Context, pkg: String?, code: String) {
        val own = pkg?.takeIf { it.isNotBlank() }
        sp(context).edit().apply {
            if (own == null) putString("target", code) else putString("target.$own", code)
        }.apply()
    }

    /**
     * Forget an app's own language: its reads fall back to the page's default.
     * This is the whole of the clear — no history, no rule, no other app moves.
     */
    fun clearTargetFor(context: Context, pkg: String) {
        sp(context).edit().remove("target.$pkg").apply()
    }

    /**
     * Refuse the network for every read. Unlike the ENGINE row this is not a
     * preference about quality: with it on, a read that would have to leave the
     * phone says so instead of quietly doing it.
     */
    fun offlineOnly(context: Context): Boolean = sp(context).getBoolean("offlineOnly", false)

    fun setOfflineOnly(context: Context, on: Boolean) =
        sp(context).edit().putBoolean("offlineOnly", on).apply()

    /** Reads that came back translated, whatever the engine — the review signal. */
    fun goodReads(context: Context): Int = sp(context).getInt("goodReads", 0)

    fun countGoodRead(context: Context): Int {
        val next = goodReads(context) + 1
        sp(context).edit().putInt("goodReads", next).apply()
        return next
    }

    /** The rating sheet is asked for once, and a no is remembered as a no. */
    fun reviewAsked(context: Context): Boolean = sp(context).getBoolean("reviewAsked", false)

    fun setReviewAsked(context: Context, asked: Boolean) =
        sp(context).edit().putBoolean("reviewAsked", asked).apply()

    /**
     * The user has read what Text access does and tapped "I agree". Asked
     * before Android's own switch, because reading other apps' text is a
     * sensitive permission and Play requires that consent to be in the open.
     */
    fun textConsent(context: Context): Boolean = sp(context).getBoolean("textConsent", false)

    fun setTextConsent(context: Context, agreed: Boolean) =
        sp(context).edit().putBoolean("textConsent", agreed).apply()

    /**
     * The screen to come back to once Android's accessibility switch is on.
     *
     * Writing it down is what makes the return trustworthy: the service is
     * bound the moment the switch flips, and this says which page it was that
     * sent the person there. See [AccessReturn].
     */
    fun armAccessReturn(context: Context, screen: String) =
        sp(context).edit()
            .putString("accessReturn", screen)
            .putLong("accessReturnAt", System.currentTimeMillis())
            .apply()

    /**
     * The armed screen, once. Anything older than ten minutes is a leftover —
     * a switch turned on from Android's own screens later on is not an answer
     * to a question this app asked, and must not pull anyone out of what they
     * were doing.
     */
    fun takeAccessReturn(context: Context): String? {
        val prefs = sp(context)
        val screen = prefs.getString("accessReturn", null)
        val at = prefs.getLong("accessReturnAt", 0L)
        if (screen.isNullOrBlank()) return null
        prefs.edit().remove("accessReturn").remove("accessReturnAt").apply()
        return if (System.currentTimeMillis() - at <= ACCESS_RETURN_WINDOW) screen else null
    }

    /**
     * The user allowed screen capture at some point. The screen-share consent itself
     * cannot be stored — Android hands it out one session at a time — so this
     * only remembers the choice, which is what has to survive an app update,
     * a restart or the phone being rebooted. Without it the row quietly went
     * back to "Turn on" and the person had to remember they had ever set it.
     */
    fun lookMode(context: Context): Boolean = sp(context).getBoolean("lookMode", false)

    fun setLookMode(context: Context, on: Boolean) =
        sp(context).edit().putBoolean("lookMode", on).apply()

    fun bubbleX(context: Context): Int = sp(context).getInt("bubbleX", -1)

    fun bubbleY(context: Context): Int = sp(context).getInt("bubbleY", -1)

    fun saveBubble(context: Context, x: Int, y: Int) =
        sp(context).edit().putInt("bubbleX", x).putInt("bubbleY", y).apply()

    /**
     * Whether the shade's Lucid button has been added, as reported by the tile
     * itself. Only ever a hint: the button's real state lives with the system.
     */
    /**
     * True when this phone refused its own accessibility screenshot API and the
     * only way left to read pixels is MediaProjection. Set by [LensService]
     * after a refusal; it is what brings the Screen capture row back on a
     * modern phone that would otherwise hide it.
     */
    fun captureFallback(context: Context): Boolean = sp(context).getBoolean("captureFallback", false)

    fun setCaptureFallback(context: Context, on: Boolean) =
        sp(context).edit().putBoolean("captureFallback", on).apply()

    /**
     * The theme the person picked: [ThemeChoice.SYSTEM], [ThemeChoice.LIGHT] or
     * [ThemeChoice.DARK]. Written when a choice is tapped, so it survives a
     * restart and an update.
     *
     * A phone that chose with the old two-state button keeps that choice: it was
     * an explicit light or dark, never "follow the phone", so it arrives as that
     * side of the three rather than being reset. The old flag is only written
     * when that button was used, so its presence — not its value — is what marks
     * a choice; a phone without it never chose.
     *
     * A phone that never chose, and a fresh install, follow the phone: the page
     * turns with the room from the first launch instead of being pinned light
     * until someone finds this section.
     */
    fun themeMode(context: Context): Int {
        val prefs = sp(context)
        if (prefs.contains("themeMode")) {
            return prefs.getInt("themeMode", ThemeChoice.SYSTEM)
        }
        if (prefs.contains("darkTheme")) {
            return if (prefs.getBoolean("darkTheme", false)) ThemeChoice.DARK else ThemeChoice.LIGHT
        }
        return ThemeChoice.SYSTEM
    }

    fun setThemeMode(context: Context, mode: Int) =
        sp(context).edit().putInt("themeMode", mode).remove("darkTheme").apply()

    fun tileAdded(context: Context): Boolean = sp(context).getBoolean("tileAdded", false)

    /**
     * Written through rather than applied: the tile service is started to say
     * this and is killed moments later, and an applied write that has not
     * reached the disk yet dies with it.
     */
    fun setTileAdded(context: Context, added: Boolean) =
        sp(context).edit().putBoolean("tileAdded", added).commit()

    /**
     * The six loops have been met. Written when they are finished or skipped,
     * so the app opens on its own screen from the next launch on.
     */
    fun onboardingSeen(context: Context): Boolean = sp(context).getBoolean("onboardingSeen", false)

    fun setOnboardingSeen(context: Context, seen: Boolean) =
        sp(context).edit().putBoolean("onboardingSeen", seen).apply()

    /* ── recent reads ─────────────────────────────────────────────────── */

    /**
     * One finished read: what the lens saw, what it answered, and which way it
     * went. Kept only on this phone, 20 at most — old ones fall off the end
     * rather than being curated.
     */
    data class Read(
        val source: String,
        val translated: String,
        val detected: String?,
        val target: String,
        val packageName: String?,
        val at: Long,
    )

    /** Newest first. A stored entry that cannot be parsed is skipped, not fatal. */
    fun history(context: Context): List<Read> {
        val raw = sp(context).getString("history", null)?.takeIf { it.isNotBlank() } ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { index ->
                val entry = array.optJSONObject(index) ?: return@mapNotNull null
                val source = entry.optString("source").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                Read(
                    source = source,
                    translated = entry.optString("translated"),
                    detected = entry.optString("detected").takeIf { it.isNotBlank() },
                    target = entry.optString("target"),
                    packageName = entry.optString("package").takeIf { it.isNotBlank() },
                    at = entry.optLong("at"),
                )
            }
        }.getOrDefault(emptyList()).take(HISTORY_LIMIT)
    }

    /**
     * Remember a read. Asking for the same line twice in a row is one read, not
     * two: the lens fires on hovers, and a list that repeats itself is not a
     * record of anything.
     */
    fun addRead(context: Context, read: Read): List<Read> {
        val current = history(context)
        val sameAsLast = current.firstOrNull()?.let {
            it.source == read.source && it.translated == read.translated
        } == true
        val next = if (sameAsLast) current else (listOf(read) + current).take(HISTORY_LIMIT)
        if (next === current) return current

        writeHistory(context, next)
        return next
    }

    /**
     * One read deleted, and only it: the rows either side keep their place, the
     * count drops by one, and nothing else on the phone changes. A read is its
     * text, its answer and the moment it was made — the same line read twice is
     * two reads, so the time is what tells them apart.
     */
    fun removeRead(context: Context, read: Read): List<Read> {
        val current = history(context)
        val next = current.filterNot {
            it.at == read.at && it.source == read.source && it.translated == read.translated
        }
        if (next.size == current.size) return current

        writeHistory(context, next)
        return next
    }

    private fun writeHistory(context: Context, reads: List<Read>) {
        val array = JSONArray()
        reads.forEach { entry ->
            array.put(
                JSONObject().apply {
                    put("source", entry.source.take(4000))
                    put("translated", entry.translated.take(4000))
                    put("detected", entry.detected ?: "")
                    put("target", entry.target)
                    put("package", entry.packageName ?: "")
                    put("at", entry.at)
                },
            )
        }
        sp(context).edit().putString("history", array.toString()).apply()
    }

    fun clearHistory(context: Context) =
        sp(context).edit().remove("history").apply()

    /* ── rules: the apps the lens opens over by itself ────────────────── */

    /**
     * The apps whose arrival brings the lens up on its own. Empty by default:
     * a lens that appears over an app nobody asked for is a lens in the way.
     */
    fun autoApps(context: Context): Set<String> =
        sp(context).getStringSet("autoApps", null)?.toSet() ?: emptySet()

    fun autoOpenIn(context: Context, pkg: String?): Boolean {
        val name = pkg?.takeIf { it.isNotBlank() } ?: return false
        return name in autoApps(context)
    }

    /** Add [pkg] to the rules, or take it out. */
    fun setAutoApp(context: Context, pkg: String, on: Boolean) {
        val current = autoApps(context)
        val next = current.toMutableSet()
        if (on) next.add(pkg) else next.remove(pkg)
        if (next == current) return
        sp(context).edit().putStringSet("autoApps", next).apply()
    }

    /* ── the apps the lens has been over ─────────────────────────────── */

    /**
     * How many apps the settings page remembers. It draws these and no more:
     * a page of every launcher entry on the phone is a page of hundreds of
     * icons, and it made the settings page lag on the phones it was meant for.
     */
    private const val RECENT_APPS = 10

    /**
     * The apps the lens has been over, newest first — what the settings page
     * lists instead of the whole phone. Package names only: no titles, no
     * contents, nothing that could say what was read there.
     */
    fun recentApps(context: Context): List<String> =
        sp(context).getString("recentApps", null)
            ?.split('\n')
            ?.filter { it.isNotBlank() }
            ?.take(RECENT_APPS)
            .orEmpty()

    /**
     * Note the app under the lens. A read that stays on the same app is not a
     * write — the head of the list already says it, which is what keeps a
     * hover from touching the disk every time it fires.
     */
    fun noteAppUse(context: Context, pkg: String?) {
        val name = pkg?.takeIf { it.isNotBlank() } ?: return
        val current = recentApps(context)
        if (current.firstOrNull() == name) return
        val next = (listOf(name) + current.filter { it != name }).take(RECENT_APPS)
        sp(context).edit().putString("recentApps", next.joinToString("\n")).apply()
    }
}
