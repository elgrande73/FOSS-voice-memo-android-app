import java.util.Properties

val keystoreProperties = Properties()
val keystorePropertiesFile = rootProject.file("keystore.properties")

if (keystorePropertiesFile.exists()) {
    keystoreProperties.load(keystorePropertiesFile.inputStream())
}

plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.kotlin.android)
  alias(libs.plugins.kotlin.compose)
  alias(libs.plugins.google.devtools.ksp)
  alias(libs.plugins.roborazzi)
}

android {
  namespace = "io.github.elgrande73.voicememo"
  compileSdk = 35

  defaultConfig {
    applicationId = "io.github.elgrande73.voicememo"
    minSdk = 24
    targetSdk = 35
    versionCode = 1
    versionName = "1.0"

    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

    // On-device speech-to-text (whisper.cpp, built from source: app/src/main/cpp, third_party/whisper.cpp)
    ndk {
      abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
    }
    externalNativeBuild {
      cmake {
        // Several native libraries (whisper, ggml, CPU variants) share one C++ runtime; c++_static would give each
        // its own copy, which the NDK does not support for more than one shared library
        arguments += listOf("-DANDROID_STL=c++_shared")
      }
    }
  }

  ndkVersion = "28.2.13676358"

  externalNativeBuild {
    cmake {
      path = file("src/main/cpp/CMakeLists.txt")
      version = "3.31.6"
    }
  }

  packaging {
    jniLibs {
      // ggml chooses its CPU backend (by instruction set) by scanning the extracted native library directory
      useLegacyPackaging = true
      // Built as part of whisper.cpp but not used by Voice Memo
      excludes += "**/libparakeet.so"
    }
  }

  androidResources {
    // The bundled speech model is read from a file; keep it uncompressed so it can be copied quickly
    noCompress += listOf("bin")
  }
  
  dependenciesInfo {
        includeInApk = false
        includeInBundle = false
  }
  
  signingConfigs {
    if (keystorePropertiesFile.exists()) {
        create("release") {
            storeFile = file(keystoreProperties["storeFile"] as String)
            storePassword = keystoreProperties["storePassword"] as String
            keyAlias = keystoreProperties["keyAlias"] as String
            keyPassword = keystoreProperties["keyPassword"] as String
        }
    }
  }


  buildTypes {
    release {
        if (keystorePropertiesFile.exists()) {
            signingConfig = signingConfigs.getByName("release")
        }

        isCrunchPngs = false
        isMinifyEnabled = false
        proguardFiles(
            getDefaultProguardFile("proguard-android-optimize.txt"),
            "proguard-rules.pro"
        )
    }

    debug {
    }
  }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }
  
  kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
  }

  buildFeatures {
    compose = true
    buildConfig = true
  }
  testOptions { unitTests { isIncludeAndroidResources = true } }
}

ksp {
  arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
  implementation(platform(libs.androidx.compose.bom))
  implementation(libs.androidx.activity.compose)
  implementation(libs.androidx.compose.material.icons.core)
  implementation(libs.androidx.compose.material3)
  implementation(libs.androidx.compose.ui)
  implementation(libs.androidx.compose.ui.graphics)
  implementation(libs.androidx.compose.ui.tooling.preview)
  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(libs.androidx.lifecycle.viewmodel.compose)
  implementation(libs.androidx.room.ktx)
  implementation(libs.androidx.room.runtime)
  implementation(libs.kotlinx.coroutines.android)
  implementation(libs.kotlinx.coroutines.core)
  testImplementation(libs.androidx.compose.ui.test.junit4)
  testImplementation(libs.androidx.compose.ui.test.manifest)
  testImplementation(libs.androidx.core)
  testImplementation(libs.androidx.junit)
  testImplementation(libs.junit)
  testImplementation(libs.kotlinx.coroutines.test)
  testImplementation(libs.robolectric)
  testImplementation(libs.roborazzi)
  testImplementation(libs.roborazzi.compose)
  testImplementation(libs.roborazzi.junit.rule)

  androidTestImplementation(platform(libs.androidx.compose.bom))
  androidTestImplementation(libs.androidx.compose.ui.test.junit4)
  androidTestImplementation(libs.androidx.espresso.core)
  androidTestImplementation(libs.androidx.junit)
  androidTestImplementation(libs.androidx.runner)

  debugImplementation(libs.androidx.compose.ui.test.manifest)
  debugImplementation(libs.androidx.compose.ui.tooling)
  "ksp"(libs.androidx.room.compiler)
}

