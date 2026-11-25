package com.grafizum.lucid

import android.app.Dialog
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.translate.TranslateRemoteModel

/**
 * The offline languages page: every language the on-device translator can hold,
 * searchable, with one tap to put it on the phone.
 *
 * The packs are ML Kit's own and live where it keeps them — nothing about them
 * is copied into Lucid. So the page reads its truth from [RemoteModelManager]
 * each time it comes forward, and the two groups it draws are exactly that set:
 * what is here, and what could be.
 *
 * A row is a decision, so it is also generous: the whole card answers a tap,
 * and the pill on the right is the same answer written down. What is on the
 * phone can be taken back only through a question, because fetching a pack
 * again costs the person data.
 */
class ModelsActivity : AppCompatActivity() {

    private lateinit var onRow: View
    private lateinit var onLabel: TextView
    private lateinit var addRow: View
    private lateinit var addLabel: TextView
    private lateinit var onList: LinearLayout
    private lateinit var addList: LinearLayout
    private lateinit var empty: TextView
    private lateinit var note: TextView
    private lateinit var search: EditText

    /** The language codes whose packs ML Kit reports on this phone. */
    private val onPhone = linkedSetOf<String>()

    /** Codes with a fetch in flight, so a second tap cannot start another. */
    private val adding = linkedSetOf<String>()

    private val manager: RemoteModelManager get() = RemoteModelManager.getInstance()

    override fun onCreate(savedInstanceState: Bundle?) {
        AppCompatDelegate.setDefaultNightMode(ThemeChoice.nightMode(this))
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_models)

        // The page's margins live in the layout: the window opts out of
        // edge-to-edge in the theme, so the system bars already sit outside this
        // view and there is no inset left to spend on it.
        onRow = findViewById(R.id.modelsOnRow)
        onLabel = findViewById(R.id.modelsOnLabel)
        addRow = findViewById(R.id.modelsAddRow)
        addLabel = findViewById(R.id.modelsAddLabel)
        onList = findViewById(R.id.modelsOnList)
        addList = findViewById(R.id.modelsAddList)
        empty = findViewById(R.id.modelsEmpty)
        note = findViewById(R.id.modelsNote)
        search = findViewById(R.id.modelsSearch)

        // The count is the page saying how big the list is before a key is
        // pressed; the picker already words this the same way. Only the
        // languages with a pack of their own are on this page.
        search.hint = getString(R.string.picker_search, Languages.targets.count { it.onDevice })

        val back = findViewById<View>(R.id.modelsBack)
        Press.attach(back)
        back.setOnClickListener { finish() }

        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun afterTextChanged(s: Editable?) = draw()
        })

        draw()
        loadPacks()
    }

    override fun onResume() {
        super.onResume()
        // A read in the lens can fetch a pack while this page is behind it.
        loadPacks()
    }

    /* ── what the phone has, according to ML Kit ─────────────────────── */

    private fun loadPacks() {
        manager.getDownloadedModels(TranslateRemoteModel::class.java)
            .addOnSuccessListener { models ->
                onPhone.clear()
                models.mapNotNullTo(onPhone) { Languages.canonical(it.language) }
                note.text = getString(R.string.models_note)
                note.setTextColor(color(R.color.screen_faint))
                draw()
            }
            .addOnFailureListener {
                // Reading the packs failing is not the same as having none, and
                // the page must not say "none" when it cannot know.
                note.text = getString(R.string.models_unavailable)
                note.setTextColor(color(R.color.screen_accent))
            }
    }

    /* ── the page ────────────────────────────────────────────────────── */

    private fun draw() {
        val query = search.text?.toString().orEmpty()
        val matches = Languages.targets
            .filter { it.onDevice && Languages.matches(it, query) }
            .sortedBy { it.label.lowercase() }

        onList.removeAllViews()
        addList.removeAllViews()

        if (matches.isEmpty()) {
            empty.visibility = View.VISIBLE
            onRow.visibility = View.GONE
            addRow.visibility = View.GONE
            return
        }
        empty.visibility = View.GONE

        val here = matches.filter { onPhone.contains(it.code) }
        val rest = matches.filterNot { onPhone.contains(it.code) }

        if (here.isEmpty()) {
            onRow.visibility = View.GONE
        } else {
            onLabel.text = getString(R.string.models_on_phone, here.size)
            onRow.visibility = View.VISIBLE
            here.forEach { onList.addView(row(it)) }
        }

        when {
            rest.isNotEmpty() -> {
                addLabel.text = "MORE LANGUAGES"
                addRow.visibility = View.VISIBLE
                rest.forEach { addList.addView(row(it)) }
            }
            // Nothing left to add, and nothing was asked for: the whole list is
            // already here, and that is worth saying once.
            query.isBlank() && onPhone.isNotEmpty() -> {
                addLabel.text = "EVERY LANGUAGE IS ON THIS PHONE"
                addRow.visibility = View.VISIBLE
            }
            else -> addRow.visibility = View.GONE
        }
    }

    /** One language: its code in a soft tile, its two names, and its state. */
    private fun row(lang: Languages.Lang): View {
        val here = onPhone.contains(lang.code)
        val busy = adding.contains(lang.code)

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = cardBackground(here)
            setPadding(dp(11), dp(9), dp(9), dp(9))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(7) }
            isClickable = true
            isFocusable = true
        }

        card.addView(
            TextView(this).apply {
                text = lang.code.uppercase()
                setTextColor(color(if (here) R.color.screen_accent else R.color.screen_muted))
                textSize = 10f
                letterSpacing = 0.06f
                gravity = Gravity.CENTER
                typeface = sans()
                background = GradientDrawable().apply {
                    cornerRadius = dp(12).toFloat()
                    setColor(color(if (here) R.color.screen_accent_fill else R.color.screen_chip))
                    setStroke(dp(1), color(if (here) R.color.screen_accent_edge else R.color.screen_edge))
                }
                layoutParams = LinearLayout.LayoutParams(dp(38), dp(38))
            },
        )

        val words = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                .apply { marginStart = dp(12) }
        }
        words.addView(
            TextView(this).apply {
                text = lang.label
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                textAlignment = View.TEXT_ALIGNMENT_VIEW_START
                setTextColor(color(R.color.screen_ink))
                textSize = 13.5f
                typeface = sans(500)
            },
        )
        if (lang.native != lang.label) {
            words.addView(
                TextView(this).apply {
                    text = lang.native
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                    textAlignment = View.TEXT_ALIGNMENT_VIEW_START
                    setTextColor(color(R.color.screen_faint))
                    textSize = 10.5f
                    typeface = sans()
                    setPadding(0, dp(2), 0, 0)
                },
            )
        }
        card.addView(words)

        val pill = TextView(this).apply {
            text = when {
                busy -> "Adding…"
                here -> "✓ On phone"
                else -> "Add"
            }
            gravity = Gravity.CENTER
            typeface = sans()
            textSize = 11f
            setPadding(dp(14), 0, dp(14), 0)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                dp(32),
            ).apply { marginStart = dp(8) }
            background = pillBackground(here, busy)
            setTextColor(
                color(
                    when {
                        busy -> R.color.screen_faint
                        here -> R.color.screen_accent
                        else -> R.color.screen_ink
                    },
                ),
            )
            isClickable = true
            isFocusable = true
            contentDescription = when {
                busy -> "Adding ${lang.label}"
                here -> "${lang.label} is on the phone. Tap to remove it."
                else -> "Add ${lang.label}"
            }
        }
        pill.setOnClickListener {
            Press.nudge(pill)
            when {
                adding.contains(lang.code) -> Unit
                onPhone.contains(lang.code) -> confirmRemove(lang)
                else -> add(lang)
            }
        }
        card.addView(pill)

        // The card is the same target as the pill, so a thumb that lands on the
        // name does what the pill says rather than nothing.
        card.setOnClickListener { pill.performClick() }
        return card
    }

    /* ── adding and taking back ──────────────────────────────────────── */

    private fun add(lang: Languages.Lang) {
        if (!adding.add(lang.code)) return
        draw()
        // Only the languages with a pack are drawn on this page, so the tag is
        // always there; the guard is what keeps ML Kit from being asked for a
        // model it does not have.
        val tag = Languages.forModel(lang.code) ?: return
        val model = TranslateRemoteModel.Builder(tag).build()
        val conditions = DownloadConditions.Builder().build()

        manager.download(model, conditions)
            .addOnSuccessListener {
                adding.remove(lang.code)
                onPhone.add(lang.code)
                draw()
            }
            .addOnFailureListener {
                adding.remove(lang.code)
                draw()
                toast("Couldn't add ${lang.label} — check the connection.")
            }
    }

    private fun confirmRemove(lang: Languages.Lang) {
        val dialog = Dialog(this)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        val sheet = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = ContextCompat.getDrawable(this@ModelsActivity, R.drawable.dialog_sheet_bg)
            setPadding(dp(18), dp(17), dp(18), dp(15))
        }

        sheet.addView(
            TextView(this).apply {
                text = "Remove ${lang.label}?"
                setTextColor(color(R.color.screen_ink))
                textSize = 18f
                letterSpacing = -0.015f
                typeface = sans(600)
            },
        )
        sheet.addView(
            TextView(this).apply {
                text = "The pack is downloaded again the next time an offline read needs it."
                setTextColor(color(R.color.screen_muted))
                textSize = 11.5f
                setLineSpacing(0f, 1.42f)
                typeface = sans()
                setPadding(0, dp(6), 0, dp(16))
            },
        )

        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        buttons.addView(
            pillButton("Keep", solid = false).apply {
                setOnClickListener { dialog.dismiss() }
            },
            LinearLayout.LayoutParams(0, dp(42), 1f),
        )
        buttons.addView(
            pillButton("Remove", solid = true).apply {
                setOnClickListener {
                    dialog.dismiss()
                    remove(lang)
                }
            },
            LinearLayout.LayoutParams(0, dp(42), 1f).apply { marginStart = dp(8) },
        )
        sheet.addView(buttons)

        dialog.setContentView(sheet)
        dialog.window?.setLayout(
            (resources.displayMetrics.widthPixels * 0.86f).toInt(),
            WindowManager.LayoutParams.WRAP_CONTENT,
        )
        dialog.window?.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        dialog.window?.setDimAmount(0.42f)
        dialog.show()
    }

    private fun remove(lang: Languages.Lang) {
        val tag = Languages.forModel(lang.code) ?: return
        val model = TranslateRemoteModel.Builder(tag).build()
        manager.deleteDownloadedModel(model)
            .addOnSuccessListener {
                onPhone.remove(lang.code)
                draw()
                toast("${lang.label} removed")
            }
            .addOnFailureListener { toast("Couldn't remove ${lang.label}.") }
    }

    private fun pillButton(label: String, solid: Boolean): TextView = TextView(this).apply {
        text = label
        gravity = Gravity.CENTER
        typeface = sans(600)
        textSize = 12.5f
        setTextColor(color(if (solid) R.color.screen_canvas else R.color.screen_ink))
        isClickable = true
        isFocusable = true
        background = GradientDrawable().apply {
            cornerRadius = dp(999).toFloat()
            if (solid) {
                setColor(color(R.color.screen_accent))
            } else {
                setColor(color(R.color.screen_button))
                setStroke(dp(1), color(R.color.screen_edge))
            }
        }
        Press.attach(this)
    }

    /* ── drapery ─────────────────────────────────────────────────────── */

    /** A card lifts a step when the pack is already here: the list has a top. */
    private fun cardBackground(here: Boolean): GradientDrawable = GradientDrawable().apply {
        cornerRadius = dp(18).toFloat()
        setColor(color(R.color.screen_card))
        setStroke(dp(1), color(if (here) R.color.screen_accent_edge else R.color.screen_divider))
    }

    private fun pillBackground(here: Boolean, busy: Boolean): GradientDrawable = GradientDrawable().apply {
        cornerRadius = dp(999).toFloat()
        when {
            busy -> {
                setColor(color(R.color.screen_chip))
                setStroke(dp(1), color(R.color.screen_edge))
            }
            here -> {
                setColor(color(R.color.screen_accent_fill))
                setStroke(dp(1), color(R.color.screen_accent_edge))
            }
            else -> {
                setColor(color(R.color.screen_button))
                setStroke(dp(1), color(R.color.screen_edge))
            }
        }
    }

    /**
     * The app's one family, at the weight a label asks for. Plus Jakarta is a
     * variable font, so the weight is a real one from 11 on; older phones fall
     * back to the regular cut rather than a synthesised bold.
     */
    private fun sans(weight: Int = 400): Typeface? {
        val base = ResourcesCompat.getFont(this, R.font.lucid_sans) ?: return null
        return if (Build.VERSION.SDK_INT >= 28) Typeface.create(base, weight, false) else base
    }

    private fun color(id: Int): Int = ContextCompat.getColor(this, id)

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}
