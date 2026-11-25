package com.grafizum.lucid

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat

/**
 * The pointer Android will not let an app give by opening the screen itself.
 *
 * Settings' per-service page — the one with Lucid's own switch — is guarded by
 * a signature permission, so the best any app can do is open the list of
 * accessibility services. On that list the row is somewhere under a section
 * whose name is the phone's own: "Installed apps" on most Galaxies, "Installed
 * services" on their tablets, "Downloaded apps" on Pixel and most others, and
 * whatever the next skin decides. None of that can be navigated to, and the
 * order cannot be changed — so the guide is written, not driven: a small card
 * over the top of Settings that says the row's exact name and that it may be
 * further down.
 *
 * It is deliberately *not* a target. The window takes no touches at all, so
 * while the person is scrolling Android's own list, nothing of ours can swallow
 * a tap; the card leaves when the switch lands, when Lucid is back in front, or
 * when it has been up long enough to have been read. It needs the float-over
 * permission, which is the step before this one in every setup path.
 */
object SetupNudge {

    /** Long enough to read the line after a scroll or two, short enough not to linger. */
    private const val LINGER_MS = 75 * 1000L

    private val handler = Handler(Looper.getMainLooper())
    private var card: View? = null
    private val expire = Runnable { hide() }

    /**
     * Puts the pointer up, unless the switch is already on or the card has no
     * permission to sit over Settings. Safe to call on every press of Set up.
     */
    fun show(context: Context) {
        val app = context.applicationContext
        if (LensAccessibilityService.isEnabled(app)) {
            hide()
            return
        }
        // Without this the card would be refused by the window manager; the
        // setup path asks for it first for exactly this reason.
        if (!Settings.canDrawOverlays(app)) return
        if (card?.isAttachedToWindow == true) return
        hide()

        val wm = app.getSystemService(WindowManager::class.java) ?: return
        val density = app.resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()

        val sans = ResourcesCompat.getFont(app, R.font.lucid_sans) ?: Typeface.DEFAULT
        val ink = ContextCompat.getColor(app, R.color.screen_ink)
        val muted = ContextCompat.getColor(app, R.color.screen_muted)

        val title = TextView(app).apply {
            text = app.getString(R.string.nudge_title)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            setTextColor(ink)
            typeface = sans
        }
        val body = TextView(app).apply {
            text = app.getString(R.string.nudge_body, sectionPhrase(app))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setTextColor(muted)
            typeface = sans
            setLineSpacing(0f, 1.12f)
            setPadding(0, dp(3), 0, 0)
        }
        val column = LinearLayout(app).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(15), dp(11), dp(15), dp(12))
            background = GradientDrawable().apply {
                cornerRadius = dp(16).toFloat()
                setColor(ContextCompat.getColor(app, R.color.card_surface))
                setStroke(dp(1), ContextCompat.getColor(app, R.color.screen_band))
            }
            elevation = dp(8).toFloat()
            addView(title)
            addView(body)
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            },
            // No touches, ever: the person is using Settings underneath this.
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            android.graphics.PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                fitInsetsTypes = 0
            }
            y = statusBarHeight(app) + dp(10)
        }

        if (!runCatching { wm.addView(column, params) }.isSuccess) return
        card = column
        handler.removeCallbacks(expire)
        handler.postDelayed(expire, LINGER_MS)
    }

    /** Takes the pointer down. Called on the grant, on coming back, and on its own clock. */
    fun hide() {
        handler.removeCallbacks(expire)
        val view = card ?: return
        card = null
        val app = view.context
        val wm = app.getSystemService(WindowManager::class.java) ?: return
        runCatching { wm.removeView(view) }
    }

    /**
     * The words the list actually uses on this phone, quoted for a sentence.
     *
     * Only the section's name is taken from the phone, never a path: a path is
     * right until the next skin renames a menu, while the section is what the
     * eye is looking for once the page is open.
     */
    fun sectionPhrase(context: Context): String = when {
        galaxy(context) && tablet(context) -> "“Installed services”"
        galaxy(context) -> "“Installed apps”"
        stock(context) -> "“Downloaded apps”"
        // A phone whose skin is not known here; both names, so whichever the
        // list is using is on the card as well.
        else -> "“Installed apps” or “Downloaded apps”"
    }

    /** The same name bare, for a drawn path or a row of a drawing. */
    fun sectionName(context: Context): String = when {
        galaxy(context) -> if (tablet(context)) "Installed services" else "Installed apps"
        stock(context) -> "Downloaded apps"
        else -> "Installed apps"
    }

    private fun galaxy(context: Context): Boolean =
        Build.MANUFACTURER.contains("samsung", true) || Build.BRAND.contains("samsung", true)

    /** AOSP and Pixel, the list whose section is named "Downloaded apps". */
    private fun stock(context: Context): Boolean =
        Build.MANUFACTURER.equals("Google", true) || Build.BRAND.equals("google", true)

    private fun tablet(context: Context): Boolean =
        context.resources.configuration.smallestScreenWidthDp >= 600

    /** The status bar's height, so the card clears it on any phone. */
    private fun statusBarHeight(context: Context): Int {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val wm = context.getSystemService(WindowManager::class.java)
            val inset = wm?.currentWindowMetrics?.windowInsets
                ?.getInsets(WindowInsets.Type.statusBars())?.top ?: 0
            if (inset > 0) return inset
        }
        val id = context.resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (id > 0) context.resources.getDimensionPixelSize(id) else 0
    }
}
