plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val buildNumber = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1

android {
    namespace = "dev.foldkeys"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.foldkeys"
        minSdk = 30
        targetSdk = 35
        versionCode = buildNumber
        versionName = "0.1.$buildNumber"
    }

    signingConfigs {
        // A fixed debug key checked into the repo, so every CI build can
        // install over the previous one instead of needing an uninstall.
        getByName("debug") {
            storeFile = rootProject.file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.window:window:1.3.0")
    implementation("androidx.window:window-java:1.3.0")
}
