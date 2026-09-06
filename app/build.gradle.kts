plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

import java.util.Properties

val keystoreProps = Properties().also { props ->
    val f = rootProject.file("app/keystore.properties")
    if (f.exists()) props.load(f.inputStream())
}

android {
    namespace = "com.example.cliprecorder"
    compileSdk = 35

    signingConfigs {
        create("release") {
            storeFile = file(keystoreProps["storeFile"] ?: "cliprecorder.keystore")
            storePassword = keystoreProps["storePassword"] as String? ?: ""
            keyAlias = keystoreProps["keyAlias"] as String? ?: "cliprecorder"
            keyPassword = keystoreProps["keyPassword"] as String? ?: ""
        }
    }

    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    defaultConfig {
        applicationId = "com.example.cliprecorder"
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // keystore.properties が存在する場合のみ署名（F-Droid ビルド環境では省略）
            if (keystoreProps.isNotEmpty()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        jniLibs {
            useLegacyPackaging = false
        }
    }

    flavorDimensions += "tier"
    productFlavors {
        create("free") {
            dimension = "tier"
            applicationIdSuffix = ".free"
            versionNameSuffix = "-free"
            buildConfigField("boolean", "IS_FREE_TIER", "true")
        }
        create("paid") {
            dimension = "tier"
            buildConfigField("boolean", "IS_FREE_TIER", "false")
        }
        // F-Droid 配布用：全機能解放・固有 applicationId
        create("fdroid") {
            dimension = "tier"
            applicationId = "io.github.udonnko.cliprecorder"
            versionNameSuffix = "-fdroid"
            buildConfigField("boolean", "IS_FREE_TIER", "false")
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.video)
    implementation(libs.androidx.camera.view)
    implementation(libs.androidx.camera.extensions)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.kotlinx.coroutines.android)
    implementation("sh.calvin.reorderable:reorderable:2.4.0")
    debugImplementation(libs.androidx.ui.tooling)
}
