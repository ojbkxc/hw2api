package com.lxseek.hw2api.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * 多账号兼容数据模型（plan.md 附录 A.6 全部字段）。
 * 扁平结构：凭证、refresh 链材料（pkceVerifier/dpopPrivateKeyJwk）、会话标识（xOtSessionId）
 * 全部落在同一可序列化对象，整体 JSON 按 `account_{id}` 落库。
 * 续期必须复用 pkceVerifier 与 dpopPrivateKeyJwk（refresh_token rotation 后链仍完整）。
 */
@Serializable
data class Account(
    /** 账号唯一标识（落库 key = account_{accountId}） */
    @SerialName("accountId") val accountId: String,
    /** 展示标签（STS 登录响应 account.label，为空时由 app 层回退 AK 前 8 位） */
    val label: String,
    /** CAS 凭证（ak/sk/securityToken/expiration） */
    val credentials: Credentials,
    /** refresh_token（rotation：每次续期后整体替换） */
    @SerialName("refreshToken") val refreshToken: String,
    /** 原 PKCE code_verifier（refresh 请求复用，敏感） */
    @SerialName("pkceVerifier") val pkceVerifier: String,
    /** DPoP 私钥 JWK（含 d，敏感；公钥可推导恢复） */
    @SerialName("dpopPrivateKeyJwk") val dpopPrivateKeyJwk: JsonObject,
    /** 上游 x-ot-session-id（登录时生成一次固定，上游按其计并发会话数） */
    @SerialName("xOtSessionId") val xOtSessionId: String,
    /** 账号入池时间（epoch 毫秒） */
    @SerialName("createdAt") val createdAt: Long,
) {
    companion object {
        /** 生成新账号（登录流程换取凭证后组装） */
        fun fromSts(
            accountId: String,
            label: String,
            credentials: Credentials,
            refreshToken: String,
            pkceVerifier: String,
            dpopPrivateKeyJwk: JsonObject,
            createdAt: Long,
        ): Account = Account(
            accountId = accountId,
            label = label,
            credentials = credentials,
            refreshToken = refreshToken,
            pkceVerifier = pkceVerifier,
            dpopPrivateKeyJwk = dpopPrivateKeyJwk,
            // x-ot-session-id 登录时生成一次，此后固定复用
            xOtSessionId = com.lxseek.hw2api.core.util.RandomHex.next32(),
            createdAt = createdAt,
        )
    }
}