plugins {
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
