plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    jvmToolchain(17)
}

// 纯 JVM 协议核心：禁止任何 Android 依赖（评审项：不得 import android.*）
dependencies {
    api(libs.kotlinx.serialization.json)
    api(libs.okhttp)
    api(libs.coroutines.core)

    testImplementation(libs.junit)
    testImplementation(libs.mockwebserver)
    testImplementation(libs.coroutines.test)
}