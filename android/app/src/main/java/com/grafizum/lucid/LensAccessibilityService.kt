package com.grafizum.lucid

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityService.ScreenshotResult
import android.accessibilityservice.AccessibilityService.TakeScreenshotCallback
import android.app.KeyguardManager
import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Point
import android.graphics.Rect
import android.graphics.RectF
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.view.inputmethod.InputMethodManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

/**
 * Reads text from other apps through the accessibility tree — no screen
 * capture, no "start recording" dialog, no per-app choice.
 *
 * The user switches this on once in Settings › Accessibility. From then on the
 * lens can ask "what text is at this point?" for any app that exposes its text.
 */
class LensAccessibilityService : AccessibilityService() {

    /**
     * [chars] is the on-screen box of every character of [text], when the app
     * publishes them — a real per-character layout, not a guess. A null entry
     * is a character with no box of its own (a newline); the whole array is
     * null when the app does not answer, or answers with the node's own rect
     * for every character, and callers fall back to fitting the node's box.
     *
     * [roomySpacing] is only consulted when the node's box turns out to be too
     * roomy to measure the type from — see [TextLines.layout].
     *
     * [context] is the rest of the screen as text: read in the same pass,
     * never drawn, and handed to the engine so the line under the lens can be
     * read against what surrounds it. Empty for apps with nothing else on show.
     */
    data class Hit(
        val text: String,
        val bounds: Rect,
        val chars: Array<RectF?>? = null,
        val roomySpacing: Float = NATIVE_ROOMY_SPACING,
        val context: String = "",
        /**
         * The rows of the list the lens is on, when it is on one: the row under
         * the lens and its neighbours, each with its own box. Empty for a block
         * that is one node.
         */
        val items: List<Item> = emptyList(),
        /**
         * True when the string is a node's label rather than text you can see:
         * an icon's description, a picture's alt text. It names the thing the
         * lens is over, it is not written on the screen.
         */
        val isLabel: Boolean = false,
    )

    /** One row of a block: its words and the box they sit in. */
    data class Item(
        val text: String,
        val bounds: Rect,
        val chars: Array<RectF?>? = null,
        val roomySpacing: Float = NATIVE_ROOMY_SPACING,
    )

    /**
     * One still frame, and where its top-left corner sits on the screen: the
     * window's own origin for a window capture, (0,0) for the whole display.
     */
    class Shot(val bitmap: Bitmap, val left: Int, val top: Int, val ofWindow: Boolean = false)

    companion object {
        @Volatile
        private var instance: LensAccessibilityService? = null

        /**
         * How tall one line of an app's text stands, as a multiple of its type
         * size, for the cases where the box is too roomy to measure the type
         * from. Android's own line spacing is 1.18, so anything above it here is
         * a box with padding of its own, or a page that set a line height.
         */
        private const val NATIVE_ROOMY_SPACING = 1.4f

        /** The shade's own package: a surface over the app, not a place to go. */
        private const val SYSTEM_UI = "com.android.systemui"

        /** Most rows one read will take in, and the most words they may hold. */
        private const val LIST_ROWS = 8
        // The same budget the block rule reads by, so a long list is not cut
        // shorter than a paragraph of the same length.
        private const val MAX_BLOCK_CHARS = TextRun.MAX_BLOCK_CHARS

        /** A page read for stepping: more than a card, far less than a novel. */
        private const val PAGE_NODE_BUDGET = 900
        private const val PAGE_MAX_CHARS = 6000
        private const val WEB_LATIN_ROOMY_SPACING = 1.35f

        /** Japanese pages run roomier still — near 1.55 by measurement. */
        private const val WEB_CJK_ROOMY_SPACING = 1.55f

        fun isRunning(): Boolean = instance != null

        /** Whether the user has switched the service on, even if it isn't bound yet. */
        fun isEnabled(context: Context): Boolean {
            if (instance != null) return true
            val enabled = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            ) ?: return false

            // The system writes the setting in its short form
            // ("pkg/.Service"), so the long form alone misses every real entry
            // and the setup screen would claim the switch is off right after a
            // restart, before the service has had a chance to bind.
            val component = ComponentName(context, LensAccessibilityService::class.java)
            val full = component.flattenToString()
            val short = component.flattenToShortString()
            return enabled.split(':', ';').any {
                val entry = it.trim()
                entry.equals(full, ignoreCase = true) || entry.equals(short, ignoreCase = true)
            }
        }

        /** The smallest text node whose on-screen bounds contain the point. */
        fun textAt(x: Int, y: Int): Hit? = instance?.findAt(x, y)

        /**
         * Every line the page is showing, in reading order — what the card's
         * sentence stepper walks through. Null when there is nothing to walk:
         * one line is a read, not a page.
         */
        fun pageTextAt(x: Int, y: Int): String? = instance?.pageText(x, y)

        /**
         * The app whose window holds the point, or null where the phone will not
         * say. Used to remember a language per app; a null answer simply leaves
         * the app's own language in charge.
         */
        fun packageAt(x: Int, y: Int): String? = instance?.packageAt(x, y)

        /**
         * True when this phone can hand over a frame with no screen-sharing
         * dialog: Android 11 added a screenshot API to the accessibility
         * service itself, which is the permission the lens already needs.
         * Below that there is no such API and MediaProjection is the only way.
         */
        fun capturesLikeLens(): Boolean =
            Capabilities.silentFrames && instance != null

        /**
         * Whether a frame taken at [point] will leave the lens's own windows out
         * of the picture.
         *
         * Android 14 and later shoot the app window the lens is over, not the
         * display, so nothing of ours is in it. Below that — or with no app
         * window under the point — the whole display comes back, and the lens
         * has to step out of the shot for the frame to be of the page.
         */
        fun framesLeaveOverlaysOut(point: Point): Boolean {
            if (!Capabilities.framesLeaveOverlaysOut) return false
            val service = instance ?: return false
            return runCatching { service.windowAt(point) != null }.getOrDefault(false)
        }

        /**
         * One frame of what is under [point], or null when this phone will not
         * give one (an old version, a refusal, or a window marked secure).
         *
         * A frame in hand also settles a doubt from earlier: whoever asked for
         * it does not need MediaProjection after all. The Screen capture row is
         * brought back by a refusal, and this is what takes it away again — so
         * a refusal that turns out to be a one-off (a rebind, a system hiccup)
         * does not leave the app asking for screen recording forever.
         */
        /**
         * Whether the app window under [point] covers the screen — a story, a
         * reel, a full-screen player. Those draw into a surface, and a window
         * capture can come back without that layer in it, so the caller asks
         * the display for the frame from the start rather than after a miss.
         */
        fun windowFillsScreen(point: Point): Boolean {
            val service = instance ?: return false
            return runCatching { service.fillsScreen(point) }.getOrDefault(false)
        }

        suspend fun shotAt(point: Point, wholeDisplay: Boolean = false): Shot? {
            val service = instance ?: return null
            val shot = service.shot(point, wholeDisplay) ?: return null
            if (Prefs.captureFallback(service)) Prefs.setCaptureFallback(service, false)
            return shot
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.i("LucidA11y", "connected (pid ${android.os.Process.myPid()})")
        LensState.publish()
        // The switch landed: the card that pointed at it is done, and it must
        // not be left floating over the list it was pointing into.
        SetupNudge.hide()
        // The rules are watched from here as well as from the events below: a
        // phone that hands out window events is answered the instant the app
        // arrives, and one that stays quiet still gets there within a beat.
        handler.removeCallbacks(rulesWatch)
        handler.postDelayed(rulesWatch, 1200L)
        // Just granted, in answer to a page of ours: hand the person back to it
        // instead of leaving them to find their own way home. A short beat, so
        // Android's own confirmation is off the screen before ours arrives.
        handler.postDelayed({ AccessReturn.come(this) }, 400L)
    }

    override fun onDestroy() {
        instance = null
        handler.removeCallbacks(rulesWatch)
        Log.i("LucidA11y", "destroyed")
        LensState.publish()
        super.onDestroy()
    }

    /**
     * The only event that matters here: an app arriving at the front.
     *
     * Text is still read on demand — nothing is followed live — but a rule the
     * person wrote ("open the lens over this app") is a promise made in
     * advance, and this is where it is kept.
     */
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        considerForeground(event.packageName?.toString())
    }

    override fun onInterrupt() = Unit

    private val handler = Handler(Looper.getMainLooper())

    /**
     * The watchdog behind the rules: read which app is at the front and let
     * [considerForeground] decide. It costs nothing while no app is chosen, and
     * a package read is all it takes when one is.
     */
    private val rulesWatch = object : Runnable {
        override fun run() {
            try {
                if (Prefs.autoApps(this@LensAccessibilityService).isNotEmpty()) {
                    considerForeground(frontPackage())
                }
            } catch (t: Throwable) {
                // A read that fails is a beat lost, not a service down.
                Log.i("LucidA11y", "watch failed: ${t.javaClass.simpleName}")
            }
            handler.postDelayed(this, 1500L)
        }
    }

    /** The app whose window is in front, or null where the phone will not say. */
    private fun frontPackage(): String? {
        val active = runCatching { rootInActiveWindow?.packageName?.toString() }.getOrNull()
        if (!active.isNullOrBlank()) return active
        return runCatching {
            windows.firstOrNull { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
                ?.root?.packageName?.toString()
        }.getOrNull()
    }

    /**
     * An app has reached the front: keep the rule the person wrote, or drop the
     * memory of the last one. Ourselves and the shade are not arrivals — the
     * lens sits over the app, and a notification pulled down is not the app
     * leaving.
     */
    private fun considerForeground(pkg: String?) {
        if (pkg.isNullOrBlank()) return
        if (pkg == packageName) return
        if (pkg == SYSTEM_UI) return
        // A keyboard, a permission sheet, the shade: a window over an app is not
        // the app leaving. Only something with a screen of its own to open is an
        // arrival — otherwise typing one word inside a ruled app would read as a
        // departure and a return, and the rule would put the lens back over the
        // words the person is writing.
        if (!isApp(pkg)) return
        if (pkg == foreground) return
        foreground = pkg

        val ruled = Prefs.autoOpenIn(this, pkg)
        if (BuildConfig.DEBUG) {
            Log.i("LucidA11y", "front=$pkg ruled=$ruled running=${LensService.isRunning}")
        }
        if (!ruled) {
            // A different app is at the front: the rule for the last one is
            // spent, and opens again the next time that app is opened.
            lastAuto = null
            return
        }
        // Already open, or opened for this app a moment ago: nothing to do.
        if (LensService.isRunning || lastAuto == pkg) return
        // No switches from here: a rule cannot conjure a permission, and the
        // home screen is where both are granted.
        if (!Settings.canDrawOverlays(this)) return
        val keyguard = getSystemService(KeyguardManager::class.java)
        if (keyguard?.isKeyguardLocked == true) return

        lastAuto = pkg
        runCatching { LensService.start(this) }
            .onFailure { Log.i("LucidA11y", "auto-open refused: ${it.javaClass.simpleName}") }
    }

    /**
     * True when [pkg] is an app someone can open: it has a screen to launch and
     * it is not a keyboard. Both halves matter — Gboard ships a launchable
     * settings activity, so "has a launcher" alone would count the keyboard as
     * an app, and a phone with an IME would then re-open the lens on every word.
     */
    private fun isApp(pkg: String): Boolean {
        if (pkg in imePackages) return false
        return runCatching { packageManager.getLaunchIntentForPackage(pkg) != null }.getOrDefault(false)
    }

    /** The keyboards installed on this phone, read once and kept. */
    private val imePackages: Set<String> by lazy {
        runCatching {
            getSystemService(InputMethodManager::class.java)
                ?.enabledInputMethodList
                ?.mapNotNull { it.packageName }
                ?.toSet()
                ?: emptySet()
        }.getOrDefault(emptySet())
    }

    /** The app at the front last, so a rule fires on arrival and not on every
     *  window change the app makes while it stays there. */
    private var foreground: String? = null

    /** The app a rule already opened the lens for, until another app arrives. */
    private var lastAuto: String? = null

    private class Candidate(
        val node: AccessibilityNodeInfo,
        val text: String,
        /** Where [text] starts inside the node's raw string. */
        val offset: Int,
        /** True when [text] is the node's own text, so its character boxes line up. */
        val locatable: Boolean,
        val bounds: Rect,
    )

    private fun findAt(x: Int, y: Int): Hit? {
        // Only what the lens is actually over. Reaching for the nearest text
        // within a few dp reads a line the lens is nowhere near, which on a
        // page with text everywhere reads as the lens answering at random —
        // so a lens over blank space answers nothing instead.
        val hit = pointHit(x, y) ?: return null

        // Asking a node for its character geometry is a call into the app that
        // owns it, so this only runs on the caller's background thread.
        val chars = if (hit.locatable) characterBoxes(hit.node, hit.text.length + hit.offset) else null
        val shown = chars?.let { boxes ->
            Array(hit.text.length) { i ->
                val at = hit.offset + i
                if (at in boxes.indices) boxes[at] else null
            }
        }

        // Everything past the last glyph that is actually inside the visible
        // box is text nobody can see: the tail of an ellipsized line, or a
        // paragraph running past the bottom of the block it was given. Reading
        // it is how the card ends up answering with words that are not there.
        val cut = shown?.let { lastVisibleIndex(it, hit.bounds) + 1 } ?: hit.text.length
        if (shown != null && cut <= 0) return null
        val text = if (cut in 1 until hit.text.length) hit.text.substring(0, cut) else hit.text

        val roomy = roomySpacingOf(hit.node, text)
        val boxes = shown?.let { array -> Array(text.length) { i -> array[i] } }

        // One row of a list is not the whole thing to read: the rows around it
        // are read with it, so a bulleted list comes back a list instead of the
        // one line the lens happened to sit on.
        val rows = if (hit.locatable) listRows(hit.node) else emptyList()
        if (rows.size > 1) {
            val union = Rect(rows.first().bounds)
            rows.forEach { union.union(it.bounds) }
            val joined = rows.joinToString("\n") { "\u2022  ${it.text}" }
            return Hit(
                text = joined,
                bounds = union,
                chars = null,
                roomySpacing = roomy,
                context = screenContext(joined),
                isLabel = false,
                items = rows,
            )
        }

        return Hit(
            text = text,
            bounds = hit.bounds,
            chars = boxes,
            roomySpacing = roomy,
            context = screenContext(text),
            isLabel = !hit.locatable,
        )
    }

    /**
     * The rows of the list [node] belongs to, when it belongs to one.
     *
     * Only a container that says it is a collection is trusted: a chat, a feed
     * or a page of paragraphs is a stack of nodes too, and reading every one of
     * them because the lens touched one would hand back a screenful. Rows are
     * capped in number and in characters so the gesture stays a gesture.
     */
    private fun listRows(node: AccessibilityNodeInfo): List<Item> {
        val list = collectionParent(node) ?: return emptyList()
        val rows = ArrayList<Item>(8)
        val screen = Rect(0, 0, resources.displayMetrics.widthPixels, resources.displayMetrics.heightPixels)
        val box = Rect()
        var total = 0

        for (index in 0 until minOf(list.childCount, LIST_ROWS)) {
            val row = runCatching { list.getChild(index) }.getOrNull() ?: continue
            if (!runCatching { row.isVisibleToUser }.getOrDefault(false)) continue

            val label = labelOf(row, allowDescription = descriptionAllowed(row)) ?: continue
            row.getBoundsInScreen(box)
            if (box.isEmpty || !box.intersect(screen)) continue

            val text = label.text
            if (text.length < 2) continue
            total += text.length
            if (total > MAX_BLOCK_CHARS) break

            rows.add(
                Item(
                    text = text,
                    bounds = Rect(box),
                    chars = if (label.locatable) characterBoxes(row, text.length) else null,
                    roomySpacing = roomySpacingOf(row, text),
                ),
            )
            if (rows.size >= LIST_ROWS) break
        }

        // Only a list written as a list: two of its rows have to open with a
        // bullet or a number. A screen of settings, a chat and a feed are
        // columns of rows too, and reading eight of them because the lens
        // touched one would be a screenful, not a read.
        val marked = rows.count { TextRun.startsWithBullet(it.text) }
        return if (rows.size >= 2 && marked >= 2) rows else emptyList()
    }

    /** The collection the node sits in, at most a few levels up. */
    private fun collectionParent(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var level = node
        repeat(3) {
            val parent = runCatching { level.parent }.getOrNull() ?: return null
            val info = runCatching { parent.collectionInfo }.getOrNull()
            if (info != null && info.rowCount in 2..LIST_ROWS) return parent
            level = parent
        }
        return null
    }

    /**
     * Everything else the screen is showing, in reading order.
     *
     * This is a second walk — [pointHit] prunes to the point, this one has to
     * visit the whole tree — so it is bounded by nodes and characters and runs
     * only on the caller's background thread, once per read. Lines that repeat
     * (list rows, toolbars) collapse, and the line under the lens is left out:
     * it travels as the line itself.
     */
    private fun screenContext(exclude: String): String {
        val out = ArrayList<String>(32)
        val seen = HashSet<String>()
        seen += exclude.trim()
        var chars = 0
        var visited = 0

        val box = Rect()
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        roots().forEach { stack.addLast(it) }

        while (stack.isNotEmpty() && visited < NODE_BUDGET) {
            val node = stack.removeFirst()
            visited++

            if (runCatching { node.isVisibleToUser }.getOrDefault(false)) {
                // A card's summary is not text on the screen, so it is no more
                // context than it is something to read.
                labelOf(node, allowDescription = descriptionAllowed(node))?.let { label ->
                    if (label.text.length < MAX_LINE && seen.add(label.text)) {
                        out += label.text
                        chars += label.text.length
                    }
                }
            }
            if (chars >= Contexts.MAX_CHARS) break

            runCatching { node.getBoundsInScreen(box) }
            if (box.isEmpty) continue
            for (index in 0 until node.childCount) {
                val child = node.getChild(index) ?: continue
                stack.addLast(child)
            }
        }

        return out.joinToString("\n")
    }

    /**
     * The page as a reader would take it in: every visible line, depth first so
     * the order is the tree's own (a paragraph before the next section), with
     * repeated lines — a toolbar's title, a pinned header — collapsed. Bounded
     * in nodes and characters because this walks the whole tree.
     */
    private fun pageText(x: Int, y: Int): String? {
        val out = ArrayList<String>(64)
        val seen = HashSet<String>()
        var chars = 0
        var visited = 0

        val stack = ArrayDeque<AccessibilityNodeInfo>()
        roots().forEach { stack.addLast(it) }

        while (stack.isNotEmpty() && visited < PAGE_NODE_BUDGET && chars < PAGE_MAX_CHARS) {
            val node = stack.removeFirst()
            visited++

            if (runCatching { node.isVisibleToUser }.getOrDefault(false)) {
                // A description is a label, not a line on the page.
                labelOf(node, allowDescription = false)?.let { label ->
                    val line = label.text.trim()
                    if (line.length >= 2 && seen.add(line)) {
                        out += line
                        chars += line.length
                    }
                }
            }

            // Children in order, so the walk reads the page top to bottom.
            for (index in node.childCount - 1 downTo 0) {
                val child = runCatching { node.getChild(index) }.getOrNull() ?: continue
                stack.addLast(child)
            }
        }

        if (out.size < 2) return null
        return out.joinToString("\n")
    }

    /**
     * How tall one line of this app's text stands, for boxes too roomy to
     * measure. A web page sets its own line height in CSS and Japanese pages
     * run roomier than Latin ones; a native app relies on Android's default
     * unless its control is padded, which is the common case for a box that
     * came out roomy.
     */
    private fun roomySpacingOf(node: AccessibilityNodeInfo, text: String): Float {
        val web = node.extras?.keySet()?.any { key ->
            key.endsWith(".cssDisplay") || key.endsWith(".chromeRole")
        } == true
        if (!web) return NATIVE_ROOMY_SPACING
        // A page sets one line height for its whole body, and Japanese pages mix
        // kanji, kana and Latin inside a sentence — so any CJK at all in the line
        // is taken as the page's convention, not a share of it.
        return if (text.any(::isCjk)) WEB_CJK_ROOMY_SPACING else WEB_LATIN_ROOMY_SPACING
    }

    private fun isCjk(ch: Char): Boolean {
        val code = ch.code
        return code in 0x3040..0x30FF ||   // kana
            code in 0x3400..0x4DBF ||      // kanji, extension A
            code in 0x4E00..0x9FFF ||      // kanji
            code in 0xF900..0xFAFF ||      // kanji, compatibility
            code in 0xAC00..0xD7AF        // hangul
    }

    /**
     * The app's own per-character boxes, or null when it does not publish them.
     * Chrome, the system UI and most text views answer this; the boxes come back
     * in screen coordinates to match the other rectangles we hold.
     */
    private fun characterBoxes(node: AccessibilityNodeInfo, length: Int): Array<RectF?>? {
        // Asking a node where each character sits arrived with Android 8. Below
        // it there is nothing to ask, and the band is fitted from the node's box
        // instead — the same answer a view that publishes no boxes gets.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return null
        if (length <= 0) return null
        if (length > AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_ARG_MAX_LENGTH) return null

        val args = Bundle().apply {
            putInt(AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_ARG_START_INDEX, 0)
            putInt(AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_ARG_LENGTH, length)
            putBoolean(AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_IN_WINDOW_KEY, false)
        }

        val refreshed = runCatching {
            node.refreshWithExtraData(
                AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_KEY,
                args,
            )
        }.getOrNull() ?: return null
        if (!refreshed) return null

        val extras = runCatching { node.extras }.getOrNull() ?: return null
        extras.classLoader = RectF::class.java.classLoader
        @Suppress("DEPRECATION")
        val raw = runCatching {
            extras.getParcelableArray(AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_KEY)
        }.getOrNull() ?: return null

        val boxes = arrayOfNulls<RectF>(length)
        var found = false
        for (i in 0 until minOf(length, raw.size)) {
            val box = raw[i] as? RectF ?: continue
            if (box.width() <= 0f || box.height() <= 0f) continue
            boxes[i] = box
            found = true
        }

        // Some apps — Chrome among them — answer with the node's own rect for
        // every character. That is not per-character geometry, and drawing from
        // it would wash the whole line, so it is refused and the fit is used.
        val distinct = boxes.filterNotNull().distinctBy { listOf(it.left, it.top, it.right, it.bottom) }
        return if (found && distinct.size >= 2) boxes else null
    }

    /**
     * The last character whose glyph box sits inside [bounds]. Everything after
     * it was clipped away by the app — the tail of an ellipsized line, a
     * paragraph running past the block it was given — and is not on screen, so
     * it must not travel to the translator as if the lens were over it.
     */
    private fun lastVisibleIndex(chars: Array<RectF?>, bounds: Rect): Int {
        // A pixel of grace: a glyph sitting exactly on the edge is still drawn.
        val room = Rect(bounds.left - 1, bounds.top - 1, bounds.right + 1, bounds.bottom + 1)
        val box = Rect()
        for (i in chars.indices.reversed()) {
            val at = chars[i] ?: continue
            box.set(at.left.toInt(), at.top.toInt(), at.right.toInt(), at.bottom.toInt())
            if (Rect.intersects(room, box)) return i
        }
        return -1
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    /* ── pixels, without a screen-sharing dialog ─────────────────────── */

    /**
     * The frame the lens needs when the tree has no text: a photo, a video
     * frame, an app that hides its words.
     *
     * On Android 14 and later this is the window the lens is over, so the lens,
     * the card and the delete key are not in the picture at all. On Android 11
     * to 13 it is the whole display, and the caller takes the lens off screen
     * for the grab. Either way no dialog is shown and nothing is recorded.
     */
    private suspend fun shot(point: Point, wholeDisplay: Boolean = false): Shot? {
        // The same thing the capability says, said where the screenshot calls
        // below can see it: taking a frame with the accessibility service itself
        // is Android 11 and later.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        if (!Capabilities.silentFrames) return null

        val window = windowAt(point)
        val box = window?.let { boundsOf(it) }

        // A window frame first where the version supports it: it leaves our own
        // overlays out and reports a secure window as a refusal rather than as
        // a black rectangle. [wholeDisplay] skips it: a window shot of a player
        // can arrive with the video layer missing, and only the display frame
        // has the picture in it.
        var bitmap: Bitmap? = null
        var fromWindow = false
        if (Capabilities.framesLeaveOverlaysOut && window != null && !wholeDisplay) {
            bitmap = snapshot(window.id, ofWindow = true)
            fromWindow = bitmap != null
        }
        if (bitmap == null || wholeDisplay) bitmap = snapshot(Display.DEFAULT_DISPLAY, ofWindow = false)
        val frame = bitmap ?: return null

        val left = if (fromWindow) box?.left ?: 0 else 0
        val top = if (fromWindow) box?.top ?: 0 else 0
        return Shot(frame, left, top, fromWindow)
    }

    /** The application window holding [point], for a window capture. */
    /** The package of the window under the point, for the per-app language. */
    private fun packageAt(x: Int, y: Int): String? {
        val window = windowAt(Point(x, y))
        val own = runCatching { window?.root?.packageName?.toString() }.getOrNull()
        if (!own.isNullOrBlank()) return own
        return runCatching { rootInActiveWindow?.packageName?.toString() }.getOrNull()
    }

    private fun windowAt(point: Point): AccessibilityWindowInfo? {
        val open = runCatching { windows }.getOrNull() ?: return null
        val box = Rect()
        for (window in open) {
            val type = runCatching { window.type }.getOrDefault(-1)
            if (type != AccessibilityWindowInfo.TYPE_APPLICATION) continue
            val onScreen = runCatching { window.getBoundsInScreen(box) }.isSuccess
            if (onScreen && box.contains(point.x, point.y)) return window
        }
        return null
    }

    /** True when the window under [point] is the display, give or take a bar. */
    private fun fillsScreen(point: Point): Boolean {
        val window = windowAt(point) ?: return false
        val box = boundsOf(window) ?: return false
        val metrics = resources.displayMetrics
        return box.width() >= metrics.widthPixels * 0.9f &&
            box.height() >= metrics.heightPixels * 0.9f
    }

    private fun boundsOf(window: AccessibilityWindowInfo): Rect? =
        runCatching { Rect().also { window.getBoundsInScreen(it) } }.getOrNull()

    /**
     * One screenshot call, turned into a bitmap the caller owns. The hardware
     * buffer is closed as soon as the pixels are copied; nothing is kept.
     */
    @androidx.annotation.RequiresApi(Build.VERSION_CODES.R)
    private suspend fun snapshot(id: Int, ofWindow: Boolean): Bitmap? =
        withContext(Dispatchers.Default) {
            suspendCancellableCoroutine { cont ->
                val answered = AtomicBoolean(false)
                val callback = object : TakeScreenshotCallback {
                    override fun onSuccess(result: ScreenshotResult) {
                        if (!answered.compareAndSet(false, true)) return
                        val bitmap = runCatching {
                            val buffer = result.hardwareBuffer
                            val wrapped = Bitmap.wrapHardwareBuffer(buffer, result.colorSpace)
                            val copy = wrapped?.copy(Bitmap.Config.ARGB_8888, false)
                            buffer.close()
                            if (wrapped != null && wrapped !== copy) wrapped.recycle()
                            copy
                        }.getOrNull()
                        if (cont.isActive) cont.resume(bitmap)
                    }

                    override fun onFailure(errorCode: Int) {
                        if (!answered.compareAndSet(false, true)) return
                        Log.i("LucidA11y", "screenshot refused (code $errorCode)")
                        if (cont.isActive) cont.resume(null)
                    }
                }

                val started = runCatching {
                    // A window shot is Android 14 and later; older versions have
                    // only the whole display to offer.
                    if (ofWindow && Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                        takeScreenshotOfWindow(id, mainExecutor, callback)
                    } else {
                        takeScreenshot(id, mainExecutor, callback)
                    }
                }
                if (started.isFailure) {
                    Log.i("LucidA11y", "screenshot call failed: ${started.exceptionOrNull()}")
                    if (answered.compareAndSet(false, true) && cont.isActive) cont.resume(null)
                }
            }
        }

    /** How far one context walk visits before it stops: a read, not a crawl. */
    private val NODE_BUDGET = 500

    /** One context line capped; a whole paragraph is not worth a hint. */
    private val MAX_LINE = 240

    /**
     * The trees worth reading: activity windows only. The lens bubble, the
     * card and the language sheet are windows too, and their trees hold this
     * app's own answers — those must never come back as context around them.
     */
    private fun roots(): List<AccessibilityNodeInfo> {
        val open = ArrayList<AccessibilityWindowInfo>(8)
        runCatching { windows.forEach { open.add(it) } }

        val appWindows = open.filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
        val roots = ArrayList<AccessibilityNodeInfo>(8)
        (if (appWindows.isNotEmpty()) appWindows else open).forEach { window ->
            window.root?.let { roots.add(it) }
        }

        if (roots.isEmpty()) {
            rootInActiveWindow?.let { roots.add(it) }
        }
        return roots
    }

    private fun pointHit(x: Int, y: Int): Candidate? {
        val candidates = ArrayList<Candidate>(24)
        val box = Rect()
        // What can be seen at all. A row scrolled past the viewport, a paragraph
        // the page lets hang outside its column and a block hidden behind a
        // collapsed "show more" all still report their full box and their text;
        // nobody can see them, and reading them is the bug where the lens
        // answers with something that is not on the screen under it.
        val screen = Rect(0, 0, resources.displayMetrics.widthPixels, resources.displayMetrics.heightPixels)

        for (root in roots()) {
            runCatching { collect(root, x, y, candidates, box, 0, screen) }
        }

        // Exactly the text node under the lens: no climbing to the container
        // around it, so what gets highlighted is only what you pointed at.
        // The smallest box wins, which is the innermost node holding this point.
        return candidates.minByOrNull { it.bounds.width().toLong() * it.bounds.height() }
    }

    private fun collect(
        node: AccessibilityNodeInfo,
        x: Int,
        y: Int,
        out: MutableList<Candidate>,
        box: Rect,
        depth: Int,
        clip: Rect,
    ) {
        if (depth > 48) return

        node.getBoundsInScreen(box)
        if (box.isEmpty) return

        // Only the part of the node that is inside everything above it can be
        // pointed at: a node's own box is not clipped to its parents'.
        val visible = Rect(box)
        if (!visible.intersect(clip)) return
        if (!visible.contains(x, y)) return

        if (node.isVisibleToUser) {
            labelOf(node, allowDescription = descriptionAllowed(node))?.let {
                out.add(Candidate(node, it.text, it.offset, it.locatable, Rect(visible)))
            }
        }

        for (index in 0 until node.childCount) {
            val child = node.getChild(index) ?: continue
            collect(child, x, y, out, box, depth + 1, visible)
        }
    }

    /**
     * Whether the text a node shows is in its children rather than in itself.
     *
     * A card — a repository, a chat row, a video tile — often carries a
     * content description that summarises it for TalkBack, including facts that
     * are nowhere on the screen (an internal id, a count, a date). When its
     * children actually carry visible words, those are what the lens should
     * read, and the summary is not text at all.
     */
    private fun descriptionAllowed(node: AccessibilityNodeInfo): Boolean {
        // The node's own text beats its description anyway, and an icon has no
        // children to read, so only the middle case costs a look at the tree.
        if (!node.text.isNullOrBlank()) return true
        if (node.childCount == 0) return true
        return !hasReadableChild(node)
    }

    private fun hasReadableChild(node: AccessibilityNodeInfo): Boolean {
        for (index in 0 until minOf(node.childCount, 4)) {
            val child = runCatching { node.getChild(index) }.getOrNull() ?: continue
            val text = child.text?.toString().orEmpty()
            if (text.count(Char::isLetter) >= 2) return true
            val description = child.contentDescription?.toString().orEmpty()
            if (description.count(Char::isLetter) >= 2) return true
        }
        return false
    }

    private class Label(val text: String, val offset: Int, val locatable: Boolean)

    private fun labelOf(node: AccessibilityNodeInfo, allowDescription: Boolean = true): Label? {
        val raw = node.text?.toString().orEmpty()
        val text = raw.trim()
        val description = node.contentDescription?.toString()?.trim().orEmpty()

        if (text.length >= description.length || !allowDescription) {
            if (text.length < 2 || text.none(Char::isLetter)) return null
            // Trimming the node's string shifts its character indices. A
            // content description has no character boxes at all.
            return Label(text, raw.indexOf(text).coerceAtLeast(0), locatable = true)
        }

        if (description.length < 2 || description.none(Char::isLetter)) return null
        return Label(description, 0, locatable = false)
    }
}
