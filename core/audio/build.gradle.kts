plugins {
    alias(libs.plugins.kotlin.jvm)
    `java-library`
}

// The audio pipeline's decision logic: when tier 3 (transcription) may run, the daily
// budget, the off-limits rules, and the speaker gate contract. No audio processing
// here; the models and the mic live in the Android module. Everything that decides
// whether buddy is allowed to listen is in this module so it can be tested.
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
