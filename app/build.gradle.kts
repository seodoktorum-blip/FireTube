plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.uykutube.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.uykutube.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 6
        versionName = "1.6"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // kişisel kullanım: kurulum için debug anahtarıyla imzala;
            // ayrıca adb run-as ile günlük dosyası çekilebilsin diye debuggable
            isDebuggable = true
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    // aşağı çekerek yenileme göstergesi
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.1.0")
}
