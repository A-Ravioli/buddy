// Actuation: typed actions, connectors, the executor with transactions, holds, and undo.
// The mail connector (IMAP/SMTP) lives here too; its message building is tested, its
// network path runs only on the phone or the host.
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
    implementation(libs.jakarta.mail.api)
    runtimeOnly(libs.angus.mail)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.sqlite.jdbc)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
}
