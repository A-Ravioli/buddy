// The mandate corpus: jobs that try to do more than they were authorised to do. The
// test proves the policy engine stops every one of them, at maximum trust, with the
// model assumed to have decided the action was a good idea.
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
    implementation(project(":core:ledger"))
    implementation(project(":core:policy"))
    implementation(project(":core:actuation"))
    implementation(project(":core:tasks"))
    implementation(project(":core:money"))
    testImplementation(libs.kotlin.test)
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.sqlite.jdbc)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
}
