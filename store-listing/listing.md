# Lucid — Google Play listing

## App name (30 max)
Lucid: Easy Translator, No Ads

Safer alternative if Google flags "No Ads" in the title:
Lucid: Easy Screen Translator

## Short description (80 max)
Drag the lens over any app and read it in your language. No ads, no sign-up.

## Full description (4000 max)

Read anything on your screen, in your language, without leaving the app you're in.

Lucid puts a small lens on top of your phone. Drag it onto a message, a menu or a web page and let go. The translation appears right there, next to the words you were reading. No copying, no switching apps, no screenshots.

✦ NO ADS. NO ACCOUNT. NO CATCH.
• Zero ads, zero pop-ups, zero trackers
• No sign-up, no login, no email needed
• No history: Lucid keeps no copy of what it reads
• Clean, calm design that stays out of your way

✦ EASY FROM THE FIRST MINUTE
1. Allow two switches once (Lucid explains each one in plain words)
2. Slide "Start the lens"
3. Drag the lens over any text and read the translation
That's it. No tutorials, no settings maze.

✦ WORKS IN EVERY APP
• Chats with friends who write in another language
• Web pages, news and online shops
• Travel apps: tickets, delay notices, bookings
• Restaurant menus, settings, anything with text
One lens for your whole phone. No per-app setup.

✦ SMART, NOT PUSHY
• Reads about 0.3 seconds after the lens stops, so you never wait
• Highlights the exact words it read, line by line
• Shows the translation with the original right below it
• Copy the translation with one tap
• Touch anywhere else and the card disappears
• Tap the lens to switch languages; long-press to pause it
• Start or stop the lens from your quick-settings tile

✦ GREAT FOR ASIAN LANGUAGES TOO
• Furigana readings above Japanese kanji
• Word-by-word help for short Japanese phrases
• Strong results for Chinese and Korean

✦ ONLINE OR FULLY OFFLINE
• Online: best quality, best for tricky sentences
• On-device: download the language once, then translate with no internet. Nothing leaves your phone.

✦ PRIVATE BY DESIGN
Lucid reads text, not pixels. There is no screen recording and nothing is stored.

PERMISSIONS, EXPLAINED
• Display over other apps: lets the lens float above the app you are using.
• Accessibility (Text access): Lucid uses Android's Accessibility API only to read the text under its lens so it can translate it. It does not control other apps and keeps no copy. With the Online engine, the words under the lens are sent to an online translation service, together with up to about 700 characters of the rest of that screen as context. With the On-device engine, nothing leaves the phone. Lucid shows this note and asks you to agree before you turn the permission on.
• Screen capture (optional): only if you turn on Look mode, for photos and apps that hide their text. One still frame per read, kept in memory only.
• Notifications: one notice that keeps the lens running, with a button to turn it off.

Questions or ideas? Write to privacy@grafizum.com
Privacy policy: https://lucid.grafizum.com/privacy

## Release notes ("What's new", en-GB)

Limit: **500 characters per language**, line breaks included. Plain text only.

**House rule — every production release note ends with a test sentence.** The notes
are read on the phone, in Play, with the lens already installed, so the last lines ask
the reader to point the lens at the line below them. It is the fastest possible proof
that the app works, and it demonstrates the feature the release is about (Japanese also
demonstrates readings and the word list, with **ENGINE: Online**).

Wording to start from:

```
Want to try it now? Point the lens at this sentence:
翻訳してこの文を読んでくれたら嬉しいです。もし翻訳を使っていないなら、あなたには役に立たない文でごめんなさい。
```

(The sentence is "I'd be glad if you read this by translating it. If you are not using
the translator, sorry — this sentence is useless to you." — 55 characters, so the whole
closing costs about 110 of the 500. It is a joke that only lands if the lens works: a
reader who did not translate it is being told, politely, that the sentence was not for
them.)

Because of that, the feature bullets have to stay tight: leave at least 120 characters
free at the bottom while drafting. If a release needs more bullets than fit, cut a bullet
rather than the test sentence — the sentence is the part a new user actually uses.

When the notes are translated, invert the pair: translate the instruction, and give the
test sentence in a language that reader probably cannot read yet. For **ja-JP** that
means a Japanese invitation and an English sentence under it:

```
いま試してみませんか？レンズをこの一文に向けてみてください。
Welcome to Lucid. If you can read this, the lens is working.
```

One thing this depends on: `Prefs.targetLanguage` defaults to `"en"` in code, not to the
phone's language. On a Japanese phone the lens therefore translates into English, and an
English test sentence comes back as *ALREADY ENGLISH* with no translation to show. Either
the first-run target must follow the system language when that language is supported, or
the ja-JP note has to tell the reader to set TRANSLATE INTO to 日本語 first. Fix the
default before the ja-JP notes go out.

**Fixed in 1.2.2:** `targetLanguage` now falls back to `Languages.defaultTarget()`, which
returns the phone's language when the lens supports it and `"en"` otherwise. The ja-JP
note can therefore use the inverted pair without asking anyone to change TRANSLATE INTO
first.

Last used: 1.2.2 — home and dark rebuild, themed header tile, history row toggle.

### 1.2.2 — en-GB (475 of 500 characters)

```
• Home rebuilt: the lens and its pages now sit in two cards, with the language beside the Lucid mark and settings one tap behind the gear.
• Dark theme tightened — surfaces step apart and the small labels are readable at night.
• The mark by the title is the launcher tile again, and turns charcoal at night.
• Translation history opens and closes from its own row.

Want to try it now? Point the lens at this sentence:
翻訳してこの文を読んでくれたら嬉しいです。もし翻訳を使っていないなら、あなたには役に立たない文でごめんなさい。
```

The ja-JP pair is not written yet. The blocker the notes above describe was fixed in this
release: `Prefs.targetLanguage` now falls back to `Languages.defaultTarget()`, which
follows the phone's language whenever the lens supports it. On a Japanese phone the lens
therefore translates into 日本語 and the English test sentence comes back as a real
translation, so the ja-JP note can use the inverted pair above.
