package com.lxseek.hw2api.server

import com.lxseek.hw2api.core.upstream.UpstreamClient
import com.lxseek.hw2api.store.CredentialManager
import com.lxseek.hw2api.store.SettingsStore
import com.lxseek.hw2api.util.Http
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoHTTPD.AsyncRunner
import fi.iki.elonen.NanoHTTPD.ClientHandler
import fi.iki.elonen.NanoHTTPD.IHTTPSession
import fi.iki.elonen.NanoHTTPD.Method
import fi.iki.elonen.NanoHTTPD.Response
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * 本地 OpenAI 兼容 server（plan.md §3.2 / 附录 A.7）：NanoHTTPD 子类。
 *
 * - 路由：GET /v1/models、POST /v1/chat/completions、GET /health（+ OPTIONS 预检）
 * - 请求体上限 8MB（超限 413）；body 从 session.inputStream 按 content-length 原样读出
 *   （不走 parseBody：其非表单分支按 US-ASCII 解码会把中文 prompt 毁成 '?'）
 * - keep-alive 一致性：带 body 的方法在路由判定前统一读满 body，
 *   避免鉴权失败/413/404 等早退分支残留未消费字节导致后续请求错位
 *   （NanoHTTPD 的 setKeepAlive 会被会话按请求头覆盖，无法用 Connection: close 兜底）
 * - 固定线程池承载连接（默认每连接新建线程无上限，SSE 长连接会放大线程开销；
 *   池满时拒绝并关闭 socket，上限保护）
 * - SSE 直通响应交给 NanoHTTPD chunked 逐块写出；客户端断开由包装流 close 取消上游 call
 */
class ProxyServer(
    private val config: ServerConfig,
    private val credentials: CredentialManager,
) : NanoHTTPD(config.bindHost, config.port) {

    private val apikeyAuth = ApiKeyAuth(config.apiKey)
    private val routes: OpenAiRoutes
    private val runner = PooledAsyncRunner()

    init {
        // 上游 401 → 强制续期（CredentialManager 内部单飞 + 回写存储）→ 重试一次（core 语义）
        val upstreamClient = UpstreamClient(Http.client) { account ->
            credentials.forceRefresh(account)
        }
        routes = OpenAiRoutes(credentials, upstreamClient)
        setAsyncRunner(runner)
    }

    override fun serve(session: IHTTPSession): Response {
        val method = session.method
        // uri 归一：去 query、去尾斜杠（decodeHeader 已剥离 query，此处防御尾斜杠）
        val uri = session.uri?.trimEnd('/') ?: ""
        // NanoHTTPD header 名统一小写
        val contentLength = session.headers["content-length"]?.toLongOrNull() ?: 0L

        // 带 body 的方法：先读满再路由（见类注释 keep-alive 一致性说明）
        var oversize = false
        var body = ByteArray(0)
        if (contentLength > 0L && method in BODY_METHODS) {
            if (contentLength > ServerConfig.MAX_BODY_BYTES) {
                oversize = true
                drainBody(session.inputStream, contentLength)
            } else {
                body = readFully(session.inputStream, contentLength) ?: ByteArray(0)
            }
        }

        return try {
            when {
                // CORS 预检（浏览器直连场景）
                method == Method.OPTIONS -> corsPreflight()

                // 健康检查（不鉴权）
                method == Method.GET && uri == "/health" -> routes.health(config)

                // 以下 /v1/* 均需鉴权（key 为空时 ApiKeyAuth 直接放行）
                method == Method.GET && uri == "/v1/models" ->
                    withAuth(session) { routes.listModels() }

                method == Method.POST && uri == "/v1/chat/completions" ->
                    withAuth(session) {
                        if (oversize) {
                            newFixedLengthResponse(
                                HttpStatus.PAYLOAD_TOO_LARGE, MIME_PLAINTEXT,
                                "PAYLOAD TOO LARGE: body exceeds ${ServerConfig.MAX_BODY_BYTES} bytes",
                            )
                        } else {
                            routes.chatCompletions(body)
                        }
                    }

                else -> notFound()
            }
        } catch (_: IOException) {
            // body 读取中客户端断开：连接随 socket 异常由 NanoHTTPD 收尾
            newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, "BAD REQUEST: body read broken")
        } catch (_: Exception) {
            // 500 通用文案：不拼入 e.message（可能暴露内部类名/路径），保持零日志原则不落盘详情
            newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, "SERVER INTERNAL ERROR")
        }
    }

    /** 可选鉴权拦截：失败 401（对齐 OpenAI 错误结构） */
    private inline fun withAuth(session: IHTTPSession, block: () -> Response): Response {
        if (!apikeyAuth.check(session.headers)) {
            val body = """{"error":{"message":"无效的 API Key（Authorization: Bearer 或 X-Api-Key）","type":"invalid_api_key"}}"""
            val denied = newFixedLengthResponse(Response.Status.UNAUTHORIZED, "application/json", body)
            denied.addHeader("Access-Control-Allow-Origin", "*")
            return denied
        }
        return block()
    }

    /** 从请求流按长度读满 UTF-8 字节；连接提前断开返回 null */
    private fun readFully(input: InputStream, length: Long): ByteArray? {
        val out = ByteArrayOutputStream(length.coerceAtMost(ServerConfig.MAX_BODY_BYTES).toInt())
        val buffer = ByteArray(8192)
        var remaining = length
        while (remaining > 0L) {
            val n = input.read(buffer, 0, minOf(remaining, buffer.size.toLong()).toInt())
            if (n < 0) return null // 连接提前断开
            out.write(buffer, 0, n)
            remaining -= n
        }
        return out.toByteArray()
    }

    /** 超限 body 丢弃读取（cap 到 8MB；异常声明长度时连接后续请求会错位，由 NanoHTTPD 报错关闭） */
    private fun drainBody(input: InputStream, length: Long) {
        var remaining = minOf(length, ServerConfig.MAX_BODY_BYTES)
        val buffer = ByteArray(8192)
        try {
            while (remaining > 0L) {
                val n = input.read(buffer, 0, minOf(remaining, buffer.size.toLong()).toInt())
                if (n < 0) break
                remaining -= n
            }
        } catch (_: IOException) {
            // 客户端已断开，无需处理
        }
    }

    /** CORS 预检放行 */
    private fun corsPreflight(): Response {
        val response = newFixedLengthResponse(Response.Status.OK, MIME_PLAINTEXT, "")
        response.addHeader("Access-Control-Allow-Origin", "*")
        response.addHeader("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
        response.addHeader("Access-Control-Allow-Headers", "Authorization, Content-Type, X-Api-Key")
        response.addHeader("Access-Control-Max-Age", "86400")
        return response
    }

    private fun notFound(): Response {
        val body = """{"error":{"message":"Unknown route: use /v1/models, /v1/chat/completions or /health","type":"invalid_request_error"}}"""
        val response = newFixedLengthResponse(Response.Status.NOT_FOUND, "application/json", body)
        response.addHeader("Access-Control-Allow-Origin", "*")
        return response
    }

    /** 固定线程池版 AsyncRunner（NanoHTTPD 2.3.1 接口三方法：exec / closed / closeAll） */
    private class PooledAsyncRunner : AsyncRunner {

        private val executor = ThreadPoolExecutor(
            CORE_THREADS, MAX_THREADS, KEEP_ALIVE_SECONDS, TimeUnit.SECONDS,
            LinkedBlockingQueue(QUEUE_CAPACITY),
        ) { r -> Thread(r, THREAD_NAME).apply { isDaemon = true } }

        private val running: MutableSet<ClientHandler> =
            ConcurrentHashMap.newKeySet()

        override fun exec(handler: ClientHandler) {
            running.add(handler)
            try {
                executor.execute(handler)
            } catch (_: RejectedExecutionException) {
                // 池与队列全满：关闭该连接做上限保护（避免无界线程放大）
                running.remove(handler)
                handler.close()
            }
        }

        override fun closed(handler: ClientHandler) {
            running.remove(handler)
        }

        override fun closeAll() {
            // 快照遍历避免并发修改
            running.forEach { it.close() }
        }

        fun shutdown() {
            executor.shutdownNow()
        }

        companion object {
            /** SSE 长连接各占一线程：核心 16 / 上限 64 / 排队 64（局域网自用规模足够） */
            private const val CORE_THREADS = 16
            private const val MAX_THREADS = 64
            private const val QUEUE_CAPACITY = 64
            private const val KEEP_ALIVE_SECONDS = 30L
            private const val THREAD_NAME = "hw2api-http"
        }
    }

    override fun stop() {
        runner.shutdown()
        runner.closeAll()
        super.stop()
    }

    companion object {
        private const val MIME_PLAINTEXT = "text/plain"

        /** 可能携带请求体的方法（其余方法直接进路由） */
        private val BODY_METHODS = setOf(Method.POST, Method.PUT, Method.PATCH)

        /** 从设置构建（服务启动入口用） */
        fun fromSettings(settings: SettingsStore, credentials: CredentialManager): ProxyServer =
            ProxyServer(settings.serverConfig(), credentials)
    }
}
