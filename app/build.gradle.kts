plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "dev.lab.location.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.lab.location.app"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
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
    // 唯一的依赖：平台无关的检测引擎。
    // 刻意不引入 androidx / 协程：这个演示工程用原生 Activity + Handler 就够了，
    // 依赖越少，越容易在任意环境里一次构建通过。
    implementation(project(":core"))
}
