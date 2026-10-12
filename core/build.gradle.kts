// 纯 JVM 模块：不依赖 Android、不依赖任何第三方库。
//
// 这样做的两个理由：
//   1. 检测规则可以在普通 JVM 上直接跑单元测试，不需要模拟器或真机（CI 友好）；
//   2. 同一份代码可以同时被客户端（Android）和服务端引用，避免两端规则不一致。
plugins {
    `java-library`
}

// 用 options.release 而不是 toolchain：本机装了 JDK 21 也能产出 17 字节码，
// 不会触发 Gradle 去下载一个额外的 JDK。
tasks.withType<JavaCompile>().configureEach {
    options.release.set(17)
    options.encoding = "UTF-8"
}

java {
    withSourcesJar()
}

// 零依赖自检：不引入 JUnit，直接跑一个 main，任何断言失败就以非零码退出。
// 用法：./gradlew :core:selfCheck
tasks.register<JavaExec>("selfCheck") {
    group = "verification"
    description = "运行不依赖任何测试框架的风险评分自检用例"
    dependsOn(tasks.named("testClasses"))
    mainClass.set("dev.lab.location.core.RiskScorerSelfCheck")
    classpath = sourceSets["test"].runtimeClasspath
}
