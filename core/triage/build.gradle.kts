plugins {
    alias(libs.plugins.kotlin.jvm)
    `java-library`
}

// Triage: the on-device first look at every event. Phase 1 ships a rule-based
// baseline that runs anywhere; the on-device model implements the same interface
// later and is scored against the same labelled days by the replay harness.
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    api(project(":core:ledger"))
    testImplementation(libs.kotlin.test)
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
}
