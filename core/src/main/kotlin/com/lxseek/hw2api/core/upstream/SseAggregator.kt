package com.lxseek.hw2api.core.upstream

import com.lxseek.hw2api.core.util.RandomHex
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader

/**
 * SSE 聚合器（语义对齐 Python `aggregate_sse`）：把上游 SSE data 帧聚合为非流式
 * chat.completion 完整 JSON（glm-5.3 仅流式，非流式请求由代理内部转流式再聚合）。
 *
 * 三种帧形态：
 * 1. 单帧完整 chat.completion（上游非流式伪流式）→ 原样返回
 * 2. 多帧 chat.completion.chunk → 聚合 content / reasoning_content / tool_calls /
 *    finish_reason / usage
 * 3. 上游错误帧（{"error_code":...} / {"error":{...}}）→ 返回带 "error" 键的对象，
 *    由调用方转成错误响应
 */
object SseAggregator {

    private val json = Json { ignoreUnknownKeys = true }

    /** 从输入流读 SSE 帧文本（data: 行合并，跳过 [DONE] 与注释行），语义对齐 Python 流式解析 */
    fun readFrames(stream: InputStream): List<String> {
        val frames = mutableListOf<String>()
        BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { reader ->
            val dataParts = mutableListOf<String>()
            while (true) {
                val line = reader.readLine() ?: break
                when {
                    line.isEmpty() -> {
                        // 空行 = 事件结束
                        if (dataParts.isNotEmpty()) {
                            val text = dataParts.joinToString("\n")
                            if (text != "[DONE]") frames.add(text)
                            dataParts.clear()
                        }
                    }
                    line.startsWith(":") -> continue // SSE 注释/心跳
                    line.startsWith("data:") -> dataParts.add(line.removePrefix("data:").trim())
                    else -> continue // event:/id:/retry: 等字段不参与聚合
                }
            }
            // 流截断兜底：缓冲区残留也作为一帧
            if (dataParts.isNotEmpty()) {
                val text = dataParts.joinToString("\n")
                if (text != "[DONE]") frames.add(text)
            }
        }
        return frames
    }

    /** 纯文本帧列表聚合（语义一比一对齐 Python aggregate_sse(frames, model_id)） */
    fun aggregate(frames: List<String>, modelId: String = ""): JsonObject? {
        if (frames.isEmpty()) return null
        // 形态 1：首帧就是完整 chat.completion（上游非流式伪流式）→ 原样返回
        val firstObj = runCatching { json.parseToJsonElement(frames[0]).jsonObject }.getOrNull()
        if (firstObj != null && firstObj["object"]?.jsonPrimitive?.content == "chat.completion") {
            return firstObj
        }
        val content = StringBuilder()
        val reasoning = StringBuilder()
        val toolCalls = mutableListOf<MutableMap<String, Any?>>()
        var finishReason: String? = null
        var usage: JsonObject? = null
        var first: JsonObject? = null
        var role = "assistant"
        for (frame in frames) {
            val obj = runCatching { json.parseToJsonElement(frame).jsonObject }.getOrNull() ?: continue
            // 形态 3：上游错误帧 → {"error": obj} 原样上抛给调用方
            if (obj.containsKey("error_code") || obj.containsKey("error")) {
                return buildJsonObject { put("error", obj) }
            }
            if (obj["object"]?.jsonPrimitive?.content != "chat.completion.chunk") continue
            if (first == null) first = obj
            val choice = obj["choices"]?.jsonArray?.firstOrNull()?.jsonObject ?: continue
            val delta = choice["delta"]?.jsonObject ?: continue
            delta["role"]?.jsonPrimitive?.content?.let { role = it }
            delta["content"]?.jsonPrimitive?.content?.let { content.append(it) }
            delta["reasoning_content"]?.jsonPrimitive?.content?.let { reasoning.append(it) }
            // tool_calls 按 index 槽位聚合（name/arguments 增量拼接）
            delta["tool_calls"]?.jsonArray?.forEach { tcEl ->
                val tc = tcEl.jsonObject
                val idx = tc["index"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0
                while (toolCalls.size <= idx) {
                    toolCalls.add(
                        mutableMapOf("id" to "", "type" to "function", "fnName" to "", "fnArgs" to "")
                    )
                }
                val slot = toolCalls[idx]
                tc["id"]?.jsonPrimitive?.content?.let { slot["id"] = it }
                val fn = tc["function"]?.jsonObject
                // 增量追加（old + new，对齐 Python slot["fnName"] += name）：
                // SSE 分帧的 arguments 是按发送顺序的片段，前置拼接会打乱 JSON 参数字符序
                fn?.get("name")?.jsonPrimitive?.content?.let { slot["fnName"] = (slot["fnName"] as String) + it }
                fn?.get("arguments")?.jsonPrimitive?.content?.let { slot["fnArgs"] = (slot["fnArgs"] as String) + it }
            }
            choice["finish_reason"]?.jsonPrimitive?.content?.let { finishReason = it }
            obj["usage"]?.jsonObject?.let { usage = it }
        }
        val firstChunk = first ?: return null
        val text = content.toString()
        return buildJsonObject {
            put("id", firstChunk["id"]?.jsonPrimitive?.content ?: "chatcmpl-${randId()}")
            put("object", "chat.completion")
            put("created", firstChunk["created"]?.jsonPrimitive?.content?.toLongOrNull() ?: (System.currentTimeMillis() / 1000))
            put("model", firstChunk["model"]?.jsonPrimitive?.content ?: modelId)
            put(
                "choices",
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put("index", 0)
                            put(
                                "message",
                                buildJsonObject {
                                    put("role", role)
                                    put("content", text.ifEmpty { null })
                                    if (reasoning.isNotEmpty()) {
                                        put("reasoning_content", reasoning.toString())
                                    }
                                    if (toolCalls.isNotEmpty()) {
                                        put(
                                            "tool_calls",
                                            buildJsonArray {
                                                toolCalls.forEach { slot ->
                                                    add(
                                                        buildJsonObject {
                                                            put("id", slot["id"] as String)
                                                            put("type", "function")
                                                            put(
                                                                "function",
                                                                buildJsonObject {
                                                                    put("name", slot["fnName"] as String)
                                                                    put("arguments", slot["fnArgs"] as String)
                                                                }
                                                            )
                                                        }
                                                    )
                                                }
                                            }
                                        )
                                    }
                                }
                            )
                            put("finish_reason", finishReason ?: "stop")
                        }
                    )
                }
            )
            usage?.let { put("usage", it) }
        }
    }

    /** 一步聚合：输入流 → 完整 chat.completion JSON（null = 无有效帧） */
    fun aggregateStream(stream: InputStream, modelId: String = ""): JsonObject? =
        aggregate(readFrames(stream), modelId)

    /** 随机 id 片段（对齐 Python `_rand` 语义：随机 24 hex 字符） */
    private fun randId(): String = RandomHex.next(12)
}