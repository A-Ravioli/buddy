// Draws buddy's boot animation from the same geometry the app draws his face with, and
// packs it as the bootanimation.zip surfaceflinger plays. Pure JVM: no Compose, no
// framework, so it runs anywhere a JDK does.
//
//   ./gradlew :platform:bootanimation:bootAnimation
//
// The zip lands in build/bootanimation.zip; platform/product copies it into the image.
plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    sourceSets["main"].kotlin.srcDirs("src/main/kotlin", "../../core/android/src/main/kotlin")
    // Only the face's geometry is shared. Everything else under core/android needs Compose
    // or the framework, and this runs before either exists.
    sourceSets["main"].kotlin.include(
        "buddy/boot/**",
        "buddy/android/surface/creature/FaceGeometry.kt",
        "buddy/android/surface/creature/CreatureState.kt",
    )
}

tasks.register<JavaExec>("bootAnimation") {
    group = "build"
    description = "Writes build/bootanimation.zip"
    mainClass.set("buddy.boot.BootAnimationKt")
    classpath = sourceSets["main"].runtimeClasspath
    args = listOf(layout.buildDirectory.file("bootanimation.zip").get().asFile.absolutePath)
}
