import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "aurius.kura"
    compileSdk = 34

    defaultConfig {
        applicationId = "aurius.kura"
        minSdk = 26
        targetSdk = 34
        versionCode = 15
        versionName = "1.0.0"
    }

    val keystorePropertiesFile = rootProject.file("keystore.properties")
    val keystoreProperties = Properties()
    if (keystorePropertiesFile.exists()) {
        keystorePropertiesFile.inputStream().use { keystoreProperties.load(it) }
    }

    val releaseStorePath = System.getenv("KURA_KEYSTORE_PATH")
        ?: keystoreProperties.getProperty("storeFile")
        ?: "kura-release-v2.keystore"
    val releaseStorePassword = System.getenv("KURA_STORE_PASSWORD")
        ?: keystoreProperties.getProperty("storePassword")
    val releaseKeyAlias = System.getenv("KURA_KEY_ALIAS")
        ?: keystoreProperties.getProperty("keyAlias")
        ?: "kura_rotated"
    val releaseKeyPassword = System.getenv("KURA_KEY_PASSWORD")
        ?: keystoreProperties.getProperty("keyPassword")

    // Only sign a release build with the release key when that key is actually
    // reachable. A checkout without the private keystore -- an F-Droid build, a
    // contributor's clone, a CI job -- still has to compile, so it falls back to
    // the debug key and the warning below makes sure nobody ships that by
    // mistake. A normal local release build is unaffected.
    val hasReleaseSigning = file(releaseStorePath).exists() &&
        !releaseStorePassword.isNullOrBlank() && !releaseKeyPassword.isNullOrBlank()

    if (!hasReleaseSigning) {
        rootProject.logger.warn(
            "Kura: no release keystore available, so the release build is signed " +
                "with the debug key. Such an APK cannot update an existing Kura " +
                "install and must not be published."
        )
    }

    signingConfigs {
        create("release") {
            val targetFile = file(releaseStorePath)
            storeFile = if (targetFile.isAbsolute) targetFile else file(releaseStorePath)
            keyAlias = releaseKeyAlias
            if (!releaseStorePassword.isNullOrBlank() && !releaseKeyPassword.isNullOrBlank()) {
                storePassword = releaseStorePassword
                keyPassword = releaseKeyPassword
            }
            enableV1Signing = true
            enableV2Signing = true
            enableV3Signing = true
        }
        getByName("debug") {
            storeFile = file("${System.getProperty("user.home")}/.android/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
            enableV1Signing = true
            enableV2Signing = true
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = if (hasReleaseSigning) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
        }
        debug {
            isMinifyEnabled = false
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
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.biometric:biometric:1.1.0")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    implementation("androidx.documentfile:documentfile:1.0.1")
    testImplementation("junit:junit:4.13.2")
}
