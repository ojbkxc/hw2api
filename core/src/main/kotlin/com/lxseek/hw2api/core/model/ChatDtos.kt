package com.lxseek.hw2api.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * OpenAI 兼容协议 DTO（请求/响应/SSE chunk）。
 * 宽松解析：未知字段透传（JsonElement / explicitNulls=false），保证上游新增字段不丢失。
 * content 兼容 string 与 parts 数组两种形态（多模态），用 JsonElement 承载。
 */

// ── 请求侧 ──

@Serializable
data class ChatMessage(
    val role: String,
    /** 字符串或多模态 parts 数组（{"type":"text","text":...} / image_url） */
    val content: JsonElement? = null,
    val name: String? = null,
    /** assistant 消息的工具调用 */
    @SerialName("tool_calls") val toolCalls: List<ToolCall>? = null,
    /** tool 消息回填 */
    @SerialName("tool_call_id") val toolCallId: String? = null,
)

@Serializable
data class ToolCallFunction(
    val name: String? = null,
    val arguments: String? = null,
)

@Serializable
data class ToolCall(
    val id: String? = null,
    val type: String? = null,
    /** 流式增量时的下标 */
    val index: Int? = null,
    val function: ToolCallFunction? = null,
)

@Serializable
data class ChatTool(
    val type: String? = null,
    val function: ToolFunction? = null,
)

@Serializable
data class ToolFunction(
    val name: String? = null,
    val description: String? = null,
    val parameters: JsonElement? = null,
)

@Serializable
data class ChatCompletionRequest(
    val model: String,
    val messages: List<ChatMessage>,
    val stream: Boolean? = null,
    @SerialName("max_tokens") val maxTokens: Int? = null,
    val temperature: Double? = null,
    @SerialName("top_p") val topP: Double? = null,
    val stop: JsonElement? = null,
    val tools: List<ChatTool>? = null,
    @SerialName("tool_choice") val toolChoice: JsonElement? = null,
    @SerialName("reasoning_effort") val reasoningEffort: String? = null,
)

// ── 响应侧 ──

@Serializable
data class ChatUsage(
    @SerialName("prompt_tokens") val promptTokens: Int? = null,
    @SerialName("completion_tokens") val completionTokens: Int? = null,
    @SerialName("total_tokens") val totalTokens: Int? = null,
)

@Serializable
data class ChatResponseMessage(
    val role: String = "assistant",
    val content: String? = null,
    @SerialName("reasoning_content") val reasoningContent: String? = null,
    @SerialName("tool_calls") val toolCalls: List<ToolCall>? = null,
)

@Serializable
data class ChatChoice(
    val index: Int = 0,
    val message: ChatResponseMessage? = null,
    val delta: ChatResponseMessage? = null,
    @SerialName("finish_reason") val finishReason: String? = null,
)

@Serializable
data class ChatCompletionResponse(
    val id: String,
    /** JSON 字段名为 object（Kotlin 关键字，经 @SerialName 映射） */
    @SerialName("object") val objectKind: String? = null,
    val created: Long? = null,
    val model: String? = null,
    val choices: List<ChatChoice> = emptyList(),
    val usage: ChatUsage? = null,
)

// ── SSE chunk（上游流式帧，object = chat.completion.chunk）──
// 直接复用 ChatCompletionResponse（delta 字段承载增量）；SseAggregator 内部区分。

// ── /v1/models 列表（对外 OpenAI 格式）──

@Serializable
data class ModelEntry(
    val id: String,
    @SerialName("object") val objectKind: String? = null,
    val created: Long? = null,
    @SerialName("owned_by") val ownedBy: String? = null,
    val metadata: ModelMetadata? = null,
)

@Serializable
data class ModelMetadata(
    val name: String? = null,
    @SerialName("context_window") val contextWindow: Int? = null,
    val desc: String? = null,
)

@Serializable
data class ModelListResponse(
    @SerialName("object") val objectKind: String? = null,
    val data: List<ModelEntry> = emptyList(),
)