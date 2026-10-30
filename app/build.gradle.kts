plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.nyxel.music"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.nyxel.music"
        minSdk = 26
        targetSdk = 35
        versionCode = providers.gradleProperty("nyxelVersionCode").orElse("1000").get().toInt()
        versionName = providers.gradleProperty("nyxelVersionName").orElse("2.0.0").get()
    }

    signingConfigs {
        create("release") {
            val keystorePath = System.getenv("NYXEL_KEYSTORE_FILE")
            if (!keystorePath.isNullOrBlank()) {
                storeFile = file(keystorePath)
                storePassword = System.getenv("NYXEL_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("NYXEL_KEY_ALIAS")
                // PKCS12 keystores use the store password to unlock the private key.
                keyPassword = storePassword
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ""
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-ktx:1.10.0")
    implementation("androidx.media3:media3-exoplayer:1.5.1")
    implementation("androidx.media3:media3-session:1.5.1")
}
