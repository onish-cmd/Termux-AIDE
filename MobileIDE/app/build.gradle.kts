// app/build.gradle.kts
plugins { id("com.android.application"); kotlin("android") }

android {
  namespace = "com.osk.aide"
  compileSdk = 34
  defaultConfig {
    applicationId = "com.osk.aide"
    minSdk = 24
    targetSdk = 34
    versionCode = 1
    versionName = "1.0"
  }
  buildFeatures { compose = true }
  composeOptions { kotlinCompilerExtensionVersion = "1.5.14" }
}

dependencies {
  implementation(platform("androidx.compose:compose-bom:2024.09.02"))
  implementation("androidx.activity:activity-compose:1.9.2")
  implementation("androidx.compose.material3:material3:1.3.0")
  implementation("androidx.compose.ui:ui")
  implementation("androidx.compose.ui:ui-tooling-preview")
  implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
}
