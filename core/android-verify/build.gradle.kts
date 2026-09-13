// Compile check for core/android, the Soong-built system app, so CI catches type and
// API errors in it without the platform tree. Not a runnable artifact.
//
// Framework classes come from Robolectric's android-all jar (the full Android 16
// framework, on Maven Central). Compose comes from JetBrains' desktop artifacts, which
// share the androidx.compose API. The handful of Android-only glue functions the app
// calls (setContent, BackHandler, LocalContext, resource fonts) are stubbed in stubs/
// with the real signatures. R is generated from the resource names.
import java.util.SortedSet

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.compose)
}

val appDir = layout.projectDirectory.dir("../android/src/main")
val generatedR = layout.buildDirectory.dir("generated/r")

val generateR by tasks.registering {
    description = "Writes app.buddy.R from the resource names, the way aapt2 would."
    val res = appDir.dir("res")
    inputs.dir(res)
    outputs.dir(generatedR)
    doLast {
        val names = sortedMapOf<String, SortedSet<String>>()
        fun add(type: String, name: String) = names.getOrPut(type) { sortedSetOf() }.add(name.replace('.', '_'))
        val valueTag = Regex("""<(string|plurals|color|dimen|bool|integer|style|array|string-array|integer-array)\s+name="([^"]+)"""")
        val idRef = Regex("""@\+id/([A-Za-z0-9_]+)""")
        res.asFile.walkTopDown().filter { it.isFile }.forEach { f ->
            val dir = f.parentFile.name.substringBefore('-')
            if (dir == "values") {
                valueTag.findAll(f.readText()).forEach { m ->
                    val t = m.groupValues[1].let { if (it.endsWith("-array")) "array" else it }
                    add(t, m.groupValues[2])
                }
            } else if (f.extension != "txt") {
                add(dir, f.nameWithoutExtension)
            }
            if (f.extension == "xml") idRef.findAll(f.readText()).forEach { add("id", it.groupValues[1]) }
        }
        var next = 0x7f010000
        val out = StringBuilder("package app.buddy;\n\n/** Generated for the compile check; aapt2 makes the real one. */\npublic final class R {\n")
        for ((type, set) in names) {
            out.append("    public static final class $type {\n")
            for (n in set) out.append("        public static final int $n = 0x${Integer.toHexString(++next)};\n")
            out.append("    }\n")
        }
        out.append("}\n")
        val file = generatedR.get().file("app/buddy/R.java").asFile
        file.parentFile.mkdirs()
        file.writeText(out.toString())
    }
}

kotlin {
    sourceSets["main"].kotlin.srcDirs(appDir.dir("kotlin"), "stubs")
}
sourceSets["main"].java.srcDirs("stubs", generatedR)
tasks.named("compileJava") { dependsOn(generateR) }
tasks.named("compileKotlin") { dependsOn(generateR) }

dependencies {
    for (m in listOf("ledger", "perception", "triage", "entities", "audio", "policy", "actuation", "style", "cognition", "automation", "money", "logistics", "voice", "profile")) {
        implementation(project(":core:$m"))
    }
    implementation(libs.anthropic.java)
    implementation(libs.jackson.annotations)
    // Everything the app compiles against but this module never runs is compileOnly: it
    // keeps Compose's transitive androidx artifacts off the test runtime classpath, where
    // they would be resolved from Google's Maven for no benefit. The unit tests only load
    // the app classes that are free of Android and Compose.
    compileOnly(libs.robolectric.android.all)
    compileOnly(libs.compose.runtime.desktop)
    compileOnly(libs.compose.foundation.desktop)
    compileOnly(libs.compose.ui.desktop)
    compileOnly(libs.compose.animation.desktop)
    implementation(libs.kotlinx.coroutines.core)
    // The Compose compiler plugin runs over the test sources too and wants the runtime on
    // the compile classpath, even though no test composes anything.
    testCompileOnly(libs.compose.runtime.desktop)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

// The app's own unit tests: anything in core/android that is free of Android and Compose,
// which is where the rules worth pinning live (see FaceGeometryTest).
tasks.test {
    useJUnitPlatform()
}
