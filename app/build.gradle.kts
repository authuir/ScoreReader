plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.scorereader"
    compileSdk = 34

    // Pin to the NDK we already have installed locally; AGP's default of
    // 25.1.8937393 would trigger a ~1GB download.
    ndkVersion = "27.1.12297006"

    defaultConfig {
        applicationId = "com.example.scorereader"
        // Android 6.0 set-top box target
        minSdk = 23
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"

        // Most Android 6.x set-top boxes are 32-bit ARM, so ship both ABIs.
        // arm64-v8a covers newer boxes; armeabi-v7a covers the legacy ones
        // (and is what was needed to make the APK install on the target STB).
        ndk {
            abiFilters += setOf("arm64-v8a", "armeabi-v7a")
        }

        externalNativeBuild {
            cmake {
                arguments += listOf(
                    "-DANDROID_STL=c++_shared",
                    "-DCMAKE_BUILD_TYPE=Release"
                )
                cppFlags += "-std=c++20"
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    // Keep the bundled Verovio resources uncompressed so the runtime
    // extractor can stream them straight out of the APK without a double
    // inflate pass.
    androidResources {
        noCompress.add("zip")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Sign release builds with the debug key so the APK produced by
            // CI is directly installable on Android TV / STB devices for
            // side-loading. Replace with a real signingConfig when shipping
            // to a store.
            signingConfig = signingConfigs.getByName("debug")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    kotlinOptions {
        jvmTarget = "1.8"
    }

    buildFeatures {
        viewBinding = true
    }

    packaging {
        resources {
            excludes += setOf("META-INF/AL2.0", "META-INF/LGPL2.1")
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.10.1")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.9.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.webkit:webkit:1.8.0")
    implementation("androidx.activity:activity-ktx:1.7.2")
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    // SVG -> Android Canvas/Bitmap renderer used by the Verovio (JNI) viewer.
    implementation("com.caverock:androidsvg-aar:1.4")
}
