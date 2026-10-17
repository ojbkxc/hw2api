package com.lxseek.hw2api.server

import fi.iki.elonen.NanoHTTPD
import java.util.concurrent.ConcurrentHashMap

/**
 * API server 启动配置（plan.md §3.2 ServerConfig）。
 * @param port 监听端口，默认 8788
 * @param listenAllInterfaces false = 仅本机 127.0.0.1（默认），true = 0.0.0.0 局域网可接入
 * @param apiKey 局域网鉴权口令（对齐 CODEARTS_PROXY_API_KEY 语义；空 = 不鉴权）
 */
data class ServerConfig(
    val port: Int = DEFAULT_PORT,
    val listenAllInterfaces: Boolean = false,
    val apiKey: String = "",
) {
    /** NanoHTTPD bind 主机 */
    val bindHost: String get() = if (listenAllInterfaces) "0.0.0.0" else "127.0.0.1"

    companion object {
        const val DEFAULT_PORT = 8788

        /** 请求体上限 8MB（附录 A.7，超限 413） */
        const val MAX_BODY_BYTES: Long = 8L * 1024 * 1024
    }
}

/**
 * 自定义 HTTP 状态（NanoHTTPD 2.3.1 的 Status 枚举缺 413/502/504，
 * 其 IStatus 接口允许自定义；工厂签名即 IStatus，直接构造）。
 */
object HttpStatus {

    private class Impl(private val code: Int, private val text: String) : NanoHTTPD.Response.IStatus {
        override fun getRequestStatus(): Int = code
        override fun getDescription(): String = text
    }

    private fun of(code: Int, text: String): NanoHTTPD.Response.IStatus = Impl(code, text)

    /** 请求体超限（8MB 上限，附录 A.7） */
    val PAYLOAD_TOO_LARGE: NanoHTTPD.Response.IStatus = of(413, "Payload Too Large")

    /** 上游返回错误 / 上游错误帧 */
    val BAD_GATEWAY: NanoHTTPD.Response.IStatus = of(502, "Bad Gateway")

    /** 上游网络层失败（连接超时 / DNS / TLS） */
    val GATEWAY_TIMEOUT: NanoHTTPD.Response.IStatus = of(504, "Gateway Timeout")
}