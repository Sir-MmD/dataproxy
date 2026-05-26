plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.dataproxy"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.dataproxy"
        minSdk = 26
        targetSdk = 36
        versionCode = 5
        versionName = "1.0.0"

        vectorDrawables {
            useSupportLibrary = true
        }
    }

    // Release signing reads from app/keystore/dataproxy-release.jks if present.
    // The keystore is gitignored — generate your own with the keytool command
    // in README.md, or download a pre-signed APK from the GitHub Releases page.
    val keystoreFile = file("keystore/dataproxy-release.jks")
    signingConfigs {
        if (keystoreFile.exists()) {
            create("release") {
                storeFile = keystoreFile
                storePassword = System.getenv("DATAPROXY_KEYSTORE_PASSWORD") ?: "dataproxy"
                keyAlias = System.getenv("DATAPROXY_KEY_ALIAS") ?: "dataproxy"
                keyPassword = System.getenv("DATAPROXY_KEY_PASSWORD") ?: "dataproxy"
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            if (keystoreFile.exists()) {
                signingConfig = signingConfigs.getByName("release")
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isMinifyEnabled = false
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
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

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.kotlinx.coroutines.android)

    debugImplementation(libs.androidx.ui.tooling)
}
