package com.lxseek.hw2api.core.upstream

import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream

/**
 * SSE 聚合器测试（语义一比一对拍 codearts_proxy.py::aggregate_sse）。
 *
 * 用构造的 SSE 帧序列（含 [DONE]、注释行、多行 data、错误帧）驱动：
 * - readFrames：data 行合并 / [DONE] 跳过 / 注释与 event 行忽略 / 截断兜底
 * - aggregate：chunk 聚合（content/reasoning/finish/usage）、单帧透传、错误帧上抛、空输入 null
 */
class SseAggregatorTest {

    // ── readFrames（SSE 文本解析）──

    @Test
    fun readFramesParsesDataLinesAndSkipsDoneAndComments() {
        val sseText = buildString {
            append("data: {\"a\":1}\n")
            append("\n")
            append(": heartbeat comment\n")
            append("\n")
            append("event: message\n")
            append("id: 42\n")
            append("data: frame-two\n")
            append("\n")
            append("data: [DONE]\n")
            append("\n")
        }
        val frames = SseAggregator.readFrames(ByteArrayInputStream(sseText.toByteArray(Charsets.UTF_8)))
        // [DONE] 与注释/event/id 行不参与聚合
        assertEquals(listOf("""{"a":1}""", "frame-two"), frames)
    }

    @Test
    fun readFramesJoinsMultiLineDataWithNewline() {
        // 同一事件的多个 data: 行以 \n 合并（SSE 规范）
        val sseText = "data: line1\ndata: line2\n\n"
        val frames = SseAggregator.readFrames(ByteArrayInputStream(sseText.toByteArray(Charsets.UTF_8)))
        assertEquals(listOf("line1\nline2"), frames)
    }

    @Test
    fun readFramesFlushesTrailingBufferWithoutFinalBlankLine() {
        // 流截断兜底：末帧没有空行结束时缓冲区残留仍应作为一帧
        val sseText = "data: tail-frame\n" // 无结尾空行
        val frames = SseAggregator.readFrames(ByteArrayInputStream(sseText.toByteArray(Charsets.UTF_8)))
        assertEquals(listOf("tail-frame"), frames)
    }

    @Test
    fun readFramesHandlesDataWithoutSpace() {
        // "data:值"（无空格）与 "data: 值"（有空格）等价（removePrefix + trim）
        val sseText = "data:nospace\n\ndata: with-space\n\n"
        val frames = SseAggregator.readFrames(ByteArrayInputStream(sseText.toByteArray(Charsets.UTF_8)))
        assertEquals(listOf("nospace", "with-space"), frames)
    }

    @Test
    fun readFramesReturnsEmptyForBareDoneOnly() {
        val frames = SseAggregator.readFrames(
            ByteArrayInputStream("data: [DONE]\n\n".toByteArray(Charsets.UTF_8)),
        )
        assertEquals(0, frames.size)
    }

    // ── aggregate：多帧 chunk 聚合 ──

    /** 构造 chat.completion.chunk 帧（finishReason 非空时写入 choices[0].finish_reason） */
    private fun chunk(delta: String, finishReason: String? = null): String {
        // 注意：普通字符串构造 finish（raw string 中 \" 是字面反斜杠，会污染 JSON）
        val finish = if (finishReason != null) ",\"finish_reason\":\"$finishReason\"" else ""
        return """{"id":"chatcmpl-test123","object":"chat.completion.chunk",""" +
            """"created":1700000000,"model":"glm-5.3",""" +
            """"choices":[{"index":0,"delta":$delta$finish}]}"""
    }

    @Test
    fun aggregateJoinsContentAndCarriesUsageAndFinishReason() {
        // 三帧 content 增量 + finish + usage → 完整 chat.completion（Python aggregate_sse 语义）
        val frames = listOf(
            chunk("""{"role":"assistant","content":"你好"}"""),
            chunk("""{"content":"，世界"}"""),
            chunk("""{"content":"！"}""", finishReason = "stop"),
        )
        val result = SseAggregator.aggregate(frames)!!
        assertEquals("chatcmpl-test123", result["id"]!!.jsonPrimitive.content)
        assertEquals("chat.completion", result["object"]!!.jsonPrimitive.content)
        assertEquals(1700000000L, result["created"]!!.jsonPrimitive.content.toLong())
        assertEquals("glm-5.3", result["model"]!!.jsonPrimitive.content)
        val choice = result["choices"]!!.jsonArray[0].jsonObject
        assertEquals(0, choice["index"]!!.jsonPrimitive.content.toInt())
        assertEquals("stop", choice["finish_reason"]!!.jsonPrimitive.content)
        val message = choice["message"]!!.jsonObject
        assertEquals("assistant", message["role"]!!.jsonPrimitive.content)
        // content 拼接顺序：按帧顺序连接
        assertEquals("你好，世界！", message["content"]!!.jsonPrimitive.content)
        // 无 usage 帧时不输出 usage 键
        assertNull(result["usage"])
    }

    @Test
    fun aggregatePicksUpUsageFromFinalChunk() {
        // usage 通常在最后一帧携带：透传原对象
        val frames = listOf(
            chunk("""{"content":"a"}"""),
            """{"id":"chatcmpl-test123","object":"chat.completion.chunk","created":1700000000,""" +
                """"model":"glm-5.3","choices":[{"index":0,"delta":{},"finish_reason":"stop"}],""" +
                """"usage":{"prompt_tokens":10,"completion_tokens":5,"total_tokens":15}}""",
        )
        val result = SseAggregator.aggregate(frames)!!
        val usage = result["usage"]!!.jsonObject
        assertEquals(10, usage["prompt_tokens"]!!.jsonPrimitive.content.toInt())
        assertEquals(5, usage["completion_tokens"]!!.jsonPrimitive.content.toInt())
        assertEquals(15, usage["total_tokens"]!!.jsonPrimitive.content.toInt())
    }

    @Test
    fun aggregateJoinsReasoningContent() {
        // reasoning_content 增量拼接（思考模型场景）
        val frames = listOf(
            chunk("""{"role":"assistant","reasoning_content":"想"}"""),
            chunk("""{"reasoning_content":"一想"}"""),
            chunk("""{"content":"答"}""", finishReason = "stop"),
        )
        val result = SseAggregator.aggregate(frames)!!
        val message = result["choices"]!!.jsonArray[0].jsonObject["message"]!!.jsonObject
        assertEquals("想一想", message["reasoning_content"]!!.jsonPrimitive.content)
        assertEquals("答", message["content"]!!.jsonPrimitive.content)
    }

    @Test
    fun aggregateCollectsToolCallsFromSingleFrame() {
        // 单帧完整 tool_calls（id/type/function 齐全，无增量拼接）
        val frames = listOf(
            chunk(
                """{"role":"assistant","tool_calls":[{"index":0,"id":"call_1","type":"function",
                    "function":{"name":"get_weather","arguments":"{\"city\":\"深圳\"}"}}]}""",
                finishReason = "tool_calls",
            ),
        )
        val result = SseAggregator.aggregate(frames)!!
        val message = result["choices"]!!.jsonArray[0].jsonObject["message"]!!.jsonObject
        val toolCalls = message["tool_calls"]!!.jsonArray
        assertEquals(1, toolCalls.size)
        val tc = toolCalls[0].jsonObject
        assertEquals("call_1", tc["id"]!!.jsonPrimitive.content)
        assertEquals("function", tc["type"]!!.jsonPrimitive.content)
        val fn = tc["function"]!!.jsonObject
        assertEquals("get_weather", fn["name"]!!.jsonPrimitive.content)
        assertTrue(fn["arguments"]!!.jsonPrimitive.content.contains("深圳"))
    }

    @Test
    fun aggregateDefaultsFinishReasonAndId() {
        // 缺省：finish_reason → "stop"；content 为空 → null
        val frames = listOf(
            chunk("""{"role":"assistant"}"""),
        )
        val result = SseAggregator.aggregate(frames)!!
        val choice = result["choices"]!!.jsonArray[0].jsonObject
        assertEquals("stop", choice["finish_reason"]!!.jsonPrimitive.content)
        val message = choice["message"]!!.jsonObject
        assertTrue(message["content"]!!.toString() == "null") // JsonNull 字面量
    }

    @Test
    fun aggregateUsesModelIdFallbackWhenChunkHasNoModel() {
        // chunk 无 model 字段：回退调用方传入的 modelId
        val frames = listOf(
            """{"id":"chatcmpl-x","object":"chat.completion.chunk","created":1700000000,
            "choices":[{"index":0,"delta":{"content":"hi"}}]}""",
        )
        val result = SseAggregator.aggregate(frames, modelId = "fallback-model")!!
        assertEquals("fallback-model", result["model"]!!.jsonPrimitive.content)
    }

    // ── aggregate：形态 1（单帧完整响应透传）──

    @Test
    fun aggregatePassesThroughSingleCompletionFrame() {
        // 首帧即完整 chat.completion（上游非流式伪流式）→ 原样返回
        val completion =
            """{"id":"chatcmpl-full","object":"chat.completion","created":1700000000,""" +
                """"model":"GLM-5.2","choices":[{"index":0,"message":{"role":"assistant",""" +
                """"content":"整包"},"finish_reason":"stop"}],
                "usage":{"prompt_tokens":1,"completion_tokens":1,"total_tokens":2}}"""
        val result = SseAggregator.aggregate(listOf(completion))!!
        assertEquals("chatcmpl-full", result["id"]!!.jsonPrimitive.content)
        assertEquals("chat.completion", result["object"]!!.jsonPrimitive.content)
        assertEquals(
            "整包",
            result["choices"]!!.jsonArray[0].jsonObject["message"]!!.jsonObject
                .let { msg -> msg["content"]!!.jsonPrimitive.content },
        )
    }

    // ── aggregate：形态 3（上游错误帧透传）──

    @Test
    fun aggregateWrapsErrorCodeFrame() {
        // {"error_code":...} 帧 → {"error": 原帧} 上抛给调用方
        val frames = listOf(
            chunk("""{"content":"partial"}"""), // 错误可出现在任意位置
            """{"error_code":413,"error_msg":"input tokens exceed hard limit"}""",
        )
        val result = SseAggregator.aggregate(frames)!!
        val error = result["error"]!!.jsonObject
        assertEquals(413, error["error_code"]!!.jsonPrimitive.content.toInt())
        assertTrue(error["error_msg"]!!.jsonPrimitive.content.contains("limit"))
    }

    @Test
    fun aggregateWrapsNestedErrorObjectFrame() {
        // {"error":{...}} 嵌套形态同样透传
        val frames = listOf(
            """{"error":{"code":"invalid_request","message":"bad model"}}""",
        )
        val result = SseAggregator.aggregate(frames)!!
        val error = result["error"]!!.jsonObject
        assertEquals("invalid_request", error["error"]!!.jsonObject["code"]!!.jsonPrimitive.content)
    }

    // ── aggregate：边界 ──

    @Test
    fun aggregateReturnsNullForEmptyFrames() {
        assertNull(SseAggregator.aggregate(emptyList()))
    }

    @Test
    fun aggregateReturnsNullWhenNoValidChunk() {
        // 全部是非 JSON 文本（如空行/垃圾行）：无有效帧 → null
        assertNull(SseAggregator.aggregate(listOf("   ", "not-json")))
    }

    @Test
    fun aggregateSkipsNonChunkJsonFrames() {
        // 混入的无关 JSON（object 不是 chat.completion.chunk）被跳过，不影响聚合
        val frames = listOf(
            """{"object":"other.event","payload":1}""",
            chunk("""{"content":"ok"}""", finishReason = "stop"),
        )
        val result = SseAggregator.aggregate(frames)!!
        assertEquals(
            "ok",
            result["choices"]!!.jsonArray[0].jsonObject["message"]!!.jsonObject
                .let { it["content"]!!.jsonPrimitive.content },
        )
    }

    // ── aggregateStream（输入流一步聚合）──

    @Test
    fun aggregateStreamEndToEnd() {
        val sseText = buildString {
            append("data: ")
            append(chunk("""{"role":"assistant","content":"流式"}"""))
            append("\n\n")
            append("data: ")
            append(chunk("""{"content":"聚合"}""", finishReason = "stop"))
            append("\n\n")
            append("data: [DONE]\n\n")
        }
        val result = SseAggregator.aggregateStream(
            ByteArrayInputStream(sseText.toByteArray(Charsets.UTF_8)),
            modelId = "glm-5.3",
        )!!
        assertEquals("chat.completion", result["object"]!!.jsonPrimitive.content)
        assertEquals(
            "流式聚合",
            result["choices"]!!.jsonArray[0].jsonObject["message"]!!.jsonObject
                .let { it["content"]!!.jsonPrimitive.content },
        )
        assertEquals("glm-5.3", result["model"]!!.jsonPrimitive.content)
    }

    @Test
    fun aggregateStreamEmptyInputReturnsNull() {
        assertNull(SseAggregator.aggregateStream(ByteArrayInputStream(ByteArray(0))))
    }
}