package com.grafizum.lucid

import android.content.Context
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat

/**
 * The language list, shared by the in-app dialog and the overlay sheet. Its
 * colours are the card's own theme roles, so it is paper on the day theme and
 * charcoal at night: the caller passes the context that knows which is in force.
 */
object LanguagePicker {

    /**
     * [title] heads the list; [includeAuto] adds an "Auto detect" row first,
     * used when picking the card's source language. [searchable] is false
     * where the surface cannot take the keyboard — the lens sheet is a
     * non-focusable overlay — so that list stays a plain scroll.
     */
    fun build(
        context: Context,
        current: String,
        title: String = "TRANSLATE INTO",
        includeAuto: Boolean = false,
        searchable: Boolean = true,
        onPick: (Languages.Lang) -> Unit,
    ): View {
        val density = context.resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()

        val ink = context.getColor(R.color.card_ink)
        val faint = context.getColor(R.color.card_faint)
        val accent = context.getColor(R.color.card_accent)
        val sans = ResourcesCompat.getFont(context, R.font.lucid_sans)

        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.card_lens_bg)
            setPadding(dp(16), dp(16), dp(16), dp(10))
        }

        column.addView(
            TextView(context).apply {
                // The heading is one of the app's own words, so it is read from
                // the pack like every other line.
                text = UiText.line(context, title)
                setTextColor(faint)
                textSize = 9.5f
                letterSpacing = 0.18f
                typeface = sans
            },
        )

        val options = buildList {
            if (includeAuto) add(Languages.Lang("auto", "Auto detect", "Auto detect"))
            addAll(Languages.targets)
        }

        // The words this phone already has, read once rather than on every
        // keystroke: they head the list, so a person can see at a glance which
        // languages they have used before and which are still the long tail.
        val here = Languages.targets
            .filter { UiText.has(context, it.code) }
            .map { it.code }
            .toSet()
        // With two hundred-odd languages in the list, the keyboard is the only
        // way to reach one in a single gesture.
        val listHeight = if (searchable) dp(226) else dp(270)

        val list = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(6), 0, 0)
        }

        val empty = TextView(context).apply {
            text = context.getString(R.string.picker_none)
            setTextColor(faint)
            textSize = 12.5f
            typeface = sans
            setPadding(0, dp(14), 0, dp(14))
            visibility = View.GONE
        }

        fun row(lang: Languages.Lang): View {
            val selected = lang.code == current
            // An entry with no autonym carries its English name twice; the row
            // then shows one name instead of the same word on both sides.
            val names = if (lang.native == lang.label) lang.label else "${lang.native}  ·  ${lang.label}"
            return TextView(context).apply {
                text = if (selected) "$names  ✓" else names
                setTextColor(if (selected) accent else ink)
                textSize = 13.5f
                gravity = Gravity.CENTER_VERTICAL
                // A row starting with a right-to-left autonym (دری) is still a
                // row of this list, so it begins at the same left edge as the rest.
                layoutDirection = View.LAYOUT_DIRECTION_LTR
                textAlignment = View.TEXT_ALIGNMENT_VIEW_START
                setPadding(0, dp(10), 0, dp(10))
                typeface = sans
                Press.attach(this)
                setOnClickListener { onPick(lang) }
            }
        }

        /** A section's name: the language the app speaks, small and quiet. */
        fun heading(text: String): View = TextView(context).apply {
            this.text = text
            setTextColor(faint)
            textSize = 9f
            letterSpacing = 0.18f
            typeface = sans
            setPadding(0, dp(12), 0, dp(2))
        }

        fun show(query: String) {
            val found = options.filter { Languages.matches(it, query) }
            val onPhone = found.filter { it.code in here }
            val rest = found.filter { it.code !in here }
            list.removeAllViews()
            empty.visibility = if (found.isEmpty()) View.VISIBLE else View.GONE
            if (onPhone.isNotEmpty()) {
                list.addView(heading(UiText.line(context, "ON THIS PHONE") + "  ·  ${onPhone.size}"))
                onPhone.forEach { list.addView(row(it)) }
                if (rest.isNotEmpty()) list.addView(heading(UiText.line(context, "MORE LANGUAGES")))
            }
            rest.forEach { list.addView(row(it)) }
            list.addView(empty)
        }

        if (searchable) {
            column.addView(
                EditText(context).apply {
                    hint = context.getString(R.string.picker_search, Languages.targets.size)
                    setHintTextColor(faint)
                    setTextColor(ink)
                    textSize = 13f
                    background = null
                    setPadding(0, dp(10), 0, dp(8))
                    isSingleLine = true
                    inputType = InputType.TYPE_CLASS_TEXT
                    imeOptions = EditorInfo.IME_ACTION_DONE
                    typeface = sans
                    addTextChangedListener(object : TextWatcher {
                        override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
                        override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
                        override fun afterTextChanged(s: Editable?) = show(s?.toString().orEmpty())
                    })
                },
            )
            column.addView(
                View(context).apply {
                    setBackgroundColor(faint)
                    alpha = 0.28f
                },
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1),
            )
        }

        show("")

        column.addView(
            ScrollView(context).apply {
                isVerticalScrollBarEnabled = false
                addView(list)
            },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, listHeight),
        )

        return column
    }
}
