pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "location-checkin-lab"

include(":core")   // 纯 JVM 检测引擎，零依赖，可直接用 javac 编译验证
include(":app")    // Android 演示工程：mock 注入 + 信号采集 + 本地评分
