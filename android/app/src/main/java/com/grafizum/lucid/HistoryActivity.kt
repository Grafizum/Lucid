package com.grafizum.lucid

import android.app.Dialog
import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.format.DateUtils
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import java.util.Calendar
import kotlin.math.abs

/**
 * What the lens has read, newest first.
 *
 * The list is built from what is on the phone — [Prefs.history] — so the page
 * costs nothing to open and never asks the network. A row shows the line the
 * lens saw; one tap opens the whole read as a card over the page — the answer,
 * the line it came from, the languages it went through, the app and the time —
 * and a long press copies the original. A leftward swipe throws one read away;
 * the row follows the finger, so the gesture says what it is doing before it
 * lands.
 */
class HistoryActivity : AppCompatActivity() {

    private lateinit var list: LinearLayout
    private lateinit var empty: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        AppCompatDelegate.setDefaultNightMode(ThemeChoice.nightMode(this))
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_history)

        // Edge to edge, with the bars paid for in padding, as the home page
        // does. The theme's opt-out is ignored on Android 16 for anything
        // targeting 36, and a page that trusts it puts its title under the
        // status bar; the title has to sit on the wordmark's line instead.
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val page = findViewById<ViewGroup>(R.id.historyRoot)
        ViewCompat.setOnApplyWindowInsetsListener(page) { view, insets ->
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

        val back = findViewById<View>(R.id.historyBack)
        Press.attach(back)
        back.setOnClickListener { finish() }

        list = findViewById(R.id.historyList)
        empty = findViewById(R.id.historyEmpty)

        val clear = findViewById<View>(R.id.historyClear)
        Press.attach(clear)
        clear.setOnClickListener { askClear() }

        paint()
    }

    /**
     * The page, drawn from what is on the phone — so clearing it is a repaint.
     * The way to clear appears only when there is something to clear.
     *
     * [toTop] is for opening the page: deleting one row in the middle of a long
     * list must leave the list where the finger was, not throw it back to the
     * newest read.
     */
    private fun paint(toTop: Boolean = true) {
        val reads = Prefs.history(this)
        list.removeAllViews()
        empty.visibility = if (reads.isEmpty()) View.VISIBLE else View.GONE
        findViewById<View>(R.id.historyClear).visibility =
            if (reads.isEmpty()) View.GONE else View.VISIBLE

        var lastDay = ""
        reads.forEach { read ->
            val day = dayKey(read.at)
            if (day != lastDay) {
                list.addView(dayLabel(dayOf(read.at)))
                lastDay = day
            }
            list.addView(readRow(read))
        }

        // Newest first, so the page opens where the person left off.
        if (toTop) findViewById<ScrollView>(R.id.historyScroll).scrollTo(0, 0)
    }

    /** Wiping the log is one tap and a question, not an undo: there is no copy
     * anywhere else to restore from, so the count that goes is said out loud. */
    private fun askClear() {
        val count = Prefs.history(this).size
        if (count == 0) return

        val dialog = Dialog(this)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.setContentView(R.layout.dialog_clear)
        dialog.window?.setLayout(
            (resources.displayMetrics.widthPixels * 0.86f).toInt(),
            WindowManager.LayoutParams.WRAP_CONTENT,
        )
        dialog.window?.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        dialog.window?.setDimAmount(0.42f)

        dialog.findViewById<TextView>(R.id.clearBody).text = when (count) {
            1 -> "The one read kept on this phone goes — the line, the answer, where and when. Nothing was uploaded, so there is no other copy and this cannot be undone."
            else -> "All $count reads kept on this phone go — the lines, the answers, where and when. Nothing was uploaded, so there is no other copy and this cannot be undone."
        }

        val keep = dialog.findViewById<View>(R.id.clearKeep)
        val confirm = dialog.findViewById<View>(R.id.clearConfirm)
        Press.attachAll(keep, confirm)
        keep.setOnClickListener { dialog.dismiss() }
        confirm.setOnClickListener {
            Prefs.clearHistory(this)
            dialog.dismiss()
            Toast.makeText(this, "Cleared — nothing is left on this phone", Toast.LENGTH_SHORT).show()
            paint()
        }
        dialog.show()
    }

    private fun readRow(read: Prefs.Read): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = cardBackground()
            setPadding(dp(13), dp(11), dp(13), dp(11))
            isClickable = true
            isFocusable = true
        }

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        row.addView(chip(read.detected ?: "auto"))

        val words = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                .apply { marginStart = dp(10) }
        }
        words.addView(
            TextView(this).apply {
                text = read.source
                maxLines = 2
                ellipsize = android.text.TextUtils.TruncateAt.END
                setTextColor(color(R.color.screen_ink))
                textSize = 13f
                typeface = typeface
            },
        )
        words.addView(
            TextView(this).apply {
                text = "${Languages.label(read.target)}  ·  ${whenAgo(read.at)}"
                setTextColor(color(R.color.screen_faint))
                textSize = 9.5f
                setPadding(0, dp(2), 0, 0)
            },
        )
        row.addView(words)

        val chevron = TextView(this).apply {
            text = "›"
            setTextColor(color(R.color.screen_faint))
            textSize = 15f
            setPadding(dp(8), 0, 0, 0)
        }
        row.addView(chevron)
        card.addView(row)

        // The line alone is what makes a history scannable: the whole read —
        // answer, original, languages, app, time — opens over the page instead.
        card.setOnClickListener { showRead(read) }
        swipeAway(card, read)
        card.setOnLongClickListener {
            val clipboard = getSystemService(ClipboardManager::class.java)
            clipboard?.setPrimaryClip(ClipData.newPlainText("Lucid", read.source))
            Toast.makeText(this, "Copied the original", Toast.LENGTH_SHORT).show()
            true
        }
        return card
    }

    /**
     * Swiping a read leftwards deletes it: left is the direction Android lists
     * use for the destructive one, and it is the one the thumb can only reach
     * on purpose. The card follows the finger and dims as it goes; a short drag
     * springs back and the read stays. Only past [DELETE_DP] does it go, and the
     * answer says so — one row is not a silent disappearance.
     *
     * The listener stays out of the way until the finger has actually travelled:
     * a tap still opens the read, and a long press still copies it.
     */
    private fun swipeAway(card: View, read: Prefs.Read) {
        val slop = ViewConfiguration.get(this).scaledTouchSlop
        val threshold = dp(DELETE_DP).toFloat()
        var downX = 0f
        var downY = 0f
        var travel = 0f
        var dragging = false

        card.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    travel = 0f
                    dragging = false
                    false
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downX
                    val dy = event.rawY - downY
                    if (!dragging && abs(dx) > slop && abs(dx) > abs(dy)) dragging = true
                    if (!dragging) {
                        false
                    } else {
                        // Leftwards only: dragging the other way would promise a
                        // second action this list does not have.
                        travel = dx.coerceAtMost(0f)
                        view.translationX = travel
                        view.alpha = 1f - (abs(travel) / (threshold * 2f)).coerceIn(0f, 0.4f)
                        true
                    }
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (!dragging) {
                        false
                    } else {
                        if (travel <= -threshold) {
                            Prefs.removeRead(this, read)
                            Toast.makeText(this, "Read removed", Toast.LENGTH_SHORT).show()
                            paint(toTop = false)
                        } else {
                            view.animate()
                                .translationX(0f)
                                .alpha(1f)
                                .setDuration(180)
                                .start()
                        }
                        true
                    }
                }

                else -> false
            }
        }
    }

    /**
     * One read, opened as a card over the page. The card itself is shared with
     * the settings dropdown — see [ReadCard] — so a read looks the same wherever
     * it is opened from.
     */
    private fun showRead(read: Prefs.Read) {
        ReadCard.show(this, read)
    }

    /** The card, built apart from the window it is shown in so it can be read and
     * checked without one. */
    internal fun detailView(read: Prefs.Read): View = ReadCard.view(this, read)

    private fun dayLabel(label: String): View = TextView(this).apply {
        text = label
        setTextColor(color(R.color.screen_faint))
        textSize = 8.5f
        letterSpacing = 0.13f
        setPadding(dp(2), dp(14), 0, dp(7))
    }

    private fun chip(code: String, accent: Boolean = false): TextView =
        ReadCard.chip(this, code, accent)

    private fun cardBackground(): GradientDrawable = GradientDrawable().apply {
        cornerRadius = dp(18).toFloat()
        setColor(color(R.color.screen_card))
        setStroke(dp(1), color(R.color.screen_divider))
    }

    private fun dayKey(at: Long): String {
        val calendar = Calendar.getInstance().apply { timeInMillis = at }
        return "${calendar.get(Calendar.YEAR)}-${calendar.get(Calendar.DAY_OF_YEAR)}"
    }

    /** "Today", "Yesterday", then the date, in the phone's own language. */
    private fun dayOf(at: Long): String {
        val now = System.currentTimeMillis()
        val key = dayKey(at)
        return when (key) {
            dayKey(now) -> "TODAY"
            dayKey(now - 24L * 60 * 60 * 1000) -> "YESTERDAY"
            else -> DateUtils.getRelativeTimeSpanString(
                at,
                now,
                DateUtils.DAY_IN_MILLIS,
            ).toString().uppercase()
        }
    }

    private fun whenAgo(at: Long): String = ReadCard.whenAgo(at)

    private fun color(id: Int): Int = ContextCompat.getColor(this, id)

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private companion object {
        /** How far a card travels before the read behind it is gone. */
        const val DELETE_DP = 84
    }
}
