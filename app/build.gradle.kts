plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "br.com.screenlite.recorder"
    compileSdk = 35
    defaultConfig {
        applicationId = "br.com.screenlite.recorder"
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }
}
