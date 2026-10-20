plugins {
    alias(libs.plugins.android.application) apply false
    // Declaring the Kotlin plugins here also pins the Kotlin Gradle plugin version used by
    // AGP's built-in Kotlin support.
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.room) apply false
}
