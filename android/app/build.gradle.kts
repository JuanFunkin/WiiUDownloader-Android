plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// CI passes -PversionName=... / -PversionCode=...; local builds fall back to dev values.
val appVersionName = (findProperty("versionName") as String?) ?: "1.0.0-dev"
val appVersionCode = ((findProperty("versionCode") as String?) ?: "1").toInt()

// Optional release keystore, supplied through environment variables (GitHub secrets).
// Without it the release build is signed with the debug key so the APK still installs.
val keystorePath: String? = System.getenv("ANDROID_KEYSTORE_PATH")

android {
    namespace = "io.github.juanfunkin.wiiudownloader"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.github.juanfunkin.wiiudownloader"
        // The gomobile AAR is built with -androidapi 26, so this must not be lower.
        minSdk = 26
        targetSdk = 35
        versionCode = appVersionCode
        versionName = appVersionName

        // One APK for every ARM phone: 64-bit (arm64-v8a) and 32-bit (armeabi-v7a).
        // The matching libraries come from the gomobile AAR.
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }

        // Spanish and English only, so the APK does not carry every library's translations.
        androidResources {
            localeFilters += listOf("en", "es")
        }
    }

    signingConfigs {
        if (keystorePath != null) {
            create("release") {
                storeFile = file(keystorePath)
                storePassword = System.getenv("ANDROID_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("ANDROID_KEY_ALIAS")
                keyPassword = System.getenv("ANDROID_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            // Minifying would need keep rules for the gomobile JNI classes; the app
            // is small enough that it is not worth the risk.
            isMinifyEnabled = false
            signingConfig = if (keystorePath != null) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }

    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
}

dependencies {
    // Built by `gomobile bind` (see .github/workflows/android.yml).
    implementation(files("libs/wiiu.aar"))

    implementation(platform("androidx.compose:compose-bom:2025.04.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")

    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.1")
}
