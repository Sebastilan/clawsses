import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Tencent Cloud ASR credentials come from local.properties (gitignored),
// never hardcoded. Injected as BuildConfig fields; ConfigStore can override
// them at runtime. See voice-xiaoc/README.md "P2a" and super-brain asr-pipeline.
val localProperties = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) load(f.inputStream())
}

android {
    namespace = "com.voicexiaoc.phone"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.voicexiaoc.phone"
        minSdk = 28
        targetSdk = 34
        // Bump versionCode on every release; VersionChecker compares this
        // against the remote version.json to decide whether to auto-OTA.
        versionCode = 4
        versionName = "0.4.0"

        buildConfigField("String", "TENCENT_SECRET_ID",
            "\"${localProperties.getProperty("tencent.secretId", "")}\"")
        buildConfigField("String", "TENCENT_SECRET_KEY",
            "\"${localProperties.getProperty("tencent.secretKey", "")}\"")
        buildConfigField("String", "TENCENT_APPID",
            "\"${localProperties.getProperty("tencent.appId", "")}\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
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
        buildConfig = true
    }

    // Local unit tests exercise TencentAsrClient against the real Tencent
    // endpoint (see TencentAsrIntegrationTest). android.util.Log stubs must
    // no-op instead of throwing so the client can run on the plain JVM.
    testOptions {
        unitTests.isReturnDefaultValues = true
        unitTests.all {
            it.testLogging {
                events("passed", "skipped", "failed")
                showStandardStreams = true   // print the recognized ASR text
            }
        }
    }
}

dependencies {
    // OkHttp for WebSocket + version.json fetch
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // Android Core
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.6.2")
    implementation("androidx.activity:activity-compose:1.8.2")

    // Jetpack Compose
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    // JSON
    implementation("com.google.code.gson:gson:2.10.1")

    // Local JVM tests (TencentAsrClient integration test)
    testImplementation("junit:junit:4.13.2")
    testImplementation("com.squareup.okhttp3:okhttp:4.12.0")
}
