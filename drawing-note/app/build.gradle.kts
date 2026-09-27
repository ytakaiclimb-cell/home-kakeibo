plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "io.github.ytakaiclimb.drawnote"
    compileSdk = 34

    defaultConfig {
        applicationId = "io.github.ytakaiclimb.drawnote"
        minSdk = 29
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    // 更新インストールできるよう、署名鍵はリポジトリに同梱した固定の鍵を使う
    signingConfigs {
        create("shared") {
            storeFile = file("signing.keystore")
            storePassword = "drawnote"
            keyAlias = "drawnote"
            keyPassword = "drawnote"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("shared")
        }
        debug {
            signingConfig = signingConfigs.getByName("shared")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}
