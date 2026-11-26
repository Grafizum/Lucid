package com.grafizum.lucid

import android.app.Dialog
import android.app.StatusBarManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Icon
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Settings: everything the app can be told, on one page.
 *
 * The corner gear used to hold one thing — the apps that bring the lens up on
 * their own. The switches Android asks for and the language every read is
 * answered in live here now, so the home page is only what the lens does and
 * this is where it is set up. What has been read is not repeated here: home's
 * own Translation history row is the way into the log.
 *
 * The app list is the apps the lens has been over, ten at most: searchable,
 * switchable one app at a time, and ordered so the apps already chosen sit
 * first. Every launcher entry on the phone used to be drawn here, and a page of
 * hundreds of rows with an icon each is what made this page lag — the rest are
 * still a search away. A rule is one package name in [Prefs.autoApps]; nothing
 * about an app's contents is kept, and switching a row off is the whole removal.
 */
class SettingsActivity : AppCompatActivity() {

    private data class Entry(val pkg: String, val label: String)

    /** How many app rows the page draws at once; the rest are a search away. */
    private companion object {
        const val APP_ROWS = 10
    }

    private lateinit var dot1: TextView
    private lateinit var dot2: TextView
    private lateinit var dot3: TextView
    private lateinit var btn1: TextView
    private lateinit var btn2: TextView
    private lateinit var btn3: TextView
    private lateinit var btn4: TextView
    private lateinit var sub1: TextView
    private lateinit var sub2: TextView
    private lateinit var sub3: TextView
    private lateinit var sub4: TextView
    private lateinit var permStatus: TextView
    private lateinit var targetName: TextView
    private lateinit var sourceName: TextView
    private lateinit var targetCode: TextView
    private lateinit var sourceCode: TextView
    private lateinit var appLangName: TextView
    private lateinit var appLangHint: TextView
    private lateinit var appLangPack: ImageView
    private lateinit var appLangState: TextView

    /**
     * The pack the app language is waiting on. The fetch itself belongs to
     * [AppLanguage], because picking a language rebuilds this page underneath
     * the pick — this only has to look at where the pack got to.
     */
    private val packWatcher = object : AppLanguage.Watcher {
        override fun onPackChanged(code: String, pack: AppLanguage.Pack, done: Int, total: Int) {
            if (code != Prefs.uiLanguage(this@SettingsActivity)) return
            paintAppLanguage()
            // The page was built in English and the pack has just arrived: ask
            // its own words for the translation now, not at the next visit.
            if (pack == AppLanguage.Pack.READY) {
                UiText.apply(this@SettingsActivity, findViewById(R.id.settingsRoot))
            }
        }
    }

    private lateinit var appsWarn: TextView
    private lateinit var appsList: LinearLayout
    private lateinit var appsEmpty: TextView
    private lateinit var appsHint: TextView
    private lateinit var search: EditText

    /** Every app that can be opened, loaded off the main thread. */
    private var everything = listOf<Entry>()
    private var loaded = false

    /** Icons are the expensive part of a long list, so each is drawn once. */
    private val icons = HashMap<String, android.graphics.drawable.Drawable?>()

    private val overlayLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { refresh() }

    private val accessibilityLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { refresh() }

    private val projectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val data = result.data
        if (result.resultCode == RESULT_OK && data != null) {
            // The choice is written down; the grant itself cannot be, so this is
            // what tells an updated app that the user allowed screen capture.
            Prefs.setLookMode(this, true)
            LensService.startWithCapture(this, result.resultCode, data)
            say(UiText.line(this, "Screen capture is on — the whole screen, every app."))
        } else {
            say(UiText.line(this, "Screen access declined. Text access alone still works everywhere it can read."))
        }
        refresh()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        AppCompatDelegate.setDefaultNightMode(ThemeChoice.nightMode(this))
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        // If this page was rebuilt because the surface was just chosen, the page
        // as it stood is still held: dissolve it away over this one.
        ThemeFade.reveal(this)

        // Edge to edge, with the bars paid for in padding — the arrangement the
        // home page uses. Handing the inset to Android instead (the theme's
        // opt-out) is not an answer any more: Android 16 gives every app
        // targeting 36 the whole window whether it asked for it or not, and the
        // page's own title was left drawn up under the status bar.
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val root = findViewById<ViewGroup>(R.id.settingsRoot)
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout(),
            )
            view.setPaddingRelative(
                dp(24) + bars.left,
                dp(10) + bars.top,
                dp(24) + bars.right,
                dp(18) + bars.bottom,
            )
            insets
        }

        dot1 = findViewById(R.id.dot1)
        dot2 = findViewById(R.id.dot2)
        dot3 = findViewById(R.id.dot3)
        btn1 = findViewById(R.id.btn1)
        btn2 = findViewById(R.id.btn2)
        btn3 = findViewById(R.id.btn3)
        btn4 = findViewById(R.id.btn4)
        sub1 = findViewById(R.id.sub1)
        sub2 = findViewById(R.id.sub2)
        sub3 = findViewById(R.id.sub3)
        sub4 = findViewById(R.id.sub4)
        permStatus = findViewById(R.id.permStatus)
        targetName = findViewById(R.id.settingsTargetName)
        sourceName = findViewById(R.id.settingsSourceName)
        targetCode = findViewById(R.id.settingsTargetCode)
        sourceCode = findViewById(R.id.settingsSourceCode)
        appLangName = findViewById(R.id.settingsAppLangName)
        appLangHint = findViewById(R.id.settingsAppLangHint)
        appLangPack = findViewById(R.id.settingsAppLangPack)
        appLangState = findViewById(R.id.settingsAppLangState)
        appsWarn = findViewById(R.id.settingsAppsWarn)
        appsList = findViewById(R.id.settingsAppsList)
        appsEmpty = findViewById(R.id.appsEmpty)
        appsHint = findViewById(R.id.appsHint)
        search = findViewById(R.id.appSearch)

        val back = findViewById<View>(R.id.settingsBack)
        Press.attach(back)
        back.setOnClickListener { finish() }

        // Each heading carries the paragraph that used to sit under it: the
        // page stays a page of rows, and the explanation is a tap away.
        info(R.id.infoAppLang, "App language", "Two languages, two jobs. This one is the app itself — its tour, its buttons, its own words. English is written in; every other language is translated once by the same online engine the lens uses and then kept on this phone, which is what the arrow downloads. The card below it is the other question: the language a read comes out as, answered online every time, so nothing is ever downloaded for it.")
        info(
            R.id.infoTarget,
            "Translate from and into",
            "A read has two sides. From is Auto recognize by default: the lens works out the language of each line as it reads it, which is right almost every time. Pick a language there and every read starts from it — the answer for a screen too short or too mixed for the guess to be sure. Into is the default for every app. If you pick a language on the lens while it sits over an app, that app keeps its own choice — a chat read into German, a browser into English — and every other app still uses this one.",
        )
        info(R.id.infoTheme, "Appearance", "The page's own light. System follows the phone, so the app changes when the phone does; Light and Dark hold the app where you put it. The lens, its card and the sheets it opens keep their own dressing either way — they have to read over whatever is underneath them.")
        info(
            R.id.infoPermissions,
            "What the lens needs",
            "These live in Android's own screens; Lucid can open them but never flips them for you. Steps 1 and 2 are the ground translation stands on, and without both the lens cannot start: float over other apps lets it sit on top of what you are reading, and read text on screen is what lets it see the words under it. A step 3 appears only where the phone will not give a screenshot any other way, and lasts one session. Anything past the optional line can stay off — the quick button is only the tile in the shade.",
        )
        info(
            R.id.infoApps,
            "Open the lens when the app opens",
            "Turn one on and the lens comes up over that app whenever it reaches the front — a chat, a game, a page you read every morning. Off is the app left alone. A switch only brings the lens up: nothing is read until you drag it onto something, and it still closes when you clear it. An app you have read into its own language shows a small cross on its row — tap it to forget that app's language and take the page's default back. Nothing else moves.",
        )

        // App language: the row is the pick and the arrow is the retry. The
        // words are fetched the moment a language is picked, so nobody has to
        // find the arrow to make the choice true.
        val appLang = findViewById<View>(R.id.settingsAppLang)
        Press.attachAll(appLang, appLangPack)
        appLang.setOnClickListener { showAppLanguageDialog() }
        appLangPack.setOnClickListener {
            AppLanguage.ensure(this, Prefs.uiLanguage(this))
            paintAppLanguage()
        }
        AppLanguage.watch(packWatcher)

        // Translation: one card, two ends. Each end is the control, and the
        // list each opens is the same list the lens opens. The seam between
        // them is the third control: it swaps the pair.
        val target = findViewById<View>(R.id.settingsTarget)
        val source = findViewById<View>(R.id.settingsSource)
        val swap = findViewById<View>(R.id.settingsSwap)
        // The two ends are the card's own controls: each sinks and takes the
        // card's wash, and holds it long enough to be seen before its picker
        // arrives. The ring is a small disc and only needs the app's ordinary
        // give under a thumb.
        PairPress.attach(
            source,
            findViewById(R.id.settingsSourceWash),
            sourceCode,
            sourceName,
        ) { showSourceDialog() }
        PairPress.attach(
            target,
            findViewById(R.id.settingsTargetWash),
            targetCode,
            targetName,
        ) { showLanguageDialog() }
        Press.attach(swap)
        swap.setOnClickListener { swapLanguages() }

        // Appearance: three answers on one card, not a switch beside things that
        // only change when the app changes them. Each answer is a drawn page in
        // its own light — see [ThemeSwatch] — with its mark and its word under
        // it, so the card can be read at a glance and still be read out.
        themeOptions().forEach { option ->
            val cell = findViewById<View>(option.cell)
            cell.tag = option.mode
            val label = findViewById<TextView>(option.label).text
            // The cell's own word is the answer, so the label carries it too:
            // a chosen cell is announced as chosen, not just read out.
            cell.contentDescription = "$label appearance"
            findViewById<ThemeSwatch>(option.swatch).mode = option.mode
            Press.attach(cell)
            cell.setOnClickListener { chooseTheme(option.mode) }
        }

        // The four switches Android owns. Each button is the ask while the
        // switch is off, and the answer once it is on.
        Press.attachAll(btn1, btn2, btn3, btn4)
        btn1.setOnClickListener { openOverlaySettings() }
        btn2.setOnClickListener {
            // The disclosure comes before Android's switch, never after: who is
            // sent to that screen has already read what it is for.
            if (!LensAccessibilityService.isEnabled(this) && !Prefs.textConsent(this)) {
                askConsent()
            } else {
                openAccessibilitySettings()
            }
        }
        btn3.setOnClickListener { startCapture() }
        btn4.setOnClickListener { addQuickButton() }

        // The rows under the field are the answer to what was typed, so they
        // have to stay above the keyboard: the field lifts to the top as soon as
        // it is touched, and stays there while the query narrows.
        search.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) search.postDelayed({ revealApps() }, 260)
        }
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                paintApps()
                if (search.hasFocus()) search.post { revealApps() }
            }
        })

        loadApps()
        refresh()
    }

    override fun onResume() {
        super.onResume()
        // Back from Android's own screens: the switches may have moved.
        // A connection that came back is the app language arriving by itself.
        AppLanguage.retryIfOnline(this, Prefs.uiLanguage(this))
        refresh()
        // A button added by hand in the shade reaches the app by the shade
        // binding it, so ask for that bind rather than waiting to be told.
        LensTileService.ask(this)
        Handler(Looper.getMainLooper()).postDelayed({ refresh() }, 700)
    }

    /* ── state ───────────────────────────────────────────────────────── */

    private fun refresh() {
        paintPermissions()
        paintTheme()
        paintAppLanguage()
        paintTarget()
        paintSource()
        appsWarn.visibility =
            if (Settings.canDrawOverlays(this) && LensAccessibilityService.isEnabled(this)) {
                View.GONE
            } else {
                View.VISIBLE
            }
        paintApps()
        // Every line above was written in English; this is where the page is
        // given the language the pack holds, if the person fetched one.
        UiText.apply(this, findViewById(R.id.settingsRoot))
    }

    /** The three choices, in the order the card draws them. */
    private fun themeOptions(): List<ThemeOption> = listOf(
        ThemeOption(
            cell = R.id.themeSystem,
            swatch = R.id.themeSystemSwatch,
            label = R.id.themeSystemLabel,
            mode = ThemeChoice.SYSTEM,
        ),
        ThemeOption(
            cell = R.id.themeLight,
            swatch = R.id.themeLightSwatch,
            label = R.id.themeLightLabel,
            mode = ThemeChoice.LIGHT,
        ),
        ThemeOption(
            cell = R.id.themeDark,
            swatch = R.id.themeDarkSwatch,
            label = R.id.themeDarkLabel,
            mode = ThemeChoice.DARK,
        ),
    )

    /** The ids that make one appearance choice; [cell] is the control. */
    private data class ThemeOption(
        val cell: Int,
        val swatch: Int,
        val label: Int,
        val mode: Int,
    )

    /**
     * The appearance card, with the answer in force outlined and named in the
     * accent — the outline is on its page and the colour on its word, two ways
     * of saying the same thing that do not depend on each other. The page above
     * the word is already a picture of the answer, so no second mark is needed
     * beside it. The hint under the card says what the pick means out loud,
     * because "System" on its own could be read either way.
     */
    private fun paintTheme() {
        val mode = ThemeChoice.mode(this)
        themeOptions().forEach { option ->
            val on = option.mode == mode
            findViewById<View>(option.cell).isSelected = on
            findViewById<TextView>(option.label).isSelected = on
            findViewById<ThemeSwatch>(option.swatch).chosen = on
        }
        findViewById<TextView>(R.id.themeHint).text = when (mode) {
            ThemeChoice.LIGHT -> "Light, wherever the phone goes"
            ThemeChoice.DARK -> "Dark, wherever the phone goes"
            else -> "Follows your phone's setting"
        }
    }

    /**
     * A surface is chosen. The choice is written first, then put on: AppCompat
     * rebuilds this page when the light actually changes, and the picture kept
     * before the call dissolves the old page over the new one. When the light
     * does not change — picking Light on a phone that is already light — nothing
     * rebuilds, so the held picture is dropped and only the chosen row moves.
     */
    private fun chooseTheme(mode: Int) {
        if (ThemeChoice.mode(this) == mode) return
        ThemeFade.capture(this)
        ThemeChoice.set(this, mode)
        AppCompatDelegate.setDefaultNightMode(ThemeChoice.nightMode(this))
        window?.decorView?.postDelayed({
            if (!isFinishing && !isDestroyed) ThemeFade.discard()
        }, 450)
        paintTheme()
    }

    private fun paintPermissions() {
        val canOverlay = Settings.canDrawOverlays(this)
        paintStep(dot1, canOverlay)
        btn1.text = if (canOverlay) "Allowed" else "Allow"
        paintAnswer(btn1, canOverlay)
        btn1.isEnabled = !canOverlay
        sub1.text = if (canOverlay) {
            "On — the lens can sit over any app"
        } else {
            "Off by default — lets the lens sit on top"
        }

        val textAccess = LensAccessibilityService.isEnabled(this)
        // The numbered steps above the OPTIONAL line are the ground translation
        // stands on; this says plainly whether it can run, before any of the rows
        // are read, and names the same numbers the chips do.
        val canTranslate = canOverlay && textAccess
        permStatus.text = when {
            canTranslate -> "Ready — steps 1 and 2 are on, so the lens can translate anywhere it can read."
            canOverlay || textAccess ->
                "One step is on and one is off. Translation can't start until both are on."
            else -> "Translation can't start yet — steps 1 and 2 are what it runs on."
        }
        permStatus.setTextColor(getColor(if (canTranslate) R.color.screen_ok_ink else R.color.screen_accent))
        paintStep(dot2, textAccess)
        val consented = Prefs.textConsent(this) || textAccess
        btn2.text = when {
            textAccess -> "Allowed"
            !consented -> "Read and agree"
            else -> "Allow"
        }
        paintAnswer(btn2, textAccess)
        btn2.isEnabled = !textAccess
        sub2.text = if (textAccess) {
            "On — the lens can read the text under it"
        } else {
            "Off by default — lets the lens read text"
        }

        // Screen capture is a permission like the two above it, not a mode. Its
        // consent lasts one session, so a choice already made is told apart from
        // a live grant.
        val lookMode = LensService.hasCapture
        val lookWanted = Prefs.lookMode(this)
        findViewById<View>(R.id.captureRow).visibility =
            if (Capabilities.needsScreenShare || Prefs.captureFallback(this)) View.VISIBLE else View.GONE
        paintStep(dot3, lookMode)
        btn3.text = if (lookMode) "Allowed" else "Allow"
        paintAnswer(btn3, lookMode)
        btn3.isEnabled = !lookMode
        sub3.text = when {
            lookMode -> "On — the lens can read photos, video and hidden text"
            lookWanted -> "Kept on — Android asks to share the screen each session"
            Capabilities.needsScreenShare ->
                "Needed on this phone — images are read through the screen share"
            else -> "Off by default — lets the lens read photos and video"
        }

        val quickButton = Prefs.tileAdded(this)
        btn4.text = if (quickButton) "Added" else "Add"
        paintAnswer(btn4, quickButton)
        btn4.isEnabled = !quickButton
        sub4.text = if (quickButton) {
            "In the shade — one tap turns the lens on"
        } else {
            "Adds Lucid to the shade's quick buttons"
        }
    }

    /**
     * A numbered step: the numeral alone, ringed in the accent, while it is still
     * to do; once Android has granted it, the ring fills with the app's one "on"
     * surface — the accent tint — and the numeral turns to accent ink.
     */
    private fun paintStep(chip: TextView, done: Boolean) {
        chip.setBackgroundResource(if (done) R.drawable.step_chip_done else R.drawable.step_chip_pending)
        chip.setTextColor(getColor(if (done) R.color.screen_accent_ink else R.color.screen_accent))
    }

    /**
     * A permission's chip: one shape and one fill for both states, so the row is
     * a row of the same object. What changes is the surface — the accent tint
     * while it is on, the page's quiet beige while it is still to do — and the
     * ink that sits on it. No second colour is introduced for a state.
     */
    private fun paintAnswer(chip: TextView, on: Boolean) {
        chip.setBackgroundResource(if (on) R.drawable.chip_accent_bg else R.drawable.btn_ghost)
        chip.setTextColor(getColor(if (on) R.color.screen_accent_ink else R.color.screen_ink))
    }

    /* ── the question marks ──────────────────────────────────────────── */

    /**
     * One heading's explanation: the mark asks, this sheet answers. Both are the
     * app's own words, so both are read through the pack.
     */
    private fun info(markId: Int, title: String, body: String) {
        val mark = findViewById<TextView>(markId)
        mark.contentDescription = getString(R.string.info_mark) + " — " + UiText.line(this, title)
        Press.attach(mark)
        mark.setOnClickListener { showInfo(UiText.line(this, title), UiText.line(this, body)) }
    }

    private fun showInfo(title: String, body: String) {
        val dialog = Dialog(this)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.setContentView(R.layout.dialog_info)
        dialog.window?.setLayout(
            (resources.displayMetrics.widthPixels * 0.86f).toInt(),
            WindowManager.LayoutParams.WRAP_CONTENT,
        )
        dialog.window?.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        dialog.window?.setDimAmount(0.42f)

        dialog.findViewById<TextView>(R.id.infoTitle).text = title
        dialog.findViewById<TextView>(R.id.infoBody).text = body
        dialog.window?.decorView?.let { UiText.apply(this, it) }
        val done = dialog.findViewById<View>(R.id.infoDone)
        Press.attach(done)
        done.setOnClickListener { dialog.dismiss() }
        dialog.show()
    }

    /* ── the switches Android owns ───────────────────────────────────── */

    private fun openOverlaySettings() {
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:$packageName"),
        )
        runCatching { overlayLauncher.launch(intent) }
            .onFailure { say("Couldn't open Settings — find Lucid under Display over other apps.") }
    }

    private fun openAccessibilitySettings() {
        // The list this opens is the furthest an app may go. Nothing of ours may
        // stand over it — an app overlay is composited at reduced alpha there,
        // so its words would read through the list behind them — and the name of
        // the section that holds Lucid's row is said once, in the phone's own
        // words, as the list arrives.
        // Turning it on is the answer to this page's question, so the service —
        // which is bound the moment the switch flips — brings this page back.
        AccessReturn.arm(this, javaClass)
        val opened = runCatching {
            accessibilityLauncher.launch(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        if (opened.isFailure) {
            say(UiText.line(this, "Couldn't open Accessibility settings — find Lucid in Settings › Accessibility."))
        } else {
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

    /**
     * The disclosure first, the switch second. The words are the setup sheet's
     * own — see [R.layout.consent_block] — so what is agreed to here is exactly
     * what is agreed to there.
     */
    private fun askConsent() {
        val dialog = Dialog(this)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.setContentView(R.layout.dialog_consent)
        dialog.window?.decorView?.let { UiText.apply(this, it) }
        dialog.window?.setLayout(
            (resources.displayMetrics.widthPixels * 0.86f).toInt(),
            WindowManager.LayoutParams.WRAP_CONTENT,
        )
        dialog.window?.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        dialog.window?.setDimAmount(0.42f)

        val agree = dialog.findViewById<View>(R.id.consentAgree)
        val decline = dialog.findViewById<View>(R.id.consentDecline)
        Press.attachAll(agree, decline)
        agree.setOnClickListener {
            Prefs.setTextConsent(this, true)
            dialog.dismiss()
            openAccessibilitySettings()
        }
        decline.setOnClickListener { dialog.dismiss() }
        dialog.show()
    }

    /** The capture dialog, asking for the whole display so no app is left out. */
    private fun startCapture() {
        val manager = getSystemService(MediaProjectionManager::class.java) ?: return
        val intent = if (Capabilities.framesLeaveOverlaysOut) {
            wholeDisplay(manager)
        } else {
            manager.createScreenCaptureIntent()
        }
        runCatching { projectionLauncher.launch(intent) }
            .onFailure { say("Couldn't ask for the screen — try again from Lucid's home page.") }
    }

    /** Android 14 can be asked for the whole display by name, so its dialog never
     * offers the single-app option. Kept apart so an older phone never has to
     * resolve [MediaProjectionConfig], which it does not have. */
    @androidx.annotation.RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private fun wholeDisplay(manager: MediaProjectionManager) =
        manager.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())

    /**
     * The one switch Android lets an app offer itself. From 13 the system puts
     * up its own "add Lucid?" sheet; before that a tile can only be dragged in
     * from the panel's edit screen, so say that instead.
     */
    private fun addQuickButton() {
        if (!Capabilities.offersQuickButton) {
            say("Open the shade, tap the pencil, and drag Lucid into your quick buttons.")
            return
        }
        val manager = getSystemService(StatusBarManager::class.java)
        if (manager == null) {
            say("This phone has no quick buttons to add to.")
            return
        }
        manager.requestAddTileService(
            ComponentName(this, LensTileService::class.java),
            getString(R.string.tile_label),
            Icon.createWithResource(this, R.drawable.ic_tile_mark),
            mainExecutor,
        ) { result ->
            runOnUiThread {
                when (result) {
                    StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED -> {
                        Prefs.setTileAdded(this, true)
                        say("Lucid is in your quick buttons — tap it from the shade.")
                    }
                    StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED -> {
                        Prefs.setTileAdded(this, true)
                        say("Lucid is already in your quick buttons.")
                    }
                    else -> say("Not added — the shade's pencil can still drag it in.")
                }
                refresh()
            }
        }
    }

    /* ── the app's own language ──────────────────────────────────────── */

    /**
     * The row, told the truth: which language the app speaks, and whether its
     * words are on this phone yet.
     */
    private fun paintAppLanguage() {
        val code = Prefs.uiLanguage(this)
        val name = Languages.nativeShort(code)
        val builtIn = code == "en"
        val pack = AppLanguage.pack(this, code)
        val ready = pack == AppLanguage.Pack.READY
        val fetching = pack == AppLanguage.Pack.FETCHING
        appLangName.text = name
        appLangPack.visibility = if (builtIn) View.GONE else View.VISIBLE
        appLangPack.setImageResource(if (ready) R.drawable.ic_check else R.drawable.ic_download)
        appLangPack.alpha = if (fetching) 0.4f else 1f
        appLangPack.isEnabled = !ready && !fetching
        appLangPack.contentDescription = when {
            ready -> "$name is on this phone"
            else -> "Download $name"
        }
        appLangHint.text = when {
            builtIn -> "The app's own words — English is built in."
            fetching -> UiText.t(this, "appLang.fetching", name)
            ready -> UiText.t(this, "appLang.onPhone", name)
            else -> UiText.t(this, "appLang.notOnPhone", name)
        }
        paintAppLangState(code, pack)
    }

    /**
     * The small line under the row: silent while the words are here, a count
     * while they are being fetched, and — the one case that needs a person —
     * the reason they are not. A language cannot be translated over a phone
     * with no connection, so it says so where the choice was made. The words
     * are [AppLanguage.notice]'s, so the tour says the same thing.
     */
    private fun paintAppLangState(code: String, pack: AppLanguage.Pack) {
        val text = AppLanguage.notice(this, code)
        appLangState.visibility = if (text == null) View.GONE else View.VISIBLE
        if (text == null) return
        appLangState.text = text
        val icon = when (pack) {
            AppLanguage.Pack.FETCHING -> 0
            AppLanguage.Pack.FAILED ->
                if (AppLanguage.online(this)) R.drawable.ic_download else R.drawable.ic_qs_wifi
            else -> R.drawable.ic_download
        }
        appLangState.setCompoundDrawablesRelativeWithIntrinsicBounds(icon, 0, 0, 0)
    }

    private fun showAppLanguageDialog() {
        // Named for what it is here: this is the app's own language, not the
        // language a read comes out as. The row below opens that one.
        pickerSheet("APP LANGUAGE", Prefs.uiLanguage(this)) { lang ->
            // Fetched first, so the rebuild the choice causes already has the
            // words to draw: the screen comes back in the language the chip
            // says, not in English with a language's name on it.
            AppLanguage.ensure(this, lang.code)
            AppLanguage.choose(this, lang.code)
            paintAppLanguage()
        }
    }

    override fun onDestroy() {
        AppLanguage.unwatch(packWatcher)
        super.onDestroy()
    }

    /* ── translate from and into ─────────────────────────────────────── */

    /**
     * The from end: the language a read starts in, or the two words that say
     * nothing is pinned and each read works it out itself.
     */
    private fun paintSource() {
        val pinned = Prefs.sourceLanguage(this)
        sourceName.text = pinned?.let { Languages.nativeShort(it) }
            ?: UiText.line(this, "Auto recognize")
        paintCode(sourceCode, pinned, accent = false)
        // The seam is the swap. With nothing pinned there is nothing to swap a
        // guess with, so the ring stays where it is and dims instead of going:
        // the card keeps the one shape it has, and the control says by its own
        // light that there is nothing to exchange yet.
        val seam = findViewById<View>(R.id.settingsSwap)
        seam.isClickable = pinned != null
        seam.isFocusable = pinned != null
        seam.contentDescription = if (pinned == null) null else "Swap the two languages"
        // Only the glyph dims. Dimming the ring itself made it see-through, and
        // the seam behind it showed straight through the circle.
        findViewById<View>(R.id.settingsSwapGlyph).alpha = if (pinned == null) 0.45f else 1f
        findViewById<View>(R.id.settingsSource).contentDescription =
            if (pinned == null) {
                "Translate from: auto recognize. Every read works its language out. Tap to pin one."
            } else {
                "Translate from ${Languages.label(pinned)}. Tap to change it."
            }
    }

    /** The into end: the language every read comes out as. */
    private fun paintTarget() {
        val code = Prefs.targetLanguage(this)
        targetName.text = Languages.nativeShort(code)
        paintCode(targetCode, code, accent = true)
        findViewById<View>(R.id.settingsTarget).contentDescription =
            "Translate into ${Languages.label(code)}. Tap to change it."
    }

    /**
     * The disc at each end: two letters of the language's code where there is a
     * language, the word AUTO where the lens works the language out itself. A
     * code is shorter than any name, so the card can carry the pair without a
     * line of explanation under it.
     */
    private fun paintCode(disc: TextView, code: String?, accent: Boolean) {
        val auto = code == null
        disc.text = if (auto) "AUTO" else code.uppercase(Locale.ROOT).take(3)
        // Three letters only ever come from a language code the picker chose;
        // AUTO is four and has to sit inside the same 40 dp disc without
        // touching its rim.
        disc.textSize = when {
            auto -> 8.5f
            disc.text.length > 2 -> 10f
            else -> 13f
        }
        disc.letterSpacing = if (auto) 0.03f else -0.01f
        disc.setBackgroundResource(
            if (accent) R.drawable.medallion_to else R.drawable.medallion_from,
        )
        disc.setTextColor(
            ContextCompat.getColor(
                this,
                if (accent) R.color.screen_accent_ink else R.color.screen_ink,
            ),
        )
    }

    /**
     * The seam's one job: the pair trades places. Only a pinned From can move,
     * because Auto is not a language the other end could be set to.
     */
    private fun swapLanguages() {
        val from = Prefs.sourceLanguage(this) ?: return
        val into = Prefs.targetLanguage(this)
        Prefs.setSourceLanguage(this, into)
        Prefs.setTargetLanguage(this, from)
        paintSource()
        paintTarget()
        say("${Languages.label(into)} → ${Languages.label(from)}")
    }

    /**
     * The same list the lens opens, with Auto recognize at its head: it is the
     * default, so it has to be reachable without a search.
     */
    private fun showSourceDialog() {
        pickerSheet(
            "TRANSLATE FROM",
            Prefs.sourceLanguage(this) ?: "auto",
            includeAuto = true,
        ) { lang ->
            val auto = lang.code == "auto"
            Prefs.setSourceLanguage(this, if (auto) null else lang.code)
            paintSource()
            say(
                if (auto) {
                    UiText.line(this, "Auto recognize — the language is worked out on every read")
                } else {
                    UiText.line(this, "Reads start in %s", lang.label)
                },
            )
        }
    }

    private fun showLanguageDialog() {
        pickerSheet("TRANSLATE INTO", Prefs.targetLanguage(this)) { lang ->
            Prefs.setTargetLanguage(this, lang.code)
            paintTarget()
            Toast.makeText(
                this,
                UiText.line(this, "Translating into %s", lang.label),
                Toast.LENGTH_SHORT,
            ).show()
        }
    }

    /**
     * A language list, as the deep-lift sheet: anchored to the bottom edge of
     * the page, wide as the page, and lifted off it by its own shadow rather
     * than by a fill that stops dead at its border.
     *
     * The sheet is a sibling of the page, not part of it, so it carries the
     * frame that leaves room for the shadow; the list it holds is the same list
     * the lens opens. Picking a row closes the sheet, so no caller has to keep
     * its own handle on it.
     */
    private fun pickerSheet(
        title: String,
        current: String,
        includeAuto: Boolean = false,
        onPick: (Languages.Lang) -> Unit,
    ) {
        val dialog = Dialog(this)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        val sheet = LanguagePicker.build(
            this,
            current,
            title = title,
            includeAuto = includeAuto,
        ) { lang ->
            onPick(lang)
            dialog.dismiss()
        }
        sheet.setBackgroundResource(R.drawable.sheet_lift_bg)
        // The long shadow is the depth: the gradient only says where the sheet
        // is thin, and paper under it still reads through the lower third.
        sheet.elevation = dp(22).toFloat()
        dialog.setContentView(
            FrameLayout(this).apply {
                // A shadow is drawn outside the sheet's own bounds, so the frame
                // has to give it room and has to stop clipping to its padding.
                // The margins are the point: the sheet floats a clear step in
                // from both sides and, since it is only a list rather than a
                // page, well clear of the bottom edge too — docked to the last
                // pixels of the screen it read as a drawer pulled out of it.
                setPadding(dp(16), dp(30), dp(16), dp(64))
                clipChildren = false
                clipToPadding = false
                addView(
                    sheet,
                    FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    ),
                )
            },
        )
        dialog.window?.setGravity(Gravity.BOTTOM)
        // The page stays readable under the sheet, so the scrim is a breath of
        // shade rather than the dialog's default half-dark: what shows through
        // the sheet's thin bottom is the page, not a grey of it.
        dialog.window?.setDimAmount(0.14f)
        // The study's glass is frosted. Android 12 can blur what is behind a
        // window, which is the same thing for a sheet this shallow; older
        // phones keep the gradient and simply see the page through it.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            dialog.window?.setBackgroundBlurRadius(dp(20))
        }
        // The search field is the reason most people open this sheet, and it
        // sits at the top of it: a keyboard arriving would cover the
        // very rows the search is for, so the sheet is asked to shrink above it
        // rather than be pushed up the page.
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        dialog.window?.setLayout(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
        )
        dialog.show()
    }

    /* ── the app switches ────────────────────────────────────────────── */

    /**
     * Scrolls the page so the search field sits at the top of what the keyboard
     * leaves, with its answer filling the rest. Left alone, the system pans just
     * enough to keep the field visible, which parks it on the keyboard's edge
     * and hides every row under it.
     */
    private fun revealApps() {
        val scroll = findViewById<ScrollView>(R.id.settingsScroll)
        if (scroll.height == 0) return
        var top = 0
        var node: View? = search
        while (node != null && node !== scroll) {
            top += node.top
            node = node.parent as? View
        }
        if (node !== scroll) return
        scroll.smoothScrollTo(0, (top - dp(10)).coerceAtLeast(0))
    }

    /** Every app that has a launcher entry, our own page left out. */
    private fun loadApps() {
        Thread {
            val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            val resolved = runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    packageManager.queryIntentActivities(
                        intent,
                        PackageManager.ResolveInfoFlags.of(0L),
                    )
                } else {
                    @Suppress("DEPRECATION")
                    packageManager.queryIntentActivities(intent, 0)
                }
            }.getOrDefault(emptyList())

            val apps = resolved.mapNotNull { info ->
                val pkg = info.activityInfo?.packageName ?: return@mapNotNull null
                if (pkg == packageName) return@mapNotNull null
                val label = runCatching { info.loadLabel(packageManager).toString() }.getOrNull()
                Entry(pkg, label?.takeIf { it.isNotBlank() } ?: pkg)
            }.distinctBy { it.pkg }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                everything = apps
                loaded = true
                paintApps()
            }
        }.start()
    }

    /**
     * The list, drawn from the search box and the apps the lens has been over.
     *
     * The order is the page keeping a promise: the apps the person has already
     * given something — the lens switched on, a language of their own — come
     * first, then the rest by how recently the lens was over them, and any tie
     * by name.
     *
     * [APP_ROWS] of them and no more while the box is empty: the phone's whole
     * catalogue is hundreds of icons to draw for one row that is wanted, and
     * that is what made this page lag. A search is the person asking for the
     * whole phone, so it answers from all of it — narrowed by what they typed.
     */
    private fun paintApps() {
        appsList.removeAllViews()

        val needle = search.text?.toString()?.trim()?.lowercase().orEmpty()
        val chosen = Prefs.autoApps(this)
        val recent = Prefs.recentApps(this)

        // The rows the person has already decided something about — the lens
        // switched on, a language of their own — are read once here rather than
        // once per comparison: they always come first.
        val decided = everything
            .filter { it.pkg in chosen || Prefs.ownTargetFor(this, it.pkg) != null }
            .mapTo(HashSet()) { it.pkg }

        val matches = everything.filter {
            needle.isEmpty() ||
                it.label.lowercase().contains(needle) ||
                it.pkg.lowercase().contains(needle)
        }
        val ordered = matches.sortedWith(
            compareBy<Entry> { if (it.pkg in decided) 0 else 1 }
                .thenBy { entry ->
                    val index = recent.indexOf(entry.pkg)
                    if (index >= 0) index else Int.MAX_VALUE
                }
                .thenBy { it.label.lowercase() },
        )
        val shown = if (needle.isEmpty()) ordered.take(APP_ROWS) else ordered

        appsEmpty.visibility =
            if (loaded && matches.isEmpty()) View.VISIBLE else View.GONE

        // Said out loud, because a short list that does not explain itself reads
        // as a phone with ten apps on it. A search shows everything it matched,
        // so the line belongs to the empty box only.
        appsHint.visibility =
            if (loaded && needle.isEmpty() && matches.size > APP_ROWS) View.VISIBLE else View.GONE
        appsHint.text = "Ten apps at most — type a name to reach any other app."

        shown.forEachIndexed { index, entry ->
            if (index > 0) appsList.addView(divider())
            appsList.addView(appRow(entry))
        }
    }

    /**
     * An app that has been read into its own language carries one small cross on
     * its row, and a tap forgets the choice: the app goes back to the page's
     * TRANSLATE INTO and nothing else moves. The cross is the whole button — no
     * word and no language name — so the row keeps its width and its one line;
     * only apps with a choice of their own draw it, and what it would forget is
     * in the row's description.
     */
    private fun ownLanguage(entry: Entry): ImageView? {
        val own = Prefs.ownTargetFor(this, entry.pkg) ?: return null
        val fallback = Languages.label(Prefs.targetLanguage(this))
        return ImageView(this).apply {
            setImageResource(R.drawable.ic_clear_language)
            background = ContextCompat.getDrawable(this@SettingsActivity, R.drawable.chip_accent_bg)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            isClickable = true
            isFocusable = true
            layoutParams = LinearLayout.LayoutParams(dp(26), dp(26)).apply {
                marginStart = dp(8)
                marginEnd = dp(2)
            }
            contentDescription =
                "Clear ${Languages.label(own)} for ${entry.label} — its reads go back to $fallback"
            Press.attach(this)
            setOnClickListener {
                Prefs.clearTargetFor(this@SettingsActivity, entry.pkg)
                // The cross carries no word of what it removes, so the answer
                // says both halves: what was forgotten, and what is back.
                say("${Languages.label(own)} forgotten — ${entry.label} reads into $fallback")
                paintApps()
            }
        }
    }

    private fun divider(): View = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1))
        setBackgroundColor(color(R.color.screen_divider))
    }

    /** One app: icon, name, and the switch that decides whether the lens comes
     * up over it by itself. */
    private fun appRow(entry: Entry): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(9), 0, dp(9))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        }

        row.addView(
            ImageView(this).apply {
                layoutParams = LinearLayout.LayoutParams(dp(26), dp(26))
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                setImageDrawable(
                    icons.getOrPut(entry.pkg) {
                        runCatching { packageManager.getApplicationIcon(entry.pkg) }.getOrNull()
                    },
                )
            },
        )

        row.addView(
            TextView(this).apply {
                text = entry.label
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                setTextColor(color(R.color.screen_ink))
                textSize = 12.5f
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    .apply { marginStart = dp(11) }
            },
        )
        ownLanguage(entry)?.let { row.addView(it) }

        val toggle = SwitchCompat(this).apply {
            // The package is on the switch itself, so what a switch belongs to
            // can be read back — by a test, and by anyone walking the list.
            tag = entry.pkg
            isChecked = Prefs.autoOpenIn(this@SettingsActivity, entry.pkg)
            thumbTintList = ContextCompat.getColorStateList(this@SettingsActivity, R.color.rules_thumb)
            trackTintList = ContextCompat.getColorStateList(this@SettingsActivity, R.color.rules_track)
            contentDescription = "Open the lens over ${entry.label}"
        }
        toggle.setOnCheckedChangeListener { _, on ->
            Prefs.setAutoApp(this, entry.pkg, on)
        }
        row.addView(toggle)
        return row
    }

    private fun say(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun color(id: Int): Int = ContextCompat.getColor(this, id)

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
