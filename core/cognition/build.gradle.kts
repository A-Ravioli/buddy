plugins {
    alias(libs.plugins.kotlin.jvm)
    `java-library`
}

// Cognition, version 0: the cloud planning cycle that produces the brief. Builds a
// bounded, deterministic context slice from the ledger, wraps every external item in
// an untrusted envelope, and asks the frontier model for a structured Brief. No
// action tools in this phase.
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
    api(project(":core:triage"))
    api(project(":core:entities"))
    implementation(libs.anthropic.java)
    implementation(libs.jackson.annotations)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.sqlite.jdbc)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
}

// The platform build cannot fetch from Maven. This copies the cloud client's runtime
// classpath into platform/prebuilts so the root Android.bp can import it with
// java_import. Run on the build host before a platform build that includes cognition.
val copyCloudDeps by tasks.registering(Copy::class) {
    from(configurations.runtimeClasspath)
    into(rootProject.file("platform/prebuilts/cognition"))
    include("*.jar")
}
