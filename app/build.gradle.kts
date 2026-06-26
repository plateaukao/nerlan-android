plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.compose.compiler)
  alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.example.nerlan"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.danielkao.nerlan"
        minSdk = 24
        targetSdk = 36
        versionCode = 5
        versionName = "1.5"

        // Browser OAuth (AppAuth) for Drive sync on GMS-less devices. Replace the
        // REPLACE_* placeholders with the custom-scheme OAuth client created in the
        // SAME GCP project as the Android client (so the appDataFolder is shared
        // with the GMS sign-in path). The redirect scheme is the reversed client ID,
        // e.g. com.googleusercontent.apps.123456789-abc.
        val driveOauthClientId = "297018645967-rt0483lsudd5k2ssncio8mtqak8537pu.apps.googleusercontent.com"
        val driveOauthRedirectScheme = "com.googleusercontent.apps.297018645967-rt0483lsudd5k2ssncio8mtqak8537pu"
        manifestPlaceholders["appAuthRedirectScheme"] = driveOauthRedirectScheme
        buildConfigField("String", "DRIVE_OAUTH_CLIENT_ID", "\"$driveOauthClientId\"")
        buildConfigField("String", "DRIVE_OAUTH_REDIRECT", "\"$driveOauthRedirectScheme:/oauth2redirect\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
      compose = true
      aidl = false
      buildConfig = true
      shaders = false
    }

    packaging {
      resources {
        excludes += "/META-INF/{AL2.0,LGPL2.1}"
      }
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
  val composeBom = platform(libs.androidx.compose.bom)
  implementation(composeBom)
  androidTestImplementation(composeBom)

  // Core Android dependencies
  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(libs.androidx.activity.compose)

  // Arch Components
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.androidx.lifecycle.viewmodel.compose)
  implementation(libs.androidx.lifecycle.process)

  // Compose
  implementation(libs.androidx.compose.ui)
  implementation(libs.androidx.compose.ui.tooling.preview)
  implementation(libs.androidx.compose.material3)
  implementation(libs.androidx.compose.material.icons.extended)
  // Tooling
  debugImplementation(libs.androidx.compose.ui.tooling)
  // Instrumented tests
  androidTestImplementation(libs.androidx.compose.ui.test.junit4)
  debugImplementation(libs.androidx.compose.ui.test.manifest)

  // Local tests: jUnit, coroutines, Android runner
  testImplementation(libs.junit)
  testImplementation(libs.kotlinx.coroutines.test)

  // Instrumented tests: jUnit rules and runners
  androidTestImplementation(libs.androidx.test.core)
  androidTestImplementation(libs.androidx.test.ext.junit)
  androidTestImplementation(libs.androidx.test.runner)
  androidTestImplementation(libs.androidx.test.espresso.core)

  // Navigation
  implementation(libs.androidx.navigation3.ui)
  implementation(libs.androidx.navigation3.runtime)
  implementation(libs.androidx.lifecycle.viewmodel.navigation3)

  // Media playback
  implementation(libs.androidx.media3.exoplayer)
  implementation(libs.androidx.media3.session)
  // CacheDataSource/SimpleCache for opt-in caching of streamed audio
  implementation(libs.androidx.media3.datasource)
  // Audio transcode (shrink episodes for OpenAI's 25 MB upload cap)
  implementation(libs.androidx.media3.transformer)

  // Networking / JSON / images
  implementation(libs.okhttp)
  implementation(libs.kotlinx.serialization.json)
  implementation(libs.coil.compose)

  // Google sign-in + Drive appDataFolder sync (REST over OkHttp)
  implementation(libs.play.services.auth)
  // Browser OAuth 2.0 + PKCE fallback for devices without Google Play Services
  implementation(libs.appauth)
}
