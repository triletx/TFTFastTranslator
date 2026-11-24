plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.tft.fasttranslator"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.tft.fasttranslator"
        minSdk = 26
        targetSdk = 34
        versionCode = 2
        versionName = "2.0-Hextech"
    }

    signingConfigs {
        getByName("debug") {
            val ks = file("fixed-debug.keystore")
            if (ks.exists()) {
                storeFile = ks
                storePassword = "tftpassword"
                keyAlias = "tftkey"
                keyPassword = "tftpassword"
            }
        }
    }

    buildTypes {
        getByName("debug") {
            signingConfig = signingConfigs.getByName("debug")
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
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("com.google.mlkit:text-recognition-chinese:16.0.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
}
