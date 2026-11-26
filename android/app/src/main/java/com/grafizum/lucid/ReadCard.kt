package com.grafizum.lucid

import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.text.format.DateFormat
import android.text.format.DateUtils
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

/**
 * One read, opened as a card over the page.
 *
 * Two pages show reads — the reads page and the settings dropdown — and a read
 * has to look the same in both, so the card is built here rather than twice.
 * The card keeps the page's own margins and never grows past the screen: a read
 * of a long paragraph scrolls inside it, and the dimmed page behind is still
 * visible, so opening a row feels like it unfolding in place rather than a
 * second screen arriving.
 */
object ReadCard {

    /** The card itself, built apart from the window it is shown in. */
    fun view(activity: AppCompatActivity, read: Prefs.Read): View {
        val detail = activity.layoutInflater.inflate(R.layout.dialog_read, null)

        val translated = read.translated.trim()
        detail.findViewById<TextView>(R.id.detailLabel).text =
            if (translated.isEmpty()) UiText.line(activity, "READ") else UiText.line(activity, "TRANSLATED")
        detail.findViewById<TextView>(R.id.detailTranslation).apply {
            text = translated
            visibility = if (translated.isEmpty()) View.GONE else View.VISIBLE
        }

        val langs = detail.findViewById<LinearLayout>(R.id.detailLangs)
        langs.addView(chip(activity, read.detected ?: "auto"))
        langs.addView(
            TextView(activity).apply {
                text = "→"
                setTextColor(color(activity, R.color.screen_faint))
                textSize = 10f
                setPadding(dp(activity, 7), 0, dp(activity, 7), 0)
                includeFontPadding = false
            },
        )
        langs.addView(chip(activity, read.target, accent = true))

        detail.findViewById<TextView>(R.id.detailSource).text = read.source
        detail.findViewById<TextView>(R.id.detailMeta).text = listOfNotNull(
            appLabel(activity, read.packageName),
            whenAgo(read.at),
            stamp(activity, read.at),
        ).joinToString("   ·   ")

        UiText.apply(activity, detail)
        return detail
    }

    /** The card, shown as a dialog over the page that asked for it. */
    fun show(activity: AppCompatActivity, read: Prefs.Read) {
        val detail = view(activity, read)
        val dialog = Dialog(activity).apply { setContentView(detail) }

        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            attributes = attributes.apply { dimAmount = 0.42f }
        }

        val width = activity.resources.displayMetrics.widthPixels - dp(activity, 48)
        val ceiling = (activity.resources.displayMetrics.heightPixels * 0.84f).toInt()
        detail.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(ceiling, View.MeasureSpec.AT_MOST),
        )
        dialog.window?.setLayout(width, detail.measuredHeight.coerceAtMost(ceiling))

        detail.findViewById<View>(R.id.detailClose).setOnClickListener { dialog.dismiss() }
        dialog.show()
    }

    /** A language, in the small caps chip the reads pages use. */
    fun chip(activity: AppCompatActivity, code: String, accent: Boolean = false): TextView =
        TextView(activity).apply {
            text = Languages.short(code).uppercase()
            setTextColor(color(activity, if (accent) R.color.screen_accent else R.color.screen_muted))
            textSize = 8.5f
            gravity = Gravity.CENTER
            setPadding(dp(activity, 7), dp(activity, 4), dp(activity, 7), dp(activity, 4))
            background = GradientDrawable().apply {
                cornerRadius = dp(activity, 6).toFloat()
                setColor(color(activity, if (accent) R.color.screen_accent_fill else R.color.screen_chip))
            }
        }

    /** How long ago, in the phone's own words. */
    fun whenAgo(at: Long): String =
        DateUtils.getRelativeTimeSpanString(at, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS)
            .toString()

    /** "WhatsApp" for a package name, or nothing when that app is gone. */
    private fun appLabel(activity: AppCompatActivity, pkg: String?): String? {
        val name = pkg?.takeIf { it.isNotBlank() } ?: return null
        return runCatching {
            val info = activity.packageManager.getApplicationInfo(name, 0)
            activity.packageManager.getApplicationLabel(info).toString()
        }.getOrNull()?.takeIf { it.isNotBlank() && it != name }
    }

    /** The date and time it was read, in the phone's own format. */
    private fun stamp(activity: AppCompatActivity, at: Long): String {
        val date = DateFormat.getDateFormat(activity).format(at)
        val time = DateFormat.getTimeFormat(activity).format(at)
        return "$date, $time"
    }

    private fun color(activity: AppCompatActivity, id: Int): Int =
        ContextCompat.getColor(activity, id)

    private fun dp(activity: AppCompatActivity, value: Int): Int =
        (value * activity.resources.displayMetrics.density).toInt()
}
