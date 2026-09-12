plugins {
    alias(libs.plugins.kotlin.jvm)
    `java-library`
}

// The entity graph, version 0: people with identities across apps, threads, and
// recurring patterns, derived from the ledger and stored beside it. Unlike the ledger
// these tables are mutable: they are a view that can always be rebuilt from events.
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
    testImplementation(libs.sqlite.jdbc)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
}
