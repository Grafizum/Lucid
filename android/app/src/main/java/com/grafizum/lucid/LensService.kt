package com.grafizum.lucid

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Point
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.MotionEvent
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.view.View
import android.view.animation.OvershootInterpolator
import android.view.animation.PathInterpolator
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs
import java.util.Locale

/**
 * The lens session.
 *
 * Owns the floating bubble, the capture frame and the translation card, and
 * runs the read → OCR → translate pipeline exactly once per tap.
 */
class LensService : Service() {

    companion object {
        const val ACTION_START = "com.grafizum.lucid.action.START"
        const val ACTION_STOP = "com.grafizum.lucid.action.STOP"

        /** Takes the lens off screen and leaves the session warm behind it. */
        const val ACTION_HIDE = "com.grafizum.lucid.action.HIDE"

        /** The one utterance the card's speaker owns, so it can be stopped. */
        private const val UTTERANCE_CARD = "lucid-card"

        /** How much of a long answer the card shows before it offers the sheet. */
        private const val CARD_CHARS = 260

        /** Puts the lens back on screen without paying for a cold start. */
        const val ACTION_SHOW = "com.grafizum.lucid.action.SHOW"
        const val EXTRA_RESULT_CODE = "extra_result_code"
        const val EXTRA_RESULT_DATA = "extra_result_data"

        /**
         * A channel's importance and sound cannot be changed once it exists, so
         * an install that already carries [LEGACY_CHANNEL_ID] would keep its
         * sound whatever this build asked for. The quiet channel therefore has
         * a new id, and the old one is deleted on the way past.
         */
        private const val CHANNEL_ID = "lens_session_quiet"
        private const val LEGACY_CHANNEL_ID = "lens_session"
        private const val NOTIFICATION_ID = 4711
        private const val TAG = "LucidLens"

        /** How often the session checks that its lens is still on screen. */
        private const val WATCHDOG_MS = 1500L

        /**
         * How long the overlays stay out of the way of a frame grab: long
         * enough for the compositor to draw one without them, short enough
         * that the lens does not visibly blink.
         */
        private const val GRAB_SETTLE_MS = 70L

        /** How long the phone's own screenshot may take before it is a no. */
        private const val SCREENSHOT_TIMEOUT_MS = 2_000L

        /**
         * How many extra frames a moving picture gets after one that read as
         * empty, and how far apart they are taken. A caption is on screen for
         * two to four seconds, so a fifth of a second is long enough for the
         * picture to have moved to a different moment and short enough that the
         * caption has not gone.
         */
        private const val BURST_EXTRA_FRAMES = 2
        private const val BURST_GAP_MS = 120L

        /**
         * A read of a moving picture holding fewer letters than this is looked
         * at again even when it is not empty: a caption caught while it fades
         * in, or between two words, comes back as a fragment rather than as
         * nothing, and a fragment of a caption is exactly what a person calls
         * "missing a letter".
         */
        private const val BURST_THIN_GLYPHS = 8

        /**
         * How many reads in a row the screenshot API has to refuse before the
         * Screen capture row is brought back. One refusal is a hiccup; a phone
         * that keeps refusing needs the old way to be offered.
         */
        private const val A11Y_REFUSALS_BEFORE_FALLBACK = 2

        /** How strong the half-disc sits while the lens is at rest. */
        private const val IDLE_ALPHA = 0.88f

        /* The way out on the bottom edge, in dp. */
        private const val DROP_WIDTH_DP = 72

        /**
         * The drawer lip that peeks out of the bottom edge at rest: a bar 156 dp
         * across and 18 dp tall, its top corners rounded as far as the bar's own
         * height allows and its bottom flat on the edge. The grabber language of
         * sheets and drawers, which is what the edge is.
         */
        private const val PEEK_WIDTH_DP = 156
        private const val PEEK_HEIGHT_DP = 18

        /** How far the lip's top corners round at rest. */
        private const val LIP_RADIUS_DP = 9

        /**
         * The size the lip grows to, and stays at, once the lens is close: a
         * little wider and a little taller, so the jump is kept rather than
         * performed.
         */
        private const val HOLD_WIDTH_DP = 176
        private const val HOLD_HEIGHT_DP = 24

        /**
         * Where the bar sits inside that box at rest, as a fraction of it. The
         * box never changes size, so the lip's whole movement is this one pair of
         * numbers going back and forth on the bar's own centre.
         */
        private val LIP_REST_SCALE_X = PEEK_WIDTH_DP.toFloat() / HOLD_WIDTH_DP
        private val LIP_REST_SCALE_Y = PEEK_HEIGHT_DP.toFloat() / HOLD_HEIGHT_DP

        /** One ease for both ways, short enough to read as a jump. */
        private const val CUE_MS = 180L

        /**
         * The comeback: the cross leans down out of the way while it fades, then
         * the bar arrives from a few pixels above its seat and settles onto the
         * edge.
         *
         * Both halves move on one centre line and only downwards, so there is no
         * sideways component left for the eye to read as arriving from a corner.
         */
        private const val FOLD_FADE_DP = 10
        private const val FOLD_FADE_MS = 120L
        private const val FOLD_RISE_DP = 8
        private const val FOLD_LAND_MS = 130L

        /** How much of the bar is already there when the cross has gone. */
        private const val COMEBACK_LAND_ALPHA = 0.35f

        /** How bright the cap goes while it is held big. */
        private const val HELD_ALPHA = 0.9f

        /**
         * How much of the lip's surface survives while it is held big: none. The
         * jump is drawn by the border alone, so the grown area is an outline and
         * not a bigger slab of the same colour.
         */
        private const val HELD_FILL_SCALE = 0f

        /**
         * How close the lens has to come before the mark answers with one small
         * hop: the ring's width, measured off the rectangle's own border and the
         * same all the way round it.
         *
         * This is a hand's distance, not a guess at one: it is where the lens had
         * to be for the hop to read as answering it. Bigger and the lip jumped
         * while the lens was still on its way.
         */
        private const val NEAR_DP = 41

        /** The disc of the mark once it opens. */
        private const val DISC_DP = 46

        private const val LABEL_HEIGHT_DP = 18
        private const val DROP_GAP_DP = 4

        /**
         * How far above the bottom edge the opened mark sits.
         *
         * This is the safety strip. Reading the last lines of a page happens with
         * the lens as low as it will go, which is where the mark opens — so the
         * cross must not be where the lens already is. It sits a hand's width
         * higher, and putting the lens away means lifting it up there on purpose.
         */
        private const val DROP_BOTTOM_INSET_DP = 56

        /**
         * How much room there is to aim: the disc's own radius plus this. Enough
         * slack that the lens does not have to land on the circle to the pixel,
         * small enough that it cannot be caught on the way past.
         */
        private const val DROP_REACH_PAD_DP = 17

        /**
         * How far the lens may drift back off the rectangle and still be held on
         * it, and so the distance at which the mark folds and the rectangle
         * shows again. The open itself has no radius: it is the lens's border
         * touching the rectangle's ([DeleteMark.touchesLip]), and nothing else.
         */
        private const val DROP_HOLD_DP = 48

        /**
         * How far off the mark itself the lens may be carried and keep it open.
         * Lifting the lens onto the cross is a lift of more than a lens height,
         * so the lip alone cannot hold the mark while that happens: the cross's
         * own reach, widened by this, is what keeps it up there.
         */
        private const val DROP_STAY_DP = 34

        /**
         * The session that is actually up, if any.
         *
         * The lens is a window of ours, not of the system, and it can be
         * dropped behind our back. A cached boolean would then keep saying
         * "on" with nothing on screen — so the live service *is* the state.
         */
        @Volatile
        private var live: LensService? = null

        val isRunning: Boolean
            get() = live != null

        /** Puts the lens's dressing on the theme that was just switched to. */
        fun restyle() {
            live?.restyleChrome()
        }

        /**
         * Running *and* on screen. A lens that was put away is neither off nor
         * on: the session is warm, the engine is loaded, and one tap brings it
         * back. Callers that mean "there is a lens to look through" want this,
         * not [isRunning].
         */
        val isLensVisible: Boolean
            get() = live?.let { !it.hiddenForLater && it.bubble?.isAttachedToWindow == true } ?: false

        /** Put the lens away, or bring it back, without touching the session. */
        fun setLensVisible(context: Context, visible: Boolean) {
            runCatching {
                context.startService(
                    Intent(context, LensService::class.java).apply {
                        action = if (visible) ACTION_SHOW else ACTION_HIDE
                    },
                )
            }
        }

        /** True while a look-mode (screen capture) session is attached. */
        var hasCapture: Boolean = false
            private set

        private var listener: ((Boolean) -> Unit)? = null

        fun observe(callback: ((Boolean) -> Unit)?) {
            listener = callback
            callback?.invoke(isRunning)
        }

        /** Text-access only: no screen capture involved at all. */
        fun start(context: Context) {
            startLens(
                context,
                Intent(context, LensService::class.java).apply { action = ACTION_START },
            )
        }

        /** Screen capture: adds whole-screen capture on top of text access. */
        fun startWithCapture(context: Context, resultCode: Int, data: Intent) {
            val intent = Intent(context, LensService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_RESULT_CODE, resultCode)
                putExtra(EXTRA_RESULT_DATA, data)
            }
            startLens(context, intent)
        }

        /**
         * Android 8 introduced startForegroundService — the call that promises a
         * notification is coming. Before it there is no such promise to make, and
         * the plain start is the same thing, so Android 7 gets the same lens.
         */
        private fun startLens(context: Context, intent: Intent) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.startService(Intent(context, LensService::class.java).apply { action = ACTION_STOP })
        }

        /**
         * Makes the live session prove its lens is on screen right now, before
         * anything that shows the state to the user draws itself — the shade,
         * above all, must never say "on" for a lens that is gone.
         */
        fun verify() {
            val session = live ?: return
            session.handler.removeCallbacks(session.watchBubble)
            session.watchBubble.run()
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val handler = Handler(Looper.getMainLooper())

    /**
     * Marks the session up or down, and tells every place that shows it: the
     * app, the accessibility service and the quick-settings tile all read one
     * value, so none of them can drift out of step with the screen.
     */
    private fun publish(running: Boolean) {
        live = if (running) this else null
        listener?.invoke(running)
        LensState.publish()
        LensTileService.refresh(this)

        // The lens only counts as on screen once its window is attached, and a
        // window added from a tile click attaches a frame or two after the
        // service starts. Say it once more, so a lens started from the shade
        // does not leave the app reading "Lens off" over a visible lens.
        handler.removeCallbacks(republish)
        if (running) handler.postDelayed(republish, 260)
    }

    private val republish = Runnable {
        if (live === this) {
            LensState.publish()
            LensTileService.refresh(this)
        }
    }

    private lateinit var wm: WindowManager
    private var capturer: ScreenCapturer? = null
    private var translator: VisionTranslator? = null

    private var bubble: View? = null
    private var bubbleParams: WindowManager.LayoutParams? = null
    private var bubbleIcon: ImageView? = null

    /**
     * The way out: the mark the lens is carried onto to put it away. A half-disc
     * peeks out of the bottom edge while the lens is at rest, and the whole mark
     * opens only once the lens is carried down to that edge.
     */
    private var dropTarget: View? = null
    private var dropParams: WindowManager.LayoutParams? = null
    private var dropSize = 0

    /** The three faces of the mark: the resting arch, the disc, the word. */
    private var dropArch: View? = null
    private var dropDisc: View? = null
    private var dropLabel: View? = null

    /** True while the mark is open — the only time a drop puts the lens away. */
    private var dropRevealed = false

    /** The theme the chrome on screen was dressed in, to notice a flip. */
    private var styledDark = false

    /**
     * True while the mark is open for the lens: its border has met the lip's,
     * and the lens is either still at the edge or up on the mark where the
     * cross is. It is not latched for the gesture — carry the lens clear of
     * both and the mark folds back into its lip while the finger is still down,
     * because a hand that has changed its mind should see that it has before it
     * lets go.
     */
    private var dropArmed = false

    /**
     * True while the cap is held at its bigger size, which it stays at for as
     * long as the lens is close to the edge. It is not a one-off: the jump is
     * kept, and only the lens moving away again puts it back.
     */
    private var cueHeld = false

    /** Which way the hand is going, with a little slack for a slow one. */
    private var descending = false

    /**
     * Whether the lens has already answered the rectangle in this arrival, so
     * the tick fires once on the way in and again only after leaving and
     * coming back.
     */
    private var lipTouched = false

    /** The grow or settle in flight, if any: it is run on the window's bounds. */
    private var cueAnim: ValueAnimator? = null

    private var dropCx = 0
    private var dropCy = 0
    private var dropReach = 0f
    private var hoveringDrop = false

    /** A comeback is in flight: it owns the mark's alpha until it finishes. */
    private var folding = false

    private var sight: SightView? = null

    /** The selection wash that sits on the text the lens picked. */
    private var highlight: HighlightView? = null
    private var highlightGen = 0

    /** The mint arc that spins around the bubble while a read is in flight. */
    private var loading: LoadingRing? = null
    private var loadingParams: WindowManager.LayoutParams? = null

    private var card: View? = null
    private var cardParams: WindowManager.LayoutParams? = null
    private var cardStatus: TextView? = null
    private var cardBody: TextView? = null
    private var cardOriginal: RubyTextView? = null

    /** How the line under the lens is pronounced, in Latin letters. */
    private var cardRoman: TextView? = null
    private var cardWords: LinearLayout? = null
    private var cardLangRow: View? = null
    private var cardSrc: TextView? = null
    private var cardDst: TextView? = null
    private var cardFoot: View? = null
    private var cardMeta: TextView? = null
    private var cardCopy: TextView? = null
    private var cardSpinner: LoadingRing? = null
    private var cardPin: ImageView? = null
    private var cardSpeak: ImageView? = null

    /** The door to the rest of an answer the card had to cut. */
    private var cardMore: TextView? = null

    /** Walking a page: one sentence at a time, with the count between the arrows. */
    private var cardStepRow: View? = null
    private var cardStepText: TextView? = null
    private var cardStepPrev: TextView? = null
    private var cardStepNext: TextView? = null

    /** The answer as it came back, before the card cut it to fit. */
    private var cardFullText = ""

    /** The reading sheet, when a cut answer is open at full size. */
    private var reader: View? = null
    private var readerParams: WindowManager.LayoutParams? = null

    /** The page the card can step through, and where it stands in it. */
    private var pageSentences: List<String> = emptyList()
    private var pageIndex = 0
    private var pageSeq = 0
    private var pageContext = ""

    /** The card is held on screen through presses elsewhere until unpinned. */
    private var cardPinned = false

    /** What the speaker reads: the answer on the card, in its own language. */
    private var cardSpeechText = ""
    private var cardSpeechLang = "en"

    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var speaking = false

    /** What the card currently holds, so its languages can be changed live. */
    private var cardSourceText: String = ""
    private var cardSourceLang: String? = null

    /** The source language the user picked for this card, when not left on auto. */
    private var cardSourcePin: String? = null
    private var cardTarget: String = ""

    /**
     * The app the lens is currently over, when the phone can say: it is what a
     * language picked from the lens is remembered against.
     */
    private var readPackage: String? = null

    /** The screen behind the read: sent to the engine, never drawn. */
    private var readContext: String = ""


    /** The language sheet shown over the card when a chip is tapped. */
    private var sheet: View? = null


    /** Long-press parks the lens: it fades back and stops reading. */
    private var dormant = false

    /**
     * The lens has been put away on purpose (from the shade's button, say). The
     * session stays up and the watchdog must not read this as a lost window.
     */
    private var hiddenForLater = false

    /** Guards shutdown() so ACTION_STOP and onDestroy can both call it. */
    private var shut = false

    private var bubbleSize = 0
    private var screenW = 0
    private var screenH = 0
    private var busy = false

    /** Reads in a row where the phone refused its own screenshot API. */
    private var a11yRefusals = 0

    /** Bumped by every read so stale callbacks can be ignored. */
    private var readGen = 0

    /** Same, for the word-by-word meanings that arrive after a translation. */
    private var wordsGen = 0

    /** Same, for the tree lookups that now run off the main thread. */
    private var lookupGen = 0
    private var lastKey: String = ""
    private var lastRect: Rect? = null

    /** Fires when the lens has been still for a moment — the cursor behaviour. */
    private val autoRead = Runnable { readNow(auto = true) }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(WindowManager::class.java)
        screenSize().also { screenW = it.x; screenH = it.y }
        bubbleSize = dp(42)
        styledDark = ThemeChoice.isDark(this)
        translator = VisionTranslator(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                shutdown()
                return START_NOT_STICKY
            }
            ACTION_HIDE -> {
                hideLens()
                // A show/hide that arrives with no session behind it must not
                // leave a started service that never called startForeground.
                if (!isRunning) stopSelf()
                return START_NOT_STICKY
            }
            ACTION_SHOW -> {
                showLens()
                if (!isRunning) stopSelf()
                return START_NOT_STICKY
            }
        }
        // A restart after a stop re-opens the session on this same instance.
        shut = false
        hiddenForLater = false

        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, 0) ?: 0
        @Suppress("DEPRECATION")
        val earlyData = intent?.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)
        startForegroundSession(withProjection = resultCode != 0 && earlyData != null)

        val data = if (Build.VERSION.SDK_INT >= 33) {
            intent?.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent?.getParcelableExtra(EXTRA_RESULT_DATA)
        }

        if (resultCode != 0 && data != null) {
            beginProjection(resultCode, data)
        }

        showBubble()
        // No lens on screen means no session — never claim one.
        if (bubble == null) return START_NOT_STICKY

        // Text-access mode has no projection callback to report from, so the
        // session counts as running as soon as the bubble is on screen.
        publish(true)
        startWatchdog()
        return START_NOT_STICKY
    }

    /* ── session ─────────────────────────────────────────────────────── */

    private fun startForegroundSession(withProjection: Boolean) {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.deleteNotificationChannel(LEGACY_CHANNEL_ID)
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notif_channel),
                NotificationManager.IMPORTANCE_MIN,
            ).apply {
                setShowBadge(false)
                enableVibration(false)
                // No sound, no peek: the session is news only to the shade.
                setSound(null, null)
                lockscreenVisibility = Notification.VISIBILITY_SECRET
            }
            manager.createNotificationChannel(channel)
        }

        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val stopAction = PendingIntent.getService(
            this,
            1,
            Intent(this, LensService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_loop)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(getString(R.string.notif_text))
            .setOngoing(true)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setContentIntent(openApp)
            .addAction(0, getString(R.string.notif_stop), stopAction)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val type = if (withProjection) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            } else {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            }
            startForeground(NOTIFICATION_ID, notification, type)
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun beginProjection(resultCode: Int, data: Intent) {
        val manager = getSystemService(MediaProjectionManager::class.java)
        val projection: MediaProjection = try {
            manager.getMediaProjection(resultCode, data)
        } catch (err: Exception) {
            null
        } ?: run {
            toast("Screen access was denied.")
            return
        }

        projection.registerCallback(
            object : MediaProjection.Callback() {
                override fun onStop() {
                    publish(false)
                    shutdown()
                }
            },
            handler,
        )

        val size = screenSize()
        screenW = size.x
        screenH = size.y

        val capture = ScreenCapturer(this)
        val ok = capture.attach(projection, screenW, screenH, resources.displayMetrics.densityDpi)
        capturer = capture

        if (!ok) {
            toast("Couldn't start screen capture.")
            publish(false)
            return
        }

        hasCapture = true
        publish(true)
    }

    /**
     * Rotation and density changes move the whole screen under the lens. The
     * session re-measures and pulls the bubble back into view instead of
     * leaving it off-screen, which looks exactly like a lens that vanished.
     */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)

        // A day/night switch comes through here too: the lens and the way out
        // put on the colours of the theme that is now in force.
        restyleChrome()

        bubbleSize = dp(42)
        val size = screenSize()
        screenW = size.x
        screenH = size.y

        // Where the frame comes from a screen share, the mirror was built at the
        // old shape: a crop taken after a rotation would land on the wrong part
        // of the page. Rebuilding it keeps the lens reading what it is over.
        capturer?.resize(screenW, screenH, resources.displayMetrics.densityDpi)

        val params = bubbleParams ?: return
        params.x = params.x.coerceIn(0, (screenW - bubbleSize).coerceAtLeast(0))
        params.y = params.y.coerceIn(0, lowestLensY())
        val view = bubble ?: return
        runCatching { wm.updateViewLayout(view, params) }
        hideCard()
    }

    /* ── bubble ──────────────────────────────────────────────────────── */

    private fun toggleDormant() {
        dormant = !dormant
        cancelAutoRead()
        hideCard()
        hideSight()

        bubble?.animate()?.alpha(if (dormant) 0.3f else 1f)?.setDuration(160)?.start()
        toast(if (dormant) "Lens paused \u2014 long-press to wake it" else "Lens on")
    }

    /**
     * Off screen, session still up. Everything the lens had floating is taken
     * down — the bubble, the read frame, the card, the sheet — because all of
     * them are the lens, and the lens is what was put away.
     */
    private fun hideLens() {
        if (!isRunning) return
        hiddenForLater = true
        cancelAutoRead()
        hideCard()
        hideSight()
        hideLoading()
        hideLanguageSheet()

        bubble?.let { view -> runCatching { wm.removeView(view) } }
        bubble = null
        bubbleParams = null
        bubbleIcon = null
        removeDropTarget()
        LensState.publish()
    }

    private fun showLens() {
        if (!isRunning) return
        hiddenForLater = false
        showBubble()
        LensState.publish()
    }

    private fun showBubble() {
        // A bubble we still hold a reference to may already be gone from the
        // screen, so the reference alone is not proof — only an attached view
        // is. A detached one is cleared away and put back.
        val existing = bubble
        if (existing != null && existing.isAttachedToWindow) return
        if (existing != null) {
            runCatching { wm.removeView(existing) }
            bubble = null
            bubbleParams = null
            bubbleIcon = null
            removeDropTarget()
        }

        if (!Settings.canDrawOverlays(this)) {
            toast("Lucid needs the draw-over-other-apps permission.")
            shutdown()
            return
        }

        val container = FrameLayout(this)
        val icon = ImageView(this).apply {
            setImageDrawable(lensDrawable(R.drawable.iris))
            layoutParams = FrameLayout.LayoutParams(dp(18), dp(18), Gravity.CENTER)
        }
        container.addView(icon)

        container.background = lensDrawable(R.drawable.bubble_bg)
        container.elevation = dp(12).toFloat()
        // Coming back from a dropped window must not wake a paused lens.
        container.alpha = if (dormant) 0.3f else 1f
        bubbleIcon = icon

        val params = overlayParams(
            bubbleSize,
            bubbleSize,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        ).apply {
            val savedX = Prefs.bubbleX(this@LensService)
            val savedY = Prefs.bubbleY(this@LensService)
            val defaultX = screenW - bubbleSize - dp(14)
            val defaultY = screenH / 3
            // Put away while it was down on the rectangle: the next start takes
            // the default seat again, well clear of the way out, so a lens left
            // in reach of the cross never comes back sitting under it ready to
            // be dropped by the first drag. Anywhere else it is remembered.
            val lipW = dp(PEEK_WIDTH_DP)
            val lipH = dp(PEEK_HEIGHT_DP)
            val lip = DeleteMark.peek(screenW, screenH, lipW, lipH)
            val inReach = savedX >= 0 && savedY >= 0 && DeleteMark.nearing(
                lipLeft = lip.x,
                lipTop = lip.y,
                lipRight = lip.x + lipW,
                lipBottom = lip.y + lipH,
                lensX = savedX,
                lensY = savedY,
                size = bubbleSize,
                distance = dp(NEAR_DP),
            )
            x = if (savedX >= 0 && !inReach) savedX else defaultX
            y = if (savedY >= 0 && !inReach) savedY else defaultY
            // A seat saved while the lens could still rest in the bar is lifted
            // off it: the floor is the floor on every start, not just on a drag.
            y = y.coerceAtMost(lowestLensY())
        }

        attachDrag(container)
        val added = runCatching { wm.addView(container, params) }.isSuccess
        if (!added) {
            Log.i(TAG, "couldn't add the lens window")
            return
        }
        bubble = container
        bubbleParams = params
        // The delete key comes up with the lens, at its remembered seat.
        showDropTarget()
    }

    /**
     * The lens can be dropped behind the session's back: the draw-over
     * permission is withdrawn, the window token is revoked, a display change
     * moves it off-screen. A session with no lens on screen must never look
     * "on" in the shade, so this puts the lens back — and when it cannot, it
     * ends the session, which turns the tile off too.
     */
    private val watchBubble = object : Runnable {
        override fun run() {
            if (live !== this@LensService) return

            if (!Settings.canDrawOverlays(this@LensService)) {
                Log.i(TAG, "draw-over permission gone \u2014 ending the session")
                shutdown()
                return
            }

            // Put away on purpose is not lost: leave it where it is.
            if (hiddenForLater) {
                handler.postDelayed(this, WATCHDOG_MS)
                return
            }

            val view = bubble
            if (view == null || !view.isAttachedToWindow) {
                Log.i(TAG, "lens bubble lost \u2014 putting it back")
                showBubble()
                if (bubble == null) {
                    Log.i(TAG, "couldn't restore the lens \u2014 ending the session")
                    shutdown()
                    return
                }
            }

            handler.postDelayed(this, WATCHDOG_MS)
        }
    }

    private fun startWatchdog() {
        handler.removeCallbacks(watchBubble)
        handler.postDelayed(watchBubble, WATCHDOG_MS)
    }

    private fun stopWatchdog() {
        handler.removeCallbacks(watchBubble)
    }

    private fun attachDrag(view: View) {
        var downRawX = 0f
        var downRawY = 0f
        var lastRawY = 0f
        var startX = 0
        var startY = 0
        var moved = false
        var longPressed = false

        val longPress = Runnable {
            longPressed = true
            view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            toggleDormant()
        }

        view.setOnTouchListener { _, event ->
            val params = bubbleParams ?: return@setOnTouchListener false

            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = event.rawX
                    downRawY = event.rawY
                    lastRawY = event.rawY
                    descending = false
                    startX = params.x
                    startY = params.y
                    moved = false
                    longPressed = false
                    lipTouched = false
                    cancelAutoRead()
                    // A lens grabbed where it already sits, at the edge, shows
                    // the way out at once: waiting for a drag of more than a few
                    // pixels meant a lens that was already on the rectangle had
                    // to be nudged off it to be answered.
                    updateDropArm(view, params)
                    // Moving the lens clears the last answer so the screen is visible again.
                    hideCard()
                    hideSight()
                    hideLoading()
                    handler.postDelayed(longPress, 460)
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downRawX
                    val dy = event.rawY - downRawY
                    // A slowly drifting hand must not be taken for a still one:
                    // the pause is only for a finger that has actually stopped.
                    if (!moved && (abs(dx) > dp(3) || abs(dy) > dp(3))) {
                        handler.removeCallbacks(longPress)
                    }
                    if (!moved && (abs(dx) > dp(6) || abs(dy) > dp(6))) {
                        moved = true
                        // Carrying the lens is also when the way out shows up:
                        // a fixed cross to drop it on, the way a floating timer
                        // is got rid of. Nothing is hidden under it while it is
                        // up that a read would need.
                        showSight()
                    }
                    if (moved) {
                        // Down the page and onto the way out's bar, and no
                        // further: the lens's border meeting the bar's is what
                        // opens the mark, and a lens carried on past that line
                        // ends up inside the bar — drawn through its outline,
                        // its middle in the phone's gesture strip, where the
                        // hand's next drag is taken for Home.
                        params.x = (startX + dx).toInt().coerceIn(0, (screenW - bubbleSize).coerceAtLeast(0))
                        params.y = (startY + dy).toInt().coerceIn(0, lowestLensY())
                        runCatching { wm.updateViewLayout(view, params) }
                        moveSight()
                        moveLoading()
                        // The way out shows itself when the lens comes down onto
                        // the lip — the borders meeting — because that is the
                        // moment the hand already reads as "on it". Nothing
                        // under the middle of a page is ever that low, so a read
                        // in place never arms the mark.
                        // Once armed the mark stays open until the gesture ends
                        // or the lens is carried clear of it, so it can be lifted
                        // onto the cross; a release at the edge itself is still a
                        // read, since the cross sits above the edge.
                        // Direction with a little slack, so a slow drag is not
                        // read as changing its mind on every tremor: a finger
                        // that is drifting carries the way it was already going.
                        if (event.rawY > lastRawY + dp(1)) descending = true
                        else if (event.rawY < lastRawY - dp(3)) descending = false
                        val falling = descending
                        lastRawY = event.rawY
                        // The edge answers in two steps. Close to it, the dome
                        // hops once — "here, and further down opens me". Down
                        // on it, its border met, the mark opens. Both are read
                        // from where the lens is now: rise a few dp off the edge
                        // and the mark folds away again, without waiting for the
                        // finger to be lifted.
                        // The way the ring is read: the lens's border within
                        // the hand's own distance of the rectangle's border, on
                        // every side of it and at its corners — a square ring
                        // around the rectangle, measured from the rectangle in
                        // its resting size, so the hop does not creep outward
                        // once the lip has grown.
                        val rest = DeleteMark.peek(screenW, screenH, dp(PEEK_WIDTH_DP), dp(PEEK_HEIGHT_DP))
                        val close = DeleteMark.nearing(
                            lipLeft = rest.x,
                            lipTop = rest.y,
                            lipRight = rest.x + dp(PEEK_WIDTH_DP),
                            lipBottom = rest.y + dp(PEEK_HEIGHT_DP),
                            lensX = params.x,
                            lensY = params.y,
                            size = bubbleSize,
                            distance = dp(NEAR_DP),
                        )
                        // Where the lens is against the lip decides the way out,
                        // read from the lens's own position on this event — never
                        // from where the gesture started, and never from the
                        // mark's window, which once open stands a hand's width
                        // higher than the lip.
                        updateDropArm(view, params)
                        if (close && falling && !cueHeld && !dropArmed) {
                            // One jump, and it stays: the cap keeps the bigger
                            // size while the lens is close.
                            cueHeld = true
                            holdCue(true)
                        } else if (!close && cueHeld) {
                            // Turned away: back down at once, finger still down.
                            cueHeld = false
                            holdCue(false)
                        }
                        // Cursor behaviour: pause over text and it reads by itself —
                        // but the way out is not text, so a hand at the lip does not
                        // fire a read under it.
                        if (cueHeld || dropArmed) cancelAutoRead() else scheduleAutoRead(650)
                    }
                    true
                }

                MotionEvent.ACTION_UP -> {
                    handler.removeCallbacks(longPress)
                    val dropped = moved && dropRevealed && onDropTarget(params)
                    dropArmed = false
                    lipTouched = false
                    // The next grab up the page gets its own answer at the edge.
                    cueHeld = false
                    stopCue()
                    // Folds back into its peek, so the next read at the bottom of
                    // a page is a read again.
                    revealDropTarget(false)
                    if (!dropped) holdCue(false)
                    if (dropped) {
                        // Let go over the cross: the lens is put away, wherever
                        // it was, and the session goes with it.
                        view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                        closeLens()
                    } else if (moved) {
                        // A lens let go at the bottom of a page is left on the
                        // bar's edge, never inside it — whatever seat the drag
                        // ended on, and whatever seat an older build saved.
                        settleOffLip(view, params)
                        Prefs.saveBubble(this, params.x, params.y)
                        // Moving the lens is the normal trigger. With
                        // auto-translate off, letting go after a move is the read.
                        // Both are reads the lens asked for, not the person:
                        // one at rest, one on letting go. Neither of them
                        // argues with a spot that has nothing on it.
                        if (Prefs.autoTranslate(this)) scheduleAutoRead(260) else readNow(auto = true)
                    } else if (!longPressed) {
                        view.performClick()
                        // A tap is not a read any more: it opens the translation
                        // options, and a press anywhere outside puts them away
                        // again with the pick saved.
                        if (dormant) {
                            toast("Lens is paused \u2014 long-press to wake it")
                        } else {
                            showLanguageSheet(pickTarget = true, save = true)
                        }
                    }
                    true
                }

                MotionEvent.ACTION_CANCEL -> {
                    handler.removeCallbacks(longPress)
                    dropArmed = false
                    lipTouched = false
                    cueHeld = false
                    stopCue()
                    revealDropTarget(false)
                    holdCue(false)
                    true
                }

                else -> false
            }
        }
    }

    /**
     * The way out, on the bottom edge.
     *
     * At rest it is only a half-disc peeking out of the very bottom of the
     * screen: it says the drop is down there without holding a strip of the page
     * hostage, and it is far too small for a read or a stray touch to land on.
     *
     * It opens into the full mark — disc, cross and the word — only when the
     * lens is taken all the way down to the bottom edge ([revealDropTarget]),
     * and only then is a drop a delete. Reading something at the bottom of a
     * page is therefore never a way to lose the lens.
     */
    private fun showDropTarget() {
        // Already up: the lens has been picked up, so the mark comes to its
        // resting shade instead of opening a second window under it.
        dropTarget?.let { existing ->
            existing.animate().cancel()
            existing.alpha = dropAlpha()
            existing.scaleX = 1f
            existing.scaleY = 1f
            hoveringDrop = false
            return
        }
        dropSize = dp(DISC_DP)
        cueHeld = false
        val peekWidth = dp(PEEK_WIDTH_DP)
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            // Clipped on purpose: the lip at rest is this column showing
            // through a window only PEEK_HEIGHT_DP tall.
            clipChildren = true
            clipToPadding = true
            contentDescription = getString(R.string.lens_close)
            elevation = dp(10).toFloat()
            alpha = 0f
        }
        // The lip, filling whatever window it is given: the window is what is
        // resized when the lip grows, and the shape is drawn from its own bounds,
        // so the corners stay round at every size.
        val arch = FrameLayout(this).apply {
            background = lipBackground()
        }
        column.addView(
            arch,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT,
            ),
        )
        val disc = FrameLayout(this).apply {
            background = discBackground()
            // Folded, the arch is the whole mark; the disc is only for opening.
            visibility = View.GONE
        }
        disc.addView(
            ImageView(this).apply {
                setImageResource(R.drawable.ic_close)
                imageTintList = ColorStateList.valueOf(lensColor(R.color.lens_glyph))
                setPadding(dp(10), dp(10), dp(10), dp(10))
                scaleType = ImageView.ScaleType.FIT_CENTER
            },
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )
        column.addView(
            disc,
            LinearLayout.LayoutParams(dropSize, dropSize),
        )
        val label = TextView(this).apply {
                text = getString(R.string.lens_delete)
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 10f)
                setTextColor(lensColor(R.color.lens_glyph))
                gravity = Gravity.CENTER
                setPadding(dp(8), 0, dp(8), 0)
                // An outlined pill under the word: the edge, in the theme's
                // own ring, with the page showing through it.
                background = labelBackground()
            }
        label.visibility = View.GONE
        column.addView(
            label,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                dp(LABEL_HEIGHT_DP),
            ).apply { topMargin = dp(DROP_GAP_DP) },
        )
        // The window is the grown lip's box for the whole of its life, and the bar
        // is drawn smaller inside it. Resizing the window as it hopped made the
        // window's left edge trail a frame behind its width, which read as the lip
        // folding in from its right end; drawn at its own size inside a box that
        // never moves, it only rises and falls.
        val lipW = dp(HOLD_WIDTH_DP)
        val lipH = dp(HOLD_HEIGHT_DP)
        val peek = DeleteMark.peek(screenW, screenH, lipW, lipH)
        val params = overlayParams(
            lipW,
            lipH,
            // Out of the way of touches on purpose: at rest the arch is a hint
            // and nothing else. It only takes touches once it has opened, so a
            // tap at the bottom of a page can never put the lens away — and a
            // wide hint would make that a very easy thing to do by accident.
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        ).apply {
            x = peek.x
            y = peek.y
        }
        column.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            // Off the touch dispatch: closing takes this window away.
            handler.post { closeLens() }
        }
        if (!runCatching { wm.addView(column, params) }.isSuccess) {
            Log.i(TAG, "couldn't add the delete mark")
            return
        }
        dropTarget = column
        dropParams = params
        dropArch = arch
        dropDisc = disc
        dropLabel = label
        dropRevealed = false
        dropCx = peek.x + peekWidth / 2
        dropCy = peek.y + dp(PEEK_HEIGHT_DP)
        dropReach = 0f
        hoveringDrop = false
        column.alpha = IDLE_ALPHA
    }

    /** The shade the mark is meant to have in the state it is in. */
    private fun dropAlpha(): Float = if (dropRevealed) 1f else IDLE_ALPHA

    /**
     * Opens the mark out of the bottom edge, or folds it back into a peek.
     *
     * Only the open state is a target: [dropReach] is zero while it is folded,
     * so letting go of the lens at the bottom of a page reads there instead of
     * putting the lens away.
     */
    private fun revealDropTarget(open: Boolean) {
        val view = dropTarget as? LinearLayout ?: return
        val params = dropParams ?: return
        if (open == dropRevealed) return
        val wasOpen = dropRevealed
        // A hop in flight must not fight the seat this is about to set.
        stopCue()
        dropRevealed = open

        // Armed or disarmed now, not when the fade finishes: a release during
        // the swap must not count as a drop.
        dropReach = if (open) dropSize / 2f + dp(DROP_REACH_PAD_DP) else 0f
        if (!open) {
            hoveringDrop = false
            view.scaleX = 1f
            view.scaleY = 1f
        }

        // Going back from the open mark: the comeback, in two halves. The cross
        // leans down the way it is about to go and fades, then the bar is there
        // and drops the last of the way onto the edge. Nothing moves sideways at
        // any point, so it cannot read as coming in from a side.
        if (wasOpen && !open) {
            startComeback(view, params)
            return
        }

        // The face and the window it is drawn in change in the same pass, before
        // anything is drawn: the lip is never seen stretched through the tall
        // window, and the mark is never a beat late — or left faded out by an
        // animation that did not run. What is on screen is the state, with no
        // second step that can be missed.
        stopComeback()
        view.animate().cancel()
        view.translationY = 0f
        dropArch?.alpha = 1f
        dropArch?.translationY = 0f
        applyDropFace(open, view, params)
        view.alpha = dropAlpha()
    }

    /**
     * The mark folding away: cross down and out, bar down and in.
     *
     * The bar arrives from [FOLD_RISE_DP] above its seat and the ease overshoots
     * the last pixel, so it presses into the edge and settles there. The window
     * itself never moves, only the drawn views inside it, so there is no window
     * pass that can land a frame late and skew the motion.
     */
    private fun startComeback(view: View, params: WindowManager.LayoutParams) {
        stopComeback()
        folding = true
        view.animate().cancel()
        view.animate()
            .translationY(dp(FOLD_FADE_DP).toFloat())
            .alpha(0f)
            .setDuration(FOLD_FADE_MS)
            .setInterpolator(PathInterpolator(0.4f, 0f, 1f, 1f))
            .withEndAction {
                if (!folding) return@withEndAction
                applyDropFace(false, view, params)
                view.translationY = 0f
                view.alpha = 0f
                val arch = dropArch
                if (arch == null) {
                    finishComeback(view)
                    return@withEndAction
                }
                arch.alpha = COMEBACK_LAND_ALPHA
                arch.translationY = -dp(FOLD_RISE_DP).toFloat()
                arch.animate().cancel()
                arch.animate()
                    .alpha(1f)
                    .translationY(0f)
                    .setDuration(FOLD_LAND_MS)
                    .setInterpolator(OvershootInterpolator(0.6f))
                    .withEndAction { finishComeback(view) }
                    .start()
            }
            .start()
    }

    private fun finishComeback(view: View) {
        folding = false
        view.alpha = dropAlpha()
        dropArch?.alpha = 1f
        dropArch?.translationY = 0f
        view.translationY = 0f
    }

    /** Drops a comeback in flight, putting the views back where they belong. */
    private fun stopComeback() {
        folding = false
        val view = dropTarget ?: return
        view.animate().cancel()
        view.translationY = 0f
        dropArch?.animate()?.cancel()
        dropArch?.alpha = 1f
        dropArch?.translationY = 0f
    }

    /** Puts the mark into one of its two shapes, and the window with it. */
    private fun applyDropFace(open: Boolean, view: View, params: WindowManager.LayoutParams) {
        // The lip's window is always the grown bar's box: what changes is the bar
        // drawn in it, not the window.
        val width = if (open) dp(DROP_WIDTH_DP) else dp(HOLD_WIDTH_DP)
        val height = if (open) openHeight() else dp(HOLD_HEIGHT_DP)
        val seat = if (open) {
            DeleteMark.opened(screenW, screenH, width, height, dp(DROP_BOTTOM_INSET_DP))
        } else {
            DeleteMark.peek(screenW, screenH, width, height)
        }

        // Folded it is the lip and nothing else; open it is the disc and the
        // word, with the lip hidden behind them.
        dropArch?.visibility = if (open) View.GONE else View.VISIBLE
        // The lip comes back in the state the cue is in: settling back to rest
        // restores its surface and its resting size, and a fold while still held
        // keeps the border-only look and the grown bar instead of leaving the
        // resting lip almost invisible.
        (dropArch?.background as? LipDrawable)?.let { lip ->
            lip.fillScale = if (cueHeld) HELD_FILL_SCALE else 1f
            lip.bodyScaleX = if (cueHeld) 1f else LIP_REST_SCALE_X
            lip.bodyScaleY = if (cueHeld) 1f else LIP_REST_SCALE_Y
        }
        dropDisc?.visibility = if (open) View.VISIBLE else View.GONE
        dropLabel?.visibility = if (open) View.VISIBLE else View.GONE
        // Open is the only time the mark is a target, for the eye and for touch.
        params.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            (if (open) 0 else WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE) or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
        params.width = width
        params.height = height
        params.x = seat.x
        params.y = seat.y
        runCatching { wm.updateViewLayout(view, params) }

        dropCx = seat.x + width / 2
        dropCy = if (open) seat.y + dropSize / 2 else seat.y + height
    }

    /**
     * Reads where the lens is now against the lip, and opens the way out or folds
     * it back to match.
     *
     * Called on every touch event rather than only on a real move, because there
     * are many ways of arriving at the rectangle and only the position should
     * decide: carried down onto it, down beside it to the same line, in from the
     * side onto its corner, quickly or slowly, or simply grabbed where it already
     * is. Nothing here looks at the path, the direction or the speed.
     */
    private fun updateDropArm(view: View, params: WindowManager.LayoutParams) {
        val seat = DeleteMark.peek(screenW, screenH, peekWidth(), peekHeight())
        val width = peekWidth()
        val height = peekHeight()

        fun near(margin: Int) = DeleteMark.reachesLip(
            lipLeft = seat.x,
            lipTop = seat.y,
            lipRight = seat.x + width,
            lipBottom = seat.y + height,
            lensX = params.x,
            lensY = params.y,
            size = bubbleSize,
            margin = margin,
        )

        if (!dropArmed) {
            // The border of the lens meeting the border of the rectangle, in
            // the rectangle's own span: the one thing that opens the way out.
            if (DeleteMark.touchesLip(
                    lipLeft = seat.x,
                    lipTop = seat.y,
                    lipRight = seat.x + width,
                    lipBottom = seat.y + height,
                    lensX = params.x,
                    lensY = params.y,
                    size = bubbleSize,
                )
            ) {
                // Arriving on the rectangle is answered once, in the smallest
                // tick the phone has: coming onto the way out is a fact the hand
                // should feel, not see.
                if (!lipTouched) {
                    lipTouched = true
                    view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                }
                dropArmed = true
            }
        } else if (!near(dp(DROP_HOLD_DP)) &&
            !DeleteMark.onMark(
                lensCx = params.x + bubbleSize / 2f,
                lensCy = params.y + bubbleSize / 2f,
                markCx = dropCx.toFloat(),
                markCy = dropCy.toFloat(),
                reach = dropReach + dp(DROP_STAY_DP),
            )
        ) {
            // Away from the rectangle and off the mark: the way out folds back
            // into the lip with the finger still down, and the rectangle is what
            // shows again. It is not held open by having once been there, and it
            // is not folded by a finger that wobbles on the lip or by lifting the
            // lens onto the cross — either of those keeps it, whichever way it
            // came down.
            dropArmed = false
            lipTouched = false
        }
        revealDropTarget(dropArmed)
        markDropHover(dropRevealed && onDropTarget(params))
    }

    /**
     * The lip's visible top border — the line the lens's own border has to meet
     * to open the way out.
     *
     * This is the lip's seat, not the mark's window: the opened mark stands a
     * hand's width higher, so reading the line off its window would keep it open
     * wherever the lens went afterwards.
     */
    private fun lipLine(): Int =
        DeleteMark.peek(screenW, screenH, peekWidth(), peekHeight()).y

    /**
     * The lowest the lens may sit: on the bar's top border, never in the bar.
     *
     * Read off the resting lip rather than the grown one, because that border is
     * where the way out opens and where the lens has to be able to arrive.
     * ([DeleteMark.restingFloor])
     */
    private fun lowestLensY(): Int = DeleteMark.restingFloor(
        lipTop = DeleteMark.peek(screenW, screenH, dp(PEEK_WIDTH_DP), dp(PEEK_HEIGHT_DP)).y,
        size = bubbleSize,
    )

    /**
     * Puts a lens that was let go on the bar back on the bar's edge.
     *
     * Only the seat moves: the read it was about to take is taken where the lens
     * comes to rest, which is the same patch of page minus the strip of bar.
     */
    private fun settleOffLip(view: View, params: WindowManager.LayoutParams) {
        val floor = lowestLensY()
        if (params.y <= floor) return
        params.y = floor
        runCatching { wm.updateViewLayout(view, params) }
    }

    /** The lip's width in the state it is in: resting, or held big. */
    private fun peekWidth(): Int =
        if (cueHeld) dp(HOLD_WIDTH_DP) else dp(PEEK_WIDTH_DP)

    /** The lip's height in the state it is in: resting, or held big. */
    private fun peekHeight(): Int =
        if (cueHeld) dp(HOLD_HEIGHT_DP) else dp(PEEK_HEIGHT_DP)

    /**
     * The little jump, kept: when the lens gets close the cap grows into its held
     * size and stays there, so the edge goes on saying "further down opens me"
     * without repeating itself. It only settles back when the lens moves away,
     * and then at once.
     *
     * The window is what is resized, not the drawing inside it, so the lip keeps
     * its flat bottom on the edge while it grows.
     */
    private fun holdCue(held: Boolean) {
        val view = dropTarget ?: return
        // Open, the window is the mark's, not the lip's: there is no jump left
        // to draw.
        if (dropRevealed) return
        // The lip is the arch inside the column, not the column itself.
        val lip = dropArch?.background as? LipDrawable ?: return
        val targetX = if (held) 1f else LIP_REST_SCALE_X
        val targetY = if (held) 1f else LIP_REST_SCALE_Y
        val targetFill = if (held) HELD_FILL_SCALE else 1f
        if (lip.bodyScaleX == targetX && lip.bodyScaleY == targetY && lip.fillScale == targetFill) {
            return
        }

        // Only the drawn bar moves, and it moves on its own centre: the sides
        // come in equally and the top edge does all the travelling, so the lip
        // rises and gives itself back straight down without drifting sideways.
        val fromX = lip.bodyScaleX
        val fromY = lip.bodyScaleY
        val fromFill = lip.fillScale
        cueAnim?.cancel()
        val anim = ValueAnimator.ofFloat(0f, 1f)
        anim.duration = CUE_MS
        // One plain ease in and out both ways: no spring, no overshoot, and the
        // same shape of motion whether the lip is coming or going.
        anim.interpolator = PathInterpolator(0.4f, 0f, 0.2f, 1f)
        anim.addUpdateListener { running ->
            val t = running.animatedValue as Float
            lip.bodyScaleX = fromX + (targetX - fromX) * t
            lip.bodyScaleY = fromY + (targetY - fromY) * t
            // The surface fades as the border grows, so the jump is a line and
            // hardly any colour.
            lip.fillScale = fromFill + (targetFill - fromFill) * t
        }
        anim.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                if (cueAnim === anim) cueAnim = null
            }
        })
        cueAnim = anim
        anim.start()

        // A comeback owns the mark's alpha until the bar has landed; the bar's
        // own size still eases underneath it.
        if (!folding) {
            view.animate().cancel()
            view.animate()
                .alpha(if (held) HELD_ALPHA else dropAlpha())
                .setDuration(CUE_MS)
                .start()
        }
    }

    private fun stopCue() {
        val anim = cueAnim ?: return
        cueAnim = null
        anim.cancel()
    }

    /** Height of the opened mark: the disc, the gap under it and the word. */
    private fun openHeight(): Int = dropSize + dp(DROP_GAP_DP) + dp(LABEL_HEIGHT_DP)

    /**
     * A ring, not a disc: the way out is an outline and nothing more. The cross
     * inside it is drawn in the theme's own ink — dark on paper, cream at night —
     * so it still reads over whatever page is behind it without a plate of colour
     * under it, and the ring around it is the app's accent, so the way out is
     * drawn in the theme it belongs to and in nothing else.
     */
    private fun discBackground(): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(Color.TRANSPARENT)
        setStroke(dp(2), lensColor(R.color.screen_accent))
    }

    /** The lip's outline, in the theme's own accent — no fill inside it. */
    private fun lipBackground(): LipDrawable = LipDrawable(
        fill = Color.TRANSPARENT,
        edge = lensColor(R.color.screen_accent),
        strokePx = dp(2).toFloat(),
        radiusPx = dp(LIP_RADIUS_DP).toFloat(),
    ).apply {
        // The cue still grows the bar itself; the outline is the whole of it.
        bodyScaleX = if (cueHeld) 1f else LIP_REST_SCALE_X
        bodyScaleY = if (cueHeld) 1f else LIP_REST_SCALE_Y
    }

    /** The pill under the mark's word: an edge only, in the same accent. */
    private fun labelBackground(): GradientDrawable = GradientDrawable().apply {
        cornerRadius = dp(20).toFloat()
        setColor(Color.TRANSPARENT)
        setStroke(dp(1), lensColor(R.color.screen_accent))
    }

    /**
     * Re-dresses everything the lens puts on screen in the theme now in force:
     * the disc, the way out and the pill under its word. Called when the app's
     * day/night mode changes, so a lens that was charcoal on the dark screen does
     * not stay charcoal on the paper one.
     */
    private fun restyleChrome() {
        val dark = ThemeChoice.isDark(this)
        // Only a flip of the theme is worth taking a floating card down for; a
        // rotation comes through here too and should leave the card alone.
        val flipped = styledDark != dark
        styledDark = dark

        bubble?.background = lensDrawable(R.drawable.bubble_bg)
        // A vector keeps the colours it was inflated with, so it is inflated again.
        bubbleIcon?.setImageDrawable(lensDrawable(R.drawable.iris))

        dropArch?.background = lipBackground()
        dropDisc?.background = discBackground()
        (dropDisc as? FrameLayout)?.getChildAt(0)?.let { cross ->
            if (cross is ImageView) cross.imageTintList = ColorStateList.valueOf(lensColor(R.color.lens_glyph))
        }
        (dropLabel as? TextView)?.let { word ->
            word.setTextColor(lensColor(R.color.lens_glyph))
            word.background = labelBackground()
        }

        // The card was inflated in the old theme, so it is put away rather than
        // left on screen in colours the page no longer has. The next read builds
        // it again, already dressed for the theme that is now in force.
        if (flipped) hideCard()
    }

    /** Grows the mark while the lens is over it, so the drop reads as aimed. */
    private fun markDropHover(on: Boolean) {
        if (on == hoveringDrop) return
        hoveringDrop = on
        val view = dropTarget ?: return
        view.animate().cancel()
        view.animate()
            .scaleX(if (on) 1.18f else 1f)
            .scaleY(if (on) 1.18f else 1f)
            .setDuration(110)
            .start()
    }

    /** Whether the lens, at this position, is on the delete mark. */
    private fun onDropTarget(lens: WindowManager.LayoutParams): Boolean {
        val half = bubbleSize / 2f
        return DeleteMark.onMark(
            lensCx = lens.x + half,
            lensCy = lens.y + half,
            markCx = dropCx.toFloat(),
            markCy = dropCy.toFloat(),
            reach = dropReach,
        )
    }

    /** Drops the key at once — used when the lens or the whole session goes away. */
    private fun removeDropTarget() {
        folding = false
        val view = dropTarget ?: return
        dropTarget = null
        dropParams = null
        dropArch = null
        dropDisc = null
        dropLabel = null
        dropArmed = false
        cueHeld = false
        stopCue()
        dropReach = 0f
        dropRevealed = false
        hoveringDrop = false
        runCatching { wm.removeView(view) }
    }

    /** The cross: the lens leaves the screen, and the session goes with it. */
    private fun closeLens() {
        toast("Lens off")
        shutdown()
    }

    /* ── capture frame ───────────────────────────────────────────────── */

    /**
     * What a pixel read looks at: the patch of screen the lens itself is on.
     *
     * It used to be a wide strip below the bubble, which was the only way to
     * keep the lens out of its own frame — but then the words under the lens
     * were never in the picture, and the read answered with whatever line
     * happened to be nearest in the strip. The lens now steps out of the frame
     * instead, and the crop follows the lens.
     */
    private fun sightRect(): Rect {
        val params = bubbleParams
        val cy = params?.let { it.y + bubbleSize / 2 } ?: (screenH / 3)

        // A block is as wide as its column, so the frame is too. A square
        // around the lens cut every line of the paragraph at the same place,
        // and the read came back with "ne running terminal codi" — the middle
        // of each line, and none of the words either side of it. Wide, and a
        // paragraph tall, is what a block read needs.
        val width = (screenW - dp(16)).coerceAtLeast(dp(80))
        val height = (bubbleSize + dp(150)).coerceAtMost(screenH - dp(16))
        val left = dp(8)
        val top = (cy - height / 2).coerceIn(dp(8), (screenH - height - dp(8)).coerceAtLeast(dp(8)))
        return Rect(left, top, left + width, top + height)
    }

    /**
     * The lens and the card are windows over the screen, so a captured frame
     * would contain them. They are made transparent for the frame and put back
     * after it: the lens at whichever alpha it is meant to have, the card as it
     * was.
     */
    private fun hideForFrame() {
        bubble?.alpha = 0f
        sight?.alpha = 0f
        // The wash from the last answer sits over the very words about to be
        // read, so it goes with them — and the delete key would land in the
        // frame as a stray cross if the lens is read near the bottom edge.
        highlight?.alpha = 0f
        card?.alpha = 0f
        dropTarget?.alpha = 0f
    }

    private fun backAfterFrame() {
        bubble?.alpha = if (dormant) 0.3f else 1f
        sight?.alpha = 1f
        highlight?.alpha = 1f
        card?.alpha = 1f
        dropTarget?.alpha = dropAlpha()
    }

    private fun showSight() {
        // The crop frame only means something when pixels are being read.
        if (capturer?.isReady != true) return

        val rect = sightRect()
        if (sight == null) {
            val view = SightView(this)
            val params = overlayParams(
                rect.width(),
                rect.height(),
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            ).apply {
                x = rect.left
                y = rect.top
            }
            runCatching { wm.addView(view, params) }
            sight = view
        } else {
            moveSight()
        }
    }

    private fun moveSight() {
        val view = sight ?: return
        val rect = sightRect()
        val params = view.layoutParams as? WindowManager.LayoutParams ?: return
        params.x = rect.left
        params.y = rect.top
        params.width = rect.width()
        params.height = rect.height()
        runCatching { wm.updateViewLayout(view, params) }
    }

    private fun hideSight() {
        val view = sight ?: return
        runCatching { wm.removeView(view) }
        sight = null
    }

    /* ── selection highlight ────────────────────────────────────────── */

    /**
     * A soft wash exactly over the text the lens picked — like Android's
     * native selection highlight. It shows you *what* was read the instant
     * the node is found, before the translation card appears, and it hides
     * with the card.
     */
    private fun showHighlight(
        bounds: Rect,
        text: String,
        from: Int = 0,
        to: Int = text.length,
        chars: Array<RectF?>? = null,
        roomySpacing: Float = TextLines.LINE_HEIGHT,
        anchor: Point? = null,
    ) {
        Log.i(TAG, "highlight $from..$to of ${text.length} in ${bounds.width()}x${bounds.height()}")

        // One band per visible line, tight to the glyphs — the Lens selection
        // look, rather than a slab around the whole bubble. Only the characters
        // that were actually read are covered, cut from the app's own character
        // boxes when it published them.
        val bands = TextLines.bands(
            text,
            bounds,
            resources.displayMetrics.density,
            from,
            to,
            chars,
            roomySpacing,
            anchor,
        )
        showBands(bands, bounds)
    }

    /**
     * The wash over a whole block: every row that was read, each with its own
     * line bands, inside one window the size of the block. A list is therefore
     * covered row by row — the whole text, not the row the lens sat on.
     */
    private fun showBlockHighlight(items: List<LensAccessibilityService.Item>) {
        if (items.isEmpty()) return
        val union = Rect(items.first().bounds)
        items.forEach { union.union(it.bounds) }

        val density = resources.displayMetrics.density
        val bands = ArrayList<Rect>(12)
        for (item in items) {
            bands.addAll(
                TextLines.bands(
                    item.text,
                    item.bounds,
                    density,
                    0,
                    item.text.length,
                    item.chars,
                    item.roomySpacing,
                ),
            )
        }
        Log.i(TAG, "highlight block ${items.size} rows in ${union.width()}x${union.height()}")
        showBands(bands, union)
    }

    /** Puts the wash on screen over [bounds], with one band per line in [bands]. */
    private fun showBands(bands: List<Rect>, bounds: Rect) {
        highlightGen++
        val view: HighlightView
        if (highlight == null) {
            view = HighlightView(this)
            highlight = view
        } else {
            view = highlight!!
        }

        view.select(bands, bounds.left, bounds.top)

        val params = overlayParams(
            bounds.width(),
            bounds.height(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        ).apply {
            x = bounds.left
            y = bounds.top
        }

        if (view.parent == null) {
            runCatching { wm.addView(view, params) }
            // A freshly-added overlay window may not run a property animation
            // until it has been laid out, so set alpha directly on first add.
            view.alpha = 1f
        } else {
            runCatching { wm.updateViewLayout(view, params) }
            // Always: reveal() cancels a fade-out that may still be running.
            view.reveal()
        }
    }

    private fun hideHighlight() {
        val view = highlight ?: return
        val gen = highlightGen
        view.conceal()
        // Remove only if it is still hidden and never re-shown when the fade
        // has finished — a re-show bumps the generation (and the alpha), so
        // the view must survive.
        view.postDelayed({
            if (gen == highlightGen && view.alpha <= 0.05f && highlight === view) {
                runCatching { wm.removeView(view) }
                highlight = null
            }
        }, 240)
    }

    private fun setScanning(on: Boolean) {
        (sight as? SightView)?.scanning = on
        if (on) showLoading() else hideLoading()
        // No background swap: the only signal that it is working is the lit
        // piece of the button's own border running around it.
    }

    /* ── loading ─────────────────────────────────────────────────────── */

    /**
     * A ring drawn around the bubble, so "it is working" is visible at the
     * cursor itself and not only down in the card.
     */
    private fun showLoading() {
        bubbleParams ?: return

        val view = loading
        if (view != null) {
            moveLoading()
            view.start()
            return
        }

        val ring = LoadingRing(this)
        // Exactly the button's size, so the lit piece runs around its own border.
        val size = bubbleSize
        val center = lensCenter()
        val params = overlayParams(
            size,
            size,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        ).apply {
            x = center.x - size / 2
            y = center.y - size / 2
        }

        val added = runCatching { wm.addView(ring, params) }.isSuccess
        if (!added) return

        loading = ring
        loadingParams = params
        ring.start()
    }

    private fun moveLoading() {
        val params = loadingParams ?: return
        val view = loading ?: return
        val size = bubbleSize
        val center = lensCenter()
        params.x = center.x - size / 2
        params.y = center.y - size / 2
        runCatching { wm.updateViewLayout(view, params) }
    }

    private fun hideLoading() {
        val view = loading ?: return
        view.stop()
        runCatching { wm.removeView(view) }
        loading = null
        loadingParams = null
    }

    /* ── pipeline ────────────────────────────────────────────────────── */

    private fun lensCenter(): Point {
        val params = bubbleParams ?: return Point(screenW / 2, screenH / 2)
        return Point(params.x + bubbleSize / 2, params.y + bubbleSize / 2)
    }

    private fun scheduleAutoRead(delayMs: Long) {
        handler.removeCallbacks(autoRead)
        if (dormant || !Prefs.autoTranslate(this)) return
        handler.postDelayed(autoRead, delayMs)
    }

    private fun cancelAutoRead() {
        handler.removeCallbacks(autoRead)
    }

    /**
     * Text access first: the accessibility tree gives the exact string and its
     * bounds, with no pixels and no capture permission involved — but only when
     * the lens is on the words. Anything the tree cannot give up at that spot
     * is read as pixels: an app that hides its text, a photo, a video frame, a
     * sheet drawn over the page.
     */
    private fun readNow(auto: Boolean = false, retry: Int = 2) {
        if (busy || dormant) {
            Log.i(TAG, "read skipped (busy=$busy dormant=$dormant)")
            return
        }

        val center = lensCenter()
        // A read goes into the language kept for the app under the lens, or the
        // app's own when that app has never been given one.
        val pkg = runCatching { LensAccessibilityService.packageAt(center.x, center.y) }.getOrNull()
        readPackage = pkg
        // The settings page lists the apps the lens has been over rather than
        // every app on the phone, so the one under it now is written down. The
        // write is skipped when it is already the newest, so a hover that fires
        // again over the same app costs nothing.
        Prefs.noteAppUse(this, pkg)
        val target = Prefs.targetFor(this, pkg)

        // A fresh read starts a fresh page: whatever the last card could step
        // through belonged to the screen it was read from.
        pageSeq++
        pageSentences = emptyList()
        pageIndex = 0
        pageContext = ""

        if (LensAccessibilityService.isRunning()) {
            // Walking the tree — and asking a node where each of its characters
            // sits — is a call into the app under the lens, so it happens off
            // the main thread and the answer comes back to it to draw.
            val gen = ++lookupGen
            scope.launch {
                val hit = withContext(Dispatchers.Default) {
                    runCatching {
                        LensAccessibilityService.textAt(center.x, center.y)
                    }.getOrNull()
                }
                if (gen != lookupGen || dormant) return@launch

                val boxes = hit?.chars?.count { it != null } ?: 0
                // The words themselves are the user's: only the shape of the
                // hit is logged, which is all a lookup ever needs to be debugged.
                Log.i(
                    TAG,
                    "read at ${center.x},${center.y} hitLen=${hit?.text?.length ?: 0} boxes=$boxes " +
                        "label=${hit?.isLabel} ctx=${hit?.context?.lines()?.count() ?: 0}",
                )

                // A label — an icon's description, a photo's alt text, a card's
                // talkback summary — is not text you can see. Any frame will do
                // here, and the accessibility screenshot is the cheap one: a
                // promoted post exposes its whole card as one description
                // ("Promoted post, … 0 upvotes, 0 comments"), and translating
                // that instead of the words painted in the picture is exactly
                // the answer nobody asked for.
                val canSeePixels = capturer?.isReady == true || LensAccessibilityService.capturesLikeLens()
                val labelsOnly = hit?.isLabel == true && canSeePixels

                // Written exactly where the lens is: a node's box is not its
                // text, so a hit alone does not mean there is anything under it.
                if (hit != null && !labelsOnly && readHit(hit, target, center, auto)) return@launch

                if (hit == null && retry > 0) {
                    // Accessibility trees are rebuilt right after a screen
                    // change; a couple of short second looks catch the moment
                    // the lens lands on fresh text.
                    handler.postDelayed({ readNow(auto, retry = retry - 1) }, 140)
                } else {
                    readPixels(auto, center, target, sawText = hit != null)
                }
            }
            return
        }

        readPixels(auto, center, target, sawText = false)
    }

    /**
     * The tree path: the lens is on a line of real text, so the sentence that
     * line belongs to is read — the same unit the pixel path answers with, and
     * the wash covers exactly its characters.
     *
     * Returns false, having drawn nothing, when the lens is not actually on the
     * text: a node's box is not its glyphs, so a padded row, a chat bubble or a
     * paragraph stretched to a page's column all contain a lot of space its
     * lines do not. Those are the places where a lens that read "whatever node
     * I am inside" would answer for a line it is nowhere near.
     */
    private fun readHit(
        hit: LensAccessibilityService.Hit,
        target: String,
        center: Point,
        auto: Boolean,
    ): Boolean {
        // The block, not the clause: a paragraph is read whole, and a row of a
        // list arrives with the rows beside it. Only a genuine minority script
        // — one foreign word in someone else's sentence — still narrows, which
        // is the one case where the lens is asking for less, not more.
        val focus = if (hit.items.size > 1) {
            TextRun.Focus(hit.text, 0, hit.text.length, narrowed = false)
        } else {
            val run = TextRun.at(
                hit.text,
                hit.bounds,
                center.x,
                center.y,
                resources.displayMetrics.density,
                hit.chars,
                hit.roomySpacing,
            )
            if (run.narrowed) {
                TextRun.sentence(hit.text, run.start, run.end)
            } else {
                TextRun.block(hit.text, run.start, run.end)
            }
        }

        val focusBox = focusBounds(hit.text, hit.bounds, focus, hit.chars, hit.roomySpacing, center)
        // A couple of dp of grace: the band is cut to the glyphs, and a lens
        // aimed at the gap between two lines should still read the line it is in.
        val band = UnderLens.Box(focusBox.left, focusBox.top, focusBox.right, focusBox.bottom)
        if (!UnderLens.holds(band, center.x, center.y, slack = dp(2))) return false

        hideSight()
        // The screen behind this line travels with it to the engine, so a later
        // language switch on this card can ask with the same neighbours.
        readContext = hit.context
        pageContext = hit.context
        // The rest of the page is fetched beside the translation, not before it,
        // so the card can be walked once the answer is there.
        loadPageSentences(center, focus.text)
        markTarget(focusBox)

        // The wash covers everything that was read — every row, every line —
        // not only the sentence the lens happened to be sitting on.
        if (hit.items.size > 1) {
            showBlockHighlight(hit.items)
        } else {
            showHighlight(
                hit.bounds,
                hit.text,
                focus.start,
                focus.end,
                hit.chars,
                hit.roomySpacing,
                center,
            )
        }

        val key = "text|$target|${focus.text}"
        if (key == lastKey && card != null) return true
        lastKey = key

        busy = true
        setScanning(true)
        renderScanning()

        // A late status callback from an earlier read must not repaint the
        // card over this one, so every read carries its generation.
        val gen = ++readGen
        scope.launch {
            val outcome = try {
                translator?.translate(
                    focus.text,
                    target,
                    onStatus = { phase ->
                        handler.post { if (gen == readGen) updateScanningPhase(phase) }
                    },
                    screenContext = hit.context,
                ) ?: Outcome(error = "Translator unavailable.")
            } catch (err: Exception) {
                Outcome(error = "Something went wrong while reading.")
            }
            Log.i(TAG, "outcome error=${outcome.error} empty=${outcome.empty} provider=${outcome.provider}")
            // Cancelled or superseded: this answer is stale, and it must not
            // touch the state of the read that replaced it.
            if (gen != readGen) return@launch
            busy = false
            setScanning(false)
            renderOutcome(outcome, target, auto)
        }
        return true
    }

    /**
     * The pixels themselves — which is how a photo, a video frame, a PDF or
     * anything else merely drawn on screen gets read. It is not a mode the user
     * turns on: with screen capture allowed, this is simply what the lens does
     * when the tree has no text at that spot.
     *
     * [sawText] says a node was under the lens but its text was not, which is
     * worth telling apart from a spot with nothing at all.
     */
    private fun readPixels(auto: Boolean, center: Point, target: String, sawText: Boolean) {
        // Nothing in the tree means nothing to read as context either.
        readContext = ""
        val capture = capturer
        // Two ways to a frame, and the cheaper one is tried first: the phone's
        // own screenshot API on the accessibility service (Android 11+, no
        // dialog), and MediaProjection only where that does not exist or is
        // refused. A phone that has neither can read text and nothing else.
        val byA11y = LensAccessibilityService.capturesLikeLens()
        if (!byA11y && (capture == null || !capture.isReady)) {
            if (auto) {
                // Nothing under the lens: don't leave the last answer behind,
                // it would read as if it described this spot.
                hideCard()
            } else {
                renderError(
                    if (sawText) {
                        "Nothing under the lens — move it onto the words."
                    } else if (Capabilities.silentFrames) {
                        "Nothing here to read."
                    } else {
                        "Nothing here to read. Turn on screen capture in Lucid to read photos and video."
                    },
                )
            }
            return
        }

        busy = true
        setScanning(true)

        val gen = ++readGen
        scope.launch {
            val rect = sightRect()
            val inset = dp(7)
            val crop = Rect(
                rect.left + inset,
                rect.top + inset,
                rect.right - inset,
                rect.bottom - inset,
            )

            // The lens and the card are windows lying on top of the screen, so
            // a frame taken now would contain them, and the lens would be
            // sitting on its own dark disc instead of on the words. They step
            // out of the frame for the grab and come straight back. On Android
            // 14+ the window frame would leave them out anyway, but the lens is
            // out of the picture either way.
            // Stepping out of the frame is only needed when the frame can see
            // us. On Android 14 and later the shot is the app window under the
            // lens, so the lens and the delete key are not in it — and taking
            // them off screen for it was the blink that flashed on every read
            // that found nothing. Where the whole display comes back, they
            // still step out.
            // Which way the frame came, so a read on a real phone can be told
            // apart from a fallback without guessing.
            var frameSource = "none"

            /**
             * The frame itself, with nothing hidden or put back: the caller owns
             * the stepping aside, so a burst costs one step and not one each.
             */
            suspend fun takeShot(wholeDisplay: Boolean): Bitmap? {
                return run {
                    val shot = if (byA11y) {
                        runCatching {
                            withTimeoutOrNull(SCREENSHOT_TIMEOUT_MS) {
                                LensAccessibilityService.shotAt(center, wholeDisplay)
                            }
                        }.getOrNull()
                    } else {
                        null
                    }
                    if (shot != null) {
                        frameSource = if (shot.ofWindow) "a11y-window" else "a11y"
                        a11yRefusals = 0
                        val cut = cropFrom(shot.bitmap, crop, shot.left, shot.top)
                        if (!shot.bitmap.isRecycled) shot.bitmap.recycle()
                        cut
                    } else {
                        // No accessibility frame: the old way, if it was allowed.
                        if (byA11y) {
                            // Only a run of refusals counts. A single one is usually
                            // the system rebinding the service or a busy compositor,
                            // and turning that into "this app needs to record your
                            // screen" would be wrong — the next read works.
                            if (++a11yRefusals >= A11Y_REFUSALS_BEFORE_FALLBACK) {
                                Prefs.setCaptureFallback(this@LensService, true)
                            }
                        }
                        if (capture != null && capture.isReady) {
                            frameSource = "media-projection"
                            withContext(Dispatchers.Default) { capture.grab(crop) }
                        } else {
                            null
                        }
                    }
                }
            }

            // One frame, cut to the lens. [wholeDisplay] asks the phone for the
            // display itself instead of the window under the lens: a window
            // shot of a player can come back with the video layer missing.
            suspend fun grab(wholeDisplay: Boolean): Bitmap? {
                val stepOut = wholeDisplay ||
                    !(byA11y && LensAccessibilityService.framesLeaveOverlaysOut(center))
                if (stepOut) hideForFrame()
                return try {
                    // A beat for the compositor to draw the frame without them.
                    if (stepOut) SystemClock.sleep(GRAB_SETTLE_MS)
                    takeShot(wholeDisplay)
                } finally {
                    if (stepOut) backAfterFrame()
                }
            }

            /** Reads one frame, then gives it back. */
            suspend fun readFrame(frame: Bitmap): Outcome {
                val at = Point(center.x - crop.left, center.y - crop.top)
                return try {
                    translator?.read(frame, at, target, slack = dp(3)) { phase ->
                        handler.post { if (gen == readGen) updateScanningPhase(phase) }
                    } ?: Outcome(error = "Translator unavailable.")
                } catch (err: Exception) {
                    Outcome(error = "Something went wrong while reading.")
                } finally {
                    if (!frame.isRecycled) frame.recycle()
                }
            }

            // A story, a reel, a full-screen player: the text is painted into a
            // surface, and a window frame can arrive without that layer at all.
            // Where the window under the lens is the whole screen, the display
            // frame is the one asked for, so there is nothing to miss first.
            val fullScreen = byA11y && LensAccessibilityService.windowFillsScreen(center)
            val bitmap = grab(wholeDisplay = fullScreen)
            Log.i(TAG, "frame source=$frameSource fullScreen=$fullScreen")

            if (bitmap == null) {
                if (gen == readGen) {
                    busy = false
                    setScanning(false)
                    if (!auto) renderError("Couldn't grab a frame. Try again.")
                }
                return@launch
            }

            // Cancelled while the frame was being taken: nothing left to show.
            if (gen != readGen) {
                if (!bitmap.isRecycled) bitmap.recycle()
                return@launch
            }

            // Nothing is drawn yet. The card and the frame used to go up here,
            // before the frame had even been read — so a lens dropped on blank
            // space answered with a card that said "Reading…" and was taken
            // down again the moment the read came back empty: a flash of a
            // translation that was never going to happen. The lit border on the
            // lens carries "it is working"; the card only appears once there is
            // something to put in it.

            var outcome = readFrame(bitmap)
            // A window frame that found nothing may simply have lost a video
            // layer — a Reel, a clip in a chat. The display frame still has it,
            // so ask once more before answering that this spot is empty.
            if (outcome.empty && frameSource == "a11y-window" && gen == readGen) {
                val again = grab(wholeDisplay = true)
                Log.i(TAG, "frame source=$frameSource empty, retried as display")
                if (again != null && gen == readGen) outcome = readFrame(again)
            }

            // A reel, a story, a player: the picture is never still, so one
            // glimpse can catch a pan between two frames or a caption halfway
            // through a word, and the read comes back as a fragment or as
            // nothing at all. Two more looks, a beat apart, and the read the
            // frames agree on is the one shown. The lens steps out of the
            // picture once for all of them rather than once each, so the screen
            // does not flash on the way.
            // Where the frame came from the screen mirror rather than the phone's
            // own screenshot, the lens is over a picture all the same — that is
            // the path older phones read a reel through.
            val moving = fullScreen || frameSource == "media-projection"
            val thin = outcome.source.count { it.isLetterOrDigit() } < BURST_THIN_GLYPHS
            if (moving && gen == readGen && (outcome.empty || thin)) {
                val reads = arrayListOf(outcome)
                hideForFrame()
                try {
                    SystemClock.sleep(GRAB_SETTLE_MS)
                    var taken = 0
                    while (taken < BURST_EXTRA_FRAMES && gen == readGen) {
                        SystemClock.sleep(BURST_GAP_MS)
                        val frame = takeShot(wholeDisplay = true) ?: break
                        reads += readFrame(frame)
                        taken++
                    }
                } finally {
                    backAfterFrame()
                }
                Log.i(TAG, "burst read ${reads.size} frames")
                outcome = agreedOutcome(reads)
            }

            // Nothing here is the user's words: only the shape of the answer,
            // which is what a pixel read is ever debugged from.
            Log.i(
                TAG,
                "pixels ${bitmap.width}x${bitmap.height} at ${Point(center.x - crop.left, center.y - crop.top)} " +
                    "sections=${outcome.blocks} " +
                    "len=${outcome.source.length} empty=${outcome.empty} error=${outcome.error}",
            )

            // Cancelled or superseded: leave the current read's state alone.
            if (gen != readGen) return@launch
            busy = false
            setScanning(false)

            // Nothing under the lens: drop the old answer rather than leave it
            // hanging where it no longer describes anything.
            if (outcome.empty) {
                hideSight()
                hideCard()
                return@launch
            }
            // There is text under the lens: now the frame around it and the
            // card over it are worth showing.
            showSight()
            outcome.box?.let { box ->
                val rect = Rect(
                    crop.left + box.left,
                    crop.top + box.top,
                    crop.left + box.right,
                    crop.top + box.bottom,
                )
                markTarget(rect)
                showHighlight(rect, outcome.source, 0, outcome.source.length)
            }
            renderOutcome(outcome, target, auto)
        }
    }

    /**
     * [crop] cut out of a frame whose own top-left corner sits at [originX],
     * [originY] on the screen — a window frame starts at the window, not at the
     * display, so the same crop rectangle is shifted by that much.
     */
    private fun cropFrom(frame: Bitmap, crop: Rect, originX: Int, originY: Int): Bitmap? {
        val left = (crop.left - originX).coerceIn(0, (frame.width - 2).coerceAtLeast(0))
        val top = (crop.top - originY).coerceIn(0, (frame.height - 2).coerceAtLeast(0))
        val right = (crop.right - originX).coerceIn(left + 2, frame.width)
        val bottom = (crop.bottom - originY).coerceIn(top + 2, frame.height)
        return runCatching {
            Bitmap.createBitmap(frame, left, top, right - left, bottom - top)
        }.getOrNull()
    }

    /* ── where the card sits ─────────────────────────────────────────── */

    /**
     * What the card hangs from: the run that was read, not the node around it,
     * so hovering one Japanese phrase in a mixed sentence puts the card under
     * that phrase.
     */
    private fun focusBounds(
        text: String,
        bounds: Rect,
        focus: TextRun.Focus,
        chars: Array<RectF?>? = null,
        roomySpacing: Float = TextLines.LINE_HEIGHT,
        anchor: Point? = null,
    ): Rect {
        val bands = TextLines.bands(
            text,
            bounds,
            resources.displayMetrics.density,
            focus.start,
            focus.end,
            chars,
            roomySpacing,
            anchor,
        )
        if (bands.isEmpty()) return bounds
        val union = Rect(bands.first())
        for (band in bands) union.union(band)
        return union
    }

    /** Remember what the lens picked so the card can sit right under it. */
    private fun markTarget(bounds: Rect) {
        val pad = dp(3)
        lastRect = Rect(
            (bounds.left - pad).coerceAtLeast(0),
            (bounds.top - pad).coerceAtLeast(0),
            (bounds.right + pad).coerceAtMost(screenW),
            (bounds.bottom + pad).coerceAtMost(screenH),
        )
    }


    /* ── card ────────────────────────────────────────────────────────── */

    private fun ensureCard(): View {
        card?.let { return it }

        val holder = FrameLayout(this).apply {
            setPadding(dp(6), dp(6), dp(6), dp(6))
            clipToPadding = false
        }
        // Inflated through the lens's own configuration, so the card's colours
        // are the ones of the theme the app is set to.
        val content = LayoutInflater.from(lensContext()).inflate(R.layout.overlay_card, holder, false)
        holder.addView(content)
        holder.elevation = dp(20).toFloat()

        cardStatus = content.findViewById(R.id.statusText)
        cardBody = content.findViewById(R.id.bodyText)
        cardOriginal = content.findViewById(R.id.originalText)
        cardRoman = content.findViewById(R.id.romanText)
        cardWords = content.findViewById(R.id.wordsBox)
        cardLangRow = content.findViewById(R.id.langRow)
        cardSrc = content.findViewById(R.id.srcChip)
        cardDst = content.findViewById(R.id.dstChip)
        cardFoot = content.findViewById(R.id.footRow)
        cardMeta = content.findViewById(R.id.metaText)
        cardCopy = content.findViewById(R.id.copyBtn)
        cardSpinner = content.findViewById(R.id.statusSpinner)
        cardPin = content.findViewById(R.id.pinBtn)
        cardSpeak = content.findViewById(R.id.speakBtn)
        cardMore = content.findViewById(R.id.readMore)
        cardStepRow = content.findViewById(R.id.stepRow)
        cardStepText = content.findViewById(R.id.stepText)
        cardStepPrev = content.findViewById(R.id.stepPrev)
        cardStepNext = content.findViewById(R.id.stepNext)

        val closeBtn = content.findViewById<View>(R.id.closeBtn)
        Press.attach(closeBtn)
        listOfNotNull(
            cardDst, cardSrc, cardCopy, cardPin, cardSpeak, cardMore, cardStepPrev, cardStepNext,
        ).forEach { Press.attach(it) }
        closeBtn.setOnClickListener { hideCard() }

        cardPin?.setOnClickListener { setPinned(!cardPinned) }
        cardSpeak?.setOnClickListener { speakCard() }
        cardMore?.setOnClickListener { openReader() }
        cardStepPrev?.setOnClickListener { stepPage(-1) }
        cardStepNext?.setOnClickListener { stepPage(1) }

        // Both language chips are live. The target chip picks what this app is
        // translated into and keeps it for the app it was picked over, so the
        // next read over the same app already speaks it — that is where an
        // app's own language comes from, and Settings is where it is taken
        // back. The source chip only says how to read this card, so its pick is
        // forgotten with the card.
        cardDst?.setOnClickListener { showLanguageSheet(pickTarget = true) }
        cardSrc?.setOnClickListener { showLanguageSheet(pickTarget = false) }

        // Copy hands over the words that were scanned, not the translation, so
        // what lands in the clipboard is the original message.
        cardCopy?.setOnClickListener {
            cardSourceText.takeIf { it.isNotBlank() }?.let { copyText(it) }
        }

        // A touch anywhere else on the screen takes the card away with it, the way
        // a popup closes when you press outside it. Presses inside still work.
        holder.setOnTouchListener { _, event ->
            if (event.actionMasked != MotionEvent.ACTION_OUTSIDE) return@setOnTouchListener false
            // While the language sheet is up it owns the screen; its scrim
            // closes only the sheet, never the translation behind it.
            if (sheet != null) return@setOnTouchListener false
            // A pinned card is being kept on purpose while the page under it
            // is used, so a press elsewhere passes through instead of closing it.
            if (cardPinned) return@setOnTouchListener false
            // A press outside while it is still reading cancels the read and
            // clears the screen, the same as pressing outside a finished card.
            if (busy) {
                cancelRead()
            } else {
                hideCard()
                lastKey = ""
            }
            false
        }

        val width = minOf(dp(330), screenW - dp(20))
        val params = overlayParams(
            width,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
        ).apply {
            x = dp(10)
            y = screenH / 2
        }

        runCatching { wm.addView(holder, params) }

        holder.post { positionCard() }

        card = holder
        cardParams = params
        return holder
    }

    private fun positionCard() {
        val holder = card ?: return
        val params = cardParams ?: return
        val rect = lastRect ?: sightRect()
        val height = holder.height.takeIf { it > 0 } ?: dp(160)
        val gap = dp(10)

        var top = rect.bottom + gap
        if (top + height > screenH - dp(16)) {
            val above = rect.top - gap - height
            top = if (above >= dp(16)) above else screenH - height - dp(16)
        }

        params.x = dp(10)
        params.y = top.coerceIn(dp(8), (screenH - height - dp(8)).coerceAtLeast(dp(8)))
        runCatching { wm.updateViewLayout(holder, params) }
    }

    private fun hideCard() {
        hideLanguageSheet()
        // The reading sheet is the card's own; it does not outlive it.
        hideReader()
        // Nothing of the card survives it: the pin it was holding, the voice
        // reading it, and the views themselves, which go with the window.
        cardPinned = false
        stopSpeaking()
        cardPin = null
        cardSpeak = null
        // A word list still in flight must not be drawn onto the next card.
        wordsGen++
        readContext = ""
        // The page the card was walking is done with it.
        pageSeq++
        pageSentences = emptyList()
        pageIndex = 0
        pageContext = ""
        val holder = card ?: return
        cardSpinner?.stop()
        runCatching { wm.removeView(holder) }
        card = null
        cardParams = null
        hideHighlight()
    }

    /**
     * A press outside while the lens is still reading: the work in flight is
     * dropped and the screen goes back to the bare lens, exactly as if the
     * answer had already arrived and been dismissed. Bumping the generations
     * is what makes the cancelled read stay gone — it cannot draw itself back
     * over the screen a moment later.
     */
    private fun cancelRead() {
        readGen++
        lookupGen++
        wordsGen++
        busy = false
        setScanning(false)
        hideCard()
        hideSight()
        lastKey = ""
    }

    /* ── live language change ────────────────────────────────────────── */

    /**
     * A dimmed sheet over the card, so either language of the current
     * translation can be changed on the spot.
     *
     * A language picked here while the lens sits over an app is kept for that
     * app: the next read over the same app already speaks it, a chat and a
     * browser can hold different answers, and every other app is left where it
     * was. With no app under the lens the page's own default moves instead.
     * Picking how to *read* ([pickTarget] false) is not a preference at all — it
     * pins the card in front of you and is forgotten with it.
     */
    private fun showLanguageSheet(pickTarget: Boolean, save: Boolean = false) {
        // A chip re-translates what is already on screen, so it needs a card to
        // work on. The lens opens the list on its own.
        if (sheet != null) return
        if (!save && (busy || card == null)) return

        val current = when {
            save -> Prefs.targetFor(this, readPackage)
            pickTarget -> cardTarget.ifBlank { Prefs.targetFor(this, readPackage) }
            // The sheet marks the choice, not the detection: a card left on
            // auto shows Auto detect ticked, whatever the words turned out to be.
            else -> cardSourcePin ?: "auto"
        }

        val panel = LanguagePicker.build(
            lensContext(),
            current,
            title = if (pickTarget) "TRANSLATE INTO" else "READ AS",
            includeAuto = !pickTarget,
            // The sheet is a non-focusable overlay: the keyboard cannot reach
            // it, so here the list is a plain scroll.
            searchable = false,
        ) { lang ->
            hideLanguageSheet()
            if (save) {
                // Picked from the lens: kept for the app it was picked over, so
                // a chat and a browser can hold different languages at once.
                // With no app known it is the app's own answer that moves.
                Prefs.setTargetFor(this, readPackage, lang.code)
                toast("Translating ${appName(readPackage)} into ${lang.label}")
                return@build
            }
            if (!pickTarget) {
                // Picking pins this card to that language; Auto hands the
                // choice back to the detector.
                cardSourcePin = if (lang.code == "auto") null else lang.code
            }
            val same = if (pickTarget) {
                lang.code == cardTarget
            } else {
                lang.code == (cardSourcePin ?: "auto")
            }
            if (pickTarget && lang.code != Prefs.targetFor(this, readPackage)) {
                // Kept for the app under the lens: the next read here already
                // says the same thing, and no other app moves. With no app known
                // this is the page's own default that moves instead.
                Prefs.setTargetFor(this, readPackage, lang.code)
                cardTarget = lang.code
            }
            if (!same) {
                retranslate(
                    target = if (pickTarget) lang.code else cardTarget,
                    sourceHint = when {
                        pickTarget -> cardSourceLang
                        lang.code == "auto" -> null
                        else -> lang.code
                    },
                )
            }
        }

        val panelHolder = FrameLayout(this).apply {
            addView(
                panel,
                FrameLayout.LayoutParams(
                    minOf(dp(320), screenW - dp(24)),
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                ),
            )
            // Taps on the sheet itself must not reach the scrim behind it.
            setOnClickListener { }
        }

        val scrim = FrameLayout(this).apply {
            setBackgroundColor(tint(R.color.scrim))
            addView(
                panelHolder,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    Gravity.CENTER,
                ),
            )
            setOnClickListener { hideLanguageSheet() }
        }

        val params = overlayParams(
            screenW,
            screenH,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        )

        if (runCatching { wm.addView(scrim, params) }.isFailure) return
        sheet = scrim
    }

    private fun hideLanguageSheet() {
        val view = sheet ?: return
        runCatching { wm.removeView(view) }
        sheet = null
    }

    /** Re-run the same source text into [target], optionally pinning the source. */
    private fun retranslate(target: String, sourceHint: String?) {
        val source = cardSourceText
        if (source.isBlank()) return

        ensureCard()
        cardStatus?.text = "TRANSLATING"
        cardStatus?.setTextColor(lensColor(R.color.card_accent))
        cardBody?.text = "Translating…"
        cardBody?.setTextColor(lensColor(R.color.card_muted))
        cardDst?.text = "${Languages.label(target)}  ▾"
        cardFoot?.visibility = View.GONE
        showSpinner()
        busy = true
        card?.post { positionCard() }

        val gen = ++readGen
        scope.launch {
            val outcome = try {
                translator?.translate(source, target, sourceHint = sourceHint, screenContext = readContext)
                    ?: Outcome(error = "Translator unavailable.")
            } catch (err: Exception) {
                Outcome(error = "Couldn't switch language just now.")
            }
            // A press outside while the switch was running cancels it too.
            if (gen != readGen) return@launch
            busy = false

            if (outcome.error != null) {
                stopSpinner()
                cardStatus?.text = "COULDN'T SWITCH"
                cardStatus?.setTextColor(lensColor(R.color.card_danger))
                cardBody?.text = outcome.error
                cardBody?.setTextColor(lensColor(R.color.card_muted))
                cardFoot?.visibility = View.VISIBLE
                card?.post { positionCard() }
            } else {
                renderOutcome(outcome, target)
            }
        }
    }

    private fun renderScanning() {
        ensureCard()
        // A fresh read detects afresh: whatever was pinned belonged to the
        // card before, not to these words, and anything being read aloud is
        // the old answer.
        cardSourcePin = null
        setPinned(false, quiet = true)
        stopSpeaking()
        cardSpeechText = ""
        cardStatus?.text = "READING"
        cardStatus?.setTextColor(lensColor(R.color.card_accent))
        cardBody?.text = "Reading…"
        cardBody?.setTextColor(lensColor(R.color.card_muted))
        cardLangRow?.visibility = View.GONE
        cardOriginal?.visibility = View.GONE
        // The last answer's door and its page count belong to that answer.
        cardMore?.visibility = View.GONE
        cardRoman?.visibility = View.GONE
        clearWords()
        cardFoot?.visibility = View.GONE
        showSpinner()
        card?.post { positionCard() }
    }

    private fun updateScanningPhase(phase: String) {
        val translating = phase == "Translating" || phase == "Trying online"
        cardStatus?.text = if (translating) "TRANSLATING" else "READING"
        cardBody?.text = if (translating) "Translating…" else "Reading…"
        showSpinner()
        card?.post { positionCard() }
    }

    private fun showSpinner() {
        val spinner = cardSpinner ?: return
        spinner.visibility = View.VISIBLE
        spinner.start()
    }

    private fun stopSpinner() {
        cardSpinner?.stop()
        cardSpinner?.visibility = View.GONE
    }

    private fun renderOutcome(outcome: Outcome, target: String, auto: Boolean = false) {
        // A read the lens started by itself, because it came to rest over
        // nothing readable, says nothing at all: the person is moving the lens
        // out of the way, and a card about a spot they are leaving is noise.
        // A tap is a question, and that one still gets an answer.
        if (auto && (outcome.error != null || outcome.empty)) {
            hideSight()
            hideCard()
            return
        }

        if (outcome.error != null) {
            renderError(outcome.error)
            return
        }

        if (outcome.empty) {
            ensureCard()
            stopSpinner()
            cardStatus?.text = "NOTHING FOUND"
            cardStatus?.setTextColor(lensColor(R.color.card_warn))
            cardBody?.text = "No readable text in the frame.\nDrag the lens over a message or caption and tap it again."
            cardBody?.setTextColor(lensColor(R.color.card_muted))
            cardLangRow?.visibility = View.GONE
            cardOriginal?.visibility = View.GONE
            cardMore?.visibility = View.GONE
            cardStepRow?.visibility = View.GONE
            clearWords()
            cardFoot?.visibility = View.GONE
            card?.post { positionCard() }
            return
        }

        ensureCard()
        stopSpinner()

        cardStatus?.text = if (outcome.sameLanguage) "ALREADY ${Languages.label(target).uppercase(Locale.ROOT)}" else "TRANSLATED"
        cardStatus?.setTextColor(lensColor(R.color.card_muted))

        cardSourceText = outcome.source
        cardSourceLang = outcome.detected
        cardTarget = target

        val temporary = target != Prefs.targetFor(this, readPackage)

        cardLangRow?.visibility = View.VISIBLE
        // The left chip says how the source was chosen, then what was heard:
        // it never silently becomes the language it guessed.
        val pin = cardSourcePin
        val heard = outcome.detected
        cardSrc?.text = when {
            pin != null -> "${Languages.short(pin)}  ▾"
            heard != null -> "Auto select (${Languages.short(heard)})  ▾"
            else -> "Auto select  ▾"
        }
        cardDst?.text = "${Languages.label(target)}  ▾"
        // A muted chip marks a target that differs from the saved setting.
        cardDst?.background = ContextCompat.getDrawable(
            this,
            if (temporary) R.drawable.chip_lens_bg else R.drawable.chip_accent_lens_bg,
        )
        cardDst?.setTextColor(lensColor(if (temporary) R.color.card_ink else R.color.card_accent_ink))

        val shown = outcome.translated ?: outcome.source
        // A block written as a list is answered as a list: the source itself
        // says so (bulleted rows, or rows joined with one bullet each), which is
        // cheaper and truer than guessing from the translation.
        val asWritten = if (TextRun.looksLikeList(outcome.source)) TextRun.asBullets(shown) else shown
        // The whole answer is kept; the card shows as much as fits and, where it
        // had to stop, the door to the rest of it.
        cardFullText = asWritten
        val cut = cutForCard(asWritten)
        // The speaker reads the answer, not the cut: hearing half a sentence is
        // the same fault as showing it.
        cardSpeechText = asWritten
        cardSpeechLang = Languages.canonical(target) ?: "en"
        cardBody?.text = cut
        cardBody?.setTextColor(lensColor(R.color.card_ink))
        cardMore?.visibility = if (cut != asWritten) View.VISIBLE else View.GONE

        cardOriginal?.visibility = if (outcome.source.isNotBlank() && outcome.source != shown) View.VISIBLE else View.GONE
        cardOriginal?.setRuby(outcome.source, rubiesOf(outcome))

        // How the line is pronounced, under it in Latin letters. Japanese keeps
        // its own reading above the kanji, so it is not repeated here.
        val roman = if (outcome.sameLanguage) null else Romanisation.of(outcome.source, outcome.detected)
        cardRoman?.text = roman
        cardRoman?.visibility = if (roman.isNullOrBlank()) View.GONE else View.VISIBLE

        // The review signal: a read that actually came back translated.
        if (!outcome.sameLanguage && outcome.translated != null) {
            Prefs.countGoodRead(this)
            // A read the person asked for is remembered; a hover is not, or the
            // history would be a log of the lens drifting across a page.
            if (!auto) {
                Prefs.addRead(
                    this,
                    Prefs.Read(
                        source = outcome.source,
                        translated = asWritten,
                        detected = outcome.detected,
                        target = target,
                        packageName = readPackage,
                        at = System.currentTimeMillis(),
                    ),
                )
            }
        }

        cardFoot?.visibility = View.VISIBLE
        cardMeta?.text = buildList {
            add(if (outcome.provider == "online") "online" else "on-device")
            add(Languages.label(outcome.detected ?: target))
            if (temporary) add("temporary")
        }.joinToString("  ·  ")
        cardCopy?.text = "Copy"
        cardCopy?.setTextColor(lensColor(R.color.card_muted))

        // A page that can be walked shows its place, and stays walkable after
        // the answer lands.
        showStepRow()

        // A language written without spaces gets its words spelled out one by
        // one underneath, filled in a moment after the translation itself.
        clearWords()
        if (outcome.provider == "online" && !outcome.sameLanguage && Words.crowded(outcome.detected)) {
            showWords(outcome, target)
        }

        card?.post { positionCard() }
    }

    /**
     * An answer longer than the card can show is cut at a sentence end rather
     * than mid-word, and the cut is marked. The whole text stays in
     * [cardFullText], which is what the sheet behind the Read button shows.
     */
    private fun cutForCard(text: String): String {
        val clean = text.trim()
        if (clean.length <= CARD_CHARS) return clean

        val room = clean.take(CARD_CHARS)
        val breakAt = listOf(
            room.lastIndexOf(". "),
            room.lastIndexOf("! "),
            room.lastIndexOf("? "),
            room.lastIndexOf("… "),
            room.lastIndexOf(", "),
            room.lastIndexOf(" "),
        ).max()
        val end = if (breakAt > CARD_CHARS / 3) breakAt + 1 else CARD_CHARS
        return clean.take(end).trimEnd().trimEnd(',', ';', ':') + "…"
    }

    /* ── walking a page ──────────────────────────────────────────────── */

    /**
     * A page is cut into sentences, not lines: a line is where the app wrapped
     * it, a sentence is where the thought ends. Newline joins first, so a
     * paragraph broken across three nodes reads as one sentence.
     */
    private fun sentencesOf(page: String?): List<String> {
        if (page.isNullOrBlank()) return emptyList()
        return page.replace('\n', ' ')
            .split(Regex("(?<=[.!?…。！？])\\s+"))
            .map { it.trim() }
            .filter { it.length > 1 }
            .take(200)
    }

    /**
     * The rest of the page, fetched beside the translation so the card can be
     * walked once it has answered. Nothing is drawn if the screen turns out to
     * hold a single sentence: that is a read, not a page.
     */
    private fun loadPageSentences(center: Point, focusText: String) {
        val gen = ++pageSeq
        pageSentences = emptyList()
        pageIndex = 0
        scope.launch {
            val page = withContext(Dispatchers.Default) {
                runCatching { LensAccessibilityService.pageTextAt(center.x, center.y) }.getOrNull()
            }
            if (gen != pageSeq || dormant || card == null) return@launch

            val list = sentencesOf(page)
            if (list.size < 2) return@launch

            pageSentences = list
            // Start where the lens is, so the first tap steps away from the
            // sentence that was actually read rather than from the top.
            val anchor = focusText.trim().take(40)
            pageIndex = if (anchor.isEmpty()) {
                0
            } else {
                list.indexOfFirst { it.contains(anchor, ignoreCase = true) }.coerceAtLeast(0)
            }
            showStepRow()
            card?.post { positionCard() }
        }
    }

    private fun showStepRow() {
        val row = cardStepRow ?: return
        if (pageSentences.size < 2) {
            row.visibility = View.GONE
            return
        }
        row.visibility = View.VISIBLE
        cardStepText?.text = "${pageIndex + 1} / ${pageSentences.size}"
        cardStepPrev?.alpha = if (pageIndex > 0) 1f else 0.35f
        cardStepNext?.alpha = if (pageIndex < pageSentences.lastIndex) 1f else 0.35f
    }

    /** Reads the sentence one step over, in the card, keeping the page count. */
    private fun stepPage(delta: Int) {
        if (pageSentences.size < 2) return
        val next = (pageIndex + delta).coerceIn(0, pageSentences.lastIndex)
        if (next == pageIndex) return
        pageIndex = next
        cardStepNext?.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)

        val sentence = pageSentences[next]
        val target = cardTarget.ifBlank { Prefs.targetFor(this, readPackage) }
        busy = true
        setScanning(true)
        renderScanning()
        // The scan state clears the foot; the count belongs to the page, not to
        // the read, so it is put back at once.
        showStepRow()

        val gen = ++readGen
        scope.launch {
            val outcome = try {
                translator?.translate(sentence, target, screenContext = pageContext)
                    ?: Outcome(error = "Translator unavailable.")
            } catch (err: Exception) {
                Outcome(error = "Something went wrong while reading.")
            }
            if (gen != readGen) return@launch
            busy = false
            setScanning(false)
            renderOutcome(outcome, target)
        }
    }

    /* ── the reading sheet ───────────────────────────────────────────── */

    /**
     * The whole answer at reading size, over a dim page: for the sentence that
     * did not fit the card. The card stays where it was underneath, so closing
     * this gives back exactly what was there.
     */
    private fun openReader() {
        if (reader != null) return
        val full = cardFullText.ifBlank { cardBody?.text?.toString().orEmpty() }
        if (full.isBlank()) return

        val holder = FrameLayout(this)
        val content = LayoutInflater.from(lensContext()).inflate(R.layout.overlay_reader, holder, false)
        holder.addView(content)

        content.findViewById<TextView>(R.id.readerText).text = full
        val original = content.findViewById<TextView>(R.id.readerOriginal)
        val originalLabel = content.findViewById<TextView>(R.id.readerOriginalLabel)
        if (cardSourceText.isNotBlank() && cardSourceText.trim() != full.trim()) {
            original.text = cardSourceText
            original.visibility = View.VISIBLE
            originalLabel.visibility = View.VISIBLE
        }

        val close = content.findViewById<View>(R.id.readerClose)
        val sheet = content.findViewById<View>(R.id.readerCard)
        val copy = content.findViewById<TextView>(R.id.readerCopy)
        val speak = content.findViewById<TextView>(R.id.readerSpeak)
        listOf(close, copy, speak).forEach { Press.attach(it) }
        close.setOnClickListener { hideReader() }
        copy.setOnClickListener { copyText(cardSourceText.ifBlank { full }) }
        speak.setOnClickListener { speakCard() }
        // The dim page closes it; the sheet itself must not.
        content.setOnClickListener { hideReader() }
        sheet.setOnClickListener { }

        val params = overlayParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        )
        runCatching { wm.addView(holder, params) }
        reader = holder
        readerParams = params
    }

    private fun hideReader() {
        val holder = reader ?: return
        runCatching { wm.removeView(holder) }
        reader = null
        readerParams = null
    }

    /**
     * Word by word. The meanings arrive after the translation, in a second and
     * smaller request; the card grows a line per word as they land. An answer
     * that does not line up with the words exactly is dropped, so no word is
     * ever shown a meaning that belongs to its neighbour.
     */
    private fun showWords(outcome: Outcome, target: String) {
        val terms = Words.terms(outcome.source, outcome.detected)
        if (terms.size < 2) return

        val gen = ++wordsGen
        scope.launch {
            val pairs = try {
                translator?.gloss(outcome.source, outcome.detected, target) ?: emptyList()
            } catch (err: Exception) {
                emptyList()
            }
            // Cancelled, superseded, or the card is already gone: say nothing.
            if (gen != wordsGen || card == null || busy) return@launch
            if (pairs.isEmpty()) return@launch

            val box = cardWords ?: return@launch
            box.removeAllViews()
            pairs.take(SHOWN_WORDS).forEach { pair -> box.addView(wordRow(pair)) }
            box.visibility = View.VISIBLE
            // The card just grew taller, so it is re-placed both now and once
            // the new rows have been laid out.
            card?.requestLayout()
            positionCard()
            card?.post { positionCard() }
        }
    }

    /** How many words the card shows; the rest would turn it into a glossary. */
    private val SHOWN_WORDS = 6

    /**
     * One word and its meaning: the word in mint on a fixed column, the
     * meaning beside it, so the meanings form a line the eye can read down.
     */
    private fun wordRow(pair: Words.Pair): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
        }

        val term = TextView(this).apply {
            text = pair.term
            setTextColor(lensColor(R.color.card_accent))
            textSize = 11.5f
            minWidth = dp(92)
            maxWidth = dp(150)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            typeface = ResourcesCompat.getFont(this@LensService, R.font.lucid_sans)
        }

        val meaning = TextView(this).apply {
            text = pair.meaning
            setTextColor(lensColor(R.color.card_muted))
            textSize = 11.5f
            setPadding(dp(12), 0, 0, 0)
            typeface = ResourcesCompat.getFont(this@LensService, R.font.lucid_sans)
        }

        row.addView(term)
        row.addView(meaning)
        return row
    }

    /** Drops the word list, rows and all, before the next one is built. */
    private fun clearWords() {
        cardWords?.removeAllViews()
        cardWords?.visibility = View.GONE
        cardRoman?.text = ""
        cardRoman?.visibility = View.GONE
    }

    /** One app's name, for a toast that has to say which app was remembered. */
    private fun appName(pkg: String?): String {
        val name = pkg?.let { own ->
            runCatching {
                val pm = packageManager
                pm.getApplicationLabel(pm.getApplicationInfo(own, 0)).toString()
            }.getOrNull()
        }
        return name ?: "this app"
    }

    /**
     * The reading of the source's kanji, to sit above them.
     *
     * Only the online engine publishes a reading, and only Japanese gets one.
     * Kanji whose kana cannot be placed with certainty are left bare — a wrong
     * reading under the eye is worse than no reading.
     */
    private fun rubiesOf(outcome: Outcome): List<Furigana.Ruby> {
        val reading = outcome.reading
        if (reading.isNullOrBlank()) return emptyList()
        if ((outcome.detected ?: "").substringBefore('-') != "ja") return emptyList()

        val kana = Furigana.kana(reading) ?: return emptyList()
        return Furigana.of(outcome.source, kana)
    }

    private fun renderError(message: String) {
        ensureCard()
        stopSpinner()
        cardStatus?.text = "TROUBLE"
        cardStatus?.setTextColor(lensColor(R.color.card_danger))
        cardMore?.visibility = View.GONE
        cardStepRow?.visibility = View.GONE
        cardBody?.text = message
        cardBody?.setTextColor(lensColor(R.color.card_muted))
        cardLangRow?.visibility = View.GONE
        cardOriginal?.visibility = View.GONE
        clearWords()
        cardFoot?.visibility = View.GONE
        card?.post { positionCard() }
    }

    private fun copyText(text: String) {
        val clipboard = getSystemService(ClipboardManager::class.java)
        clipboard.setPrimaryClip(ClipData.newPlainText("Lucid", text))
        cardCopy?.text = "Copied"
        cardCopy?.setTextColor(lensColor(R.color.card_accent))
        toast("Copied")
    }

    /* ── holding the card, and hearing it ───────────────────────────── */

    /**
     * The card kept on screen while the app underneath is used.
     *
     * Pinning is for the moment a translation has to be compared with the page,
     * or read while scrolling it: with the pin set, a press anywhere else passes
     * through to the app instead of taking the answer away. Picking the lens up,
     * a fresh read, ✕ and unpinning all still let it go.
     */
    private fun setPinned(on: Boolean, quiet: Boolean = false) {
        cardPinned = on
        cardPin?.imageTintList = ColorStateList.valueOf(
            lensColor(if (on) R.color.card_accent else R.color.card_faint),
        )
        cardPin?.contentDescription = if (on) "Unpin the card" else "Pin the card"
        if (!quiet) toast(if (on) "Pinned — press ✕ to close" else "Unpinned")
    }

    /**
     * The answer read aloud, in the language it is written in.
     *
     * The phone's own voice does the talking: no audio ships with the app, and
     * the words do not leave the phone for this. The button is a switch — a
     * second press, a press elsewhere, a new read or the lens going away all
     * stop it.
     */
    private fun speakCard() {
        if (speaking) {
            stopSpeaking()
            return
        }
        val words = cardSpeechText.trim()
        if (words.isEmpty()) return
        if (tts == null) prepareSpeech()
        val engine = tts ?: return
        if (!ttsReady) {
            toast("Voice is still starting — try again in a moment.")
            return
        }
        val locale = Locale.forLanguageTag(cardSpeechLang)
        val available = engine.isLanguageAvailable(locale)
        if (available == TextToSpeech.LANG_MISSING_DATA ||
            available == TextToSpeech.LANG_NOT_SUPPORTED
        ) {
            toast(
                "No ${Languages.label(cardSpeechLang)} voice on this phone. " +
                    "Add one in Settings › Text-to-speech.",
            )
            return
        }
        engine.language = locale
        val started = engine.speak(words, TextToSpeech.QUEUE_FLUSH, null, UTTERANCE_CARD)
        if (started == TextToSpeech.ERROR) {
            toast("The voice could not read this one.")
            return
        }
        setSpeaking(true)
    }

    /** The phone's speech engine, started once and kept for the session. */
    private fun prepareSpeech() {
        tts = TextToSpeech(this) { status ->
            if (shut) {
                tts?.shutdown()
                tts = null
                return@TextToSpeech
            }
            ttsReady = status == TextToSpeech.SUCCESS
            if (!ttsReady) return@TextToSpeech
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) = Unit

                override fun onDone(utteranceId: String?) {
                    handler.post { setSpeaking(false) }
                }

                @Suppress("OVERRIDE_DEPRECATION")
                override fun onError(utteranceId: String?) {
                    handler.post { setSpeaking(false) }
                }
            })
        }
    }

    private fun setSpeaking(on: Boolean) {
        speaking = on
        cardSpeak?.imageTintList = ColorStateList.valueOf(
            lensColor(if (on) R.color.card_accent else R.color.card_muted),
        )
        cardSpeak?.contentDescription =
            if (on) "Stop reading aloud" else "Speak the translation"
    }

    private fun stopSpeaking() {
        if (speaking) runCatching { tts?.stop() }
        setSpeaking(false)
    }

    /* ── lifecycle ───────────────────────────────────────────────────── */

    private fun shutdown() {
        if (shut) return
        shut = true
        hiddenForLater = false
        stopWatchdog()
        cancelAutoRead()
        hideCard()
        hideSight()
        hideLoading()

        bubble?.let { view -> runCatching { wm.removeView(view) } }
        bubble = null
        bubbleParams = null
        bubbleIcon = null
        removeDropTarget()

        capturer?.release(keepProjection = false)
        capturer = null
        hasCapture = false

        publish(false)
        stopForeground(true)
        stopSelf()
    }

    /**
     * Closing the app closes the lens.
     *
     * The lens is a session of this app, not a second little app of its own.
     * Leaving Lucid with Home keeps it over whatever opens next, because that
     * is what a lens is for — but swiping Lucid out of recents (or Close all)
     * is the person putting the app away, and a bubble left floating with no
     * app behind it is a window only reopening the app can get rid of. So the
     * task leaving ends the session, the same way the cross in the app does.
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        shutdown()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        // Always tear the session down: a stopService() from the quick-settings
        // tile lands here without an ACTION_STOP, and the overlay windows must
        // not outlive the service. shutdown() is idempotent.
        shutdown()
        tts?.stop()
        tts?.shutdown()
        tts = null
        super.onDestroy()
        scope.cancel()
        runCatching { translator?.close() }
    }

    /* ── helpers ─────────────────────────────────────────────────────── */

    private fun screenSize(): Point {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = wm.currentWindowMetrics.bounds
            Point(bounds.width(), bounds.height())
        } else {
            val point = Point()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealSize(point)
            point
        }
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    /** Resolve a palette colour for the overlay, which has no theme of its own. */
    private fun tint(id: Int): Int = ContextCompat.getColor(this, id)

    /**
     * The configuration the lens's own dressing is read through: the app's day
     * or night theme, straight from the preference the screen switches. The lens
     * is a window of its own and must not wait for the system to hand the whole
     * process a new configuration before it changes colour with the app.
     */
    private fun lensContext(): Context {
        val dark = ThemeChoice.isDark(this)
        val config = Configuration(resources.configuration)
        config.uiMode = (config.uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
            (if (dark) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO)
        return createConfigurationContext(config)
    }

    private fun lensColor(id: Int): Int = lensContext().getColor(id)

    private fun lensDrawable(id: Int): android.graphics.drawable.Drawable? =
        ContextCompat.getDrawable(lensContext(), id)

    /**
     * Overlay windows must not be inset by the status/navigation bars, otherwise
     * the drawn window and its input region disagree and taps miss the bubble.
     */
    private fun overlayParams(
        width: Int,
        height: Int,
        flags: Int,
    ): WindowManager.LayoutParams {
        // TYPE_APPLICATION_OVERLAY is the Android 8 window type; before it,
        // TYPE_PHONE is the one an app holding SYSTEM_ALERT_WINDOW may use to
        // float above other apps.
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
        val params = WindowManager.LayoutParams(
            width,
            height,
            type,
            flags,
            PixelFormat.TRANSLUCENT,
        )
        params.gravity = Gravity.TOP or Gravity.START
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            params.fitInsetsTypes = 0
        }
        return params
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}
