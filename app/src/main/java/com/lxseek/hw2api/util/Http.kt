package com.lxseek.hw2api.util

import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * 全局 OkHttpClient 单例：STS token 交换与上游 chat 请求共用一个连接池。
 * - 连接 15s / 写 30s；读超时放宽到 10 分钟兜底长生成（SSE 流式逐块到达，读间隔通常远小于此值）
 * - OkHttp 依赖经 :core 的 api 传递（core/build.gradle.kts），无需在 app 重复声明
 */
object Http {

    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.MINUTES)
            .retryOnConnectionFailure(true)
            .build()
    }
}