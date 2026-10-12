// 根构建脚本：只声明插件版本，不应用。
//
// 工具链要求
//   - JDK 17 或更高（AGP 8.5 要求 JDK 17+）
//   - Gradle 8.7+（用 `gradle wrapper` 或 Android Studio 自动补齐 wrapper）
//   - Android SDK Platform 35（仅 :app 需要；:core 是纯 JVM 模块，不需要 SDK）
plugins {
    id("com.android.application") version "8.5.2" apply false
    id("org.jetbrains.kotlin.android") version "1.9.24" apply false
}
