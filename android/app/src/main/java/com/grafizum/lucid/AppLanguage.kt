package com.grafizum.lucid

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat

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
 */
object AppLanguage {

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
}
