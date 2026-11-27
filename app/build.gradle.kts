plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "dev.karim.kcast"
    compileSdk = 34
    defaultConfig {
        applicationId = "dev.karim.kcast"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
    }
    val ks = System.getenv("KC_KEYSTORE")
    if (ks != null) {
        signingConfigs.getByName("debug") {
            storeFile = file(ks)
            storePassword = System.getenv("KC_KS_PASS")
            keyAlias = "kcast"
            keyPassword = System.getenv("KC_KS_PASS")
        }
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
}
