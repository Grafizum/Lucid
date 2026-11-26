package com.grafizum.lucid

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.Point
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Debug builds only. Asks the accessibility service for one frame the same way
 * the lens does, so the screenshot path can be tried from adb without dragging
 * the lens onto a photo by hand:
 *
 *   adb shell am broadcast -a com.grafizum.lucid.DEBUG_SHOT --ei x 540 --ei y 900
 *
 * It is declared in `src/debug/AndroidManifest.xml`, so a release build has no
 * such entry point.
 */
class DebugShotReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val x = intent.getIntExtra("x", 540)
        val y = intent.getIntExtra("y", 900)
        CoroutineScope(Dispatchers.Default).launch {
            val started = System.currentTimeMillis()
            val shot = runCatching { LensAccessibilityService.shotAt(Point(x, y)) }.getOrElse {
                Log.i("LucidShot", "probe threw: $it")
                null
            }
            val took = System.currentTimeMillis() - started
            if (shot == null) {
                Log.i("LucidShot", "probe at $x,$y -> no frame (${took} ms)")
            } else {
                Log.i(
                    "LucidShot",
                    "probe at $x,$y -> ${shot.bitmap.width}x${shot.bitmap.height} " +
                        "origin ${shot.left},${shot.top} (${took} ms)",
                )
                shot.bitmap.recycle()
            }
        }
    }
}
