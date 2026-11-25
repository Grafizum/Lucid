package com.grafizum.lucid

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.util.Log
import android.widget.Toast

/**
 * The quick-settings tile: turn the lens on or off from the notification
 * shade, without opening Lucid at all.
 *
 * The tile is an ACTIVE tile, so the system binds it whenever the shade is
 * open and this service can keep its state in sync with the lens session.
 */
class LensTileService : TileService() {

    companion object {
        private const val TAG = "LucidLens"
        private val handler = Handler(Looper.getMainLooper())

        /**
         * The tile that is on screen right now, when the shade holds one. A
         * shade that is already open keeps its own instance, and that instance
         * is the only thing that can repaint the button while it stays open.
         */
        @Volatile
        private var bound: LensTileService? = null

        /**
         * Ask the tile to re-read the lens state. Safe to call anywhere.
         *
         * The bound instance is repainted directly: requestListeningState only
         * wakes a tile that is *not* listening yet, so a shade that was already
         * out kept its last picture. That is how the button went on reading
         * "on" for a lens that had just been switched off.
         */
        fun refresh(context: Context) {
            bound?.let { tile -> handler.post { runCatching { tile.refreshTile() } } }
        }

        /**
         * Asks the shade to hand this tile over, once.
         *
         * A button dragged in by hand says nothing to Lucid: the tile's
         * callback never reaches a process that is not running, and the list
         * the shade keeps its buttons in has been closed to apps since Android
         * 14. The one answer the platform still gives is this call — a tile
         * that is in the shade is bound and told it is listening, one that is
         * not is left alone — so the row learns the truth the moment the person
         * is back on it.
         */
        fun ask(context: Context) {
            runCatching {
                requestListeningState(
                    context,
                    ComponentName(context, LensTileService::class.java),
                )
            }
        }
    }

    override fun onTileAdded() {
        super.onTileAdded()
        // The app's own row reads this, so it can say "Added" the moment the
        // user drops the button into the shade.
        Prefs.setTileAdded(this, true)
        LensState.publish()
    }

    override fun onTileRemoved() {
        super.onTileRemoved()
        Prefs.setTileAdded(this, false)
        LensState.publish()
    }

    override fun onStartListening() {
        bound = this
        super.onStartListening()
        // Only the shade binds this to listen — or [ask] on the app's behalf —
        // so a listening tile is a tile that is in the shade. It is written
        // here as well as in onTileAdded because a button added while Lucid was
        // not running reaches the app for the first time this way — and
        // published, so a page that is already open repaints its row instead of
        // waiting for the next visit.
        if (!Prefs.tileAdded(this)) {
            Prefs.setTileAdded(this, true)
            LensState.publish()
        }
        // The shade is where the user finds out whether the lens is still
        // there, so check the window is actually on screen before drawing the
        // tile: a tile must never read "on" for a lens that has gone.
        LensService.verify()
        refreshTile()
        // A lens put back by verify() attaches a frame or two later; look once
        // more so a restored lens is not reported off.
        handler.postDelayed({ runCatching { refreshTile() } }, 320)
    }

    override fun onStopListening() {
        if (bound === this) bound = null
        super.onStopListening()
    }

    override fun onClick() {
        super.onClick()
        // The same truth the button draws: what it says is what a tap does. A
        // stale "on" would otherwise start a lens when the user meant to end it.
        if (LensService.isLensVisible) stopLens() else startLens()
        // The session flips its own state slightly after the start call, so
        // settle the tile once more once that has landed.
        handler.postDelayed({ runCatching { refreshTile() } }, 500)
    }

    private fun startLens() {
        // The lens needs its two switches; from the shade there is nobody to
        // show a permission screen to, so say which one is missing and let the
        // user open Lucid and allow it there.
        if (!android.provider.Settings.canDrawOverlays(this)) {
            toast("Allow \u201CFloat over other apps\u201D in Lucid first.")
            openApp()
            return
        }
        if (!LensAccessibilityService.isEnabled(this)) {
            toast("Allow Text access in Lucid first.")
            openApp()
            return
        }
        val started = runCatching { LensService.start(this) }.isSuccess
        if (!started) {
            toast("Couldn't start the lens — open Lucid once.")
        }
        refreshTile()
    }

    /**
     * Bring the app up, since every missing permission is allowed there. From
     * 14 a service may only open a screen through the tile's own collapse, so
     * that is the route; older versions take the intent directly.
     */
    private fun openApp() {
        val intent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        runCatching {
            if (Build.VERSION.SDK_INT >= 34) {
                val pending = PendingIntent.getActivity(
                    this,
                    0,
                    intent,
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )
                startActivityAndCollapse(pending)
            } else {
                @Suppress("DEPRECATION")
                startActivityAndCollapse(intent)
            }
        }
    }

    private fun stopLens() {
        // stopService() is allowed from the background, while the ACTION_STOP
        // startService() route throws when the app is cold — so stop the
        // service directly; its onDestroy runs the full session shutdown.
        val stopped = runCatching { stopService(Intent(this, LensService::class.java)) }
            .getOrDefault(false)
        if (!stopped) {
            runCatching { LensService.stop(this) }
                .onFailure { toast("Couldn't stop the lens — open Lucid.") }
        }
        refreshTile()
        // The session goes down a moment after the request: read it again then,
        // so the button cannot be left saying "on" over an empty screen.
        handler.postDelayed({ runCatching { refreshTile() } }, 400)
    }

    private fun refreshTile() {
        val tile = qsTile ?: return
        // What the tile says is whether the lens is *on the screen*, not whether
        // a session is warm somewhere: those two are not the same thing, and a
        // toggle reading "on" over nothing is the one wrong answer.
        val running = LensService.isLensVisible
        tile.label = getString(R.string.tile_label)
        // The app's two glyphs rather than a plain magnifier: the shade's
        // button is the one place Lucid sits beside tools that all draw a lens.
        tile.icon = Icon.createWithResource(this, R.drawable.ic_tile_mark)
        if (Capabilities.tileSubtitles) {
            tile.subtitle = getString(if (running) R.string.tile_on else R.string.tile_off)
        }
        tile.state = if (running) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.updateTile()
        Log.i(TAG, "tile active=$running")
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}
