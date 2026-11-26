import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// ── the version ───────────────────────────────────────────────────────────
// `version.properties` at the top of the repository is the only place either
// number is written down; nothing below repeats them. VersionSourceTest fails
// the build if anyone types a version number into this file again.
//
// VERSION_CODE moves on by itself, once per signed release build, so two
// uploads to Play can never share a code.
//
// VERSION_NAME moves once per release, on the release build itself, by the step
// the work deserves: 1.0.1 -> 1.0.2 for a fix, 1.0.1 -> 1.1.1 for a feature,
// 1.1.1 -> 2.0.0 for a remake. The step is worked out from the commits since
// the last bump, or stated outright with `lucid.releaseType` in
// android/gradle.properties (or -Plucid.releaseType=), which is what a real
// release should do when the commits do not say it clearly. The name moves at
// most once per commit, so rebuilding the same release keeps its name.
val versionFile: File = File(rootProject.projectDir.parentFile, "version.properties")

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

val versionStore = readVersion()
var VERSION_NAME: String = versionStore.getProperty("VERSION_NAME")?.trim()
    ?: error("VERSION_NAME is missing from ${versionFile.absolutePath}")
var VERSION_CODE: Int = versionStore.getProperty("VERSION_CODE")?.trim()?.toIntOrNull()
    ?: error("VERSION_CODE is missing or not a number in ${versionFile.absolutePath}")

fun git(vararg args: String): String = providers.exec {
    commandLine("git", *args)
    workingDir = rootProject.projectDir.parentFile
    isIgnoreExitValue = true
}.standardOutput.asText.get().trim()

/**
 * fix: 1.0.1 -> 1.0.2 · feature: 1.0.1 -> 1.1.1 · breaking: 1.1.1 -> 2.0.0.
 *
 * The same three lines live in android/build.gradle.kts for `bumpVersion`; it
 * cannot share this one, because a task runs after the module has been
 * configured and the name has to be decided while configuring.
 */
fun nextName(from: String, step: String): String {
    val parts = from.split('.').map { it.toIntOrNull() ?: 0 }.toMutableList()
    while (parts.size < 3) parts.add(0)
    return when (step) {
        "fix" -> "${parts[0]}.${parts[1]}.${parts[2] + 1}"
        "feature" -> "${parts[0]}.${parts[1] + 1}.${parts[2]}"
        "breaking" -> "${parts[0] + 1}.0.0"
        else -> throw GradleException("Unknown step \"$step\"; use fix, feature or breaking.")
    }
}

/**
 * What this release is, read from the work since the version was last written:
 * a `BREAKING` note or a `type!:` subject is a remake, a `feat:` subject or a
 * new source file is a feature, anything else that touches the app is a fix,
 * and documentation on its own earns no number at all (null).
 */
fun stepFromHistory(): String? {
    val since = git("log", "-1", "--format=%H", "--", "version.properties")
    if (since.isBlank()) return "fix"
    val span = "$since..HEAD"
    val subjects = git("log", "--format=%s", span).lines().filter { it.isNotBlank() }
    val touched = git("diff", "--name-only", span).lines().filter { it.isNotBlank() }
    val added = git("diff", "--name-only", "--diff-filter=A", span).lines().filter { it.isNotBlank() }
    if (touched.none { it.startsWith("android/") }) return null
    if (subjects.any { it.contains("BREAKING", ignoreCase = true) }) return "breaking"
    if (subjects.any { Regex("""^\w+(\(.+\))?!:""").containsMatchIn(it) }) return "breaking"
    if (subjects.any { Regex("""^(feat|feature)\b""", RegexOption.IGNORE_CASE).containsMatchIn(it) }) return "feature"
    if (added.any { it.startsWith("android/app/src/main") }) return "feature"
    return "fix"
}

/** Where the last name bump was recorded, so one commit can only move it once. */
val versionStateFile: File = File(rootProject.projectDir, ".gradle/lucid-version-state")

fun lastNamedCommit(): String =
    versionStateFile.takeIf { it.isFile }
        ?.readLines()
        ?.firstOrNull { it.startsWith("head=") }
        ?.substringAfter('=')
        .orEmpty()

fun recordName(name: String) {
    versionStateFile.parentFile.mkdirs()
    versionStateFile.writeText("head=${git("rev-parse", "HEAD").ifBlank { "none" }}\nname=$name\n")
}

// ── one code per signed release build ─────────────────────────────────────
// Only a task that produces the artifact counts, and it counts only when its
// leaf name is exactly `bundleRelease` or `assembleRelease` — so
// `compileReleaseKotlin`, `testReleaseUnitTest` and `lintRelease` never move
// the number. A dry run writes nothing: `--dry-run` has to be free.
val requestedTaskLeaves = gradle.startParameter.taskNames.map { it.substringAfterLast(':').lowercase() }
val signedReleaseRequested = requestedTaskLeaves.any { it == "bundlerelease" || it == "assemblerelease" }

if (signedReleaseRequested && !gradle.startParameter.isDryRun) {
    val previousCode = VERSION_CODE
    val previousName = VERSION_NAME
    val head = git("rev-parse", "HEAD").ifBlank { "none" }

    val stated = (providers.gradleProperty("lucid.releaseType").orNull ?: providers.gradleProperty("type").orNull)
        ?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
    if (stated != null && stated !in setOf("fix", "feature", "breaking")) {
        throw GradleException("lucid.releaseType=$stated is not a step; use fix, feature or breaking.")
    }

    val step = when {
        lastNamedCommit() == head -> null          // this commit already named its release
        stated != null -> stated
        else -> stepFromHistory()
    }

    if (step == null) {
        logger.lifecycle(
            "Lucid version name stays at $VERSION_NAME — " +
                if (lastNamedCommit() == head) "already moved for this commit"
                else "nothing since the last bump earned a new one"
        )
    } else {
        VERSION_NAME = nextName(VERSION_NAME, step)
        logger.lifecycle("Lucid version name: $previousName -> $VERSION_NAME ($step)")
    }

    VERSION_CODE = previousCode + 1
    writeVersion(VERSION_NAME, VERSION_CODE)
    recordName(VERSION_NAME)
    logger.lifecycle("Lucid version code: $previousCode -> $VERSION_CODE")
}

// ── signing ───────────────────────────────────────────────────────────────
// Nothing secret lives in this repository: the credentials come from the
// environment or from a `.env` at the top of the repository (git-ignored), and
// the keystore itself stays outside the repo — ~/.grafizum/keys/lucid-upload.jks
// — so a clone, a CI checkout or a shared archive has no key in it.
val repoRoot: File = rootProject.projectDir.parentFile

val dotEnv: Map<String, String> = File(repoRoot, ".env")
    .takeIf { it.isFile }
    ?.readLines()
    ?.mapNotNull { raw ->
        val line = raw.trim()
        if (line.isEmpty() || line.startsWith("#")) return@mapNotNull null
        line.removePrefix("export ").split('=', limit = 2)
            .takeIf { it.size == 2 }
            ?.let { (key, value) -> key.trim() to value.trim().removeSurrounding("\"").removeSurrounding("'") }
    }
    ?.toMap()
    .orEmpty()

fun secret(key: String): String? =
    System.getenv(key)?.takeIf { it.isNotBlank() } ?: dotEnv[key]?.takeIf { it.isNotBlank() }

val releaseKeystore: File? = secret("LUCID_KEYSTORE_PATH")
    ?.let { path -> if (File(path).isAbsolute) File(path) else File(repoRoot, path) }
    ?.takeIf { it.isFile }

val releaseSigningReady = releaseKeystore != null &&
    secret("LUCID_KEYSTORE_PASSWORD") != null &&
    secret("LUCID_KEY_ALIAS") != null &&
    secret("LUCID_KEY_PASSWORD") != null

if (signedReleaseRequested && !gradle.startParameter.isDryRun) {
    logger.lifecycle(
        if (releaseSigningReady) "Lucid release will be signed with $releaseKeystore"
        else "Lucid release will be UNSIGNED — set LUCID_KEYSTORE_PATH, LUCID_KEYSTORE_PASSWORD, " +
            "LUCID_KEY_ALIAS and LUCID_KEY_PASSWORD (environment or .env)"
    )
}

android {
    namespace = "com.grafizum.lucid"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.grafizum.lucid"
        // Android 7.0 is as far back as the app can honestly go: Play's age
        // signals declare 24, and the quick-settings tile is API 24 too. The
        // lens itself needs nothing newer — every Android 8 call it makes is
        // guarded — so a higher floor would only turn phones away.
        minSdk = 24
        // Play requires API 36 (Android 16) for new apps and updates.
        targetSdk = 36
        versionCode = VERSION_CODE
        versionName = VERSION_NAME
        // Lucid's own shell (notification, quick button, text-access name)
        // follows the phone's language wherever a folder below exists.
        resourceConfigurations += listOf(
            "en", "ar", "cs", "de", "es", "fr", "hi", "id", "it", "ja", "ko",
            "nl", "pl", "pt", "ru", "sv", "th", "tr", "uk", "vi", "zh",
        )

        // Look mode is checked against a real OCR pass on a drawn picture, so the
        // instrumented suite runs on a device or emulator.
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // 32-bit ARM is not optional: plenty of the older phones Lucid is meant
        // to run on are armeabi-v7a only, and a build without it is one Play
        // refuses to install on them. ML Kit ships that slice of its libraries,
        // so the cost is size, not capability. x86_64 keeps emulators working.
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }
    }

    signingConfigs {
        if (releaseSigningReady) {
            create("release") {
                storeFile = releaseKeystore
                storePassword = secret("LUCID_KEYSTORE_PASSWORD")
                keyAlias = secret("LUCID_KEY_ALIAS")
                keyPassword = secret("LUCID_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (releaseSigningReady) signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    packaging {
        resources.excludes += setOf("META-INF/{AL2.0,LGPL2.1}")
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    // The start screen prints which build it is, bottom left, so the version
    // numbers have to be readable from code.
    buildFeatures {
        buildConfig = true
    }
}

// The bundle carries its version in its own file name, the way Repio's builds
// do: Android Studio's "Generate Signed App Bundle" then writes
// Lucid-v1.0.0-v2-release.aab instead of an anonymous app-release.aab.
base {
    val name = android.defaultConfig.versionName
    val code = android.defaultConfig.versionCode
    archivesName.set("Lucid-v$name-v$code")
}

// VersionSourceTest reads the two files directly, so the test does not have to
// guess where Gradle put the project.
tasks.withType<Test>().configureEach {
    systemProperty("lucid.versionFile", versionFile.absolutePath)
    systemProperty("lucid.appBuildFile", layout.projectDirectory.file("build.gradle.kts").asFile.absolutePath)
}

tasks.register("printVersion") {
    group = "versioning"
    description = "Prints the version the next build would carry."
    doLast {
        logger.lifecycle("Lucid is at $VERSION_NAME ($VERSION_CODE) — ${versionFile.absolutePath}")
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("com.google.android.material:material:1.12.0")

    // Age assurance (CA, AL, TX, UT, LA, BR). Read once on open and logged;
    // Lucid is all-ages, so nothing is gated on it.
    implementation("com.google.android.play:age-signals:0.0.6")

    // The Play rating sheet, asked once after the lens has answered a few
    // reads. Android draws it; the app only decides when.
    implementation("com.google.android.play:review:2.0.2")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.9.0")

    // On-device ML: OCR, language identification, translation. No API key.
    // The Latin recogniser is bundled; the four other scripts are separate
    // models that Play services downloads the first time one is asked for.
    implementation("com.google.mlkit:text-recognition:16.0.1")
    implementation("com.google.mlkit:text-recognition-japanese:16.0.1")
    implementation("com.google.mlkit:text-recognition-chinese:16.0.1")
    implementation("com.google.mlkit:text-recognition-korean:16.0.1")
    implementation("com.google.mlkit:text-recognition-devanagari:16.0.1")
    implementation("com.google.mlkit:language-id:17.0.6")
    implementation("com.google.mlkit:translate:17.0.3")

    // The splitting and alignment rules that make the word-by-word card are
    // pure logic, so they are checked on the JVM rather than on a device.
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")

    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.9.0")
}
