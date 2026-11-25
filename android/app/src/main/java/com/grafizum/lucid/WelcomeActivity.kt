package com.grafizum.lucid

import android.app.Dialog
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat

/**
 * The first thing a new install sees, on its own screen rather than as a page
 * of the tour: the app's mark, the welcome, and the way in. The mark is the
 * button — the hint under it says so — and tapping it opens the six loops.
 *
 * The language chip is here because this is the screen someone meets first, and
 * which language the lens reads into is the one choice worth making before it
 * starts.
 */
class WelcomeActivity : AppCompatActivity() {

    private lateinit var language: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        // The theme goes on before anything is drawn, so the page never flashes
        // light on its way into a dark choice.
        AppCompatDelegate.setDefaultNightMode(ThemeChoice.nightMode(this))
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_welcome)
        // If this page was rebuilt because the theme was just chosen, the page
        // as it stood is still held: dissolve it away over this one.
        ThemeFade.reveal(this)

        language = findViewById(R.id.welcomeLang)
        bindLanguage()
        Press.attach(language)
        language.setOnClickListener { pickLanguage() }

        // The mark plays its own launch first — the ring opens, the mark blooms
        // and fades — and only then asks for the tour.
        findViewById<WelcomeMark>(R.id.welcomeMark).onLaunch = {
            startActivity(Intent(this, OnboardingActivity::class.java))
            // The welcome is not a place to come back to: the tour is next, and
            // the screen behind both of them is the app itself.
            finish()
        }

        // Edge to edge, with the bars paid for in padding: the label at the foot
        // has to clear the gesture bar on short phones too.
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val root = findViewById<ViewGroup>(R.id.welcomeRoot)
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout(),
            )
            view.setPaddingRelative(
                dp(26f).toInt() + bars.left,
                dp(18f).toInt() + bars.top,
                dp(26f).toInt() + bars.right,
                dp(16f).toInt() + bars.bottom,
            )
            insets
        }
    }

    /** The chip's word: the language the lens is set to, short enough for a chip. */
    private fun bindLanguage() {
        // The label alone: the chip is a button, and an arrow beside a name at
        // this size only ever read as noise.
        language.text = Languages.nativeShort(Prefs.targetLanguage(this))
    }

    /** The picker the main screen and the tour both use. */
    private fun pickLanguage() {
        val dialog = Dialog(this)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.setContentView(
            LanguagePicker.build(this, Prefs.targetLanguage(this)) { lang ->
                Prefs.setTargetLanguage(this, lang.code)
                bindLanguage()
                dialog.dismiss()
            },
        )
        dialog.window?.setLayout(
            (resources.displayMetrics.widthPixels * 0.86f).toInt(),
            WindowManager.LayoutParams.WRAP_CONTENT,
        )
        dialog.show()
    }

    private fun dp(value: Float): Float = value * resources.displayMetrics.density
}
