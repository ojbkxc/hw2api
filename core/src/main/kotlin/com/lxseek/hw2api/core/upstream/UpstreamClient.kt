package com.lxseek.hw2api.core.upstream

import com.lxseek.hw2api.core.model.Account
import com.lxseek.hw2api.core.model.ModelRegistry
import com.lxseek.hw2api.core.signing.AkskSigner
import com.lxseek.hw2api.core.util.RandomHex
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.InputStream

/**
 * 上游 chat 客户端（语义对齐 Python `upstream_headers` + `_upstream_request` + 401 重试循环）。
 *
 * - 全量请求头构造 + SDK-HMAC-SHA256 签名（X-Security-Token、UNSIGNED-PAYLOAD、
 *   每次随机 32hex 的 x-ot-trace-id/x-ot-span-id/x-snap-traceid、
 *   每账号固定的 user-session-id/x-ot-session-id、X-Language、Content-Type）
 * - 流式调用返回 OkHttp 响应体 InputStream（供 app 层 SSE 直通逐块转发）
 * - 401 → 强制续期回调（绕过 3600s 阈值）→ 重试恰好一次；仍 401 → NEEDS_RELOGIN
 */
class UpstreamClient(
    private val httpClient: OkHttpClient,
    private val baseUrl: String = ModelRegistry.UPSTREAM_CHAT_URL,
    /** 强制续期回调：返回续期后的新账号（app 层串联 TokenRefresher.forceRefresh + 存储） */
    private val forceRefresh: suspend (account: Account) -> Account,
) {

    /** 流式响应句柄：调用方负责 close（关闭时同步取消底层 call 防泄漏） */
    class StreamResponse(
        val statusCode: Int,
        val contentType: String,
        val stream: InputStream,
        private val closeHook: () -> Unit,
    ) : AutoCloseable {
        override fun close() {
            try {
                stream.close()
            } finally {
                closeHook()
            }
        }
    }

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    /**
     * 流式 chat 请求（stream=true 的 payload 由调用方组装）。
     * 返回上游 SSE 原始字节流；非 2xx 抛 [UpstreamException]。
     * @param account 当前账号（临期续期由上层 ensureFresh 先行处理）
     * @param payloadBytes 完整请求体 JSON 字节
     */
    suspend fun streamChat(
        account: Account,
        payloadBytes: ByteArray,
    ): StreamResponse = withContext(Dispatchers.IO) {
        var current = account
        var lastException: UpstreamException? = null
        // 401 → 强制续期 → 重试恰好一次（对齐 Python attempt in range(2)）
        repeat(MAX_ATTEMPTS) { attempt ->
            try {
                val resp = executeOnce(current, payloadBytes)
                return@withContext resp
            } catch (e: UpstreamException) {
                if (e.statusCode == 401 && attempt == 0) {
                    // 强制续期（无视 3600s 阈值）；续期链死 → NEEDS_RELOGIN 直接上抛
                    current = try {
                        forceRefresh(current)
                    } catch (refreshError: Exception) {
                        throw UpstreamException(
                            statusCode = 401,
                            responseBody = refreshError.message ?: "",
                            kind = UpstreamException.Kind.NEEDS_RELOGIN,
                            message = "上游 401 且强制续期失败: ${refreshError.message}",
                            cause = refreshError,
                        )
                    }
                    lastException = e
                } else if (e.statusCode == 401) {
                    // 重试后仍 401：凭证链已死
                    throw UpstreamException(
                        statusCode = 401,
                        responseBody = e.responseBody,
                        kind = UpstreamException.Kind.NEEDS_RELOGIN,
                        message = "上游 401（续期后重试仍失败），需要重新登录",
                        cause = e,
                    )
                } else {
                    throw e
                }
            }
        }
        throw lastException ?: UpstreamException(0, "", UpstreamException.Kind.NETWORK, "上游请求未完成")
    }

    /** 单次请求执行（构造头 + 签名 + 发出），2xx 返回流句柄，否则抛类型化异常 */
    private fun executeOnce(account: Account, payloadBytes: ByteArray): StreamResponse {
        val headers = buildHeaders(account)
        val request = Request.Builder()
            .url(baseUrl)
            .apply { headers.forEach { (k, v) -> header(k, v) } }
            .post(payloadBytes.toRequestBody(jsonMediaType))
            .build()
        val call = httpClient.newCall(request)
        val resp = try {
            call.execute()
        } catch (e: java.io.IOException) {
            throw UpstreamException(
                statusCode = 0,
                responseBody = "",
                kind = UpstreamException.Kind.NETWORK,
                message = "上游连接失败: ${e.message}",
                cause = e,
            )
        }
        if (!resp.isSuccessful) {
            val body = try { resp.body?.string() ?: "" } catch (_: Exception) { "" }
            resp.close()
            throw UpstreamException(
                statusCode = resp.code,
                responseBody = body.take(500),
                kind = UpstreamException.Kind.HTTP_ERROR,
                message = "上游 ${resp.code}: ${body.take(200)}",
            )
        }
        return StreamResponse(
            statusCode = resp.code,
            contentType = resp.header("Content-Type") ?: "text/event-stream",
            stream = resp.body!!.byteStream(),
            closeHook = { call.cancel() },
        )
    }

    /** 构造全量请求头并签名（语义对齐 Python upstream_headers；头顺序无关，签名内部排序） */
    internal fun buildHeaders(account: Account): Map<String, String> {
        val headers = linkedMapOf(
            "X-Security-Token" to account.credentials.securityToken,
            // 实测必需：POST 网关要求显式声明
            "X-Sdk-Content-Sha256" to AkskSigner.UNSIGNED_PAYLOAD,
            "x-ot-trace-id" to RandomHex.next32(),
            "x-ot-span-id" to RandomHex.next32(),
            "x-snap-traceid" to RandomHex.next32(),
            // 每账号固定复用（对齐 Python 会话固定值语义：session["user_session_id"] 恒定），
            // 随机值等效无限并发会话身份，长高频使用有触发上游风控/限流的风险
            "user-session-id" to account.accountId,
            // 登录时生成一次固定（上游按其计并发会话数）
            "x-ot-session-id" to account.xOtSessionId,
            "X-Language" to "zh-cn",
            "Content-Type" to "application/json",
        )
        return AkskSigner.signRequest(
            method = "POST",
            url = baseUrl,
            headers = headers,
            ak = account.credentials.accessKeyId,
            sk = account.credentials.secretAccessKey,
        )
    }

    companion object {
        /** 401 重试上限（首轮 + 续期后重试一次） */
        private const val MAX_ATTEMPTS = 2
    }
}