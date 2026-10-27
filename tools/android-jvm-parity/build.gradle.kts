// Runs the Android app's data path (YahooFinanceClient + core engines) on a plain JVM.
// No Android SDK: compiles apps/android/core sources and the SDK-free app files directly.
plugins {
    id("org.jetbrains.kotlin.jvm") version "2.0.21"
    id("org.jetbrains.kotlin.plugin.serialization") version "2.0.21"
    application
}

kotlin { jvmToolchain(17) }

val android = rootDir.resolve("../../apps/android")
val appSrc = android.resolve("app/src/main/kotlin/com/discountscreener/android")

sourceSets {
    main {
        kotlin {
            srcDir(android.resolve("core/src/main/kotlin"))
            srcDir("src/main/kotlin")
        }
        resources {
            srcDir(android.resolve("core/src/main/resources"))
            srcDir(rootDir.resolve("build/shared-yaml"))
        }
    }
}

// The app's SDK-free sources, compiled as-is (same module, so `internal` resolves).
val appFiles = tasks.register<Sync>("syncAppSources") {
    from(appSrc) {
        include("data/remote/*.kt")
        exclude("data/remote/NasdaqTraderSymbolDirectoryClient.kt")
        include("data/repository/DcfSourceCoordinator.kt")
        include("domain/logging/AppLogger.kt")
    }
    into(layout.buildDirectory.dir("app-src/com/discountscreener/android"))
}
val sharedYaml = tasks.register<Sync>("syncSharedYaml") {
    from(rootDir.resolve("../../shared/contracts")) {
        include("valuation-policy.yaml", "earnings-gate-policy.yaml")
    }
    into(rootDir.resolve("build/shared-yaml"))
}
sourceSets.main { kotlin.srcDir(layout.buildDirectory.dir("app-src")) }


tasks.named("compileKotlin") { dependsOn(appFiles) }
tasks.named("processResources") { dependsOn(sharedYaml) }

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    implementation("org.yaml:snakeyaml:2.3")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:okhttp-urlconnection:4.12.0")
}

application { mainClass.set("parity.AndroidJvmLiveKt") }
tasks.named<JavaExec>("run") {
    workingDir = rootDir.resolve("../..")
    maxHeapSize = "4g"
}
