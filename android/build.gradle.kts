import java.util.Properties

plugins {
    id("com.android.application") version "8.13.2" apply false
    id("org.jetbrains.kotlin.android") version "2.2.21" apply false
}

// ── the version ───────────────────────────────────────────────────────────
// `version.properties` at the top of the repository holds both numbers and is
// the only place they are written down. The app module reads it; this task is
// the manual way to move the name, and a release build moves it on its own.
val versionFile: File = File(projectDir.parentFile, "version.properties")

fun readVersion(): Properties {
    check(versionFile.isFile) { "Lucid: version.properties is missing at ${versionFile.absolutePath}" }
    return Properties().apply { versionFile.inputStream().use { load(it) } }
}

fun writeVersion(name: String, code: Int) {
    versionFile.writeText(
        listOf(
            "# Lucid's version. The only place either number is written down — read by",
            "# android/app/build.gradle.kts, never typed there.",
            "#   VERSION_CODE moves on its own for each signed release build.",
            "#   VERSION_NAME moves once per release: ./gradlew bumpVersion -Ptype=fix|feature|breaking",
            "VERSION_NAME=$name",
            "VERSION_CODE=$code",
            "",
        ).joinToString("\n")
    )
}

/**
 * The size of the step, in the numbers a release actually shows:
 *
 *   1.0.1  a fix      — the patch moves, 1.0.1 -> 1.0.2
 *   1.1.1  a feature  — the minor moves and the patch stays, 1.0.1 -> 1.1.1
 *   2.0.0  a remake   — the major moves and everything under it resets, 1.1.1 -> 2.0.0
 *
 * The app module has the same three lines for the release build (it has to
 * decide the name at configuration time, where this task cannot reach); keep
 * the two in step if the rule ever changes.
 */
fun raise(name: String, type: String): String {
    val parts = name.split('.').map { it.toIntOrNull() ?: 0 }.toMutableList()
    while (parts.size < 3) parts.add(0)
    return when (type) {
        "fix" -> "${parts[0]}.${parts[1]}.${parts[2] + 1}"
        "feature" -> "${parts[0]}.${parts[1] + 1}.${parts[2]}"
        "breaking" -> "${parts[0] + 1}.0.0"
        else -> throw GradleException(
            "Pass -Ptype=fix (patch), -Ptype=feature (minor) or -Ptype=breaking (major)."
        )
    }
}

fun git(vararg args: String): String = providers.exec {
    commandLine("git", *args)
    workingDir = projectDir.parentFile
    isIgnoreExitValue = true
}.standardOutput.asText.get().trim()

/** Where the last name bump was recorded, so a rebuild cannot move it twice. */
val versionStateFile: File = File(projectDir, ".gradle/lucid-version-state")

fun recordNameBump(name: String) {
    versionStateFile.parentFile.mkdirs()
    versionStateFile.writeText("head=${git("rev-parse", "HEAD").ifBlank { "none" }}\nname=$name\n")
}

/**
 * Raises VERSION_NAME once per release: `./gradlew bumpVersion -Ptype=fix
 * -Pnote="what shipped"`. VERSION_CODE is left exactly as it is — a code belongs
 * to a build, and only a signed release build consumes one.
 */
tasks.register("bumpVersion") {
    group = "versioning"
    description = "Raises VERSION_NAME by -Ptype=fix|feature|breaking. VERSION_CODE is untouched."

    val type = providers.gradleProperty("type").orNull?.trim()?.lowercase()
    val note = providers.gradleProperty("note").orNull?.trim()

    doLast {
        val store = readVersion()
        val oldName = store.getProperty("VERSION_NAME")?.trim()
            ?: throw GradleException("VERSION_NAME is missing from ${versionFile.absolutePath}")
        val code = store.getProperty("VERSION_CODE")?.trim()?.toIntOrNull()
            ?: throw GradleException("VERSION_CODE is missing or not a number in ${versionFile.absolutePath}")

        val newName = raise(oldName, type ?: throw GradleException("Missing -Ptype=fix|feature|breaking."))
        writeVersion(newName, code)
        recordNameBump(newName)
        logger.lifecycle("Lucid version name: $oldName -> $newName ($type)")
        logger.lifecycle("Lucid version code: $code -> $code (unchanged: only a signed release build moves it)")
        if (!note.isNullOrBlank()) logger.lifecycle("Release note: $note")
    }
}
