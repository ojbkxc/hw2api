package com.lxseek.hw2api.core.auth

/**
 * STS OAuth 端点异常（语义对齐 Python `_token_request` 的 RuntimeError + 响应体保留）。
 * 保留 HTTP 状态码与响应体片段（error_code/error_msg，如 STS5.1806 invalid refresh token）供诊断。
 */
class StsException(
    val statusCode: Int,
    val responseBody: String,
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause) {

    /** refresh_token 链失效（典型 STS5.1806 invalid refresh token）：需要重新登录 */
    val isRefreshChainDead: Boolean
        get() = statusCode == 400 && responseBody.contains("STS.5.1806", ignoreCase = true)
}