plugins {
    alias(libs.plugins.kotlin.jvm)
    `java-library`
}

// Perception normalisers are pure functions from platform-neutral snapshots (what a
// notification, an SMS row, a calendar instance, a content-capture tree looks like)
// to ledger Events. The Android services in :core:android only map platform objects
// into these snapshots, so everything with logic in it is tested here on the JVM.
// Target 17 bytecode so the modules build under any JDK 17 or newer, including the
// AOSP build host's JDK, without a toolchain download.
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
