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
    private lateinit var appLangName: TextView
    private lateinit var appLangHint: TextView
    private lateinit var appLangPack: ImageView

    /** The language being fetched right now, or null. One download at a time. */
    private var fetching: String? = null
    private var fetchJob: Job? = null
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
            say("Screen capture is on — the whole screen, every app.")
        } else {
            say("Screen access declined. Text access alone still works everywhere it can read.")
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
        appLangName = findViewById(R.id.settingsAppLangName)
        appLangHint = findViewById(R.id.settingsAppLangHint)
        appLangPack = findViewById(R.id.settingsAppLangPack)
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
        info(R.id.infoAppLang, "App language", R.string.info_app_lang)
        info(R.id.infoTarget, "Translate into", R.string.info_target)
        info(R.id.infoTheme, "Appearance", R.string.info_theme)
        info(R.id.infoPermissions, "What the lens needs", R.string.info_permissions)
        info(R.id.infoApps, "Open the lens when the app opens", R.string.info_apps)

        // App language: the row is the pick, the arrow is the download, and the
        // tour is one tap away because the tour is where the words show.
        val appLang = findViewById<View>(R.id.settingsAppLang)
        val appLangTour = findViewById<View>(R.id.settingsAppLangTour)
        Press.attachAll(appLang, appLangPack, appLangTour)
        appLang.setOnClickListener { showAppLanguageDialog() }
        appLangPack.setOnClickListener { fetchAppLanguage() }
        appLangTour.setOnClickListener {
            startActivity(Intent(this, OnboardingActivity::class.java))
        }

        // Translate into: one row, the language is the control.
        val target = findViewById<View>(R.id.settingsTarget)
        Press.attach(target)
        target.setOnClickListener { showLanguageDialog() }

        // Appearance: three answers on one card, not a switch beside things that
        // only change when the app changes them.
        listOf(
            R.id.themeSystem to ThemeChoice.SYSTEM,
            R.id.themeLight to ThemeChoice.LIGHT,
            R.id.themeDark to ThemeChoice.DARK,
        ).forEach { (id, mode) ->
            val row = findViewById<TextView>(id)
            row.tag = mode
            // The row's own word is the answer, so the label carries it too:
            // a chosen row is announced as chosen, not just read out.
            row.contentDescription = "${row.text} appearance"
            Press.attach(row)
            row.setOnClickListener { chooseTheme(mode) }
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
        // Back from Android's own screens: the switches may have moved. A card
        // left over Settings has served its purpose and must not hover here.
        SetupNudge.hide()
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
        targetName.text = Languages.nativeShort(Prefs.targetLanguage(this))
        appsWarn.visibility =
            if (Settings.canDrawOverlays(this) && LensAccessibilityService.isEnabled(this)) {
                View.GONE
            } else {
                View.VISIBLE
            }
        paintApps()
    }

    /**
     * The appearance card's three rows, with the one in force taken as a pill.
     * The hint under the card says what the pick means out loud, because "System"
     * on its own could be read either way.
     */
    private fun paintTheme() {
        val mode = ThemeChoice.mode(this)
        listOf(R.id.themeSystem, R.id.themeLight, R.id.themeDark).forEach { id ->
            findViewById<TextView>(id).isSelected = findViewById<TextView>(id).tag == mode
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
        btn1.setTextColor(getColor(if (canOverlay) R.color.screen_ok_ink else R.color.screen_ink))
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
        btn2.setTextColor(getColor(if (textAccess) R.color.screen_ok_ink else R.color.screen_ink))
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
        btn3.setTextColor(getColor(if (lookMode) R.color.screen_ok_ink else R.color.screen_ink))
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
        btn4.setTextColor(getColor(if (quickButton) R.color.screen_ok_ink else R.color.screen_ink))
        btn4.isEnabled = !quickButton
        sub4.text = if (quickButton) {
            "In the shade — one tap turns the lens on"
        } else {
            "Adds Lucid to the shade's quick buttons"
        }
    }

    /**
     * A numbered step: the numeral alone while it is still to do, and a filled
     * olive disc with the numeral knocked out of it once Android has granted it.
     * Filled rather than merely tinted, so the two states are told apart by shape
     * as much as colour — the page is read at a glance, often outdoors.
     */
    private fun paintStep(chip: TextView, done: Boolean) {
        chip.setBackgroundResource(if (done) R.drawable.step_chip_done else R.drawable.step_chip_pending)
        chip.setTextColor(getColor(if (done) R.color.screen_canvas else R.color.screen_accent))
    }

    /* ── the question marks ──────────────────────────────────────────── */

    /** One heading's explanation: the mark asks, this sheet answers. */
    private fun info(markId: Int, title: String, bodyRes: Int) {
        val mark = findViewById<TextView>(markId)
        mark.contentDescription = getString(R.string.info_mark) + " — " + title
        Press.attach(mark)
        mark.setOnClickListener { showInfo(title, getString(bodyRes)) }
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
        // The list this opens is the furthest an app may go; the card says which
        // section holds Lucid's row, in the phone's own words.
        SetupNudge.show(this)
        // Turning it on is the answer to this page's question, so the service —
        // which is bound the moment the switch flips — brings this page back.
        AccessReturn.arm(this, javaClass)
        val opened = runCatching {
            accessibilityLauncher.launch(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        if (opened.isFailure) {
            say("Couldn't open Accessibility settings — find Lucid in Settings › Accessibility.")
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
        val ready = UiText.has(this, code)
        appLangName.text = name
        appLangPack.visibility = if (builtIn) View.GONE else View.VISIBLE
        appLangPack.setImageResource(if (ready) R.drawable.ic_check else R.drawable.ic_download)
        appLangPack.alpha = if (fetching != null) 0.4f else 1f
        appLangPack.isEnabled = !ready && fetching == null
        appLangPack.contentDescription = when {
            ready -> "$name is on this phone"
            else -> "Download $name"
        }
        appLangHint.text = when {
            builtIn -> "The app's own words — English is built in."
            fetching == code -> appLangHint.text
            ready -> "$name is on this phone — the tour reads in it."
            else -> "Not on this phone — tap the arrow to fetch $name."
        }
    }

    private fun showAppLanguageDialog() {
        val dialog = Dialog(this)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.setContentView(
            LanguagePicker.build(this, Prefs.uiLanguage(this)) { lang ->
                Prefs.setUiLanguage(this, lang.code)
                dialog.dismiss()
                paintAppLanguage()
            },
        )
        dialog.window?.setLayout(
            (resources.displayMetrics.widthPixels * 0.86f).toInt(),
            WindowManager.LayoutParams.WRAP_CONTENT,
        )
        dialog.show()
    }

    /**
     * Translates the app's word list into the chosen language, once.
     *
     * The work is a handful of small requests against the engine the lens
     * already uses, so it is a download in the ordinary sense: Wi-Fi, a few
     * seconds, and then it is on the phone for good. Progress is counted in
     * lines rather than guessed at, and a language that cannot be answered
     * leaves the row exactly as it was.
     */
    private fun fetchAppLanguage() {
        val code = Prefs.uiLanguage(this)
        if (code == "en" || fetching != null || UiText.has(this, code)) return
        fetching = code
        paintAppLanguage()
        appLangHint.text = "Fetching ${Languages.nativeShort(code)}…"
        fetchJob = CoroutineScope(Dispatchers.Main).launch {
            val result = UiText.download(this@SettingsActivity, code) { done, total ->
                appLangHint.text = "Fetching ${Languages.nativeShort(code)}… $done/${total}"
            }
            fetching = null
            paintAppLanguage()
            if (isFinishing || isDestroyed) return@launch
            result.onSuccess { count ->
                say("$count lines in ${Languages.nativeShort(code)} — the tour reads in it now.")
            }.onFailure {
                say("Couldn't reach the translation service. Try again on Wi-Fi.")
            }
        }
    }

    override fun onDestroy() {
        fetchJob?.cancel()
        super.onDestroy()
    }

    /* ── translate into ──────────────────────────────────────────────── */

    private fun showLanguageDialog() {
        val dialog = Dialog(this)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.setContentView(
            LanguagePicker.build(this, Prefs.targetLanguage(this)) { lang ->
                Prefs.setTargetLanguage(this, lang.code)
                targetName.text = Languages.label(lang.code)
                Toast.makeText(this, "Translating into ${lang.label}", Toast.LENGTH_SHORT).show()
                dialog.dismiss()
            },
        )
        dialog.window?.setLayout(
            (resources.displayMetrics.widthPixels * 0.86f).toInt(),
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
