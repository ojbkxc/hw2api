package com.lxseek.hw2api.core.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * OpenAI 兼容协议 DTO 序列化测试（ChatDtos.kt）。
 * 重点：@SerialName("object") 关键字字段映射、snake_case 参数名、
 * 未知字段宽松解析（上游协议漂移不炸）、content 双形态承载。
 */
class ChatDtosTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun chatCompletionResponseMapsObjectKeywordField() {
        // "object" 是 Kotlin 关键字：经 @SerialName 映射到 objectKind
        val resp = json.decodeFromString(
            ChatCompletionResponse.serializer(),
            """
            {"id":"chatcmpl-abc","object":"chat.completion","created":1700000000,
            "model":"GLM-5.2","choices":[{"index":0,"message":{"role":"assistant",
            "content":"hi"},"finish_reason":"stop"}],
            "usage":{"prompt_tokens":1,"completion_tokens":2,"total_tokens":3}}
            """.trimIndent(),
        )
        assertEquals("chatcmpl-abc", resp.id)
        assertEquals("chat.completion", resp.objectKind)
        assertEquals(1700000000L, resp.created)
        assertEquals("GLM-5.2", resp.model)
        assertEquals(1, resp.choices.size)
        assertEquals("assistant", resp.choices[0].message!!.role)
        assertEquals("hi", resp.choices[0].message!!.content)
        assertEquals("stop", resp.choices[0].finishReason)
        assertEquals(3, resp.usage!!.totalTokens)
    }

    @Test
    fun chatCompletionResponseRoundTrip() {
        val resp = ChatCompletionResponse(
            id = "chatcmpl-1",
            objectKind = "chat.completion",
            created = 1700000000L,
            model = "glm-5.3",
            choices = listOf(
                ChatChoice(
                    index = 0,
                    message = ChatResponseMessage(
                        role = "assistant",
                        content = "答案",
                        reasoningContent = "思考",
                    ),
                    finishReason = "stop",
                ),
            ),
            usage = ChatUsage(promptTokens = 5, completionTokens = 6, totalTokens = 11),
        )
        val encoded = json.encodeToString(ChatCompletionResponse.serializer(), resp)
        val decoded = json.decodeFromString(ChatCompletionResponse.serializer(), encoded)
        assertEquals(resp, decoded)
        // 序列化键名回写为 "object"
        assertTrue(encoded.contains("\"object\":\"chat.completion\""))
    }

    @Test
    fun sseChunkUsesDeltaFieldOfSameDto() {
        // SSE chunk 复用 ChatCompletionResponse（object = chat.completion.chunk，delta 承载增量）
        val chunk = json.decodeFromString(
            ChatCompletionResponse.serializer(),
            """
            {"id":"chatcmpl-1","object":"chat.completion.chunk","created":1700000000,
            "model":"glm-5.3","choices":[{"index":0,"delta":{"role":"assistant",
            "content":"片段"},"finish_reason":null}]}
            """.trimIndent(),
        )
        assertEquals("chat.completion.chunk", chunk.objectKind)
        assertNull(chunk.choices[0].message)
        assertEquals("assistant", chunk.choices[0].delta!!.role)
        assertEquals("片段", chunk.choices[0].delta!!.content)
        assertNull(chunk.choices[0].finishReason)
    }

    @Test
    fun chatRequestMapsSnakeCaseParameterNames() {
        // 请求侧 @SerialName：max_tokens / top_p / tool_choice / reasoning_effort
        val req = json.decodeFromString(
            ChatCompletionRequest.serializer(),
            """
            {"model":"glm-5.2","messages":[{"role":"user","content":"你好"}],
            "stream":true,"max_tokens":100,"temperature":0.7,"top_p":0.9,
            "reasoning_effort":"high","unknown_param":"x"}
            """.trimIndent(),
        )
        assertEquals("glm-5.2", req.model)
        assertEquals(true, req.stream)
        assertEquals(100, req.maxTokens)
        assertEquals(0.7, req.temperature!!, 1e-9)
        assertEquals(0.9, req.topP!!, 1e-9)
        assertEquals("high", req.reasoningEffort)
        assertEquals(1, req.messages.size)
    }

    @Test
    fun chatRequestEncodesSnakeCaseOnOutput() {
        // 反向序列化：Kotlin 属性名 → OpenAI 参数名
        val req = ChatCompletionRequest(
            model = "pangu-pro",
            messages = listOf(ChatMessage(role = "user", content = null)),
            stream = false,
            maxTokens = 8,
            topP = 0.5,
        )
        val encoded = json.encodeToString(ChatCompletionRequest.serializer(), req)
        assertTrue(encoded.contains("\"max_tokens\":8"))
        assertTrue(encoded.contains("\"top_p\":0.5"))
    }

    @Test
    fun chatMessageContentSupportsStringAndPartsArray() {
        // content 双形态：字符串 / 多模态 parts 数组（JsonElement 承载）
        val msgs = json.decodeFromString(
            kotlinx.serialization.builtins.ListSerializer(ChatMessage.serializer()),
            """
            [{"role":"user","content":"纯文本"},
             {"role":"user","content":[{"type":"text","text":"分片1"},
                                       {"type":"image_url","image_url":{"url":"http://x"}}]}]
            """.trimIndent(),
        )
        assertEquals(2, msgs.size)
        assertEquals("纯文本", msgs[0].content!!.jsonPrimitive.content)
        // parts 数组：保持 JsonArray 结构不丢
        val parts = msgs[1].content!!
        assertTrue(parts.toString().contains("image_url"))
        assertTrue(parts.toString().contains("分片1"))
    }

    @Test
    fun chatMessageCarriesToolCallsAndToolCallId() {
        // assistant tool_calls / tool 消息回填
        val msg = json.decodeFromString(
            ChatMessage.serializer(),
            """
            {"role":"assistant","content":null,"tool_calls":[
            {"id":"call_1","type":"function","index":0,
            "function":{"name":"get_weather","arguments":"{\"city\":\"深圳\"}"}}]}
            """.trimIndent(),
        )
        assertEquals("call_1", msg.toolCalls!![0].id)
        assertEquals("function", msg.toolCalls!![0].type)
        assertEquals(0, msg.toolCalls!![0].index)
        assertEquals("get_weather", msg.toolCalls!![0].function!!.name)
        assertTrue(msg.toolCalls!![0].function!!.arguments!!.contains("city"))

        val toolMsg = json.decodeFromString(
            ChatMessage.serializer(),
            """{"role":"tool","content":"25℃","tool_call_id":"call_1"}""",
        )
        assertEquals("tool", toolMsg.role)
        assertEquals("call_1", toolMsg.toolCallId)
    }

    @Test
    fun modelEntryMapsObjectAndOwnedBy() {
        // /v1/models 列表条目：object / owned_by 映射
        val entry = json.decodeFromString(
            ModelEntry.serializer(),
            """
            {"id":"GLM-5.2","object":"model","created":1700000000,
            "owned_by":"hw2api","metadata":{"name":"GLM-5.2",
            "context_window":202752,"desc":"旗舰模型，专为长程任务打造"}}
            """.trimIndent(),
        )
        assertEquals("model", entry.objectKind)
        assertEquals("hw2api", entry.ownedBy)
        assertEquals(202752, entry.metadata!!.contextWindow)
        val list = json.decodeFromString(
            ModelListResponse.serializer(),
            """{"object":"list","data":[]}""",
        )
        assertEquals("list", list.objectKind)
        assertEquals(0, list.data.size)
    }

    @Test
    fun chatUsageMapsSnakeCaseTokenFields() {
        val usage = json.decodeFromString(
            ChatUsage.serializer(),
            """{"prompt_tokens":10,"completion_tokens":20,"total_tokens":30}""",
        )
        assertEquals(10, usage.promptTokens)
        assertEquals(20, usage.completionTokens)
        assertEquals(30, usage.totalTokens)
    }
}