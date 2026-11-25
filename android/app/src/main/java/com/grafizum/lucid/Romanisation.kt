package com.grafizum.lucid

import android.icu.text.Transliterator
import android.os.Build

/**
 * How a line is pronounced, spelled out in Latin letters under the original.
 *
 * Pinyin for Chinese, romaji for a Japanese line that is all kana, and a
 * transliteration for the other writing systems Android can spell out. This is
 * a conversion, never a guess: a script with no transliterator, or a result
 * that came back unchanged, leaves the card as it was rather than drawing
 * something invented.
 *
 * The ICU transliterators arrive with Android 10. Before that only the online
 * engine's own reading (the Japanese ruby) exists, so nothing is drawn here.
 */
object Romanisation {

    /**
     * What Android can spell out, by Lucid's own language code. A code that is
     * already written in Latin letters is absent on purpose: transliterating
     * "Español" would answer "Espanol", which is not a pronunciation.
     */
    private val scripts: Map<String, String> = mapOf(
        "zh" to "Han-Latin",                 // pinyin
        "ko" to "Hangul-Latin",
        "ru" to "Cyrillic-Latin",
        "uk" to "Cyrillic-Latin",
        "be" to "Cyrillic-Latin",
        "bg" to "Cyrillic-Latin",
        "sr" to "Cyrillic-Latin",
        "mk" to "Cyrillic-Latin",
        "el" to "Greek-Latin",
        "hi" to "Devanagari-Latin",
        "mr" to "Devanagari-Latin",
        "ne" to "Devanagari-Latin",
        "ar" to "Arabic-Latin",
        "fa" to "Arabic-Latin",
        "ur" to "Arabic-Latin",
        "he" to "Hebrew-Latin",
        "th" to "Thai-Latin",
        "ka" to "Georgian-Latin",
        "hy" to "Armenian-Latin",
        "bn" to "Bengali-Latin",
        "gu" to "Gujarati-Latin",
        "pa" to "Gurmukhi-Latin",
        "ta" to "Tamil-Latin",
        "te" to "Telugu-Latin",
        "kn" to "Kannada-Latin",
        "ml" to "Malayalam-Latin",
        "si" to "Sinhala-Latin",
    )

    /** Kanji are absent from ICU's Japanese romanisation, so a line with them is left alone. */
    private val han = '\u4E00'..'\u9FFF'

    /** The line in Latin letters, or null when there is nothing honest to draw. */
    fun of(text: String, lang: String?): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        if (text.isBlank()) return null

        val code = Languages.canonical(lang) ?: return null
        val id = when (code) {
            // Kana-only Japanese: ICU spells the whole line. With kanji in it
            // the reading comes from the engine instead (the ruby above the
            // line), because ICU would answer Chinese readings for those glyphs.
            "ja" -> if (text.any { it in han }) null else "Any-Latin"
            else -> scripts[code]
        } ?: return null

        val out = runCatching { Transliterator.getInstance(id).transliterate(text) }
            .getOrNull()
            ?.trim()
            ?: return null

        if (out.isBlank() || out.equals(text.trim(), ignoreCase = true)) return null
        return out
    }
}
