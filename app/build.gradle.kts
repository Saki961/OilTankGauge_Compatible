plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.oilterminal.tankcalc"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.oilterminal.tankcalc"
        minSdk = 26
        targetSdk = 34
        versionCode = 4
        versionName = "1.2.0"
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
}

dependencies {
    // 本工程只使用 Android SDK、Kotlin 标准库和 SQLite，
    // 不依赖网络框架或第三方 Excel 库。
}
