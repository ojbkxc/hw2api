package com.lxseek.hw2api.core.model

/**
 * 模型表 + 别名表（写死，数值严格按 plan.md 附录 A.5，禁止改动）。
 * 别名解析双向覆盖：请求模型名 → 真实上游 ID。
 * 上游协议常量（URL 等）也集中在此（单点生效，见 plan.md R9）。
 */
object ModelRegistry {

    // ── 上游协议常量（附录 A）──
    const val UPSTREAM_CHAT_URL = "https://snap-access.cn-north-4.myhuaweicloud.com/api/v2/chat/completions"
    const val UPSTREAM_HOST = "snap-access.cn-north-4.myhuaweicloud.com"
    const val STS_TOKEN_URL = "https://sts.cn-north-4.myhuaweicloud.com/v1/oauth2/tokens"
    const val PORTAL_AUTHORIZE_URL = "https://codearts.huaweicloud.com/portal/authorize"
    const val CLIENT_ID = "codearts-agent"
    const val LOGIN_PLUGIN_NAME = "snap_AIIDE"
    const val EXTENSION_VERSION = "5.4.2"
    const val LOGIN_CALLBACK_PORT = 8789
    const val OAUTH_REDIRECT_PATH = "/oauth/callback"

    /** 完整 redirect_uri（登录换 token 表单用） */
    val OAUTH_REDIRECT_URI: String =
        "http://127.0.0.1:$LOGIN_CALLBACK_PORT$OAUTH_REDIRECT_PATH"

    // ── 模型表（LinkedHashMap 保序，/v1/models 按此顺序输出）──
    val MODELS: LinkedHashMap<String, ModelInfo> = linkedMapOf(
        "GLM-5.2" to ModelInfo(
            id = "GLM-5.2", name = "GLM-5.2", ctx = 202752, maxTokens = 65536,
            limitErr = 81027, desc = "旗舰模型，专为长程任务打造",
        ),
        "glm-5.2-sft-harmony" to ModelInfo(
            id = "glm-5.2-sft-harmony", name = "GLM-5.2-ArkTS-SPARK", ctx = 202752, maxTokens = 65536,
            limitErr = 81001, desc = "基于GLM-5.2增训鸿蒙代码与开发知识",
        ),
        "glm-5.3" to ModelInfo(
            id = "glm-5.3", name = "GLM-5.3", ctx = 202752, maxTokens = 65536,
            limitErr = 81001, desc = "最新旗舰模型", streamOnly = true,
        ),
        "openpangu-2.0-pro" to ModelInfo(
            id = "openpangu-2.0-pro", name = "OpenPangu-2.0-Pro", ctx = 524288, maxTokens = 65536,
            limitErr = 81001, desc = "最新旗舰模型，复杂工程稳定交付",
        ),
        "openpangu-2.0-flash" to ModelInfo(
            id = "openpangu-2.0-flash", name = "OpenPangu-2.0-Flash", ctx = 524288, maxTokens = 65536,
            limitErr = 81001, desc = "均衡推理效果与性能",
        ),
    )

    /** 别名 → 真实模型 ID（双向覆盖：别名表与模型表自身 ID 都可请求） */
    val MODEL_ALIAS: Map<String, String> = mapOf(
        "glm-5.2" to "GLM-5.2",
        "glm-5.2-harmony" to "glm-5.2-sft-harmony",
        "GLM-5.3" to "glm-5.3",
        "pangu-pro" to "openpangu-2.0-pro",
        "pangu-flash" to "openpangu-2.0-flash",
    )

    /** 模型名归一：别名解析（无别名映射则原样返回） */
    fun resolve(model: String): String = MODEL_ALIAS[model] ?: model

    /** 模型是否存在（归一后查表） */
    fun contains(model: String): Boolean = MODELS.containsKey(resolve(model))

    /** 归一后取模型信息；不存在返回 null */
    fun info(model: String): ModelInfo? = MODELS[resolve(model)]

    /** 是否仅流式模型 */
    fun isStreamOnly(model: String): Boolean = info(model)?.streamOnly == true
}