plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.datanet.share"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.datanet.share"
        minSdk = 26
        targetSdk = 34
        versionCode = 30
        versionName = "5.0-unified"
    }

    signingConfigs {
        create("release") {
            // Read keystore path and passwords from environment variables or local.properties.
            // This prevents the credentials from being committed to source control.
            // Fallback to the legacy keystore for backwards compatibility.
            storeFile = file(System.getenv("DATANET_STORE_FILE") ?: "/home/z/my-project/keystore/datanet-release.jks")
            storePassword = System.getenv("DATANET_STORE_PASSWORD") ?: "datanetshare2024"
            keyAlias = System.getenv("DATANET_KEY_ALIAS") ?: "datanet"
            keyPassword = System.getenv("DATANET_KEY_PASSWORD") ?: "datanetshare2024"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("release")
        }
        debug {
            isMinifyEnabled = false
        }
    }

    externalNativeBuild {
        // No native build — unified version uses pure-Kotlin receiver code (v3.5 style)
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        viewBinding = true
    }

    packaging {
        resources {
            excludes += setOf("/META-INF/{AL2.0,LGPL2.1}", "/META-INF/DEPENDENCIES")
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-service:2.7.0")
    implementation("androidx.localbroadcastmanager:localbroadcastmanager:1.1.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
}
