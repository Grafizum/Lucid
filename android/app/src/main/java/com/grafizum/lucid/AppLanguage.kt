package com.grafizum.lucid

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.CopyOnWriteArraySet

/**
 * The language the app itself is drawn in.
 *
 * The app ships its own words in ~20 languages as string resources, and those
 * resources were being chosen by the phone's own language — so a phone set to
 * German drew Lucid in German whatever the app language said, and the setting
 * only reached the tour. This is the missing link: the choice is handed to
 * AppCompat, which re-draws every screen in it, including the languages Lucid
 * has no resources for (those fall back to English and are read through the
 * downloaded pack where one exists).
 *
 * The pack is the second half. Picking a language is the whole answer now: the
 * list that makes the app read in it is fetched from the same engine the lens
 * uses, once, and kept on the phone. The fetch is owned here rather than by a
 * screen, because choosing a language rebuilds the screen underneath it — a job
 * tied to that screen would be cancelled by the rebuild that the pick itself
 * caused, which is exactly the bug this replaces.
 */
object AppLanguage {

    /** How one language's words stand on this phone. */
    enum class Pack {
        /** Nothing to fetch: English is built in, or the pack is here. */
        READY,

        /** Being translated right now; the counts are lines, not guesses. */
        FETCHING,

        /** Tried and could not: no connection, or the engine did not answer. */
        FAILED,

        /** Never asked for — the arrow in the app-language row fetches it. */
        MISSING,
    }

    /** A screen that wants to hear when a pack lands or fails. Called on Main. */
    interface Watcher {
        fun onPackChanged(code: String, pack: Pack, done: Int, total: Int)
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val watchers = CopyOnWriteArraySet<Watcher>()

    private var job: Job? = null
    private var fetching: String? = null
    private var doneLines = 0
    private var totalLines = 0

    /** Languages that were asked for and did not arrive, this run. */
    private val failed = HashSet<String>()

    /** Redraw the app in [code]; a blank code means English. */
    fun apply(code: String) {
        val tag = code.ifBlank { "en" }
        // Asking for the language that is already in force would start a
        // recreate loop, so only a real change is sent.
        if (AppCompatDelegate.getApplicationLocales().toLanguageTags() == tag) return
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(tag))
    }

    /** Remember [code] as the app language and redraw in it straight away. */
    fun choose(context: Context, code: String) {
        Prefs.setUiLanguage(context, code)
        apply(code)
    }

    /**
     * The stored choice, put in force. Called once as the app starts. Nothing
     * is applied until a language has actually been picked, so a phone that has
     * never been told otherwise still follows its own language.
     */
    fun restore(context: Context) {
        Prefs.storedUiLanguage(context)?.let(::apply)
    }

    /** Where [code]'s words stand, asked of the phone rather than remembered. */
    fun pack(context: Context, code: String): Pack = when {
        code.isBlank() || code == "en" || UiText.has(context, code) -> Pack.READY
        fetching == code -> Pack.FETCHING
        code in failed -> Pack.FAILED
        else -> Pack.MISSING
    }

    /** Whether any language is being translated right now. */
    val busy: Boolean get() = job != null

    /** Lines answered and lines asked for, for the screen drawing the wait. */
    fun progress(): Pair<Int, Int> = doneLines to totalLines

    fun watch(watcher: Watcher) {
        watchers += watcher
    }

    fun unwatch(watcher: Watcher) {
        watchers -= watcher
    }

    /**
     * Put [code]'s words on the phone, if they are not there already.
     *
     * Called the moment a language is picked, from the tour and from Settings,
     * so the choice is in force everywhere instead of waiting for someone to
     * find the download arrow. A phone with no connection says so at once rather
     * than sitting on a request that cannot arrive.
     */
    fun ensure(context: Context, code: String) {
        val app = context.applicationContext
        if (code.isBlank() || code == "en") return
        if (job != null || UiText.has(app, code)) return
        failed -= code
        if (!online(app)) {
            failed += code
            tell(code, Pack.FAILED, 0, 0)
            return
        }
        fetching = code
        doneLines = 0
        totalLines = 0
        tell(code, Pack.FETCHING, 0, 0)
        job = scope.launch {
            val result = UiText.download(app, code) { done, total ->
                doneLines = done
                totalLines = total
                tell(code, Pack.FETCHING, done, total)
            }
            if (result.isFailure) failed += code
            fetching = null
            job = null
            tell(code, pack(app, code), doneLines, totalLines)
        }
    }

    /**
     * Try again for [code] when a connection has come back since it failed.
     *
     * Called when a screen comes back to the front: someone who read "no
     * internet" and then connected expects the language to arrive by itself,
     * not to have to pick it a second time.
     */
    fun retryIfOnline(context: Context, code: String) {
        val state = pack(context, code)
        if (state != Pack.FAILED && state != Pack.MISSING) return
        if (!online(context)) return
        ensure(context, code)
    }

    /**
     * The one line a screen shows beside the language chip, or null when there
     * is nothing to say. Written here so the tour and Settings cannot explain
     * the same wait in two different ways.
     */
    fun notice(context: Context, code: String): String? {
        val name = Languages.nativeShort(code)
        return when (pack(context, code)) {
            Pack.READY -> null
            Pack.FETCHING -> {
                val (done, total) = progress()
                UiText.t(context, "appLang.progress", name, done, total)
            }
            // A phone with no connection gets the reason and the way out; one
            // that has a connection but no answer gets the retry instead.
            Pack.FAILED -> if (online(context)) {
                UiText.line(context, "Couldn't reach Google — try again")
            } else {
                UiText.line(context, "No internet — connect to translate %s", name)
            }
            Pack.MISSING -> UiText.line(context, "Not on this phone yet — tap to fetch %s", name)
        }
    }

    /** Whether the phone has a connection the app may use, asked not assumed. */
    fun online(context: Context): Boolean {
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return true
        val network = manager.activeNetwork ?: return false
        val caps = manager.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun tell(code: String, pack: Pack, done: Int, total: Int) {
        for (watcher in watchers) watcher.onPackChanged(code, pack, done, total)
    }
}
