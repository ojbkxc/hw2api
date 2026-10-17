package com.lxseek.hw2api.core.model

/**
 * 模型表条目（数值严格按 plan.md 附录 A.5 / Python MODELS 表）。
 */
data class ModelInfo(
    /** 上游真实模型 ID（大小写敏感，请求上游用这个值） */
    val id: String,
    /** 对外展示名 */
    val name: String,
    /** context 窗口（/v1/models 暴露） */
    val ctx: Int,
    /** max_tokens 参数上游上限（超限钳制） */
    val maxTokens: Int,
    /** 上游硬限错误码（input+output 超限时上游返回） */
    val limitErr: Int,
    /** 描述 */
    val desc: String,
    /** 上游仅支持流式路由（非流式请求需代理内部转流式再聚合） */
    val streamOnly: Boolean = false,
)