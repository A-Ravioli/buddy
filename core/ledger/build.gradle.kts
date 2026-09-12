plugins {
    alias(libs.plugins.kotlin.jvm)
    `java-library`
}

// The ledger has no Android dependencies on purpose: the schema, the event model and
// the append-only rules are verified on the JVM against real SQLite (via JDBC). On the
// phone the same SqliteLedger runs over the framework's SQLite through a tiny driver.
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
    testImplementation(libs.kotlin.test)
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.sqlite.jdbc)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
}
