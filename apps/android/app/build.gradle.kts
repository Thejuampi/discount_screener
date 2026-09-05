import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    jacoco
}

val localProperties = Properties().apply {
    val localPropertiesFile = rootProject.file("local.properties")
    if (localPropertiesFile.exists()) {
        localPropertiesFile.inputStream().use(::load)
    }
}

fun releaseProperty(name: String): String? =
    providers.gradleProperty(name).orNull
        ?.takeIf { it.isNotBlank() }
        ?: localProperties.getProperty(name)?.takeIf { it.isNotBlank() }
        ?: System.getenv(name)?.takeIf { it.isNotBlank() }

val releaseStoreFilePath = releaseProperty("DISCOUNT_SCREENER_RELEASE_STORE_FILE")
val releaseStorePassword = releaseProperty("DISCOUNT_SCREENER_RELEASE_STORE_PASSWORD")
val releaseKeyAlias = releaseProperty("DISCOUNT_SCREENER_RELEASE_KEY_ALIAS")
val releaseKeyPassword = releaseProperty("DISCOUNT_SCREENER_RELEASE_KEY_PASSWORD")
val hasCustomReleaseSigning = listOf(
    releaseStoreFilePath,
    releaseStorePassword,
    releaseKeyAlias,
    releaseKeyPassword,
).all { !it.isNullOrBlank() }

// Opt-in to signing a release with the debug key, for a local sideload. See the release build type.
val allowDebugSignedRelease = providers.gradleProperty("allowDebugSignedRelease").orNull.toBoolean()

// Date-based version, computed once from git state by scripts/version.ps1 (single source
// of truth also used by the Windows build). Falls back if git/powershell is unavailable
// so IDE syncs never hard-fail.
val computedVersion: Pair<String, Int> = run {
    val fallback = "0.0.0-unknown" to 1
    try {
        val output = providers.exec {
            commandLine(
                "powershell",
                "-NoProfile",
                "-ExecutionPolicy",
                "Bypass",
                "-File",
                rootProject.file("../../scripts/version.ps1").absolutePath,
            )
        }.standardOutput.asText.get()
        val lines = output.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val name = lines.getOrNull(0)
        val code = lines.getOrNull(1)?.toIntOrNull()
        if (name != null && code != null) name to code else fallback
    } catch (_: Exception) {
        fallback
    }
}

// Live / agent QA universe (≤20 symbols) is opt-in via `make android-run-qa`
// (-PdsQaUniverse=true). A plain debug install is the regular app and cold-starts the
// product universe. Release never honours the flag.
val qaUniverseRequested = providers.gradleProperty("dsQaUniverse").orNull.toBoolean()

android {
    namespace = "com.discountscreener.android"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.discountscreener.android"
        minSdk = 26
        targetSdk = 35
        versionCode = computedVersion.second
        versionName = computedVersion.first
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.all {
            var bench = providers.gradleProperty("dsWarmStartBench")
                .orElse(providers.environmentVariable("DS_WARMSTART_BENCH"))
                .orElse("")
                .get()
            it.systemProperty("dsWarmStartBench", bench)
            it.systemProperty(
                "dsWarmStartBenchReport",
                providers.gradleProperty("dsWarmStartBenchReport")
                    .orElse(providers.environmentVariable("DS_WARMSTART_BENCH_REPORT"))
                    .orElse("")
                    .get(),
            )
            if (bench == "1") {
                it.maxHeapSize = "3g"
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    signingConfigs {
        if (hasCustomReleaseSigning) {
            create("release") {
                storeFile = file(requireNotNull(releaseStoreFilePath))
                storePassword = requireNotNull(releaseStorePassword)
                keyAlias = requireNotNull(releaseKeyAlias)
                keyPassword = requireNotNull(releaseKeyPassword)
            }
        }
    }

    buildTypes {
        getByName("debug") {
            enableUnitTestCoverage = true
            buildConfigField("boolean", "QA_UNIVERSE", qaUniverseRequested.toString())
        }
        getByName("release") {
            buildConfigField("boolean", "QA_UNIVERSE", "false")
            // Which key is used is decided here; *whether that key is acceptable* is decided in
            // `packageRelease` below, because this block is evaluated during configuration and a
            // refusal thrown here would fail `:app:testDebugUnitTest` too.
            signingConfig = if (hasCustomReleaseSigning) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
        }
    }

    lint {
        // AGP/Lint is currently failing to load its own runtime classes for release-only
        // lintVital on this toolchain, which blocks local release packaging even when
        // compilation, tests, and assembleRelease are otherwise healthy.
        checkReleaseBuilds = false
    }
}

dependencies {
    implementation(project(":core"))

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:okhttp-urlconnection:4.12.0")

    implementation(platform("androidx.compose:compose-bom:2024.10.01"))
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.7")
    implementation("androidx.work:work-runtime-ktx:2.9.1")

    debugImplementation("androidx.compose.ui:ui-tooling")

    testImplementation("junit:junit:4.13.2")
    testImplementation("androidx.test:core:1.6.1")
    testImplementation(platform("androidx.compose:compose-bom:2024.10.01"))
    testImplementation("androidx.compose.ui:ui-test-junit4")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("androidx.work:work-testing:2.9.1")

    debugImplementation("androidx.compose.ui:ui-test-manifest")
}

/**
 * Robolectric loads classes through its own sandbox classloader, which leaves them without a code
 * location — JaCoCo drops those by default and reports every Robolectric-only class at zero, which
 * reads as "untested" for code that is in fact exercised. `jdk.internal.*` is excluded because
 * instrumenting it throws under Java 17.
 */
/**
 * A release signed with the debug key is not a release, and it used to become one in silence.
 *
 * With no keystore configured the release build type falls through to the debug signing config.
 * A bare `assembleRelease` used to export that as a release APK in silence. The Android debug
 * key sits on every machine that has ever built an app, so anyone can sign a forged update.
 *
 * `make android-release` is the release build type, not a signing identity. It passes
 * `-PallowDebugSignedRelease=true` on purpose. A bare `assembleRelease` still refuses unless
 * that flag is set, so a silent Gradle release cannot downgrade its own trust.
 *
 * **Checked on the packaging task, not in the `release` build type.** The build type is evaluated
 * during configuration, so a refusal thrown there fails every invocation in this module — including
 * `:app:testDebugUnitTest`, which has nothing to do with signing. That is not a hypothetical: the
 * first version of this guard did exactly that, and a loop of the unit suite is what caught it.
 *
 * The two flags are copied into locals first so the action captures plain booleans. Reading the
 * script's own properties from inside `doFirst` captures the script object, which the configuration
 * cache cannot serialize — the second version of this guard did that, and `assembleRelease` is what
 * caught it.
 */
run {
    val signingKeyIsReal = hasCustomReleaseSigning
    val debugSignedReleaseAllowed = allowDebugSignedRelease
    tasks.matching { it.name == "packageRelease" }.configureEach {
        doFirst {
            if (signingKeyIsReal) return@doFirst
            if (!debugSignedReleaseAllowed) {
                throw GradleException(
                    "Refusing to build a release signed with the debug key.\n" +
                        "  To sign properly: run `make android-signing-bootstrap` once, which " +
                        "creates a keystore and writes it into local.properties.\n" +
                        "  For a local sideload only: re-run with " +
                        "-PallowDebugSignedRelease=true and do not distribute the result.",
                )
            }
            logger.warn(
                "WARNING: signing the release with the DEBUG key. This APK is for local " +
                    "sideloading only and must not be distributed. Run " +
                    "`make android-signing-bootstrap` to create a real keystore.",
            )
        }
    }
}

tasks.withType<Test>().configureEach {
    extensions.configure<JacocoTaskExtension> {
        isIncludeNoLocationClasses = true
        excludes = listOf("jdk.internal.*")
    }
}

/**
 * Coverage for the debug unit tests.
 *
 * Generated sources are excluded rather than reported at zero: Compose's `*ComposableSingletons*`
 * holders, Hilt-style `*_Factory` classes and `BuildConfig` are emitted by the toolchain, so
 * counting them would move the number without any test being able to move it back.
 */
tasks.register<JacocoReport>("jacocoTestReport") {
    dependsOn("testDebugUnitTest")
    reports {
        xml.required.set(true)
        html.required.set(true)
    }

    val excluded = listOf(
        "**/R.class", "**/R$*.class", "**/BuildConfig.*", "**/Manifest*.*",
        "**/*_Factory.*", "**/*Test*.*", "**/ComposableSingletons*.*",
        "**/*\$\$inlined*.*", "**/*_Impl*.*",
    )
    classDirectories.setFrom(
        fileTree(layout.buildDirectory.dir("tmp/kotlin-classes/debug")) { exclude(excluded) },
    )
    sourceDirectories.setFrom(files("src/main/kotlin"))
    // Named directories, not a scan of the whole build folder. A `fileTree(buildDirectory)` makes
    // this task an implicit consumer of every task that writes anywhere under `build/`, so running
    // it alongside `assembleDebug` fails on an undeclared dependency on the dex tasks.
    executionData.setFrom(
        fileTree(layout.buildDirectory.dir("outputs/unit_test_code_coverage")) { include("**/*.exec") },
        fileTree(layout.buildDirectory.dir("jacoco")) { include("*.exec") },
    )
}
