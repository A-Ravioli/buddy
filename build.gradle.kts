plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
}

allprojects {
    group = "buddy"
    version = "0.0.1"
}
