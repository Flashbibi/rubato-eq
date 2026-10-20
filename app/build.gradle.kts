import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.room)
}

// Versioning convention: versionCode = GitHub run number, versionName = 0.1.<run>, tag v0.1.<run>.
val runNumber: Int? = providers.environmentVariable("GITHUB_RUN_NUMBER").orNull?.toIntOrNull()

// Release signing: environment variables (CI) take precedence over the git-ignored
// keystore.properties (local builds).
val keystoreProperties = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.isFile) file.inputStream().use { load(it) }
}

fun signingValue(envName: String, propertyName: String): String? =
    providers.environmentVariable(envName).orNull?.takeIf { it.isNotBlank() }
        ?: keystoreProperties.getProperty(propertyName)?.takeIf { it.isNotBlank() }

val releaseStoreFile: File? = signingValue("KEYSTORE_FILE", "storeFile")?.let { rootProject.file(it) }
val releaseStorePassword: String? = signingValue("KEYSTORE_PASSWORD", "storePassword")
val releaseKeyAlias: String? = signingValue("KEY_ALIAS", "keyAlias")
val releaseKeyPassword: String? = signingValue("KEY_PASSWORD", "keyPassword")

val releaseSigningProblems: List<String> = buildList {
    if (releaseStoreFile == null) {
        add("KEYSTORE_FILE (or storeFile in keystore.properties) is not set")
    } else if (!releaseStoreFile.isFile) {
        add("keystore file not found: $releaseStoreFile")
    }
    if (releaseStorePassword == null) add("KEYSTORE_PASSWORD (or storePassword) is not set")
    if (releaseKeyAlias == null) add("KEY_ALIAS (or keyAlias) is not set")
    if (releaseKeyPassword == null) add("KEY_PASSWORD (or keyPassword) is not set")
}

android {
    namespace = "ch.sommermeyer.rubato"
    compileSdk {
        version = release(37) {
            minorApiLevel = 2
        }
    }

    defaultConfig {
        applicationId = "ch.sommermeyer.rubato"
        minSdk = 31
        targetSdk = 37
        versionCode = runNumber ?: 1
        versionName = if (runNumber != null) "0.1.$runNumber" else "0.1.0-local"
    }

    signingConfigs {
        if (releaseSigningProblems.isEmpty()) {
            create("release") {
                storeFile = releaseStoreFile
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            buildConfigField("boolean", "UPDATER_ENABLED", "false")
        }
        release {
            // Never fall back to the debug key: an APK signed with another key cannot update the
            // installed app. Without a release key the build fails (see verifyReleaseSigning).
            signingConfig = signingConfigs.findByName("release")
            isMinifyEnabled = false
            buildConfigField("boolean", "UPDATER_ENABLED", "true")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

room {
    schemaDirectory("$projectDir/schemas")
}

val verifyReleaseSigning = tasks.register("verifyReleaseSigning") {
    group = "verification"
    description = "Fails the release build if no release signing key is configured."
    val problems = releaseSigningProblems
    doLast {
        if (problems.isNotEmpty()) {
            throw GradleException(
                "Release signing is not configured:\n" +
                    problems.joinToString("\n") { "  - $it" } +
                    "\nSet KEYSTORE_FILE, KEYSTORE_PASSWORD, KEY_ALIAS and KEY_PASSWORD or create " +
                    "keystore.properties (see README). Release builds are never signed with the debug key.",
            )
        }
    }
}

tasks.named { it == "packageRelease" || it == "signReleaseBundle" }.configureEach {
    dependsOn(verifyReleaseSigning)
}

dependencies {
    implementation(project(":core"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.core)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
}
