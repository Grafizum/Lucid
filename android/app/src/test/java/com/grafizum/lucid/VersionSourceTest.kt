package com.grafizum.lucid

import java.io.File
import java.util.Properties
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the rule that `version.properties` at the top of the repository is the
 * only source of truth for the version.
 *
 * Two things can go wrong here, and both are silent until Play rejects an
 * upload: somebody types a version number back into the build file, or the name
 * drifts into something that is not `x.y.z`. These tests fail on both.
 */
class VersionSourceTest {

    private fun fileFrom(property: String): File {
        val path = requireNotNull(System.getProperty(property)) {
            "$property is not set — the test task in app/build.gradle.kts passes it"
        }
        return File(path)
    }

    private val versionFile: File = fileFrom("lucid.versionFile")
    private val appBuildFile: File = fileFrom("lucid.appBuildFile")

    private fun versionStore(): Properties =
        Properties().apply { versionFile.inputStream().use { load(it) } }

    private fun versionName(): String =
        requireNotNull(versionStore().getProperty("VERSION_NAME")).trim()

    @Test
    fun `the version file is at the repository root, not beside the build`() {
        assertTrue("version.properties is missing at ${versionFile.absolutePath}", versionFile.isFile)
        assertEquals("version.properties", versionFile.name)
        // android/app/build.gradle.kts -> android/app -> android -> repository root
        val repoRoot = appBuildFile.parentFile.parentFile.parentFile
        assertEquals(repoRoot, versionFile.parentFile)
    }

    @Test
    fun `no second version file exists inside the gradle project`() {
        val strayFile = File(appBuildFile.parentFile.parentFile, "version.properties")
        assertFalse(
            "android/version.properties is a second source of truth; delete it",
            strayFile.exists()
        )
    }

    @Test
    fun `version name is three numbers`() {
        val name = versionName()
        assertTrue(
            "VERSION_NAME \"$name\" must match ^\\d+\\.\\d+\\.\\d+$ so Play accepts it",
            Regex("""^\d+\.\d+\.\d+$""").matches(name)
        )
    }

    @Test
    fun `version code is a positive whole number`() {
        val code = requireNotNull(versionStore().getProperty("VERSION_CODE")).trim()
        assertTrue(
            "VERSION_CODE \"$code\" must be a positive integer",
            (code.toIntOrNull() ?: 0) > 0
        )
    }

    @Test
    fun `the module build file binds both numbers to the file-backed values`() {
        val build = appBuildFile.readText()
        assertTrue(
            "build.gradle.kts must contain `versionCode = VERSION_CODE`",
            build.contains("versionCode = VERSION_CODE")
        )
        assertTrue(
            "build.gradle.kts must contain `versionName = VERSION_NAME`",
            build.contains("versionName = VERSION_NAME")
        )
    }

    @Test
    fun `the module build file contains no hardcoded version number`() {
        val build = appBuildFile.readText()
        val assignments = Regex("""(?m)^[ \t]*version(Code|Name)[ \t]*=[ \t]*(.+?)[ \t]*$""")
            .findAll(build)
            .map { it.groupValues[1] to it.groupValues[2] }
            .toList()

        assertTrue("no versionCode/versionName assignment found at all", assignments.size >= 2)
        assignments.forEach { (kind, value) ->
            val allowed = if (kind == "Code") "VERSION_CODE" else "VERSION_NAME"
            assertTrue(
                "version$kind is assigned `$value` — it must come from version.properties, " +
                    "so it has to be exactly `$allowed`",
                value == allowed
            )
        }
    }
}
