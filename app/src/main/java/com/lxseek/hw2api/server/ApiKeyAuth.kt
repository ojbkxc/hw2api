package com.lxseek.hw2api.server

import java.security.MessageDigest

/**
 * 可选 API Key 鉴权（plan.md §3.2 / 附录 A.7）：对齐 CODEARTS_PROXY_API_KEY 语义。
 * - key 为空 → 不鉴权（全部放行）
 * - `Authorization: Bearer {key}`（bearer 前缀不区分大小写）或 `X-Api-Key: {key}` 命中即放行
 * - 失败由调用方返回 401
 *
 * @param effectiveKey 生效口令（SettingsStore 已按「开关开且非空」归一后的值）
 */
class ApiKeyAuth(private val effectiveKey: String) {

    /** 是否启用鉴权 */
    fun isEnabled(): Boolean = effectiveKey.isNotEmpty()

    /** 常量时间口令比较（对齐 Python secrets.compare_digest，防时序侧信道） */
    private fun tokenMatches(token: String): Boolean =
        MessageDigest.isEqual(
            token.toByteArray(Charsets.UTF_8),
            effectiveKey.toByteArray(Charsets.UTF_8),
        )

    /** 校验请求头（NanoHTTPD 的 header 名已统一小写） */
    fun check(headers: Map<String, String>): Boolean {
        if (!isEnabled()) return true
        headers["authorization"]?.let { raw ->
            val lower = raw.lowercase()
            if (lower.startsWith("bearer ")) {
                if (tokenMatches(raw.substring(7).trim())) return true
            }
        }
        headers["x-api-key"]?.let {
            if (tokenMatches(it.trim())) return true
        }
        return false
    }
}