package com.grafizum.lucid

import android.content.Context
import android.content.res.Configuration
import androidx.appcompat.app.AppCompatDelegate

/**
 * The theme, decided in one place.
 *
 * The page used to carry a two-state moon/sun button in its header, which meant
 * a phone could never follow the system and a page never turned with the room.
 * The choice lives in Settings now, beside the language, and has three answers:
 * follow the phone, always light, always dark.
 *
 * Everything that draws itself asks this object rather than reading a boolean:
 * the activities for the mode to be put in, and the services for whether the
 * app is dark at this moment — which on [SYSTEM] is the phone's own answer.
 */
object ThemeChoice {

    /** Follow the phone's day/night setting, including its sunset flip. */
    const val SYSTEM = 0

    /** Always light, whatever the phone does. */
    const val LIGHT = 1

    /** Always dark, whatever the phone does. */
    const val DARK = 2

    fun mode(context: Context): Int = Prefs.themeMode(context)

    fun set(context: Context, mode: Int) = Prefs.setThemeMode(context, mode)

    /** The AppCompat mode an activity is put in before anything is inflated. */
    fun nightMode(context: Context): Int = when (mode(context)) {
        DARK -> AppCompatDelegate.MODE_NIGHT_YES
        LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
        else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
    }

    /**
     * Whether the app is dark at this moment. On [SYSTEM] the caller's own
     * configuration already holds the phone's answer, so it is read from there
     * rather than guessed at.
     */
    fun isDark(context: Context): Boolean = when (mode(context)) {
        DARK -> true
        LIGHT -> false
        else -> (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
    }

    /**
     * The same context, read through the app's own light.
     *
     * An activity gets the choice put on by AppCompat before anything is
     * inflated. A service, a card, or any view built from the application
     * context does not: it resolves @color through the *phone's* setting. That is
     * how a paper page ended up under a dark card, and a dark card's cream labels
     * ended up sitting on paper. Everything drawn outside an activity reads its
     * colours through this one call, so there is a single answer to "which theme
     * is the app in" and no surface can invent a second one.
     */
    fun themed(context: Context): Context {
        val config = Configuration(context.resources.configuration)
        val night = if (isDark(context)) {
            Configuration.UI_MODE_NIGHT_YES
        } else {
            Configuration.UI_MODE_NIGHT_NO
        }
        config.uiMode = (config.uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or night
        return context.createConfigurationContext(config)
    }
}
