plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

// The replay harness: run a triage implementation over a ledger database and score it
// against labelled decisions. This is how every triage change is judged before it
// ships to the phone (docs/05-roadmap.md, "Eval").
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
    mainClass.set("buddy.eval.ReplayKt")
}

dependencies {
    implementation(project(":core:ledger"))
    implementation(project(":core:triage"))
    implementation(project(":core:entities"))
    implementation(project(":core:cognition"))
    implementation(libs.sqlite.jdbc)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
}
