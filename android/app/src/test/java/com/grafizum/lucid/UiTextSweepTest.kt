package com.grafizum.lucid

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A pack this version can no longer read is the one kind of language pack that
 * survives everything else: uninstall takes it, but until the sweep ran, nothing
 * else did. It would sit in the app's folder for as long as the app stayed
 * installed, taking space for words no screen can show. These tests pin that the
 * sweep takes it, and leaves the packs that still work alone.
 */
class UiTextSweepTest {

    private fun folder(): File = Files.createTempDirectory("lucid-ui").toFile()

    private fun pack(dir: File, code: String, version: Int): File =
        File(dir, "$code.json").apply {
            writeText("""{"v":$version,"code":"$code","strings":{"tour.skip":"Weiter"}}""")
        }

    @Test
    fun `a pack from an older word list is deleted`() {
        val dir = folder()
        val file = pack(dir, "de", UiText.VERSION - 1)
        assertEquals(listOf("de.json"), UiText.sweep(dir))
        assertFalse(file.exists())
    }

    @Test
    fun `a pack this version wrote is kept`() {
        val dir = folder()
        val file = pack(dir, "de", UiText.VERSION)
        assertTrue("nothing should be taken from $dir", UiText.sweep(dir).isEmpty())
        assertTrue(file.exists())
    }

    @Test
    fun `a download the phone cut short is deleted`() {
        val dir = folder()
        val part = File(dir, "fr.json.part").apply { writeText("{\"v\":") }
        assertEquals(listOf("fr.json.part"), UiText.sweep(dir))
        assertFalse(part.exists())
    }

    @Test
    fun `a pack that cannot be read at all is deleted`() {
        val dir = folder()
        val broken = File(dir, "es.json").apply { writeText("not json") }
        assertEquals(listOf("es.json"), UiText.sweep(dir))
        assertFalse(broken.exists())
    }

    @Test
    fun `a folder that was never made is not a failure`() {
        val dir = File(folder(), "ui")
        assertTrue(UiText.sweep(dir).isEmpty())
    }
}
