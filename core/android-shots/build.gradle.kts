// Renders the app's screens on the host as PNGs, from the same sources as the app, so
// a screen can be looked at without a phone. Opt-in (see settings.gradle.kts).
// Compose Multiplatform 1.5.12 is the last release whose desktop artifacts run without
// anything from Google's Maven; the compile check (core/android-verify) uses 1.7.
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.compose)
    application
}
val appDir = layout.projectDirectory.dir("../android/src/main")
kotlin {
    sourceSets["main"].kotlin.srcDirs(appDir.dir("kotlin"), "../android-verify/stubs", "src/main/kotlin")
}
sourceSets["main"].java.srcDirs("../android-verify/stubs", "../android-verify/build/generated/r")
dependencies {
    for (m in listOf("ledger", "perception", "triage", "entities", "audio", "policy", "actuation", "style", "cognition", "automation", "money", "logistics", "voice", "profile")) {
        implementation(project(":core:$m"))
    }
    implementation(libs.anthropic.java)
    implementation(libs.jackson.annotations)
    implementation(libs.robolectric.android.all)
    val cmp = "1.5.12"
    implementation("org.jetbrains.compose.runtime:runtime-desktop:$cmp")
    implementation("org.jetbrains.compose.foundation:foundation-desktop:$cmp")
    implementation("org.jetbrains.compose.ui:ui-desktop:$cmp")
    implementation("org.jetbrains.compose.animation:animation-desktop:$cmp")
    implementation("org.jetbrains.compose.desktop:desktop-jvm-linux-x64:$cmp")
    implementation(libs.kotlinx.coroutines.core)
}
application {
    mainClass.set("ShotsKt")
    applicationDefaultJvmArgs = listOf("-Djava.awt.headless=true", "-Dbuddy.fontDir=" + rootProject.file("core/android/src/main/res/font").absolutePath)
}
