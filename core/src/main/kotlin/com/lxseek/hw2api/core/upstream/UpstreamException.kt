package com.lxseek.hw2api.core.upstream

/**
 * 上游请求异常（语义对齐 Python 上游 4xx/5xx 透传 + 续期失败类型化）。
 * - [statusCode] HTTP 状态码（0 = 网络层失败）
 * - [responseBody] 响应体片段（截断保留，诊断用）
 * - [kind] 异常分类：401 且续期链死亡 → NEEDS_RELOGIN（UI 引导重新登录）
 */
class UpstreamException(
    val statusCode: Int,
    val responseBody: String,
    val kind: Kind,
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause) {

    enum class Kind {
        /** 普通 HTTP 错误（4xx/5xx，可透传状态码与详情给客户端） */
        HTTP_ERROR,
        /** 上游 401 且强制续期失败/重试仍 401：需要重新登录 */
        NEEDS_RELOGIN,
        /** 网络层失败（连接超时/DNS/TLS） */
        NETWORK,
    }

    /** 是否需要重新登录（app 层据此弹引导） */
    val needsRelogin: Boolean get() = kind == Kind.NEEDS_RELOGIN
}