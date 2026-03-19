plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.aitorpazos.pipertts"
    compileSdk = 34
    ndkVersion = "27.0.12077973"

    defaultConfig {
        applicationId = "com.aitorpazos.pipertts"
        minSdk = 24
        targetSdk = 34
        versionCode = 24
        versionName = "1.19.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }

        externalNativeBuild {
            cmake {
                cppFlags += "-std=c++17"
                arguments += listOf(
                    "-DBUILD_SHARED_LIBS=OFF",
                    "-DUSE_ASYNC=OFF",
                    "-DUSE_MBROLA=OFF",
                    "-DUSE_LIBPCAUDIO=OFF",
                    "-DUSE_LIBSONIC=OFF",
                    "-DUSE_KLATT=ON",
                    "-DUSE_SPEECHPLAYER=ON",
                    "-DCOMPILE_INTONATIONS=OFF",
                    "-DENABLE_TESTS=OFF",
                    "-DESPEAK_COMPAT=OFF"
                )
            }
        }
    }

    signingConfigs {
        // Stable debug keystore committed to repo — ensures consistent signing across
        // all CI builds so APK updates never fail due to certificate mismatch.
        getByName("debug") {
            storeFile = rootProject.file("keystores/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
        create("release") {
            val keystoreFile = System.getenv("KEYSTORE_FILE")
            if (keystoreFile != null && file(keystoreFile).exists()) {
                storeFile = file(keystoreFile)
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
            }
            // If no keystore env vars, release signingConfig has no storeFile.
            // buildTypes.release will fall back to debug signing below.
        }
    }

    buildTypes {
        release {
            // IMPORTANT: R8 minification is DISABLED for TTS engine compatibility.
            // Android's TTS framework discovers and binds to the service using
            // PackageManager queries and then calls methods via framework binding.
            // R8 can strip or rename methods/classes that the framework needs even
            // with extensive ProGuard keep rules, because the TTS framework uses
            // internal reflection paths that are not fully documented.
            // The APK size increase (~2-3MB) is negligible for a TTS app that
            // downloads 60MB+ voice models.
            isMinifyEnabled = false
            isShrinkResources = false
            // Use release keystore if configured, otherwise fall back to debug signing
            val releaseConfig = signingConfigs.getByName("release")
            signingConfig = if (releaseConfig.storeFile?.exists() == true) {
                releaseConfig
            } else {
                signingConfigs.getByName("debug")
            }
        }
        debug {
            isMinifyEnabled = false
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
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
        viewBinding = true
    }

    // ABI splits — produce per-architecture APKs for smaller downloads
    splits {
        abi {
            isEnable = project.findProperty("abi.splits.enable")?.toString()?.toBoolean() ?: false
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86_64")
            isUniversalApk = true
        }
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
        jniLibs {
            useLegacyPackaging = true
        }
    }
}

// Assign distinct version codes per ABI split so Play Store accepts all APKs
// Universal APK gets the highest ABI multiplier (4) so it can always be updated to/from
val abiVersionCodes = mapOf("armeabi-v7a" to 1, "arm64-v8a" to 2, "x86_64" to 3)
android.applicationVariants.all {
    outputs.forEach { output ->
        if (output is com.android.build.gradle.api.ApkVariantOutput) {
            val abiName = output.filters.find {
                it.filterType == com.android.build.VariantOutput.FilterType.ABI.name
            }?.identifier
            // For ABI-specific APKs, use abiCode * 1000 + versionCode
            // For universal APK (abiName == null), use 4 * 1000 + versionCode
            val abiCode = if (abiName != null) abiVersionCodes[abiName] ?: 0 else 4
            output.versionCodeOverride =
                abiCode * 1000 + (android.defaultConfig.versionCode ?: 1)
        }
    }
}

dependencies {
    // ONNX Runtime for Piper inference
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.17.0")

    // AndroidX
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.preference:preference-ktx:1.2.1")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    // JSON parsing: uses Android's built-in org.json (no external dependency needed)
    // org.json stub for unit tests (JVM doesn't include Android's org.json)
    testImplementation("org.json:json:20231013")

    // Testing
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.mockito:mockito-core:5.8.0")
    testImplementation("org.mockito.kotlin:mockito-kotlin:5.2.1")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
    androidTestImplementation("androidx.test:runner:1.5.2")
    androidTestImplementation("androidx.test:rules:1.5.0")
    androidTestImplementation("androidx.test:core:1.5.0")

    // ONNX Runtime JVM for roundtrip unit tests (runs on CI without Android emulator)
    testImplementation("com.microsoft.onnxruntime:onnxruntime:1.17.0")

    // Vosk JVM for offline STT in roundtrip unit tests
    testImplementation("com.alphacephei:vosk:0.3.45")
}
