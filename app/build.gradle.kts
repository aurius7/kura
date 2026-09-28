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
        versionCode = 13
        versionName = "1.0.0"
    }

    val keystorePropertiesFile = rootProject.file("keystore.properties")
    val keystoreProperties = Properties()
    if (keystorePropertiesFile.exists()) {
        keystorePropertiesFile.inputStream().use { keystoreProperties.load(it) }
    }

    signingConfigs {
        create("release") {
            val keyStorePath = System.getenv("KURA_KEYSTORE_PATH")
                ?: keystoreProperties.getProperty("storeFile")
                ?: "kura-release-v2.keystore"
            val sPass = System.getenv("KURA_STORE_PASSWORD")
                ?: keystoreProperties.getProperty("storePassword")
            val kAlias = System.getenv("KURA_KEY_ALIAS")
                ?: keystoreProperties.getProperty("keyAlias")
                ?: "kura_rotated"
            val kPass = System.getenv("KURA_KEY_PASSWORD")
                ?: keystoreProperties.getProperty("keyPassword")

            val targetFile = file(keyStorePath)
            storeFile = if (targetFile.isAbsolute) targetFile else file(keyStorePath)
            keyAlias = kAlias
            if (!sPass.isNullOrBlank() && !kPass.isNullOrBlank()) {
                storePassword = sPass
                keyPassword = kPass
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
            signingConfig = signingConfigs.getByName("release")
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
