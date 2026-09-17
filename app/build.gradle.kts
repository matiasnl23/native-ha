plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.matiasnl.hakiosk"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.matiasnl.hakiosk"
        minSdk = 26
        targetSdk = 37
        // Release builds take these from the tag (`-PversionCode` / `-PversionName`, see
        // .github/workflows/release.yml). A local build without the properties keeps the placeholders:
        // versionCode must only ever grow, or installed tablets reject the update. See docs/RELEASE-OTA.md.
        versionCode = providers.gradleProperty("versionCode").map(String::toInt).getOrElse(1)
        versionName = providers.gradleProperty("versionName").getOrElse("1.0")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        // Shared debug key committed on purpose: every machine signs debug builds identically, so
        // `adb install -r` updates an app installed from another computer without wiping its data.
        // Debug only; the release key must never be committed. See docs/SETUP.md.
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }

        // Release key: never in the repo. The CI decodes it from the PRODUCTION environment secrets and
        // points these env vars at it; without them the build stays unsigned instead of failing, so
        // `assembleRelease` still works locally. See docs/SETUP.md.
        create("release") {
            providers.environmentVariable("RELEASE_KEYSTORE_PATH").orNull?.let { path ->
                storeFile = file(path)
                storePassword = providers.environmentVariable("RELEASE_KEYSTORE_PASSWORD").orNull
                keyAlias = providers.environmentVariable("RELEASE_KEY_ALIAS").orNull
                keyPassword = providers.environmentVariable("RELEASE_KEY_PASSWORD").orNull
            }
            // Explicit instead of trusting AGP's defaults: targetSdk 30+ requires v2 or newer, and v1
            // (JAR signing) is dead weight because every device on minSdk 26 verifies v2.
            enableV1Signing = false
            enableV2Signing = true
            enableV3Signing = true
        }
    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
            // Signed only when the key is available (the CI); unsigned otherwise.
            signingConfig = signingConfigs.getByName("release").takeIf { it.storeFile != null }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
    }
    packaging {
        resources {
            // Duplicated jar metadata from the Netty modules pulled in by the MQTT client; unused at runtime.
            excludes += setOf("META-INF/INDEX.LIST", "META-INF/io.netty.versions.properties")
        }
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    // Prebuilt Google libwebrtc (org.webrtc.*) for the camera focus view. No abiFilters on purpose:
    // the target tablets include 32-bit armeabi-v7a devices.
    implementation(libs.webrtc.android)
    // MQTT client for remote control from Home Assistant (MQTT 3.1.1/5, TLS, Last Will). Actively
    // maintained, unlike Eclipse Paho (last release 2020). Only the core artifact: no websocket,
    // proxy or epoll extras.
    implementation(libs.hivemq.mqtt.client)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.turbine)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}