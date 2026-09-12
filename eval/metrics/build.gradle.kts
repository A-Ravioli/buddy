plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

// The gate report: the vision's success metrics computed from a ledger, and whether
// each phase gate passes. This is what decides whether a second user is added.
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

application {
    mainClass.set("buddy.eval.metrics.GatesKt")
}

dependencies {
    implementation(project(":core:ledger"))
    implementation(project(":core:triage"))
    implementation(project(":core:cognition"))
    implementation(project(":core:policy"))
    implementation(libs.sqlite.jdbc)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
}
