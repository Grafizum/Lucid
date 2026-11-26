package com.grafizum.lucid

import android.content.Context
import android.content.res.ColorStateList
import android.text.Editable
import android.text.InputType
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat

/**
 * The language list, shared by the in-app dialog and the overlay sheet. Its
 * colours are the card's own theme roles, so it is paper on the day theme and
 * charcoal at night: the caller passes the context that knows which is in force.
 *
 * The sheet used to be a heading and a column of names — correct, and read as a
 * settings dump. It opens with a mark now, and every row carries its own tile:
 * the language's first letter set in clay, the autonym above the name the phone
 * calls it by, and, on the row already in force, a clay medallion with a tick.
 * Nothing here is decoration for its own sake — the tile is what makes a list of
 * two hundred names scannable, and the tick is the choice said twice, so it is
 * not carried by colour alone.
 */
object LanguagePicker {

    /**
     * [title] heads the list; [includeAuto] adds an "Auto recognize" row first,
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
        val accentInk = context.getColor(R.color.card_accent_ink)
        val paper = context.getColor(R.color.card_surface)
        val sans = ResourcesCompat.getFont(context, R.font.lucid_sans)

        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.card_lens_bg)
            setPadding(dp(16), dp(18), dp(16), dp(12))
        }

        // ── the head ─────────────────────────────────────────────────────
        //
        // A mark, the question, and how long the list is on a dotted band: the
        // sheet's title is a surface, and the alphabet rail lives inside it, so
        // two hundred names have a key at the top of the page. The count is
        // still the one fact needed before deciding to scroll or to type.
        //
        // The rail is made empty and filled by show(), because which letters
        // exist depends on what the query left in the list.
        val letterViews = HashMap<Char, View>()
        var scroller: ScrollView? = null

        val rail = AlphabetRail(context).apply {
            contentDescription = context.getString(R.string.picker_jump)
            onLetter = { letter ->
                val row = letterViews[letter]
                if (row != null) scroller?.post { scroller?.smoothScrollTo(0, row.top) }
            }
        }

        val titleRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL

            addView(
                FrameLayout(context).apply {
                    setBackgroundResource(R.drawable.picker_tile_bg)
                    addView(
                        ImageView(context).apply {
                            setImageResource(R.drawable.ic_sec_language)
                            imageTintList = ColorStateList.valueOf(accent)
                            layoutParams = FrameLayout.LayoutParams(
                                dp(17),
                                dp(17),
                                Gravity.CENTER,
                            )
                        },
                    )
                    layoutParams = LinearLayout.LayoutParams(dp(32), dp(32)).apply {
                        marginEnd = dp(11)
                    }
                },
            )

            addView(
                TextView(context).apply {
                    // The heading is one of the app's own words, so it is read
                    // from the pack like every other line.
                    text = UiText.line(context, title)
                    setTextColor(faint)
                    textSize = 9.5f
                    letterSpacing = 0.18f
                    typeface = sans
                    layoutParams = LinearLayout.LayoutParams(
                        0,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        1f,
                    )
                },
            )

            addView(
                TextView(context).apply {
                    text = Languages.targets.size.toString()
                    setTextColor(accentInk)
                    textSize = 10f
                    letterSpacing = 0.04f
                    gravity = Gravity.CENTER
                    typeface = sans
                    setBackgroundResource(R.drawable.picker_count_bg)
                    setPadding(dp(10), 0, dp(10), 0)
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        dp(22),
                    )
                },
            )
        }

        column.addView(
            DottedBand(context).apply {
                setPadding(dp(12), dp(11), dp(12), dp(10))
                addView(
                    titleRow,
                    LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                    ),
                )
                addView(
                    rail,
                    LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        dp(22),
                    ).apply { topMargin = dp(6) },
                )
            },
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ),
        )

        val options = buildList {
            if (includeAuto) add(Languages.Lang("auto", "Auto recognize", "Auto recognize"))
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
        val listHeight = if (searchable) dp(258) else dp(300)

        val list = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(2), 0, 0)
        }

        val empty = TextView(context).apply {
            text = context.getString(R.string.picker_none)
            setTextColor(faint)
            textSize = 12.5f
            typeface = sans
            setPadding(dp(2), dp(16), 0, dp(16))
            visibility = View.GONE
        }

        fun row(lang: Languages.Lang): View {
            val selected = lang.code == current
            // An entry with no autonym carries its English name twice; the row
            // then shows one name instead of the same word twice.
            // Auto is not a language name but a word about the list, so it is
            // read from the pack like the rest of the app's own words.
            val auto = lang.code == "auto"
            val name = if (auto) UiText.line(context, lang.label) else lang.native
            val also = if (auto || lang.native == lang.label) null else lang.label

            val line = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                // A row starting with a right-to-left autonym (دری) is still a
                // row of this list, so it begins at the same left edge as the rest.
                layoutDirection = View.LAYOUT_DIRECTION_LTR
                minimumHeight = dp(46)
                setPadding(dp(8), dp(5), dp(8), dp(5))
                if (selected) setBackgroundResource(R.drawable.picker_row_selected)
                Press.attach(this)
                setOnClickListener { onPick(lang) }
            }

            // The tile: a letter, not a flag — a flag names a country, and half
            // of these languages have none that would not be a lie about who
            // speaks them. Auto is the list speaking, so its tile says so.
            line.addView(
                TextView(context).apply {
                    text = if (auto) "AUTO" else name.trim().take(1).uppercase()
                    textSize = if (auto) 6.8f else 12.5f
                    letterSpacing = if (auto) 0.08f else 0f
                    setTextColor(accent)
                    gravity = Gravity.CENTER
                    typeface = sans
                    setBackgroundResource(R.drawable.picker_tile_bg)
                    layoutParams = LinearLayout.LayoutParams(dp(30), dp(30)).apply {
                        marginEnd = dp(11)
                    }
                },
            )

            line.addView(
                LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(
                        TextView(context).apply {
                            text = name
                            setTextColor(if (selected) accent else ink)
                            textSize = 13f
                            maxLines = 1
                            ellipsize = TextUtils.TruncateAt.END
                            typeface = sans
                        },
                    )
                    // The name this phone calls the language by, small under the
                    // one it calls itself — the same pairing the card uses.
                    also?.let {
                        addView(
                            TextView(context).apply {
                                text = it
                                setTextColor(faint)
                                textSize = 9.5f
                                maxLines = 1
                                ellipsize = TextUtils.TruncateAt.END
                                typeface = sans
                            },
                        )
                    }
                },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
            )

            if (selected) {
                line.addView(
                    TextView(context).apply {
                        text = "\u2713"
                        textSize = 9f
                        setTextColor(paper)
                        gravity = Gravity.CENTER
                        typeface = sans
                        setBackgroundResource(R.drawable.picker_tick_bg)
                        layoutParams = LinearLayout.LayoutParams(dp(18), dp(18)).apply {
                            marginStart = dp(8)
                        }
                    },
                )
            }
            return line
        }

        /** A section's name: the language the app speaks, small and quiet. */
        fun heading(text: String): View = TextView(context).apply {
            this.text = text
            setTextColor(faint)
            textSize = 9f
            letterSpacing = 0.18f
            typeface = sans
            setPadding(dp(2), dp(14), 0, dp(6))
        }

        /** Lights the letter whose group the list's top edge is sitting in. */
        fun syncRail(scrollY: Int) {
            val threshold = scrollY + dp(6)
            rail.active = letterViews.entries
                .filter { it.value.top <= threshold }
                .maxByOrNull { it.value.top }
                ?.key
        }

        fun show(query: String) {
            val found = options.filter { Languages.matches(it, query) }
            // Auto is a choice about the list, not a language on the phone, so
            // it heads the list whenever the search still matches it — above
            // the sections, never buried in the long tail.
            val auto = found.firstOrNull { it.code == "auto" }
            val langs = found.filter { it.code != "auto" }
            val onPhone = langs.filter { it.code in here }
            val rest = langs.filter { it.code !in here }
            list.removeAllViews()
            letterViews.clear()
            empty.visibility = if (found.isEmpty()) View.VISIBLE else View.GONE
            auto?.let { list.addView(row(it)) }
            if (onPhone.isNotEmpty()) {
                list.addView(heading(UiText.line(context, "ON THIS PHONE") + "  ·  ${onPhone.size}"))
                onPhone.forEach { list.addView(row(it)) }
                if (rest.isNotEmpty()) list.addView(heading(UiText.line(context, "MORE LANGUAGES")))
            }
            rest.forEach { lang ->
                val view = row(lang)
                // The rail keys on the name the list is sorted by — the English
                // label — and not the autonym, which may begin in another script
                // and would put 日本語 under a letter no list runs to.
                val letter = lang.label.firstOrNull()?.uppercaseChar()
                if (letter != null && letter in 'A'..'Z' && letter !in letterViews) {
                    letterViews[letter] = view
                }
                list.addView(view)
            }
            list.addView(empty)
            rail.letters = letterViews.keys.sorted()
            // The lit letter is where the list actually is, not where it starts:
            // at the top of the sheet that is no long-tail letter at all, and
            // after a query the rows are new, so it is read again once they have
            // been laid out.
            list.post { syncRail(scroller?.scrollY ?: 0) }
        }

        if (searchable) {
            // The keyboard's way in: a well of the sheet's own chip tint with the
            // magnifier drawn inside it, so the field is a control on this sheet
            // rather than a bare line of text waiting to be typed over.
            column.addView(
                LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setBackgroundResource(R.drawable.picker_search_bg)
                    setPadding(dp(12), 0, dp(10), 0)

                    addView(
                        ImageView(context).apply {
                            setImageResource(R.drawable.ic_search)
                            imageTintList = ColorStateList.valueOf(faint)
                            layoutParams = LinearLayout.LayoutParams(dp(16), dp(16)).apply {
                                marginEnd = dp(8)
                            }
                        },
                    )

                    addView(
                        EditText(context).apply {
                            hint = context.getString(R.string.picker_search, Languages.targets.size)
                            setHintTextColor(faint)
                            setTextColor(ink)
                            textSize = 13f
                            background = null
                            setPadding(0, 0, 0, 0)
                            isSingleLine = true
                            inputType = InputType.TYPE_CLASS_TEXT
                            imeOptions = EditorInfo.IME_ACTION_DONE
                            typeface = sans
                            layoutParams = LinearLayout.LayoutParams(
                                0,
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                1f,
                            )
                            addTextChangedListener(object : TextWatcher {
                                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) =
                                    Unit

                                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) =
                                    Unit

                                override fun afterTextChanged(s: Editable?) =
                                    show(s?.toString().orEmpty())
                            })
                        },
                    )

                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        dp(42),
                    ).apply { topMargin = dp(13) }
                },
            )
        }

        show("")

        val listView = ScrollView(context).apply {
            isVerticalScrollBarEnabled = false
            addView(list)
        }
        scroller = listView
        // The lit letter follows the list as well as the finger, so the rail says
        // where the sheet is looking without being touched.
        listView.setOnScrollChangeListener { _, _, y, _, _ -> syncRail(y) }
        column.addView(
            listView,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, listHeight).apply {
                topMargin = if (searchable) dp(5) else dp(10)
            },
        )

        return column
    }
}
