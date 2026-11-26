package com.grafizum.lucid

import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * The one request the app makes to the keyless translate endpoint.
 *
 * Two things here are not decoration. The request is a **POST**: the same
 * endpoint answers a plain GET with Google's "Sorry…" page (HTTP 429) from
 * ordinary phone networks — carrier addresses are shared by thousands of
 * people, and a GET from one of them is what the throttling catches — while
 * the identical form body sent as a POST comes back with the answer. That was
 * the whole of "translation does not work on my phone": the request never
 * reached the engine, so the app could only report that it could not reach it.
 *
 * A POST also removes the length ceiling a GET has. A block of Japanese is
 * nine percent-encoded bytes per character, so a page of it ran past ten
 * kilobytes of URL and was refused by size before it was ever translated.
 *
 * The two hosts speak the same protocol and answer with the same JSON. The
 * first is the one that has always answered; the second is the older sibling
 * under the same service, kept as the retry so one host refusing a network is
 * not the end of the read.
 */
internal object TranslateNet {

    /**
     * A browser's own string. A made-up one ("Lucid/1.0") is a request no
     * browser would ever send, and some networks — and the endpoint itself —
     * treat it as one to be shown the door.
     */
    private const val BROWSER =
        "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/130.0 Mobile Safari/537.36"

    private val HOSTS = listOf(
        "https://clients5.google.com/translate_a/single",
        "https://translate.googleapis.com/translate_a/single",
    )

    /** One host's answer: the status it gave, and its body when that was a 2xx. */
    private class Answer(val status: Int, val body: String?)

    /**
     * The engine's JSON for [params], or null when no host answered.
     *
     * A busiest-of-all answer — 429, or a 503 while the service sheds load — is
     * the endpoint saying "not this second", not a refusal of the request. One
     * short pause and the same host is asked again before the next one is
     * tried: a lens read that fell back to an undownloaded offline model over a
     * momentary throttle is exactly the read people report as broken.
     */
    fun ask(params: String): String? {
        for (host in HOSTS) {
            var answer = attempt(host, params)
            if (answer.status == 429 || answer.status == 503) {
                Thread.sleep(RETRY_PAUSE_MS)
                answer = attempt(host, params)
            }
            val body = answer.body
            if (!body.isNullOrBlank()) return body
        }
        return null
    }

    private fun attempt(host: String, params: String): Answer =
        runCatching { post(host, params) }.getOrElse { Answer(0, null) }

    /**
     * One POST. Timeouts are generous because a phone on a slow network is the
     * phone that needs this to work; they are still bounded, so a request that
     * will never answer cannot hold the card spinning — two hosts, and a retry,
     * still add up to less than the wait a thumb will sit through.
     */
    private fun post(host: String, params: String): Answer {
        val connection = (URL(host).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            // A redirect is not followed: the connection would resend this as a
            // GET, and a GET carries none of the form body — the query would be
            // gone and the answer would be an error about a request with no
            // text in it. The second host is asked instead.
            instanceFollowRedirects = false
            setRequestProperty("User-Agent", BROWSER)
            setRequestProperty(
                "Content-Type",
                "application/x-www-form-urlencoded; charset=UTF-8",
            )
            connectTimeout = TimeUnit.SECONDS.toMillis(6).toInt()
            readTimeout = TimeUnit.SECONDS.toMillis(10).toInt()
        }

        try {
            connection.outputStream.use { it.write(params.toByteArray(Charsets.UTF_8)) }
            val status = connection.responseCode
            if (status !in 200..299) return Answer(status, null)
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            return Answer(status, body)
        } finally {
            connection.disconnect()
        }
    }

    /** The breath taken before a throttled request is asked for again. */
    private const val RETRY_PAUSE_MS = 350L

    /**
     * The form body of one request. Newlines travel as %0A, because the engine
     * keeps them: that is what makes a block of lines come back as lines.
     */
    fun params(
        query: String,
        target: String,
        source: String,
        reading: Boolean,
    ): String = buildString {
        // dict-chrome-ex is the client the sibling host answers for, and the
        // newer endpoint accepts it too — so one body works on both hosts.
        append("client=dict-chrome-ex")
        append("&sl=").append(encode(source))
        append("&dt=t")
        if (reading) append("&dt=rm")
        append("&tl=").append(encode(target))
        append("&q=").append(encode(query))
    }

    /** Form encoding: spaces as %20, never as a plus sign. */
    private fun encode(value: String): String =
        URLEncoder.encode(value, "UTF-8").replace("+", "%20")
}
