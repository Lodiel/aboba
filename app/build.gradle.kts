plugins {
    id("com.android.application")
}

val decodedKeystore: File = layout.buildDirectory.file("keystore/starlink-reconnect.jks").get().asFile.also { out ->
    val encoded = file("keystore/starlink-reconnect.jks.base64").readText()
    val bytes = java.util.Base64.getMimeDecoder().decode(encoded)
    if (!out.exists() || !out.readBytes().contentEquals(bytes)) {
        out.parentFile.mkdirs()
        out.writeBytes(bytes)
    }
}

android {
    namespace = "com.aboba.starlinkreconnect"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.aboba.starlinkreconnect"
        minSdk = 26
        // targetSdk is intentionally 28: on Android 10+ the legacy WifiManager
        // connect APIs (addNetwork / enableNetwork / reconnect / disconnect)
        // keep working only for apps targeting API < 29. This is a sideloaded
        // utility, not a Play Store app, so the lower target is acceptable.
        targetSdk = 28
        versionCode = 1
        versionName = "1.0"
    }

    signingConfigs {
        // Shared keystore committed to the repo (as base64 text, decoded below) so
        // every CI build is signed with the same key and the APK can be updated in
        // place on the phone.
        create("shared") {
            storeFile = decodedKeystore
            storePassword = "starlink"
            keyAlias = "starlink"
            keyPassword = "starlink"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("shared")
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("shared")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        // targetSdk 28 is deliberate (see defaultConfig), don't fail the build on it.
        disable += listOf("ExpiredTargetSdkVersion", "OldTargetApi")
        abortOnError = false
    }
}

dependencies {
    // No dependencies on purpose: the app uses only platform APIs.
}
