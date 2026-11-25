package com.grafizum.lucid

import android.app.Application

/**
 * Where the app language is put in force, before any screen is drawn: a stored
 * choice has to be active for the very first activity, or the app opens in the
 * phone's language and corrects itself a frame later.
 */
class LucidApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AppLanguage.restore(this)
    }
}
