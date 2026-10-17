package com.lxseek.hw2api.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * STS 签发的临时凭证（CAS 凭证，约 2h 寿命，与 refresh 链独立）。
 * 字段语义对齐 Python `_session_from_sts` 解析结果。
 */
@Serializable
data class Credentials(
    /** 访问密钥 ID（ak） */
    @SerialName("access_key_id") val accessKeyId: String,
    /** 访问密钥 Secret（sk，敏感，仅存加密存储） */
    @SerialName("secret_access_key") val secretAccessKey: String,
    /** 安全令牌（上游 X-Security-Token 头） */
    @SerialName("security_token") val securityToken: String,
    /** 凭证过期时刻（epoch 毫秒，UTC） */
    val expiration: Long,
    /** 过期时刻 ISO8601 原文（如 2026-10-16T07:27:35.696Z，展示用） */
    val expiresAt: String = "",
) {
    /** 是否已过期（与续期阈值判断解耦的纯过期判断） */
    fun isExpired(nowEpochMillis: Long): Boolean = expiration in 1..nowEpochMillis
}