package com.lxseek.hw2api.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 模型表 + 别名表测试（逐项对照 plan.md 附录 A.5 与
 * codearts_proxy.py 顶部 MODELS / MODEL_ALIAS 常量表，数值禁止漂移）。
 */
class ModelRegistryTest {

    @Test
    fun modelTableHasExactlyFiveModelsInFixedOrder() {
        // LinkedHashMap 保序：/v1/models 输出顺序即插入顺序
        assertEquals(
            listOf(
                "GLM-5.2",
                "glm-5.2-sft-harmony",
                "glm-5.3",
                "openpangu-2.0-pro",
                "openpangu-2.0-flash",
            ),
            ModelRegistry.MODELS.keys.toList(),
        )
        assertEquals(5, ModelRegistry.MODELS.size)
    }

    @Test
    fun modelEntriesMatchPythonModelsTable() {
        // 逐项对照 codearts_proxy.py MODELS：id/name/ctx/max_tokens/limit_err/desc/stream_only
        val glm52 = ModelRegistry.MODELS["GLM-5.2"]!!
        assertEquals("GLM-5.2", glm52.id)
        assertEquals("GLM-5.2", glm52.name)
        assertEquals(202752, glm52.ctx)
        assertEquals(65536, glm52.maxTokens)
        assertEquals(81027, glm52.limitErr)
        assertEquals("旗舰模型，专为长程任务打造", glm52.desc)
        assertFalse(glm52.streamOnly)

        val harmony = ModelRegistry.MODELS["glm-5.2-sft-harmony"]!!
        assertEquals("GLM-5.2-ArkTS-SPARK", harmony.name)
        assertEquals(202752, harmony.ctx)
        assertEquals(65536, harmony.maxTokens)
        assertEquals(81001, harmony.limitErr)
        assertEquals("基于GLM-5.2增训鸿蒙代码与开发知识", harmony.desc)
        assertFalse(harmony.streamOnly)

        val glm53 = ModelRegistry.MODELS["glm-5.3"]!!
        assertEquals("GLM-5.3", glm53.name)
        assertEquals(202752, glm53.ctx)
        assertEquals(65536, glm53.maxTokens)
        assertEquals(81001, glm53.limitErr)
        assertEquals("最新旗舰模型", glm53.desc)
        // glm-5.3 仅流式（stream_only: True）
        assertTrue(glm53.streamOnly)

        val panguPro = ModelRegistry.MODELS["openpangu-2.0-pro"]!!
        assertEquals("OpenPangu-2.0-Pro", panguPro.name)
        assertEquals(524288, panguPro.ctx)
        assertEquals(65536, panguPro.maxTokens)
        assertEquals(81001, panguPro.limitErr)
        assertFalse(panguPro.streamOnly)

        val panguFlash = ModelRegistry.MODELS["openpangu-2.0-flash"]!!
        assertEquals("OpenPangu-2.0-Flash", panguFlash.name)
        assertEquals(524288, panguFlash.ctx)
        assertEquals(65536, panguFlash.maxTokens)
        assertEquals(81001, panguFlash.limitErr)
        assertFalse(panguFlash.streamOnly)
    }

    @Test
    fun modelTableMatchesAppendixA5Values() {
        // 附录 A.5 精简对照：id → (ctx, max_tokens, 仅流式)
        val expected = mapOf(
            "GLM-5.2" to (202752 to false),
            "glm-5.2-sft-harmony" to (202752 to false),
            "glm-5.3" to (202752 to true),
            "openpangu-2.0-pro" to (524288 to false),
            "openpangu-2.0-flash" to (524288 to false),
        )
        expected.forEach { (id, spec) ->
            val info = ModelRegistry.MODELS[id]
            assertNotNull("缺少模型 $id", info)
            assertEquals(spec.first, info!!.ctx)
            assertEquals(65536, info.maxTokens)
            assertEquals(spec.second, info.streamOnly)
        }
    }

    @Test
    fun aliasTableMatchesPythonModelAlias() {
        // 别名 → 真实模型 ID（codearts_proxy.py MODEL_ALIAS 逐项一致）
        val expected = mapOf(
            "glm-5.2" to "GLM-5.2",
            "glm-5.2-harmony" to "glm-5.2-sft-harmony",
            "GLM-5.3" to "glm-5.3",
            "pangu-pro" to "openpangu-2.0-pro",
            "pangu-flash" to "openpangu-2.0-flash",
        )
        assertEquals(expected, ModelRegistry.MODEL_ALIAS)
    }

    @Test
    fun resolveMapsAliasesAndPassesThroughRealIds() {
        // 双向覆盖：别名解析到真实 ID；真实 ID 原样返回
        ModelRegistry.MODEL_ALIAS.forEach { (alias, real) ->
            assertEquals(real, ModelRegistry.resolve(alias))
        }
        // 真实 ID（注意大小写：GLM-5.2 大写、glm-5.3 小写）原样
        ModelRegistry.MODELS.keys.forEach { id ->
            assertEquals(id, ModelRegistry.resolve(id))
        }
        // 未知模型名原样返回（由上层判断 404）
        assertEquals("unknown-model", ModelRegistry.resolve("unknown-model"))
    }

    @Test
    fun containsAcceptsBothAliasAndRealId() {
        ModelRegistry.MODELS.keys.forEach { id ->
            assertTrue("应包含真实 ID $id", ModelRegistry.contains(id))
        }
        ModelRegistry.MODEL_ALIAS.keys.forEach { alias ->
            assertTrue("应包含别名 $alias", ModelRegistry.contains(alias))
        }
        assertFalse(ModelRegistry.contains("gpt-4"))
        assertFalse(ModelRegistry.contains("glm-5.2 ")) // 空格变体不算
    }

    @Test
    fun infoReturnsModelInfoAfterAliasResolution() {
        // 归一后取信息：别名与真实 ID 拿到同一 ModelInfo
        assertEquals(
            ModelRegistry.info("GLM-5.2"),
            ModelRegistry.info("glm-5.2"),
        )
        assertEquals("glm-5.3", ModelRegistry.info("GLM-5.3")!!.id)
        assertNull(ModelRegistry.info("no-such-model"))
    }

    @Test
    fun isStreamOnlyOnlyForGlm53() {
        // glm-5.3（含别名 GLM-5.3）仅流式；其余模型流式+非流式
        assertTrue(ModelRegistry.isStreamOnly("glm-5.3"))
        assertTrue(ModelRegistry.isStreamOnly("GLM-5.3"))
        assertFalse(ModelRegistry.isStreamOnly("GLM-5.2"))
        assertFalse(ModelRegistry.isStreamOnly("glm-5.2"))
        assertFalse(ModelRegistry.isStreamOnly("glm-5.2-harmony"))
        assertFalse(ModelRegistry.isStreamOnly("pangu-pro"))
        assertFalse(ModelRegistry.isStreamOnly("pangu-flash"))
        assertFalse(ModelRegistry.isStreamOnly("openpangu-2.0-pro"))
        assertFalse(ModelRegistry.isStreamOnly("openpangu-2.0-flash"))
        assertFalse(ModelRegistry.isStreamOnly("unknown-model"))
    }

    @Test
    fun upstreamConstantsMatchAppendixA() {
        // 上游协议常量（附录 A / Python 常量定义）：单点集中，禁止漂移
        assertEquals(
            "https://snap-access.cn-north-4.myhuaweicloud.com/api/v2/chat/completions",
            ModelRegistry.UPSTREAM_CHAT_URL,
        )
        assertEquals("snap-access.cn-north-4.myhuaweicloud.com", ModelRegistry.UPSTREAM_HOST)
        assertEquals(
            "https://sts.cn-north-4.myhuaweicloud.com/v1/oauth2/tokens",
            ModelRegistry.STS_TOKEN_URL,
        )
        assertEquals(
            "https://codearts.huaweicloud.com/portal/authorize",
            ModelRegistry.PORTAL_AUTHORIZE_URL,
        )
        assertEquals("codearts-agent", ModelRegistry.CLIENT_ID)
        assertEquals("snap_AIIDE", ModelRegistry.LOGIN_PLUGIN_NAME)
        assertEquals("5.4.2", ModelRegistry.EXTENSION_VERSION)
        assertEquals(8789, ModelRegistry.LOGIN_CALLBACK_PORT)
        assertEquals("/oauth/callback", ModelRegistry.OAUTH_REDIRECT_PATH)
        assertEquals(
            "http://127.0.0.1:8789/oauth/callback",
            ModelRegistry.OAUTH_REDIRECT_URI,
        )
    }
}