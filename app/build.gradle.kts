import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// CI passes GITHUB_RUN_NUMBER so every build installs over the previous one.
val buildNumber = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toIntOrNull() ?: 1

android {
    namespace = "com.radiant.pcbrowser"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.radiant.pcbrowser"
        minSdk = 29
        targetSdk = 35
        versionCode = buildNumber
        versionName = "1.0.$buildNumber"
    }

    signingConfigs {
        // A fixed key keeps the signature identical across CI runs, so updates install in place.
        // Override with your own key through the RADIANT_* environment variables (see README).
        create("shared") {
            fun env(name: String): String? = System.getenv(name)?.takeIf { it.isNotBlank() }
            storeFile = file(env("RADIANT_KEYSTORE_FILE") ?: "${rootDir}/keystore/radiant-shared.jks")
            storePassword = env("RADIANT_KEYSTORE_PASSWORD") ?: "radiant-shared"
            keyAlias = env("RADIANT_KEY_ALIAS") ?: "radiant"
            keyPassword = env("RADIANT_KEY_PASSWORD") ?: "radiant-shared"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("shared")
        }
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            signingConfig = signingConfigs.getByName("shared")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        buildConfig = true
    }

    lint {
        // Sideloaded game browser: keep CI green on advisory checks, still fail on real errors.
        abortOnError = true
        checkReleaseBuilds = true
        // Not published on Google Play, so Play's target-SDK deadline checks do not apply.
        disable += setOf(
            "DiscouragedApi", "UseSwitchCompatOrMaterialCode", "SetJavaScriptEnabled",
            "ExpiredTargetSdkVersion", "OldTargetApi"
        )
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.activity:activity-ktx:1.10.1")
    implementation("androidx.webkit:webkit:1.18.0-rc01")
    implementation("androidx.viewpager2:viewpager2:1.1.0")
    // viewpager2 drags in an old androidx.fragment; the activity-result API needs 1.3+.
    implementation("androidx.fragment:fragment:1.8.5")
}
