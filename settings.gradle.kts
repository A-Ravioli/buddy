pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        google()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        google()
    }
}

rootProject.name = "buddy"

// Pure-JVM modules: the ledger schema and the perception normalisers. These build and
// test anywhere with a JDK, which is how they are verified in CI and in sessions
// without an Android SDK.
include(":core:ledger")
include(":core:perception")
include(":core:triage")
include(":core:entities")
include(":core:cognition")
include(":core:audio")
include(":core:policy")
include(":core:actuation")
include(":core:style")
include(":core:automation")
include(":core:money")
include(":core:logistics")
include(":core:voice")
include(":core:profile")
include(":eval:replay")
include(":eval:injection")
include(":eval:metrics")

// The Android app (core/android) is not a Gradle module. It uses platform and system
// APIs (the content capture service, for one) that the public SDK does not expose, so
// it is built by Soong inside the GrapheneOS tree from the Android.bp at the repo root.
