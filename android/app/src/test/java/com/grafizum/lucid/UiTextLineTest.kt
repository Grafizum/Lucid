package com.grafizum.lucid

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A pack is keyed by the English the app already says, so two things have to stay
 * true or a language would be translated twice, or asked for a line it does not
 * have. Both are silent on the phone — the app simply stays English — which is
 * what these tests exist for.
 */
class UiTextLineTest {

    /** android/app, read from the path the test task already passes. */
    private val app: File = File(
        requireNotNull(System.getProperty("lucid.appBuildFile")) {
            "lucid.appBuildFile is not set — the test task in app/build.gradle.kts passes it"
        },
    ).parentFile!!

    @Test
    fun `every line the pages are written in is unique`() {
        assertTrue(
            "a line appears twice in UiText.lines, so one of its translations would be lost",
            UiText.linesAreUnique(),
        )
    }

    /**
     * Every sentence the code asks [UiText.line] for has to be in the list, or the
     * page reads in English with no sign that anything is missing.
     */
    @Test
    fun `no sentence is asked for that the pack does not carry`() {
        val pattern = Regex("""UiText\.line\(\s*(?:this|context|activity)\s*,\s*"([^"]+)"""")
        val missing = ArrayList<String>()
        File(app, "src/main/java").walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .forEach { file ->
                for (match in pattern.findAll(file.readText())) {
                    val line = match.groupValues[1]
                    if (!UiText.carries(line)) missing.add("${file.name}: $line")
                }
            }
        assertTrue("lines asked for that UiText.lines does not have: $missing", missing.isEmpty())
    }
}
