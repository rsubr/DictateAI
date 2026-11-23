import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.voiceclean.app"
    compileSdk = 35
    ndkVersion = "27.2.12479018"

    defaultConfig {
        applicationId = "com.voiceclean.app"
        minSdk = 31
        targetSdk = 35
        versionCode = 3
        versionName = "0.3.0"

        ndk {
            abiFilters += "arm64-v8a"
            debugSymbolLevel = "NONE" // no native debug symbols in release outputs
        }
        externalNativeBuild {
            cmake {
                arguments += listOf("-DANDROID_STL=c++_shared")
                cppFlags += "-std=c++17"
                arguments += listOf("-DCMAKE_BUILD_TYPE=Release")
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    // Release signing: keystore.properties (local, gitignored) or environment variables (CI).
    // Keys: storeFile, storePassword, keyAlias, keyPassword  /  env KEYSTORE_FILE, KEYSTORE_PASSWORD, KEY_ALIAS, KEY_PASSWORD.
    val keystoreProps = Properties().apply {
        rootProject.file("keystore.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
    }
    fun signing(prop: String, env: String): String? = keystoreProps.getProperty(prop) ?: System.getenv(env)
    val releaseStore = signing("storeFile", "KEYSTORE_FILE")
    signingConfigs {
        if (releaseStore != null) create("release") {
            storeFile = file(releaseStore)
            storePassword = signing("storePassword", "KEYSTORE_PASSWORD")
            keyAlias = signing("keyAlias", "KEY_ALIAS")
            keyPassword = signing("keyPassword", "KEY_PASSWORD")
        }
    }

    buildTypes {
        release {
            isDebuggable = false
            isMinifyEnabled = false // JNI/ExecuTorch reflection; not verified with R8 on a device
            packaging.jniLibs.keepDebugSymbols.clear() // strip native libs
            signingConfig = signingConfigs.findByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
        // ggml loads CPU-variant backend .so files from nativeLibraryDir by path -> must be extracted on install
        jniLibs.useLegacyPackaging = true
    }
}

ksp { arg("room.schemaLocation", "$projectDir/schemas") }

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    debugImplementation(libs.compose.ui.tooling)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.coroutines.android)
    // ExecuTorch LLM runtime with the Qualcomm QNN delegate (BSD) + Qualcomm QNN runtime libs (Qualcomm AI Hub Model License,
    // redistributable from Maven Central; see docs/QNN.md). Version override: -PqnnRuntimeVersion=2.37.0 to pin the version ExecuTorch was built with
    implementation("org.pytorch:executorch-android-qnn:1.5.1")
    implementation("com.qualcomm.qti:qnn-runtime:${providers.gradleProperty("qnnRuntimeVersion").getOrElse("2.39.3")}")
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation("com.squareup.okhttp3:mockwebserver:5.5.1")
}
