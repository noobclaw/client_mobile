plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.noobclaw.mobile"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.noobclaw.mobile"
        minSdk = 29          // Android 10; screenshots/OCR fallback need 30+ (checked at runtime)
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
        // Backend base URL; override with -PapiBase=https://... for staging builds.
        buildConfigField("String", "API_BASE", "\"${project.findProperty("apiBase") ?: "https://api.noobclaw.com"}\"")
        // Capability API version of the ctx bridge. Scripts declare capability_api_min.
        buildConfigField("int", "CAP_VER", "1")
        // Ed25519 public key (hex, 32 bytes) used to verify script packs. The private key is
        // kept offline (see docs/SCRIPT_SIGNING.md). Placeholder until the release key is generated.
        buildConfigField("String", "SCRIPT_PUBKEY_HEX", "\"${project.findProperty("scriptPubKey") ?: ""}\"")
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") } // x86_64: some cloud phones / emulators
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("debug") // replaced by the real release key in CI
        }
    }

    buildFeatures { buildConfig = true }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions { unitTests.isReturnDefaultValues = true }
}

kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    // QuickJS binding with coroutine support and evaluation timeouts (Apache-2.0).
    implementation("io.github.dokar3:quickjs-kt:1.0.15")
    // Ed25519 verification on Android 10-12 (the platform JCA only has it from API 33).
    implementation("org.bouncycastle:bcprov-jdk18on:1.78.1")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
