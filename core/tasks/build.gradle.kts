// Tasks: the unit of work that survives a wake, a restart, and a week (docs/07).
// A task is not a row in a side table; it is a fold over TASK events in the ledger,
// which is what keeps replay, undo, and the timeline true for jobs as well as actions.
plugins {
    alias(libs.plugins.kotlin.jvm)
    `java-library`
}

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
    api(project(":core:policy"))
    testImplementation(libs.kotlin.test)
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.sqlite.jdbc)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
}
