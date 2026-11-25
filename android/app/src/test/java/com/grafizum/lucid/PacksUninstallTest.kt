package com.grafizum.lucid

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every language pack Lucid keeps has to be inside the folder Android deletes
 * with the app, or it outlives the uninstall and fills the phone with words
 * nothing on it can read. The packs are the app's own word lists, written under
 * `filesDir`, and ML Kit's offline models, which it keeps in the same private
 * folder. Two things could break that without any screen looking wrong, and both
 * are what these tests watch: a file written somewhere the phone shares, and a
 * backup rule that puts the packs back on the next phone the app is installed on.
 */
class PacksUninstallTest {

    /** android/app — the module, read from the path the test task already passes. */
    private val app: File = File(
        requireNotNull(System.getProperty("lucid.appBuildFile")) {
            "lucid.appBuildFile is not set — the test task in app/build.gradle.kts passes it"
        },
    ).parentFile!!

    /** The ways a file can be written outside the folder uninstall deletes. */
    private val sharedStorage = listOf(
        "getExternalStorageDirectory",
        "getExternalFilesDir",
        "getExternalCacheDir",
        "getExternalMediaDirs",
        "getExternalFilesDirs",
        "Environment.getExternal",
        "/sdcard",
    )

    @Test
    fun `nothing is written where the phone shares files`() {
        val sources = File(app, "src/main/java").walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
        val found = ArrayList<String>()
        for (source in sources) {
            val text = source.readText()
            for (token in sharedStorage) {
                if (text.contains(token)) found.add("${source.name}: $token")
            }
        }
        assertTrue(
            "a pack outside the app's own folder survives uninstall: $found",
            found.isEmpty(),
        )
    }

    @Test
    fun `the app's data is not backed up to the next install`() {
        val manifest = File(app, "src/main/AndroidManifest.xml").readText()
        assertTrue(
            "allowBackup must stay false, or Android restores old packs onto a new install",
            manifest.contains("""android:allowBackup="false""""),
        )
    }

    @Test
    fun `the phone is never asked to keep the app's data`() {
        val manifest = File(app, "src/main/AndroidManifest.xml").readText()
        assertFalse(
            "hasFragileUserData would ask the phone to keep Lucid's packs on uninstall",
            manifest.contains("hasFragileUserData"),
        )
    }
}
