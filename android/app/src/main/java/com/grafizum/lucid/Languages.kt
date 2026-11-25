package com.grafizum.lucid

import java.text.Normalizer
import java.util.Locale

/**
 * Every language the lens can be asked for: the corpus is Google's own list,
 * which is what the online engine answers, and inside it sits the smaller ML
 * Kit set that can be downloaded as an on-device pack.
 *
 * [Lang.onDevice] is the line between the two. A language without it is not a
 * lesser choice — the lens reads it whenever the online engine is allowed —
 * but it cannot be fetched for the offline page, so the page leaves it out and
 * Offline only says plainly that it has no model rather than pretending.
 *
 * English names are the app's own labels; [native] is what the row shows first,
 * because that is the name someone looks for when the app's language is not
 * theirs. Where no autonym is known the English name stands in for it, and the
 * row prints one name rather than the same one twice.
 *
 * Codes are the modern BCP-47 ones, except where ML Kit spells a language
 * differently (Hebrew iw, Tagalog tl, Norwegian no, Chinese zh). [forModel]
 * and [canonical] are the two directions of that bridge, so a code picked here
 * and a code the detector answered with always compare equal.
 */
object Languages {

    data class Lang(
        val code: String,
        val label: String,
        val native: String,
        /** Whether ML Kit can keep a model of it on the phone. */
        val onDevice: Boolean = true,
    )

    /**
     * The engine's own spellings for the scripts and regions it lists beside a
     * language. Each maps a lowercase tag back to the code the row carries, so
     * a pick and an answer from the engine still compare equal.
     */
    private val variants = mapOf(
        "fa-af" to "fa-AF",
        "mni-mtei" to "mni-Mtei",
        "ms-arab" to "ms-Arab",
        "pa-arab" to "pa-Arab",
        "crh-latn" to "crh-Latn",
        "iu-latn" to "iu-Latn",
        "bm-nkoo" to "bm-Nkoo",
        "ndc-zw" to "ndc-ZW",
    )

    val targets = listOf(
        Lang("ab", "Abkhaz", "Аԥсшәа", onDevice = false),
        Lang("ace", "Acehnese", "Acehnese", onDevice = false),
        Lang("ach", "Acholi", "Acholi", onDevice = false),
        Lang("aa", "Afar", "Qafar", onDevice = false),
        Lang("af", "Afrikaans", "Afrikaans"),
        Lang("sq", "Albanian", "Shqip"),
        Lang("alz", "Alur", "Alur", onDevice = false),
        Lang("am", "Amharic", "አማርኛ", onDevice = false),
        Lang("ar", "Arabic", "العربية"),
        Lang("hy", "Armenian", "Հայերեն", onDevice = false),
        Lang("as", "Assamese", "অসমীয়া", onDevice = false),
        Lang("av", "Avar", "Avar", onDevice = false),
        Lang("awa", "Awadhi", "Awadhi", onDevice = false),
        Lang("ay", "Aymara", "Aymara", onDevice = false),
        Lang("az", "Azerbaijani", "Azərbaycan dili", onDevice = false),
        Lang("ban", "Balinese", "Balinese", onDevice = false),
        Lang("bal", "Baluchi", "بلۆچی", onDevice = false),
        Lang("bm", "Bambara", "Bamanakan", onDevice = false),
        Lang("bci", "Baoulé", "Baoulé", onDevice = false),
        Lang("ba", "Bashkir", "Башҡорт теле", onDevice = false),
        Lang("eu", "Basque", "Euskara", onDevice = false),
        Lang("btx", "Batak Karo", "Batak Karo", onDevice = false),
        Lang("bts", "Batak Simalungun", "Batak Simalungun", onDevice = false),
        Lang("bbc", "Batak Toba", "Batak Toba", onDevice = false),
        Lang("be", "Belarusian", "Беларуская"),
        Lang("bem", "Bemba", "Ichibemba", onDevice = false),
        Lang("bn", "Bengali", "বাংলা"),
        Lang("bew", "Betawi", "Betawi", onDevice = false),
        Lang("bho", "Bhojpuri", "भोजपुरी", onDevice = false),
        Lang("bik", "Bikol", "Bikol", onDevice = false),
        Lang("bs", "Bosnian", "Bosanski", onDevice = false),
        Lang("br", "Breton", "Brezhoneg", onDevice = false),
        Lang("bg", "Bulgarian", "Български"),
        Lang("bua", "Buryat", "Buryat", onDevice = false),
        Lang("yue", "Cantonese", "粵語", onDevice = false),
        Lang("ca", "Catalan", "Català"),
        Lang("ceb", "Cebuano", "Cebuano", onDevice = false),
        Lang("ch", "Chamorro", "Chamorro", onDevice = false),
        Lang("ce", "Chechen", "Нохчийн", onDevice = false),
        Lang("ny", "Chichewa", "Chichewa", onDevice = false),
        Lang("zh", "Chinese (Simplified)", "简体中文"),
        Lang("zh-TW", "Chinese (Traditional)", "繁體中文", onDevice = false),
        Lang("chk", "Chuukese", "Chuukese", onDevice = false),
        Lang("cv", "Chuvash", "Чӑваш", onDevice = false),
        Lang("co", "Corsican", "Corsu", onDevice = false),
        Lang("crh", "Crimean Tatar (Cyrillic)", "Crimean Tatar (Cyrillic)", onDevice = false),
        Lang("crh-Latn", "Crimean Tatar (Latin)", "Qırımtatarca", onDevice = false),
        Lang("hr", "Croatian", "Hrvatski"),
        Lang("cs", "Czech", "Čeština"),
        Lang("da", "Danish", "Dansk"),
        Lang("fa-AF", "Dari", "دری", onDevice = false),
        Lang("dv", "Dhivehi", "ދިވެހިބަސް", onDevice = false),
        Lang("din", "Dinka", "Dinka", onDevice = false),
        Lang("doi", "Dogri", "डोगरी", onDevice = false),
        Lang("dov", "Dombe", "Dombe", onDevice = false),
        Lang("nl", "Dutch", "Nederlands"),
        Lang("dyu", "Dyula", "Dyula", onDevice = false),
        Lang("dz", "Dzongkha", "རྫོང་ཁ", onDevice = false),
        Lang("en", "English", "English"),
        Lang("eo", "Esperanto", "Esperanto"),
        Lang("et", "Estonian", "Eesti"),
        Lang("ee", "Ewe", "Eʋegbe", onDevice = false),
        Lang("fo", "Faroese", "Føroyskt", onDevice = false),
        Lang("fj", "Fijian", "Fijian", onDevice = false),
        Lang("tl", "Filipino", "Filipino"),
        Lang("fi", "Finnish", "Suomi"),
        Lang("fon", "Fon", "Fon", onDevice = false),
        Lang("fr", "French", "Français"),
        Lang("fy", "Frisian", "Frysk", onDevice = false),
        Lang("fur", "Friulian", "Furlan", onDevice = false),
        Lang("ff", "Fulani", "Pulaar", onDevice = false),
        Lang("gaa", "Ga", "Gã", onDevice = false),
        Lang("gl", "Galician", "Galego"),
        Lang("ka", "Georgian", "ქართული"),
        Lang("de", "German", "Deutsch"),
        Lang("el", "Greek", "Ελληνικά"),
        Lang("gn", "Guarani", "Avañe’ẽ", onDevice = false),
        Lang("gu", "Gujarati", "ગુજરાતી"),
        Lang("ht", "Haitian Creole", "Kreyòl ayisyen"),
        Lang("cnh", "Hakha Chin", "Hakha Chin", onDevice = false),
        Lang("ha", "Hausa", "Hausa", onDevice = false),
        Lang("haw", "Hawaiian", "ʻŌlelo Hawaiʻi", onDevice = false),
        Lang("he", "Hebrew", "עברית"),
        Lang("hil", "Hiligaynon", "Hiligaynon", onDevice = false),
        Lang("hi", "Hindi", "हिन्दी"),
        Lang("hmn", "Hmong", "Hmoob", onDevice = false),
        Lang("hu", "Hungarian", "Magyar"),
        Lang("hrx", "Hunsrik", "Hunsrik", onDevice = false),
        Lang("iba", "Iban", "Iban", onDevice = false),
        Lang("is", "Icelandic", "Íslenska"),
        Lang("ig", "Igbo", "Igbo", onDevice = false),
        Lang("ilo", "Ilocano", "Ilocano", onDevice = false),
        Lang("id", "Indonesian", "Indonesia"),
        Lang("iu", "Inuktut (Syllabics)", "ᐃᓄᒃᑎᑐᑦ", onDevice = false),
        Lang("iu-Latn", "Inuktut (Latin)", "Inuktut", onDevice = false),
        Lang("ga", "Irish", "Gaeilge"),
        Lang("it", "Italian", "Italiano"),
        Lang("jam", "Jamaican Patois", "Jamaican Patois", onDevice = false),
        Lang("ja", "Japanese", "日本語"),
        Lang("jw", "Javanese", "Basa Jawa", onDevice = false),
        Lang("kac", "Jingpo", "Jingpo", onDevice = false),
        Lang("kl", "Kalaallisut", "Kalaallisut", onDevice = false),
        Lang("kn", "Kannada", "ಕನ್ನಡ"),
        Lang("kr", "Kanuri", "Kanuri", onDevice = false),
        Lang("pam", "Kapampangan", "Kapampangan", onDevice = false),
        Lang("kk", "Kazakh", "Қазақ тілі", onDevice = false),
        Lang("kha", "Khasi", "Khasi", onDevice = false),
        Lang("km", "Khmer", "ខ្មែរ", onDevice = false),
        Lang("cgg", "Kiga", "Rukiga", onDevice = false),
        Lang("kg", "Kikongo", "Kikongo", onDevice = false),
        Lang("rw", "Kinyarwanda", "Ikinyarwanda", onDevice = false),
        Lang("ktu", "Kituba", "Kituba", onDevice = false),
        Lang("trp", "Kokborok", "Kokborok", onDevice = false),
        Lang("kv", "Komi", "Komi", onDevice = false),
        Lang("gom", "Konkani", "कोंकणी", onDevice = false),
        Lang("ko", "Korean", "한국어"),
        Lang("kri", "Krio", "Krio", onDevice = false),
        Lang("ku", "Kurdish", "Kurdî", onDevice = false),
        Lang("ckb", "Kurdish (Sorani)", "کوردیی ناوەندی", onDevice = false),
        Lang("ky", "Kyrgyz", "Кыргызча", onDevice = false),
        Lang("lo", "Lao", "ລາວ", onDevice = false),
        Lang("ltg", "Latgalian", "Latgalīšu", onDevice = false),
        Lang("la", "Latin", "Latina", onDevice = false),
        Lang("lv", "Latvian", "Latviešu"),
        Lang("lij", "Ligurian", "Ligure", onDevice = false),
        Lang("li", "Limburgish", "Limburgish", onDevice = false),
        Lang("ln", "Lingala", "Lingála", onDevice = false),
        Lang("lt", "Lithuanian", "Lietuvių"),
        Lang("lmo", "Lombard", "Lombard", onDevice = false),
        Lang("lg", "Luganda", "Luganda", onDevice = false),
        Lang("luo", "Luo", "Dholuo", onDevice = false),
        Lang("lb", "Luxembourgish", "Lëtzebuergesch", onDevice = false),
        Lang("mk", "Macedonian", "Македонски"),
        Lang("mad", "Madurese", "Madurese", onDevice = false),
        Lang("mai", "Maithili", "मैथिली", onDevice = false),
        Lang("mak", "Makassar", "Makassar", onDevice = false),
        Lang("mg", "Malagasy", "Malagasy", onDevice = false),
        Lang("ms", "Malay", "Bahasa Melayu"),
        Lang("ms-Arab", "Malay (Jawi)", "بهاس ملايو", onDevice = false),
        Lang("ml", "Malayalam", "മലയാളം", onDevice = false),
        Lang("mt", "Maltese", "Malti"),
        Lang("mam", "Mam", "Mam", onDevice = false),
        Lang("gv", "Manx", "Gaelg", onDevice = false),
        Lang("mi", "Maori", "Te reo Māori", onDevice = false),
        Lang("mr", "Marathi", "मराठी"),
        Lang("mh", "Marshallese", "Marshallese", onDevice = false),
        Lang("mwr", "Marwadi", "Marwadi", onDevice = false),
        Lang("mfe", "Mauritian Creole", "Kreol morisien", onDevice = false),
        Lang("chm", "Meadow Mari", "Meadow Mari", onDevice = false),
        Lang("mni-Mtei", "Meiteilon (Manipuri)", "ꯃꯤꯇꯩꯂꯣꯟ", onDevice = false),
        Lang("min", "Minang", "Minang", onDevice = false),
        Lang("lus", "Mizo", "Mizo", onDevice = false),
        Lang("mn", "Mongolian", "Монгол", onDevice = false),
        Lang("my", "Myanmar (Burmese)", "မြန်မာ", onDevice = false),
        Lang("nhe", "Nahuatl (Eastern Huasteca)", "Nahuatl (Eastern Huasteca)", onDevice = false),
        Lang("ndc-ZW", "Ndau", "Ndau", onDevice = false),
        Lang("nr", "Ndebele (South)", "IsiNdebele", onDevice = false),
        Lang("new", "Nepalbhasa (Newari)", "Nepalbhasa (Newari)", onDevice = false),
        Lang("ne", "Nepali", "नेपाली", onDevice = false),
        Lang("bm-Nkoo", "NKo", "ߒߞߏ", onDevice = false),
        Lang("no", "Norwegian", "Norsk"),
        Lang("nus", "Nuer", "Thok Nath", onDevice = false),
        Lang("oc", "Occitan", "Occitan", onDevice = false),
        Lang("or", "Odia (Oriya)", "ଓଡ଼ିଆ", onDevice = false),
        Lang("om", "Oromo", "Oromoo", onDevice = false),
        Lang("os", "Ossetian", "Ирон", onDevice = false),
        Lang("pag", "Pangasinan", "Pangasinan", onDevice = false),
        Lang("pap", "Papiamento", "Papiamentu", onDevice = false),
        Lang("ps", "Pashto", "پښتو", onDevice = false),
        Lang("fa", "Persian", "فارسی"),
        Lang("pl", "Polish", "Polski"),
        Lang("pt", "Portuguese", "Português"),
        Lang("pa", "Punjabi", "ਪੰਜਾਬੀ", onDevice = false),
        Lang("pa-Arab", "Punjabi (Shahmukhi)", "پنجابی", onDevice = false),
        Lang("qu", "Quechua", "Runasimi", onDevice = false),
        Lang("kek", "Qʼeqchiʼ", "Qʼeqchiʼ", onDevice = false),
        Lang("rom", "Romani", "Romani", onDevice = false),
        Lang("ro", "Romanian", "Română"),
        Lang("rn", "Rundi", "Ikirundi", onDevice = false),
        Lang("ru", "Russian", "Русский"),
        Lang("se", "Sami (North)", "Davvisámegiella", onDevice = false),
        Lang("sm", "Samoan", "Gagana Samoa", onDevice = false),
        Lang("sg", "Sango", "Sängö", onDevice = false),
        Lang("sa", "Sanskrit", "संस्कृत भाषा", onDevice = false),
        Lang("sat", "Santali (Ol Chiki)", "ᱥᱟᱱᱛᱟᱲᱤ", onDevice = false),
        Lang("gd", "Scots Gaelic", "Gàidhlig", onDevice = false),
        Lang("nso", "Sepedi", "Sesotho sa Leboa", onDevice = false),
        Lang("sr", "Serbian", "Српски"),
        Lang("st", "Sesotho", "Sesotho", onDevice = false),
        Lang("crs", "Seychellois Creole", "Seychellois Creole", onDevice = false),
        Lang("shn", "Shan", "တႆး", onDevice = false),
        Lang("sn", "Shona", "chiShona", onDevice = false),
        Lang("scn", "Sicilian", "Sicilianu", onDevice = false),
        Lang("szl", "Silesian", "Ślōnski", onDevice = false),
        Lang("sd", "Sindhi", "سنڌي", onDevice = false),
        Lang("si", "Sinhala", "සිංහල", onDevice = false),
        Lang("sk", "Slovak", "Slovenčina"),
        Lang("sl", "Slovenian", "Slovenščina"),
        Lang("so", "Somali", "Soomaali", onDevice = false),
        Lang("es", "Spanish", "Español"),
        Lang("su", "Sundanese", "Basa Sunda", onDevice = false),
        Lang("sus", "Susu", "Susu", onDevice = false),
        Lang("sw", "Swahili", "Kiswahili"),
        Lang("ss", "Swati", "SiSwati", onDevice = false),
        Lang("sv", "Swedish", "Svenska"),
        Lang("ty", "Tahitian", "Tahitian", onDevice = false),
        Lang("tg", "Tajik", "Тоҷикӣ", onDevice = false),
        Lang("ber", "Tamazight (Tifinagh)", "Tamazight (Tifinagh)", onDevice = false),
        Lang("ta", "Tamil", "தமிழ்"),
        Lang("tt", "Tatar", "Татар", onDevice = false),
        Lang("te", "Telugu", "తెలుగు"),
        Lang("tet", "Tetum", "Tetum", onDevice = false),
        Lang("th", "Thai", "ไทย"),
        Lang("bo", "Tibetan", "བོད་སྐད་", onDevice = false),
        Lang("ti", "Tigrinya", "ትግርኛ", onDevice = false),
        Lang("tiv", "Tiv", "Tiv", onDevice = false),
        Lang("tpi", "Tok Pisin", "Tok Pisin", onDevice = false),
        Lang("to", "Tongan", "Lea fakatonga", onDevice = false),
        Lang("lua", "Tshiluba", "Tshiluba", onDevice = false),
        Lang("ts", "Tsonga", "Xitsonga", onDevice = false),
        Lang("tn", "Tswana", "Setswana", onDevice = false),
        Lang("tcy", "Tulu", "Tulu", onDevice = false),
        Lang("tum", "Tumbuka", "Tumbuka", onDevice = false),
        Lang("tr", "Turkish", "Türkçe"),
        Lang("tk", "Turkmen", "Türkmençe", onDevice = false),
        Lang("tyv", "Tuvan", "Tuvan", onDevice = false),
        Lang("ak", "Twi", "Twi", onDevice = false),
        Lang("udm", "Udmurt", "Udmurt", onDevice = false),
        Lang("uk", "Ukrainian", "Українська"),
        Lang("ur", "Urdu", "اردو"),
        Lang("ug", "Uyghur", "ئۇيغۇرچە", onDevice = false),
        Lang("uz", "Uzbek", "Oʻzbekcha", onDevice = false),
        Lang("ve", "Venda", "Tshivenḓa", onDevice = false),
        Lang("vec", "Venetian", "Veneto", onDevice = false),
        Lang("vi", "Vietnamese", "Tiếng Việt"),
        Lang("war", "Waray", "Waray", onDevice = false),
        Lang("cy", "Welsh", "Cymraeg"),
        Lang("wo", "Wolof", "Wolof", onDevice = false),
        Lang("xh", "Xhosa", "isiXhosa", onDevice = false),
        Lang("sah", "Yakut", "Саха тыла", onDevice = false),
        Lang("yi", "Yiddish", "ייִדיש", onDevice = false),
        Lang("yo", "Yoruba", "Yorùbá", onDevice = false),
        Lang("yua", "Yucatec Maya", "Yucatec Maya", onDevice = false),
        Lang("zap", "Zapotec", "Zapotec", onDevice = false),
        Lang("zu", "Zulu", "isiZulu", onDevice = false),
    )

    fun label(code: String): String =
        targets.firstOrNull { it.code == code }?.label
            ?: runCatching { Locale.forLanguageTag(code).displayLanguage }.getOrNull()
            ?: code

    /** The name as it fits a chip: any parenthesised qualifier left off. */
    fun short(code: String): String = label(code).substringBefore(" (")

    fun nativeLabel(code: String): String =
        targets.firstOrNull { it.code == code }?.native ?: label(code)

    /**
     * The name as it fits a chip, in the language's own words.
     *
     * The corners of the app show the autonym — "日本語", not "Japanese" —
     * because someone who picked Japanese from a list is looking for the word
     * they recognise, and an English name beside a page of English reads as the
     * app's own language rather than the one the lens is set to.
     */
    fun nativeShort(code: String): String = nativeLabel(code).substringBefore(" (")

    /**
     * What the lens should translate into when the person has not chosen: their
     * phone's own language, if Lucid carries it. An English phone keeps English.
     */
    fun defaultTarget(locale: Locale = Locale.getDefault()): String {
        val device = canonical(locale.language) ?: return "en"
        return if (targets.any { it.code == device }) device else "en"
    }

    /**
     * The tag ML Kit wants for one of our codes, or null when it has no model
     * for that language — the online-only half of the list, and Traditional
     * Chinese, which ML Kit only ships in its simplified form.
     *
     * Null is not a failure: the caller skips the on-device attempt instead of
     * handing ML Kit a language it would answer with an exception for.
     */
    fun forModel(code: String): String? {
        val canonicalCode = canonical(code) ?: return null
        if (canonicalCode == "zh-tw") return null
        val known = targets.firstOrNull { it.code == canonicalCode } ?: return null
        if (!known.onDevice) return null
        return when (canonicalCode) {
            "he" -> "iw"
            "tl" -> "tl"
            "no" -> "no"
            "zh" -> "zh"
            else -> canonicalCode
        }
    }

    /**
     * Our code for a tag the detector or the online engine answered with —
     * "iw" and "he" are one language, "fil" and "tl" another, "zh-TW" and
     * "zh-Hant" are the traditional script while plain "zh" is the simplified.
     */
    fun canonical(tag: String?): String? {
        val raw = tag?.trim()?.lowercase(Locale.ROOT)?.replace('_', '-')
            ?.takeIf { it.isNotBlank() && it != "und" && it != "auto" }
            ?: return null
        // The engine lists a few scripts and regions beside a language: Dari is
        // Persian in Afghanistan, Meiteilon has no base code, Jawi and Shahmukhi
        // are Malay and Punjabi in their other script. They stay as the engine
        // spells them, because the row picked is the code sent.
        variants[raw]?.let { return it }
        return when {
            raw == "iw" || raw.startsWith("iw-") || raw == "he" || raw.startsWith("he-") -> "he"
            raw == "fil" || raw == "tl" || raw.startsWith("fil-") -> "tl"
            raw == "nb" || raw == "nn" || raw == "no" -> "no"
            raw == "zh-hant" || raw.startsWith("zh-hant-") ||
                raw == "zh-tw" || raw.startsWith("zh-tw-") ||
                raw == "zh-hk" || raw.startsWith("zh-hk-") -> "zh-tw"
            raw.startsWith("zh") -> "zh"
            else -> raw.substringBefore('-')
        }
    }

    /** Whether [lang] is worth showing for what has been typed so far. */
    fun matches(lang: Lang, query: String): Boolean {
        val needle = fold(query)
        if (needle.isEmpty()) return true
        return fold(lang.label).contains(needle) ||
            fold(lang.native).contains(needle) ||
            lang.code.startsWith(needle)
    }

    /** Lower case and strip the accents, so "espanol" finds "Español". */
    private fun fold(text: String): String =
        Normalizer.normalize(text.trim().lowercase(Locale.ROOT), Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")
}
