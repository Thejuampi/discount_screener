plugins {
    `java-library`
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlin.plugin.serialization")
    jacoco
}

kotlin {
    jvmToolchain(17)
}

val valuationPolicyYaml = rootProject.projectDir.resolve("../../shared/contracts/valuation-policy.yaml")

tasks.processResources {
    from(valuationPolicyYaml)
}

dependencies {
    // Exposed as `api` because `:app` writes the captured request with the same Json instance the
    // replay tool reads it with. Two configurations of one format are two file dialects.
    api("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("org.yaml:snakeyaml:2.3")

    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.3")
    testImplementation("com.squareup.okhttp3:okhttp:4.12.0")
    testImplementation("com.squareup.okhttp3:okhttp-urlconnection:4.12.0")
}

tasks.test {
    useJUnitPlatform()
    // Manual harnesses gate on -D properties; forward them so they reach the test JVM.
    // providers.systemProperty is a tracked configuration-cache input, so a new -D on the
    // command line invalidates the cached configuration instead of being silently ignored.
    listOf("v4.capture", "v4.scoreboard.tag").forEach { name ->
        providers.systemProperty(name).orNull?.let { systemProperty(name, it) }
    }
    finalizedBy(tasks.jacocoTestReport)
}

/**
 * Deliberately not `dependsOn(test)`. A dependency is skipped when the task it depends on fails,
 * and this module carries pre-existing failures unrelated to coverage — so the report would vanish
 * exactly when it is most worth reading. `finalizedBy` above still runs it after every test run.
 */
tasks.jacocoTestReport {
    reports {
        xml.required.set(true)
        html.required.set(true)
    }
}

/**
 * Runs the dashboard projection over a captured request, with no emulator in the loop.
 *
 * `./gradlew :core:replayScreen --args="--request=capture.json"`
 *
 * This is an experimentation tool. It exists so a model change can be judged in seconds instead of
 * an emulator boot, and so two engine versions can be compared over the same bytes.
 */
tasks.register<JavaExec>("replayScreen") {
    group = "verification"
    description = "Projects a captured ScreenDataProjectionRequest and prints the rows."
    mainClass.set("com.discountscreener.core.replay.ScreenReplayKt")
    classpath = sourceSets["main"].runtimeClasspath
}
