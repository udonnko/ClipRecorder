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

// F-Droid の prebuild で gradle.properties に書き込まれる (例: targetAbi=arm64-v8a)
val targetAbi = project.findProperty("targetAbi") as String?

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
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("release")
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

    splits {
        abi {
            isEnable = true
            reset()
            // targetAbi が指定されている場合は1 ABI のみビルド（F-Droid 用）
            if (targetAbi != null) {
                include(targetAbi)
            } else {
                include("armeabi-v7a", "arm64-v8a", "x86", "x86_64")
            }
            isUniversalApk = false
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

// ABI ごとのバージョンコード: 100 * baseCode + suffix (armeabi-v7a=1, arm64-v8a=2, x86=3, x86_64=4)
androidComponents {
    onVariants { variant ->
        val baseVersionCode = android.defaultConfig.versionCode ?: 1
        variant.outputs.forEach { output ->
            val abi = output.filters.firstOrNull {
                it.filterType == com.android.build.api.variant.FilterConfiguration.FilterType.ABI
            }?.identifier
            if (abi != null) {
                val suffix = mapOf("armeabi-v7a" to 1, "arm64-v8a" to 2, "x86" to 3, "x86_64" to 4)[abi] ?: 0
                output.versionCode.set(baseVersionCode * 100 + suffix)
            }
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
