package com.grafizum.lucid

import android.Manifest
import android.animation.ValueAnimator
import android.app.ActivityManager
import android.app.Dialog
import android.content.Intent
import android.content.res.ColorStateList
import android.content.res.Configuration
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
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import androidx.core.widget.NestedScrollView
import androidx.core.view.ViewCompat
import android.view.animation.AccelerateInterpolator
import android.view.animation.Interpolator
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import kotlin.math.cos
import kotlin.math.exp
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatDelegate
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.play.core.review.ReviewManagerFactory
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.translate.TranslateRemoteModel

class MainActivity : AppCompatActivity() {

    companion object {
        /** The launcher shortcut: put the lens on screen without opening the page. */
        const val ACTION_START_LENS = "com.grafizum.lucid.action.START_LENS"
    }

    private lateinit var autoBtn: TextView
    private lateinit var autoHint: TextView
    private lateinit var engBtn: TextView
    private lateinit var engHint: TextView
    private lateinit var startBtn: SwipeToStart
    private lateinit var stopX: FrameLayout
    private lateinit var stopXBtn: FrameLayout
    private lateinit var offlineBtn: TextView
    private lateinit var offlineHint: TextView
    private lateinit var modelsHint: TextView
    private lateinit var historyHint: TextView


    /**
     * Whether the setup sheet has been offered on this screen yet. Held here so
     * the question is asked once — after the onboarding, not over it.
     */
    private var setupAsked = false

    /** Whether the cross is currently sitting beside the slide. */
    private var stopXShown = false
    private var stopXAnim: ValueAnimator? = null

    /** The one-time setup sheet, so returning from Settings can update it. */
    private var setupDialog: Dialog? = null

    /** Which place in Android the guide sheet is pointing at. */
    private enum class Ask { OVERLAY, TEXT, CAPTURE }

    /** Where the disclosure should lead, when it was reached on the way somewhere. */
    private var pendingAsk: Ask? = null

    /** The breathing ring on the sheet's drawing, stopped when it closes. */
    private var guidePulse: ValueAnimator? = null

    /**
     * The Settings screen the app has just sent the user to, if any. While it is
     * open the switch is polled, so the app can come straight back the moment it
     * is allowed instead of leaving the user to find their own way home.
     */
    private var waitingFor: Ask? = null
    private var waitTicks = 0
    private val waitHandler = Handler(Looper.getMainLooper())
    private val watch = object : Runnable {
        override fun run() {
            val ask = waitingFor ?: return
            if (granted(ask)) {
                waitingFor = null
                comeBack(ask)
                return
            }
            // Two minutes of watching; after that the way back is their own.
            if (++waitTicks > 240) {
                waitingFor = null
                return
            }
            waitHandler.postDelayed(this, 500)
        }
    }

    private val overlaySettingsLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { refresh(); bindSetupDialog() }

    private val accessibilityLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { startBtn.postDelayed({ refresh(); bindSetupDialog() }, 400) }

    private val notificationLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { }

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
            startBtn.postDelayed({ refresh() }, 900)
        } else {
            say("Screen access declined. Text access alone still works everywhere it can read.")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // The theme is put on before anything is inflated, so a dark choice is
        // already true on the first frame of the next launch — not a light page
        // that flips over after it has been drawn.
        AppCompatDelegate.setDefaultNightMode(ThemeChoice.nightMode(this))
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        // A theme chosen from the corner rebuilds this page; the picture kept
        // before the rebuild dissolves the old page over the new one.
        ThemeFade.reveal(this)

        autoBtn = findViewById(R.id.autoBtn)
        autoHint = findViewById(R.id.autoHint)
        engBtn = findViewById(R.id.engBtn)
        engHint = findViewById(R.id.engHint)
        offlineBtn = findViewById(R.id.offlineBtn)
        offlineHint = findViewById(R.id.offlineHint)
        modelsHint = findViewById(R.id.modelsHint)
        historyHint = findViewById(R.id.historyHint)
        startBtn = findViewById(R.id.startBtn)
        stopX = findViewById(R.id.stopX)
        stopXBtn = findViewById(R.id.stopXBtn)
        startBtn.startLabel = UiText.t(this, "tour.start")
        startBtn.stopLabel = UiText.line(this, "Stop the lens")

        // Settings, in the corner: the page where everything the app can be
        // told lives — the switches Android asks for, the language every read is
        // answered in, the apps that open the lens by themselves, and what has
        // been read.
        val settingsBtn = findViewById<View>(R.id.settingsBtn)
        Press.attach(settingsBtn)
        settingsBtn.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        // Every control answers a press the same way: it gives under the finger
        // and springs back. No ripples, no grey boxes — see Press.kt.
        Press.attachAll(autoBtn, engBtn, offlineBtn, stopXBtn)

        // The slide reads the way it is facing at the moment it lands, so one
        // control starts the lens and, from its other seat, switches it off.
        // It reports that seat itself: the control has already handed over by
        // the time the action runs.
        startBtn.onComplete = { done ->
            if (done == SwipeToStart.Mode.START) startLens() else stopLens()
        }
        stopXBtn.setOnClickListener {
            stopXBtn.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            stopLens()
        }
        autoBtn.setOnClickListener { toggleAuto() }
        engBtn.setOnClickListener { toggleEngine() }
        offlineBtn.setOnClickListener { toggleOfflineOnly() }
        findViewById<View>(R.id.modelsRow).setOnClickListener { showModels() }
        findViewById<View>(R.id.historyRow).setOnClickListener {
            startActivity(Intent(this, HistoryActivity::class.java))
        }

        // Edge to edge, with the bars paid for in padding instead of left to
        // fitsSystemWindows: the bottom line — version and grafizum.com — has to
        // clear the gesture bar on every device, not just most of them.
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val root = findViewById<ViewGroup>(R.id.root)
        @Suppress("DEPRECATION")
        val sheet = findViewById<NestedScrollView>(R.id.sheetScroll)
        sheet.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {
                v.removeOnAttachStateChangeListener(this)
                // ViewCompat, not View#getRootWindowInsets: that call only
                // exists from Android 11, and this runs on every launch, so on
                // Android 7 to 10 it was a crash, not a missing inset.
                val sheetBars = ViewCompat.getRootWindowInsets(v)?.getInsets(
                    WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout(),
                ) ?: return
                v.setPaddingRelative(0, sheetBars.top, 0, sheetBars.bottom)
            }
            override fun onViewDetachedFromWindow(v: View) {}
        })
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout(),
            )
            view.setPaddingRelative(
                dp(32) + bars.left,
                dp(30) + bars.top,
                dp(32) + bars.right,
                dp(18) + bars.bottom,
            )
            insets
        }

        window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
        window.statusBarColor = Color.TRANSPARENT

        refresh()
        // A first launch meets the welcome and then the six loops, and the
        // setup question waits until those are done: a permission sheet on top
        // of an animation is a sheet nobody reads.
        val firstRun = !Prefs.onboardingSeen(this)
        setupAsked = !firstRun
        if (firstRun && savedInstanceState == null) {
            // A real first launch, not a page just rebuilt: a theme change
            // rebuilds this screen, and that is not the app being opened for
            // the first time — the welcome would flash up over the tour, or
            // over the page the choice was made on.
            startActivity(Intent(this, WelcomeActivity::class.java))
        } else if (savedInstanceState == null) {
            // Asked as the app opens: the switches are off until they are allowed.
            // Only a real open, though — a screen rebuilt under the person is not
            // an open, and a sheet arriving on a rebuilt page is a flicker.
            startBtn.postDelayed({ askForSetup() }, 350)
        }

        // Age assurance, once the first frame is up rather than before it: Play
        // is asked whether this install may share its age range and the answer
        // is only logged. The call is never awaited and changes nothing on
        // screen — Lucid has no age gate to apply it to.
        window.decorView.post { AgeSignalsHelper.requestAgeSignals(this) }

        // The launcher shortcut asks for the lens itself, not for this page.
        handleShortcut(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleShortcut(intent)
    }

    /**
     * Comes from the home-screen shortcut. With both switches on the lens goes up
     * and the page steps out of the way; with one off, the page stays, because
     * that is the only place the switches can be found.
     */
    private fun handleShortcut(intent: Intent?) {
        if (intent?.action != ACTION_START_LENS) return
        intent.action = null
        if (!Settings.canDrawOverlays(this) || !LensAccessibilityService.isEnabled(this)) {
            startBtn.postDelayed({ askForSetup() }, 300)
            return
        }
        startLens()
        moveTaskToBack(true)
    }

    override fun onResume() {
        super.onResume()
        // Back in the app: a card left over Settings has done its job either way,
        // and it must never hang over this page.
        SetupNudge.hide()
        // In front again: if a switch was flipped on the way back, carry on with
        // the next one; otherwise there is nothing left to watch.
        val finished = waitingFor
        stopWaiting()
        if (finished != null && granted(finished)) {
            startBtn.postDelayed({ nextStep(finished) }, 450)
        }
        // The shade keeps the last picture it was handed, and a stopped
        // process cannot repaint it — after an update, a force-stop or the
        // system ending the session, the button can be left reading "on" over
        // an empty screen. Opening Lucid settles it back to the truth.
        LensTileService.refresh(this)
        // And a button put in by hand is only ever discovered by the shade
        // binding it, so ask for that bind now rather than after the person has
        // opened the shade — the row says "Added" as they land back here.
        LensTileService.ask(this)
        // Coming back from a system screen means its switch may have changed.
        refresh()
        bindSetupDialog()
        // Asked from the page the person is already on, never over another app.
        maybeAskForReview()
        // The tour asks for the switch itself, on its last loop, and Skip walks
        // out of it without one. So leaving the tour no longer opens the sheet:
        // the rows on this page carry the same two buttons from here on.
        if (!setupAsked && Prefs.onboardingSeen(this)) setupAsked = true
        LensState.observe { runOnUiThread { refresh() } }
    }

    override fun onPause() {
        super.onPause()
        LensState.observe(null)
    }

    override fun onDestroy() {
        stopWaiting()
        super.onDestroy()
    }

    /* ── actions ─────────────────────────────────────────────────────── */

    private fun openOverlaySettings() {
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:$packageName"),
        )
        val opened = runCatching { overlaySettingsLauncher.launch(intent) }
        if (opened.isSuccess) {
            startWaiting(Ask.OVERLAY)
        } else {
            say("Couldn't open that screen — go to Settings › Apps › Special access › Display over other apps.")
        }
    }

    /**
     * Text access is never asked for straight from the switch: the sheet that
     * says what the lens reads, and where that text goes, comes first, and
     * Android's own screen follows the agree. That disclosure is the app's,
     * separate from the system prompt and from the other switches.
     */
    private fun openAccessibilitySettings() {
        if (!Prefs.textConsent(this) && !LensAccessibilityService.isEnabled(this)) {
            // The agreement is a step of this same ask, so the way there is kept.
            askForSetup(Ask.TEXT)
            return
        }

        // Android's own accessibility screen. There is no per-service page an app
        // is allowed to open — that one is guarded by a system permission — so
        // what can be given is a card over the list, naming the section Lucid's
        // row sits in on this phone. The page is also written down as the way
        // back: the granted service returns here on its own, the switch on.
        SetupNudge.show(this)
        AccessReturn.arm(this, javaClass)
        val opened = runCatching {
            accessibilityLauncher.launch(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        if (opened.isSuccess) {
            startWaiting(Ask.TEXT)
        } else {
            say("Couldn't open Accessibility settings — find Lucid in Settings › Accessibility.")
        }
    }

    /** Android's own capture dialog, once the sheet has said what to pick. */
    private fun startCapture() {
        val manager = getSystemService(MediaProjectionManager::class.java)
        val intent = if (Capabilities.framesLeaveOverlaysOut) {
            wholeDisplay(manager)
        } else {
            manager.createScreenCaptureIntent()
        }
        projectionLauncher.launch(intent)
    }

    /**
     * Android 14 can be asked for the whole display by name, so its dialog never
     * offers the single-app option. Kept in its own method so an older phone
     * never has to resolve [MediaProjectionConfig], which it does not have.
     */
    @androidx.annotation.RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private fun wholeDisplay(manager: MediaProjectionManager) =
        manager.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())


    private fun startLens() {
        // Neither switch can be worked around: without one the lens has nowhere
        // to sit, without the other it reads nothing. So this is a decision, said
        // once in a sheet, not a line that waits under the slide.
        // The slide has already turned itself round for a lens that will not
        // start, so it is told the truth again a beat later: nothing to stop.
        if (!Settings.canDrawOverlays(this)) {
            askPermission(Ask.OVERLAY)
            startBtn.postDelayed({ refresh() }, 420)
            return
        }

        if (!LensAccessibilityService.isEnabled(this)) {
            askPermission(Ask.TEXT)
            startBtn.postDelayed({ refresh() }, 420)
            return
        }

        LensService.start(this)
        startBtn.postDelayed({ refresh() }, 500)
    }

    /**
     * What the app says when a switch is still off, or when Android is about to
     * ask to share the screen: why it matters, and a drawing of the screen the
     * user is about to meet with the one control ringed and captioned. Nothing
     * is left to be found by reading through Settings.
     */
    private fun askPermission(ask: Ask) {
        val dialog = Dialog(this)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.setContentView(R.layout.dialog_permission)
        dialog.window?.setLayout(
            (resources.displayMetrics.widthPixels * 0.86f).toInt(),
            WindowManager.LayoutParams.WRAP_CONTENT,
        )
        dialog.window?.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        dialog.window?.setDimAmount(0.42f)

        dialog.findViewById<TextView>(R.id.permTitle).text = when (ask) {
            Ask.OVERLAY -> "The lens needs to sit on top"
            Ask.TEXT -> "The lens needs Text access"
            Ask.CAPTURE -> "One screen to pick"
        }
        dialog.findViewById<TextView>(R.id.permBody).text = when (ask) {
            Ask.OVERLAY ->
                "Android keeps \u201CDisplay over other apps\u201D off. Without it the lens cannot float over a chat or a browser, so it does nothing at all."
            Ask.TEXT ->
                "Android keeps Text access off. Without it the lens still opens, but it reads nothing \u2014 every read comes back empty."
            Ask.CAPTURE ->
                "Android asks which part of the screen to share. The lens needs the whole screen to read anything sitting on top of other apps."
        }

        // The drawing follows the real screen: its title, the one row that
        // matters, and the way there.
        dialog.findViewById<TextView>(R.id.guidePageLabel).text = when (ask) {
            Ask.OVERLAY -> "Display over other apps"
            Ask.TEXT -> "Accessibility"
            Ask.CAPTURE -> "Start recording or casting"
        }
        dialog.findViewById<TextView>(R.id.guideRowTitle).text = when (ask) {
            Ask.OVERLAY -> "Lucid"
            Ask.TEXT -> getString(R.string.a11y_label)
            Ask.CAPTURE -> "Entire screen"
        }
        dialog.findViewById<TextView>(R.id.guideRowSub).text = when (ask) {
            Ask.OVERLAY -> "Tap this to reach its switch"
            // Named the way this phone's own list names it: the drawn row is
            // meant to be recognised in Settings, not merely looked at.
            Ask.TEXT -> UiText.line(this, "%s · Off", SetupNudge.sectionName(this))
            Ask.CAPTURE -> "So the lens sees every app, not one"
        }
        dialog.findViewById<TextView>(R.id.guideCaption).text = when (ask) {
            Ask.OVERLAY -> "\u2191 tap Lucid, then switch it on"
            Ask.TEXT -> "\u2191 tap it, then turn it on"
            Ask.CAPTURE -> "then this one \u2191"
        }
        dialog.findViewById<TextView>(R.id.guidePath).text = when (ask) {
            Ask.OVERLAY -> "Settings \u203A Apps \u203A Special access \u203A Display over other apps"
            Ask.TEXT -> UiText.line(
                this,
                "Settings › Accessibility › %s › %s",
                SetupNudge.sectionName(this),
                getString(R.string.a11y_label),
            )
            Ask.CAPTURE -> "Pick Entire screen, then Start now \u2014 a single app will not do."
        }
        val note = dialog.findViewById<TextView>(R.id.guideNote)
        // Sideloaded builds are greyed out until Android's own restriction is
        // lifted, which is the one trap this screen has.
        note.visibility = if (ask == Ask.TEXT) View.VISIBLE else View.GONE

        // Both Settings screens are lists, so the ringed row is the target; only
        // Android's own dialog has a button instead.
        dialog.findViewById<View>(R.id.guidePillWrap).visibility =
            if (ask == Ask.CAPTURE) View.VISIBLE else View.GONE

        val open = dialog.findViewById<TextView>(R.id.permOpen)
        val later = dialog.findViewById<TextView>(R.id.permLater)
        // Text access is disclosed before Android's switch, so someone who has
        // not agreed yet is sent to the disclosure rather than promised a screen
        // they cannot reach in one tap.
        open.text = when {
            ask == Ask.CAPTURE -> UiText.line(this, "Continue")
            ask == Ask.TEXT && !Prefs.textConsent(this) -> UiText.line(this, "Read and agree")
            else -> UiText.line(this, "Open settings")
        }
        Press.attachAll(open, later)
        open.setOnClickListener {
            dialog.dismiss()
            when (ask) {
                Ask.OVERLAY -> openOverlaySettings()
                Ask.TEXT -> openAccessibilitySettings()
                Ask.CAPTURE -> startCapture()
            }
        }
        later.setOnClickListener { dialog.dismiss() }

        dialog.setOnDismissListener { guidePulse?.cancel() }
        dialog.window?.decorView?.let { UiText.apply(this, it) }
        dialog.show()
        pulse(dialog.findViewById(R.id.guideRing), dialog.findViewById(R.id.guidePillRing))
    }

    /** The ring breathes, so the eye lands on it before a word is read. */
    private fun pulse(vararg rings: View) {
        guidePulse?.cancel()
        guidePulse = ValueAnimator.ofFloat(0.3f, 1f).apply {
            duration = 900
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            addUpdateListener { a ->
                val alpha = a.animatedValue as Float
                rings.forEach { it.alpha = alpha }
            }
            start()
        }
    }

    private fun stopLens() {
        LensService.stop(this)
        startBtn.postDelayed({ refresh() }, 400)
    }

    private fun toggleEngine() {
        val next = !Prefs.onlineEngine(this)
        Prefs.setOnlineEngine(this, next)
        applyEngine(next)
    }

    private fun applyEngine(online: Boolean) {
        engBtn.text = if (online) "Online" else "On-device"
        engBtn.setBackgroundResource(if (online) R.drawable.chip_accent_bg else R.drawable.btn_ghost)
        // A state label is read at 11sp, so it is never the muted grey: the fill
        // and the hairline already say which state the chip is in.
        engBtn.setTextColor(getColor(if (online) R.color.screen_accent_ink else R.color.screen_ink))
        engHint.text = if (online) {
            "Best quality \u2014 sent online to translate"
        } else {
            "Offline models \u2014 nothing leaves the phone"
        }
    }

    /**
     * Offline only. A promise rather than a preference: with it on, a read that
     * would have to leave the phone says so instead of going, whatever ENGINE
     * says — so ENGINE is dimmed while it is on, because it no longer decides.
     */
    private fun toggleOfflineOnly() {
        val next = !Prefs.offlineOnly(this)
        Prefs.setOfflineOnly(this, next)
        applyOfflineOnly(next)
    }

    private fun applyOfflineOnly(on: Boolean) {
        offlineBtn.text = if (on) "On" else "Off"
        offlineBtn.setBackgroundResource(if (on) R.drawable.chip_accent_bg else R.drawable.btn_ghost)
        offlineBtn.setTextColor(getColor(if (on) R.color.screen_accent_ink else R.color.screen_ink))
        offlineHint.text = if (on) {
            "On — nothing leaves the phone, whatever the engine"
        } else {
            "No read ever leaves the phone"
        }
        engBtn.alpha = if (on) 0.45f else 1f
        engBtn.isEnabled = !on
    }

    /** The row that says what is installed on the phone right now. */
    private fun refreshSidePanels() {
        RemoteModelManager.getInstance()
            .getDownloadedModels(TranslateRemoteModel::class.java)
            .addOnSuccessListener { models ->
                val count = models.map { it.language }.distinct().size
                modelsHint.text = when (count) {
                    0 -> UiText.line(this, "No packs downloaded yet")
                    1 -> UiText.line(this, "1 pack downloaded")
                    else -> UiText.line(this, "%d packs downloaded", count)
                }
            }
            .addOnFailureListener { modelsHint.text = UiText.line(this, "Downloaded language packs") }
    }

    /**
     * The offline languages, on their own page: search a language, add its pack,
     * or take one back. The page reads its truth from ML Kit itself, so nothing
     * about the packs is duplicated here.
     */
    private fun showModels() {
        startActivity(Intent(this, ModelsActivity::class.java))
    }

    /**
     * The rating sheet, asked once and only after the lens has actually answered
     * several reads: a prompt on a first launch is a prompt nobody means.
     */
    private fun maybeAskForReview() {
        if (Prefs.reviewAsked(this) || Prefs.goodReads(this) < 8) return
        // Recorded before the ask: whether the sheet is shown or not, it is asked
        // once, and a dismissal is an answer.
        Prefs.setReviewAsked(this, true)

        val manager = ReviewManagerFactory.create(this)
        manager.requestReviewFlow().addOnCompleteListener { task ->
            if (task.isSuccessful) manager.launchReviewFlow(this, task.result)
        }
    }

    private fun toggleAuto() {
        val next = !Prefs.autoTranslate(this)
        Prefs.setAutoTranslate(this, next)
        applyAuto(next)
    }

    private fun applyAuto(enabled: Boolean) {
        autoBtn.text = if (enabled) "On" else "Off"
        autoBtn.setBackgroundResource(if (enabled) R.drawable.chip_accent_bg else R.drawable.btn_ghost)
        autoBtn.setTextColor(
            getColor(if (enabled) R.color.screen_accent_ink else R.color.screen_ink),
        )
        autoHint.text =
            if (enabled) "Reads the moment the lens stops moving" else "Only reads when you let go of the lens"
    }

    private fun askNotificationPermission() {
        if (Capabilities.asksForNotifications) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
                android.content.pm.PackageManager.PERMISSION_GRANTED
            ) {
                notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    /**
     * Whether the switch a place in Android leads to is on. Watching one that is
     * already on would yank the user out of Settings a moment after opening it,
     * so nothing is ever watched unless it is still off.
     */
    private fun granted(ask: Ask): Boolean = when (ask) {
        Ask.OVERLAY -> Settings.canDrawOverlays(this)
        Ask.TEXT -> LensAccessibilityService.isEnabled(this)
        Ask.CAPTURE -> true
    }

    private fun startWaiting(ask: Ask) {
        if (granted(ask)) return
        waitingFor = ask
        waitTicks = 0
        waitHandler.removeCallbacks(watch)
        waitHandler.postDelayed(watch, 500)
    }

    private fun stopWaiting() {
        waitHandler.removeCallbacks(watch)
        waitingFor = null
        waitTicks = 0
    }

    /**
     * Back to the front the moment the switch is allowed. A granted overlay
     * permission is one of the exemptions Android leaves open for exactly this,
     * which is why it is only ever needed while one of these two is being turned on.
     */
    private fun comeBack(from: Ask) {
        runCatching { getSystemService(ActivityManager::class.java)?.moveTaskToFront(taskId, 0) }
        runCatching {
            startActivity(
                Intent(this, MainActivity::class.java).addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT,
                ),
            )
        }
        refresh()
        // Setup is one run, not a series of errands: on to the next switch.
        startBtn.postDelayed({ nextStep(from) }, 700)
    }

    /** What the setup still needs, once a switch has been allowed. */
    private fun nextStep(from: Ask) {
        if (isFinishing || isDestroyed) return
        when {
            !Settings.canDrawOverlays(this) ->
                if (from != Ask.OVERLAY) askPermission(Ask.OVERLAY)
            !LensAccessibilityService.isEnabled(this) ->
                if (from != Ask.TEXT) askPermission(Ask.TEXT)
            // Where the phone has no screenshot API of its own, pixels only come
            // through a screen share, so it is asked as the last step of the same
            // setup instead of being left as a row to be found later.
            Capabilities.needsScreenShare && !LensService.hasCapture && from != Ask.CAPTURE ->
                askPermission(Ask.CAPTURE)
            else -> askNotificationPermission()
        }
    }

    /* ── state ───────────────────────────────────────────────────────── */

    private fun refresh() {
        val canOverlay = Settings.canDrawOverlays(this)
        val textAccess = LensAccessibilityService.isEnabled(this)
        // A lens put away from the shade's button is not running *on screen*:
        // the slide offers "Start the lens" and brings it back.
        val running = LensService.isLensVisible
        val lookMode = LensService.hasCapture

        // The count is the whole row's promise: it says whether opening it is
        // worth the tap.
        val reads = Prefs.history(this).size
        historyHint.text = when (reads) {
            0 -> UiText.line(this, "What the lens has read, kept on this phone")
            1 -> UiText.line(this, "1 read kept on this phone")
            else -> UiText.line(this, "%d reads kept on this phone", reads)
        }

        applyAuto(Prefs.autoTranslate(this))
        applyEngine(Prefs.onlineEngine(this))
        applyOfflineOnly(Prefs.offlineOnly(this))
        refreshSidePanels()

        // Off: the well sits left and drags right. On: it sits right, drags
        // left, and the cross appears beside it as the other way to stop.
        startBtn.mode = if (running) SwipeToStart.Mode.STOP else SwipeToStart.Mode.START
        applyStopX(running)

        // Everything above was written in English; this gives the page the
        // language the person fetched, if there is one.
        UiText.apply(this, findViewById(R.id.root))
    }

    /* ── the one-time setup ask ──────────────────────────────────────── */

    /**
     * Both switches ship off. The first thing the app does is ask for the one
     * or two that are still off, in the same sheet, with a single tap through
     * to Android's own screen for each.
     */
    private fun askForSetup(resumeTo: Ask? = null) {
        if (Settings.canDrawOverlays(this) && LensAccessibilityService.isEnabled(this)) {
            // Older phones need the screen share before a photo can be read at
            // all, so it is asked here once; after that the row on the page owns
            // it and the app never brings it up again by itself.
            if (Capabilities.needsScreenShare && !Prefs.lookMode(this)) {
                askPermission(Ask.CAPTURE)
            } else {
                askNotificationPermission()
            }
            return
        }
        if (setupDialog?.isShowing == true) return
        pendingAsk = resumeTo

        val dialog = Dialog(this)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.setContentView(R.layout.dialog_setup)
        dialog.window?.setLayout(
            (resources.displayMetrics.widthPixels * 0.86f).toInt(),
            WindowManager.LayoutParams.WRAP_CONTENT,
        )
        dialog.window?.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        dialog.window?.setDimAmount(0.42f)
        val setupBtn1 = dialog.findViewById<View>(R.id.setupBtn1)
        val setupBtn2 = dialog.findViewById<View>(R.id.setupBtn2)
        val setupLater = dialog.findViewById<View>(R.id.setupLater)
        val consentAgree = dialog.findViewById<View>(R.id.consentAgree)
        val consentDecline = dialog.findViewById<View>(R.id.consentDecline)
        Press.attachAll(setupBtn1, setupBtn2, setupLater, consentAgree, consentDecline)
        // The sheet asks; the guide sheet then shows exactly where the switch
        // is before Android's own screen takes over.
        setupBtn1.setOnClickListener {
            dialog.dismiss()
            askPermission(Ask.OVERLAY)
        }
        setupBtn2.setOnClickListener {
            dialog.dismiss()
            askPermission(Ask.TEXT)
        }
        setupLater.setOnClickListener { dialog.dismiss() }
        // The agreement is the app's own, taken before Android's switch, and
        // only then does the text access row appear.
        consentAgree.setOnClickListener { afterAgree(dialog) }
        consentDecline.setOnClickListener { dialog.dismiss() }
        dialog.setOnDismissListener {
            if (setupDialog === dialog) setupDialog = null
            // No notification ask here: closing this sheet now means going on to
            // the drawn guide, and a system prompt would cut across it. It is
            // asked once both switches are on, which is where it belongs.
        }

        setupDialog = dialog
        dialog.window?.decorView?.let { UiText.apply(this, it) }
        dialog.show()
        bindSetupDialog(dialog)
    }

    /**
     * Agreement is the answer the disclosure was waiting for, so it leads
     * straight on: to the switch it unlocks, or to the same guide every other
     * text-access ask opens with — never back to the sheet already read.
     */
    private fun afterAgree(dialog: Dialog) {
        Prefs.setTextConsent(this, true)
        dialog.dismiss()
        val next = pendingAsk
        pendingAsk = null
        when {
            next == Ask.TEXT -> openAccessibilitySettings()
            !Settings.canDrawOverlays(this) -> askPermission(Ask.OVERLAY)
            !LensAccessibilityService.isEnabled(this) -> askPermission(Ask.TEXT)
        }
    }

    /** Keeps the open sheet honest while the user is over in Settings. */
    private fun bindSetupDialog(dialog: Dialog? = setupDialog) {
        if (dialog == null || !dialog.isShowing) return

        val canOverlay = Settings.canDrawOverlays(this)
        val textAccess = LensAccessibilityService.isEnabled(this)
        if (canOverlay && textAccess) {
            dialog.dismiss()
            return
        }

        val both = !canOverlay && !textAccess
        val consented = Prefs.textConsent(this) || textAccess

        dialog.findViewById<TextView>(R.id.setupTitle).text = when {
            !consented -> "Text access, explained"
            both -> "Allow, swipe, read"
            else -> "One switch left"
        }
        dialog.findViewById<TextView>(R.id.setupBody).text = when {
            !consented ->
                "Agree to what the lens reads, then allow Android's switch. Nothing is recorded and no frame is saved."
            both -> "Two permissions, one swipe, and the lens is on. Nothing is recorded and no frame is saved."
            else -> "Android keeps it off until you allow it — nothing is recorded, no frame is saved."
        }

        // The whole of onboarding, one line per step, in the order they happen:
        // let it read, swipe to start, put the lens on text. Nothing to learn
        // beyond that.
        dialog.findViewById<TextView>(R.id.setupSteps).text = when {
            !consented ->
                "1  Read and agree to what Text access does\n2  Allow the switches\n3  Swipe \u201CStart the lens\u201D to the right"
            both ->
                "1  Allow both switches\n2  Swipe \u201CStart the lens\u201D to the right\n3  Drag the lens onto any text \u2014 that\u2019s it"
            else ->
                "1  Allow the switch\n2  Swipe \u201CStart the lens\u201D to the right\n3  Drag the lens onto any text \u2014 that\u2019s it"
        }

        dialog.findViewById<View>(R.id.setupConsent).visibility =
            if (consented) View.GONE else View.VISIBLE
        dialog.findViewById<View>(R.id.setupRow1).visibility =
            if (canOverlay) View.GONE else View.VISIBLE
        // No text access switch before the disclosure has been agreed to.
        dialog.findViewById<View>(R.id.setupRow2).visibility =
            if (textAccess || !consented) View.GONE else View.VISIBLE
    }

    private fun applyStopX(show: Boolean, animate: Boolean = true) {
        if (show == stopXShown) return
        stopXShown = show
        stopXAnim?.cancel()

        val size = dp(52)
        val gap = dp(12)
        val lp = stopX.layoutParams as ViewGroup.MarginLayoutParams
        if (!animate) {
            lp.width = if (show) size else 0
            lp.marginStart = if (show) gap else 0
            stopX.layoutParams = lp
            stopX.visibility = if (show) View.VISIBLE else View.GONE
            stopXBtn.isEnabled = show
            stopXBtn.alpha = if (show) 1f else 0f
            stopXBtn.scaleX = 1f
            stopXBtn.scaleY = 1f
            return
        }

        if (show) stopX.visibility = View.VISIBLE
        stopXBtn.isEnabled = false
        val fromW = lp.width.coerceAtLeast(0)
        val fromM = lp.marginStart
        val toW = if (show) size else 0
        val toM = if (show) gap else 0
        val fromAlpha = if (show) 0f else stopXBtn.alpha
        val toAlpha = if (show) 1f else 0f

        stopXAnim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = if (show) 300 else 190
            interpolator = if (show) spring else AccelerateInterpolator(1.6f)
            addUpdateListener { a ->
                val t = a.animatedValue as Float
                lp.width = (fromW + (toW - fromW) * t).toInt()
                lp.marginStart = (fromM + (toM - fromM) * t).toInt()
                stopX.layoutParams = lp
                stopXBtn.alpha = fromAlpha + (toAlpha - fromAlpha) * t
                stopXBtn.scaleX = 0.72f + 0.28f * t
                stopXBtn.scaleY = stopXBtn.scaleX
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    lp.width = toW
                    lp.marginStart = toM
                    stopX.layoutParams = lp
                    stopXBtn.alpha = toAlpha
                    stopXBtn.scaleX = 1f
                    stopXBtn.scaleY = 1f
                    stopXBtn.isEnabled = show
                    stopX.visibility = if (show) View.VISIBLE else View.GONE
                }
            })
            start()
        }
    }

    private val spring: Interpolator = Interpolator { t ->
        (1.0 - exp(-6.0 * t) * cos(9.0 * t)).toFloat()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    /**
     * A passing word, where nothing was decided. The space under the slide
     * belongs to the version and grafizum.com alone — never a line of text.
     */
    private fun say(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}
