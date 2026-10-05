import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "aurius.kura"
    compileSdk = 36

    defaultConfig {
        applicationId = "aurius.kura"
        minSdk = 26
        targetSdk = 36
        // Both flavors share one versionCode on purpose; see the online flavor.
        versionCode = 17
        versionName = "1.0.3"
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

    buildFeatures {
        // NETWORK_UPDATES is read at runtime so the offline flavor never even
        // attempts a request. The absent INTERNET permission already blocks it;
        // the flag keeps the code path from being reached at all.
        buildConfig = true
    }

    flavorDimensions += "network"

    productFlavors {
        create("offline") {
            dimension = "network"
            // No INTERNET permission: this build cannot open a socket.
            buildConfigField("boolean", "NETWORK_UPDATES", "false")
        }
        create("online") {
            dimension = "network"
            // Same applicationId, same versionCode, same signing key as offline.
            // Android rejects a lower versionCode as a downgrade, so giving this
            // flavor a higher one would make returning to offline impossible --
            // and switching by uninstalling would destroy the keystore-backed
            // vault. Equal codes install in either direction.
            buildConfigField("boolean", "NETWORK_UPDATES", "true")
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
    testOptions {
        unitTests {
            // Framework methods like Log.w are stubs that throw on the JVM. The
            // failure paths in the update checker log on the way out, so they
            // need logging to be inert rather than fatal. No test asserts on
            // framework behaviour.
            isReturnDefaultValues = true
        }
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
    // Test-only. org.json ships as an Android stub whose methods all throw, so
    // parsing update.json cannot be exercised on the JVM without a real
    // implementation. Never packaged: the app uses the platform's own.
    testImplementation("org.json:json:20231013")
}
