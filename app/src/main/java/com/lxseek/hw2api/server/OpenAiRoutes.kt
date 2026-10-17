package com.lxseek.hw2api.server

import com.lxseek.hw2api.core.auth.StsException
import com.lxseek.hw2api.core.model.ChatCompletionRequest
import com.lxseek.hw2api.core.model.ModelEntry
import com.lxseek.hw2api.core.model.ModelListResponse
import com.lxseek.hw2api.core.model.ModelMetadata
import com.lxseek.hw2api.core.model.ModelRegistry
import com.lxseek.hw2api.core.upstream.SseAggregator
import com.lxseek.hw2api.core.upstream.UpstreamClient
import com.lxseek.hw2api.core.upstream.UpstreamException
import com.lxseek.hw2api.service.ProxyForegroundService
import com.lxseek.hw2api.store.CredentialManager
import com.lxseek.hw2api.store.NotLoggedInException
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.longOrNull

import kotlinx.serialization.json.put
import java.io.FilterInputStream

/**
 * OpenAI 兼容路由（plan.md §3.2 / 附录 A.7）：
 * - GET /v1/models：由 ModelRegistry 模型表 + 别名表生成 OpenAI list JSON
 * - POST /v1/chat/completions：别名归一 → UpstreamClient 转发；
 *   stream=true 用 chunked response 直通上游 SSE；stream=false 聚合返回
 *   （glm-5.3 仅流式：非流式请求内部强制转流式再由 SseAggregator 聚合）
 * - GET /health：账号 label、凭证到期、上游连通（最近一次上游调用结果）
 *
 * 本类在 NanoHTTPD 工作线程被同步调用；suspend 调用点（ensureFresh / streamChat）
 * 用 runBlocking 桥接（阻塞的正是该工作线程，符合 NanoHTTPD 每连接一线程模型）。
 */
class OpenAiRoutes(
    private val credentials: CredentialManager,
    upstreamClient: UpstreamClient,
) {

    private val upstream = upstreamClient
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    // ── 上游连通记录（/health 的 upstream 布尔；避免每次探测消耗上游配额）──
    @Volatile
    private var lastUpstreamOk: Boolean? = null

    @Volatile
    private var lastUpstreamAt: Long = 0

    @Volatile
    private var lastUpstreamError: String? = null

    /** GET /v1/models */
    fun listModels(): NanoHTTPD.Response {
        val entries = buildList {
            ModelRegistry.MODELS.forEach { (id, info) ->
                add(
                    ModelEntry(
                        id = id, objectKind = "model", created = MODELS_CREATED_AT, ownedBy = "hw2api",
                        metadata = ModelMetadata(name = info.name, contextWindow = info.ctx, desc = info.desc),
                    )
                )
            }
            ModelRegistry.MODEL_ALIAS.forEach { (alias, realId) ->
                val info = ModelRegistry.MODELS[realId]
                add(
                    ModelEntry(
                        id = alias, objectKind = "model", created = MODELS_CREATED_AT, ownedBy = "hw2api",
                        metadata = info?.let { ModelMetadata(name = it.name, contextWindow = it.ctx, desc = it.desc) },
                    )
                )
            }
        }
        val body = json.encodeToString(
            ModelListResponse.serializer(),
            ModelListResponse(objectKind = "list", data = entries),
        )
        return jsonResponse(NanoHTTPD.Status.OK, body)
    }

    /** POST /v1/chat/completions（body 已由 ProxyServer 读出为 UTF-8 字节） */
    fun chatCompletions(bodyBytes: ByteArray): NanoHTTPD.Response {
        // 1. 解析请求体
        val root = try {
            json.parseToJsonElement(bodyBytes.decodeToString()).let { it as? JsonObject }
        } catch (_: Exception) {
            null
        } ?: return errorResponse(NanoHTTPD.Status.BAD_REQUEST, "请求体不是合法 JSON")

        // 2. 模型名校验与别名归一（as? 安全转换：字段为对象/数组时给出可读 400 而非 500）
        val requestedModel = (root["model"] as? JsonPrimitive)?.content?.trim().orEmpty()
        if (requestedModel.isEmpty()) {
            return errorResponse(NanoHTTPD.Status.BAD_REQUEST, "缺少 model 字段")
        }
        if (!ModelRegistry.contains(requestedModel)) {
            return errorResponse(
                NanoHTTPD.Status.NOT_FOUND,
                "模型不存在：$requestedModel（可用模型见 /v1/models）",
                type = "invalid_request_error",
            )
        }
        val resolvedModel = ModelRegistry.resolve(requestedModel)

        // 3. 流式决策：请求要求流式，或该模型仅流式（如 glm-5.3）→ 上游一律按流式发
        val streamWanted = (root["stream"] as? JsonPrimitive)?.booleanOrNull ?: false
        val streamOnly = ModelRegistry.isStreamOnly(resolvedModel)
        val upstreamStream = streamWanted || streamOnly
        // 上游 payload：模型名替换为真实 ID，stream 字段强制写决策值，其余字段（messages/tools/…）原样透传。
        // 注意：apply 必须挂在 HashMap 可变拷贝上（MutableMap.put 成员可用）——
        // JsonObject 是只读 Map 委托没有 put，挂在 JsonObject 上属编译错误
        val modelInfo = ModelRegistry.info(resolvedModel)
        val upstreamPayload = JsonObject(
            HashMap<String, JsonElement>(root).apply {
                put("model", JsonPrimitive(resolvedModel))
                put("stream", JsonPrimitive(upstreamStream))
                // max_tokens 参数上限（对齐 Python _handle_chat 3a）：超模型 hard_cap 钳制到上限，
                // 避免上游 RANGE_VALIDATOR 参数校验 400；不指定或非整数值则不动。
                // isString 过滤对齐 Python isinstance(int)：字符串形态（如 "70000"）不钳制
                if (modelInfo != null) {
                    (root["max_tokens"] as? JsonPrimitive)
                        ?.takeIf { !it.isString }
                        ?.longOrNull
                        ?.let { requested ->
                            if (requested > modelInfo.maxTokens) {
                                put("max_tokens", JsonPrimitive(modelInfo.maxTokens))
                            }
                        }
                }
            }
        )
        // 弱类型请求 DTO 校验：messages 必填（提前给出可读错误，避免打到上游才失败）
        try {
            json.decodeFromString(ChatCompletionRequest.serializer(), upstreamPayload.toString())
        } catch (e: Exception) {
            return errorResponse(NanoHTTPD.Status.BAD_REQUEST, "请求体不合法：${e.message?.lineSequence()?.firstOrNull() ?: ""}")
        }

        // 4. 取可用账号（临期自动续期，单飞）
        val account = try {
            runBlocking { credentials.ensureFresh() }
        } catch (e: NotLoggedInException) {
            return errorResponse(
                NanoHTTPD.Status.UNAUTHORIZED, "未登录华为云账号，请先在 HW API App 内登录",
                type = "relogin_required",
            )
        } catch (e: StsException) {
            markNeedsRelogin()
            return errorResponse(
                NanoHTTPD.Status.UNAUTHORIZED,
                "凭证续期失败（${e.message}），请在 HW API App 内重新登录",
                type = "relogin_required",
            )
        }

        // 5. 转发上游（401 时 UpstreamClient 内部已强制续期并重试一次）
        val streamResponse = try {
            runBlocking { upstream.streamChat(account, upstreamPayload.toString().toByteArray(Charsets.UTF_8)) }
        } catch (e: UpstreamException) {
            markUpstream(false, e.message)
            if (e.needsRelogin) {
                markNeedsRelogin()
                return errorResponse(
                    NanoHTTPD.Status.UNAUTHORIZED,
                    "凭证已失效（上游 401 且续期无效），请在 HW API App 内重新登录",
                    type = "relogin_required",
                )
            } else if (e.kind == UpstreamException.Kind.NETWORK) {
                return errorResponse(HttpStatus.GATEWAY_TIMEOUT, "上游网络错误：${e.message}", type = "network_error")
            } else {
                // 上游 token 硬限错误（响应体含 limit_err 错误码）：转 413 友好提示，
                // 对齐 Python _handle_chat（input+output 总量超限场景）
                if (modelInfo != null && e.responseBody.contains(modelInfo.limitErr.toString())) {
                    return errorResponse(
                        HttpStatus.PAYLOAD_TOO_LARGE,
                        "请求超出 $resolvedModel 上游硬限（input+output ≤ ${modelInfo.maxTokens} tokens，" +
                            "错误码 ${modelInfo.limitErr}）。请减少上下文或缩短 max_tokens。",
                        type = "upstream_error",
                        code = modelInfo.limitErr,
                        upstreamStatus = e.statusCode,
                    )
                }
                return errorResponse(
                    HttpStatus.BAD_GATEWAY, "上游 ${e.statusCode}：${e.responseBody.take(200)}",
                    type = "upstream_error", upstreamStatus = e.statusCode,
                )
            }
        }
        markUpstream(true, null)

        // 6. 返回：流式直通 / 非流式聚合
        return if (streamWanted) {
            ssePipe(streamResponse)
        } else {
            aggregateResponse(streamResponse, resolvedModel)
        }
    }

    /** GET /health（不鉴权）：账号、凭证到期、上游连通、监听信息 */
    fun health(config: ServerConfig): NanoHTTPD.Response {
        val account = credentials.activeOrNull()
        val now = System.currentTimeMillis()
        val body = buildJsonObject {
            put(
                "status",
                when {
                    account == null -> "no_account"
                    credentials.needsRefresh(account) && account.credentials.isExpired(now) -> "credentials_expired"
                    else -> "ok"
                },
            )
            put("account", account?.label ?: "")
            put("credentials_expire_at", account?.credentials?.expiration ?: 0L)
            put("credentials_expire_at_iso", account?.credentials?.expiresAt ?: "")
            put("expires_in_seconds", account?.let { (it.credentials.expiration - now) / 1000 } ?: 0L)
            put(
                "upstream",
                buildJsonObject {
                    put("last_call_ok", lastUpstreamOk)
                    put("last_call_at", lastUpstreamAt)
                    put("last_error", lastUpstreamError)
                },
            )
            put("listen", "${config.bindHost}:${config.port}")
            put("auth_enabled", config.apiKey.isNotEmpty())
        }
        return jsonResponse(NanoHTTPD.Status.OK, body.toString())
    }

    // ── 内部工具 ──

    /**
     * SSE 直通：上游 OkHttp 字节流包装后交给 NanoHTTPD chunked response 逐块写出。
     * 包装流的 close 由 NanoHTTPD 在响应结束（正常 EOF / 客户端断连写失败）时触发，
     * close 内联 StreamResponse.close() → cancel 底层 OkHttp call，防止上游流泄漏。
     */
    private fun ssePipe(upstreamResponse: UpstreamClient.StreamResponse): NanoHTTPD.Response {
        val piped = object : FilterInputStream(upstreamResponse.stream) {
            override fun close() {
                try {
                    super.close()
                } finally {
                    // closeHook 会同步 cancel 上游 call（客户端断开 → 上游流取消）
                    upstreamResponse.close()
                }
            }
        }
        val response = NanoHTTPD.newChunkedResponse(NanoHTTPD.Status.OK, MIME_SSE, piped)
        // NanoHTTPD 对 text/* 会按 Accept-Encoding 自动 gzip：GZIP 缓冲会聚积 SSE 事件块、
        // 破坏逐块实时推送（且 EventSource 规范不支持 gzip 流），流式直通必须显式关闭
        response.setGzipEncoding(false)
        response.addHeader("Cache-Control", "no-cache")
        response.addHeader("X-Accel-Buffering", "no")
        response.addHeader("Access-Control-Allow-Origin", "*")
        return response
    }

    /** 非流式：上游 SSE → SseAggregator 聚合为完整 chat.completion；上游直回 JSON 则透传 */
    private fun aggregateResponse(upstreamResponse: UpstreamClient.StreamResponse, modelId: String): NanoHTTPD.Response {
        val isSse = upstreamResponse.contentType.contains("text/event-stream", ignoreCase = true)
        return try {
            if (isSse) {
                val aggregated = SseAggregator.aggregateStream(upstreamResponse.stream, modelId)
                    ?: return errorResponse(HttpStatus.BAD_GATEWAY, "上游未返回有效数据", type = "upstream_error")
                if (aggregated.containsKey("error")) {
                    // 上游错误帧（{"error_code":...} / {"error":{...}}）原样透传
                    jsonResponse(HttpStatus.BAD_GATEWAY, aggregated.toString())
                } else {
                    jsonResponse(NanoHTTPD.Status.OK, aggregated.toString())
                }
            } else {
                // 上游以完整 JSON 响应（stream=false 的常规模型）：直接透传
                jsonResponse(NanoHTTPD.Status.OK, upstreamResponse.stream.readBytes().toString(Charsets.UTF_8))
            }
        } finally {
            upstreamResponse.close()
        }
    }

    private fun jsonResponse(status: NanoHTTPD.Response.IStatus, body: String): NanoHTTPD.Response {
        val response = NanoHTTPD.newFixedLengthResponse(status, MIME_JSON, body)
        response.addHeader("Access-Control-Allow-Origin", "*")
        return response
    }

    private fun errorResponse(
        status: NanoHTTPD.Response.IStatus,
        message: String,
        type: String = "invalid_request_error",
        code: Int? = null,
        upstreamStatus: Int? = null,
    ): NanoHTTPD.Response {
        val body = buildJsonObject {
            put(
                "error",
                buildJsonObject {
                    put("message", message)
                    put("type", type)
                    if (code != null) put("code", code)
                    if (upstreamStatus != null) put("upstream_status", upstreamStatus)
                },
            )
        }
        return jsonResponse(status, body.toString())
    }

    private fun markUpstream(ok: Boolean, error: String?) {
        lastUpstreamOk = ok
        lastUpstreamAt = System.currentTimeMillis()
        lastUpstreamError = error
    }

    /** 续期链死亡：置 UI 引导重新登录（通知栏 + 主界面横幅） */
    private fun markNeedsRelogin() {
        ProxyForegroundService.markNeedsRelogin()
    }

    companion object {
        private const val MIME_JSON = "application/json"
        private const val MIME_SSE = "text/event-stream"

        /** /v1/models 的 created 固定值（模型表静态写死，取 2025-01-01 UTC） */
        private const val MODELS_CREATED_AT = 1735689600L
    }
}