package com.grafizum.lucid

import android.app.Dialog
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import android.view.animation.DecelerateInterpolator

/**
 * The first screen a new install meets: seven loops, each one showing a single
 * gesture the lens answers, and a last screen that asks for the permission the
 * lens cannot work without. Nothing here is a manual — the loop plays the move
 * on a drawn handset, and the words underneath say what the move is for.
 *
 * A skip counts as seen. The flag lives in [Prefs], so the loops are met once
 * and never again; the main screen is behind this one and is waiting for it.
 */
class OnboardingActivity : AppCompatActivity() {

    private companion object {
        /** The loop the screen was showing, carried across a rebuild. */
        const val KEY_PAGE = "onboardingPage"
    }

    private class Page(val title: String, val body: String)

    /**
     * The seven loops, by key rather than by words.
     *
     * The English side of every line lives in [UiText], which is what a
     * downloaded language translates; keeping the copy there means a page of
     * Japanese is a file on the phone, not a second APK.
     */
    private val pages = listOf(
        Page("tour.1.title", "tour.1.body"),
        Page("tour.2.title", "tour.2.body"),
        Page("tour.3.title", "tour.3.body"),
        Page("tour.4.title", "tour.4.body"),
        Page("tour.5.title", "tour.5.body"),
        Page("tour.6.title", "tour.6.body"),
        Page("tour.7.title", "tour.7.body"),
    )

    /** One line of the tour, in the language the app is set to. */
    private fun words(key: String): String = UiText.t(this, key)

    private lateinit var scene: OnboardingScene
    private lateinit var language: TextView
    private lateinit var langState: TextView
    private lateinit var title: TextView
    private lateinit var body: TextView
    private lateinit var dots: LinearLayout
    private lateinit var back: TextView
    private lateinit var next: TextView
    private lateinit var skip: TextView

    private var index = 0

    /**
     * The corner chip's own story: a language being translated, the reason it
     * cannot be, and the repaint the moment it lands.
     *
     * The words of the tour are the pack's, not the layout's, so choosing a
     * language used to leave the chip saying Japanese over an English page
     * until someone found the download in Settings. The fetch now starts with
     * the pick, and this is what redraws the page when it finishes.
     */
    private val packWatcher = object : AppLanguage.Watcher {
        override fun onPackChanged(code: String, pack: AppLanguage.Pack, done: Int, total: Int) {
            if (code != Prefs.uiLanguage(this@OnboardingActivity)) return
            paintLanguage()
            if (pack == AppLanguage.Pack.READY) {
                show(index, animate = false)
                reserveHeights()
                UiText.apply(this@OnboardingActivity, findViewById(R.id.obRoot))
            }
        }
    }

    /** Set up was pressed with float-over still off; the next page is the list. */
    private var pendingAccessibility = false

    private val flingDetector by lazy {
        GestureDetector(
            this,
            object : GestureDetector.SimpleOnGestureListener() {
                override fun onDown(e: MotionEvent): Boolean = true

                override fun onFling(
                    e1: MotionEvent?,
                    e2: MotionEvent,
                    velocityX: Float,
                    velocityY: Float,
                ): Boolean {
                    if (e1 == null) return false
                    val dx = e2.x - e1.x
                    // A sideways flick is worth a page; a small drag is not.
                    if (kotlin.math.abs(dx) < dp(56f) || kotlin.math.abs(dx) < kotlin.math.abs(e2.y - e1.y)) {
                        return false
                    }
                    show(if (dx < 0) index + 1 else index - 1)
                    return true
                }
            },
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // The theme is put on before anything is drawn, so a dark choice is
        // already true on this screen and it cannot flash light on the way in.
        AppCompatDelegate.setDefaultNightMode(ThemeChoice.nightMode(this))
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_onboarding)
        // The theme can be changed from this screen, so a rebuild after that
        // choice finds the page it left held: dissolve it away over this one.
        ThemeFade.reveal(this)

        scene = findViewById(R.id.obScene)
        language = findViewById(R.id.obLang)
        langState = findViewById(R.id.obLangState)
        title = findViewById(R.id.obTitle)
        body = findViewById(R.id.obBody)
        dots = findViewById(R.id.obDots)
        back = findViewById(R.id.obBack)
        next = findViewById(R.id.obNext)
        skip = findViewById(R.id.obSkip)

        // The CTA is the app's one primary button, not a second design of one:
        // it wears the same file every other primary action wears, so the tour's
        // Next and a sheet's Allow are the same object in the same colour.
        next.setBackgroundResource(R.drawable.btn_primary)
        Press.attachAll(back, next, language, skip)

        // The app's language, in the corner. The pick made here is the one the
        // whole app is drawn in and the one a read comes out as — the same
        // switch Settings has, not a second setting that can drift from it.
        bindLanguage()
        paintLanguage()
        AppLanguage.watch(packWatcher)
        language.setOnClickListener { pickLanguage() }

        // The tour is the first place a decision about the app's light is worth
        // offering, and Settings is not reachable from here yet. It is one button,
        // and the button wears the surface the page is not: a sun on charcoal, a
        // moon on paper. Tapping it turns the page under the finger, and the
        // button then wears the other one — so it always says what a tap does.
        val surface = findViewById<ImageView>(R.id.obTheme)
        Press.attach(surface)
        surface.setOnClickListener {
            ThemePicker.choose(
                this,
                if (ThemeChoice.isDark(this)) ThemeChoice.LIGHT else ThemeChoice.DARK,
            )
            bindSurface(surface)
        }
        bindSurface(surface)

        // A skip leaves the tour on any page: it counts as seen, exactly as the
        // last page's button does.
        skip.setOnClickListener { finishOnboarding() }
        // The left button only ever turns the deck back. On the last screen it
        // used to walk into the app, which read as a second next and left the
        // person unable to look at the loop before it again; leaving early is
        // Skip's job, in the corner, on every page.
        back.setOnClickListener { stepBack() }
        // Nothing here opens Android's switch on its own: the last screen's Set
        // up button is the only way to it, so leaving for Settings is always
        // something the person chose, and Not now walks straight into the app.
        next.setOnClickListener {
            if (index < pages.lastIndex) {
                show(index + 1)
            } else if (LensAccessibilityService.isEnabled(this)) {
                finishOnboarding()
            } else {
                openAccessibilitySettings()
            }
        }

        // Edge to edge, with the bars paid for in padding: the last button has
        // to clear the gesture bar on every phone, short ones included.
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val root = findViewById<ViewGroup>(R.id.obRoot)
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout(),
            )
            view.setPaddingRelative(
                dp(26f).toInt() + bars.left,
                dp(18f).toInt() + bars.top,
                dp(26f).toInt() + bars.right,
                dp(18f).toInt() + bars.bottom,
            )
            insets
        }

        // A rebuild (rotation, a font-size change, the phone reclaiming the
        // process) returns to the loop that was on screen, not to the first.
        index = savedInstanceState?.getInt(KEY_PAGE) ?: 0
        show(index, animate = false)

        // Every page's words get the room of the longest of them, so the handset
        // above them never changes size or moves when the deck is turned. It is
        // measured, not guessed at, so a larger system font still fits.
        reserveHeights()

        // The corner chip and the buttons are the app's own words too.
        UiText.apply(this, findViewById(R.id.obRoot))
    }

    /** Holds the words' blocks at one height, the tallest page's, so the
     *  handset above them never changes size or moves when the deck turns. It
     *  is measured, not guessed at, so a larger system font still fits. */
    private fun reserveHeights() {
        body.post {
            val width = body.width
            if (width <= 0) return@post
            val exact = View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY)
            val free = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
            fun tallest(view: TextView, values: List<String>): Int {
                val shown = view.text
                val height = values.maxOf { value ->
                    view.text = value
                    view.measure(exact, free)
                    view.measuredHeight
                }
                view.text = shown
                return height
            }
            val titleHeight = tallest(title, pages.map { words(it.title) })
            val bodyHeight = tallest(body, pages.map { words(it.body) })
            if (title.minimumHeight != titleHeight || body.minimumHeight != bodyHeight) {
                title.minimumHeight = titleHeight
                body.minimumHeight = bodyHeight
                title.requestLayout()
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(KEY_PAGE, index)
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        flingDetector.onTouchEvent(ev)
        return super.dispatchTouchEvent(ev)
    }

    /** Which of the six is showing, with the words changing under it. */
    private fun show(target: Int, animate: Boolean = true) {
        val at = target.coerceIn(0, pages.lastIndex)
        if (at == index && animate) return
        index = at
        val page = pages[at]

        scene.page = at

        // No step counter: the progress is the row of dots under the words, and
        // the way out is the small Skip in the corner, on every page.
        bindActions()

        dots.removeAllViews()
        repeat(pages.size) { dot ->
            val on = dot == at
            dots.addView(
                View(this).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        dp(if (on) 19f else 7f).toInt(),
                        dp(7f).toInt(),
                    ).apply { marginEnd = dp(6f).toInt() }
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.RECTANGLE
                        cornerRadius = dp(4f)
                        setColor(
                            ContextCompat.getColor(
                                this@OnboardingActivity,
                                if (on) R.color.screen_accent else R.color.screen_dot,
                            ),
                        )
                    }
                },
            )
        }

        val pageTitle = words(page.title)
        val pageBody = words(page.body)

        if (!animate) {
            title.text = pageTitle
            body.text = pageBody
            return
        }

        // The words leave to the side the page came from, and the new ones
        // arrive from the other: the motion says which way the deck is going.
        val shift = dp(14f)
        listOf(title, body).forEach { view ->
            view.animate().cancel()
            view.animate()
                .alpha(0f)
                .translationX(-shift)
                .setDuration(90)
                .withEndAction {
                    title.text = pageTitle
                    body.text = pageBody
                    title.translationX = shift
                    body.translationX = shift
                    listOf(title, body).forEach { incoming ->
                        incoming.animate()
                            .alpha(1f)
                            .translationX(0f)
                            .setDuration(190)
                            .setInterpolator(DecelerateInterpolator(1.8f))
                            .start()
                    }
                }
                .start()
        }
    }

    /**
     * The two buttons at the foot, whatever page is showing.
     *
     * Every loop says Next. The last screen is the permission decision, so it
     * says Set up — or Start the lens, when the switch is already on — and its
     * left button says Not now, which is the way into the app without it.
     */
    private fun bindActions() {
        val permission = index == pages.lastIndex
        val enabled = LensAccessibilityService.isEnabled(this)
        skip.text = words("tour.skip")
        back.text = words("tour.back")
        back.visibility = if (index > 0) View.VISIBLE else View.INVISIBLE
        back.isEnabled = index > 0
        next.text = when {
            permission && !enabled -> words("tour.setup")
            permission -> words("tour.start")
            else -> words("tour.next")
        }
    }

    /**
     * The one picture, redrawn for the surface in force.
     *
     * Charcoal shows a sun, because the thing a tap can do from there is turn the
     * page to paper; paper shows a moon for the same reason the other way round.
     * The words go with it, so TalkBack reads what the button will do rather than
     * what the page already is.
     */
    private fun bindSurface(button: ImageView) {
        val night = ThemeChoice.isDark(this)
        button.setImageResource(if (night) R.drawable.ic_sun else R.drawable.ic_moon)
        button.contentDescription = if (night) "Switch to light" else "Switch to dark"
    }

    /**
     * The chip's word: the language the app is drawn in, in that language's own
     * name — "日本語", not "Japanese" — and short enough for a chip.
     */
    private fun bindLanguage() {
        language.text = Languages.nativeShort(Prefs.uiLanguage(this))
    }

    /** The line under the chip, or nothing while the words are already here. */
    private fun paintLanguage() {
        val notice = AppLanguage.notice(this, Prefs.uiLanguage(this))
        langState.visibility = if (notice == null) View.GONE else View.VISIBLE
        langState.text = notice ?: ""
    }

    /**
     * The app's language switch, opened from the chip: the pick is remembered and
     * the tour is rebuilt in it, on the page it was left on. Reads come out in it
     * too, because a page answered in a language other than the one on screen
     * would be a second, silent setting.
     */
    private fun pickLanguage() {
        val dialog = Dialog(this)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.setContentView(
            LanguagePicker.build(this, Prefs.uiLanguage(this), title = "APP LANGUAGE") { lang ->
                Prefs.setTargetLanguage(this, lang.code)
                // The words are fetched with the pick: a German chip over an
                // English tour is the bug this closes. With no connection the
                // corner says so instead of pretending the page changed.
                AppLanguage.ensure(this, lang.code)
                AppLanguage.choose(this, lang.code)
                bindLanguage()
                paintLanguage()
                dialog.dismiss()
            },
        )
        dialog.window?.setLayout(
            (resources.displayMetrics.widthPixels * 0.86f).toInt(),
            WindowManager.LayoutParams.WRAP_CONTENT,
        )
        dialog.show()
    }

    /**
     * Android's own switch, opened only when Set up is pressed.
     *
     * The card that names Lucid's row has to sit over Settings, so float-over is
     * asked for first and the list follows on the way back — the order the home
     * page's sheet uses. Without the card the ask is the same list with nothing
     * pointing inside it, which is exactly the trip people lose their way on.
     */
    private fun openAccessibilitySettings() {
        if (!Settings.canDrawOverlays(this)) {
            pendingAccessibility = true
            val opened = runCatching {
                startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName"),
                    ),
                )
            }
            if (opened.isFailure) {
                Toast.makeText(this, "Allow Lucid to float over other apps first.", Toast.LENGTH_LONG)
                    .show()
            }
            return
        }
        // The last loop is this ask, so the switch being turned on is the end of
        // it: the service brings this page back with its button already saying
        // Start the lens.
        AccessReturn.arm(this, javaClass)
        val opened = runCatching { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        opened.onFailure { finishOnboarding() }
        // Nothing of ours may stand over that list — an app overlay is drawn at
        // reduced alpha there — so the section the row sits under is said once,
        // as the list arrives, in the phone's own words.
        if (opened.isSuccess) {
            Toast.makeText(
                this,
                UiText.line(
                    this,
                    "Under %s — scroll down if it isn't there.",
                    SetupNudge.sectionPhrase(this),
                ),
                Toast.LENGTH_LONG,
            ).show()
        }
    }

    override fun onDestroy() {
        AppLanguage.unwatch(packWatcher)
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        // A pack may have landed while this page was away — or a connection may
        // have, which is what the corner promised. Both are answered here.
        paintLanguage()
        AppLanguage.retryIfOnline(this, Prefs.uiLanguage(this))
        // Back from float-over: the list is the next step, and the card can now
        // sit over it.
        if (pendingAccessibility) {
            pendingAccessibility = false
            if (Settings.canDrawOverlays(this)) {
                next.postDelayed({ openAccessibilitySettings() }, 400L)
            }
        }
        // Back from the switch: the last screen's buttons say what is left to
        // do, without restarting its loop.
        bindActions()
    }

    /** One loop back, never out. The first page has nothing earlier to show. */
    private fun stepBack() {
        if (index > 0) show(index - 1) else confirmExit()
    }

    /**
     * Asked before the tour is given up.
     *
     * Back on the first page used to fall straight through to the home screen,
     * which is the one way out of the tour a thumb can take by accident. It is
     * still the way out — the app is not a trap — but it is now a choice, made
     * on a card of the page's own paper, with staying the quieter answer.
     */
    private fun confirmExit() {
        val dialog = Dialog(this)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.setContentView(R.layout.dialog_exit)
        dialog.window?.setLayout(
            (resources.displayMetrics.widthPixels * 0.86f).toInt(),
            WindowManager.LayoutParams.WRAP_CONTENT,
        )
        dialog.window?.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        dialog.window?.setDimAmount(0.42f)

        dialog.findViewById<TextView>(R.id.exitTitle).text = UiText.line(this, "Leave the tour?")
        dialog.findViewById<TextView>(R.id.exitBody).text = UiText.line(
            this,
            "The two switches can still be set up from the home page — the card there says what is missing. The tour will not come back on its own.",
        )
        val stay = dialog.findViewById<TextView>(R.id.exitStay)
        val leave = dialog.findViewById<TextView>(R.id.exitLeave)
        stay.text = UiText.line(this, "Stay")
        leave.text = UiText.line(this, "Leave")
        Press.attachAll(stay, leave)
        stay.setOnClickListener { dialog.dismiss() }
        leave.setOnClickListener {
            dialog.dismiss()
            finishOnboarding()
        }
        dialog.window?.decorView?.let { UiText.apply(this, it) }
        dialog.show()
    }

    private fun finishOnboarding() {
        Prefs.setOnboardingSeen(this, true)
        // Straight into the screen the loops were about, so the first thing
        // anyone does after them is the thing they were just shown.
        finish()
        startActivity(Intent(this, MainActivity::class.java))
    }

    /**
     * The phone's own Back, held to the same rule as the button on the page:
     * turn one loop back, and on the first page ask rather than drop the tour.
     */
    override fun onBackPressed() {
        stepBack()
    }

    private fun dp(value: Float): Float = value * resources.displayMetrics.density
}
