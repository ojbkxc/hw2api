package com.lxseek.hw2api.core.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Account / Credentials 序列化测试（附录 A.6 数据模型，
 * 对拍 Python session dict 落库字段的 Kotlin 镜像）。
 *
 * Account 顶层 8 字段 + Credentials 5 字段（snake_case）全量 round-trip；
 * 存储格式（account_{id} → JSON）依赖此稳定性，字段名漂移 = 旧数据不可读。
 */
class AccountDtosTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun sampleAccount(): Account = Account(
        accountId = "acc-0001",
        label = "hw-test-account",
        credentials = Credentials(
            accessKeyId = "AKTEST123",
            secretAccessKey = "SKTEST456",
            securityToken = "TOKEN789",
            expiration = 1792135655696L,
            expiresAt = "2026-10-16T07:27:35.696Z",
        ),
        refreshToken = "RT_ROTATED_1",
        pkceVerifier = "ab".repeat(64),
        dpopPrivateKeyJwk = buildJsonObject {
            put("kty", "EC")
            put("crv", "P-256")
            put("x", "QfZI-TM8DKDAXqESNxVLzppy1D7RFSeAUPLpMtU5GXw")
            put("y", "n8nq-_gIe-q2S49hnJDudXR0VMe8licGF9e-V1riisM")
            put("d", "ESIzRFVmd4gRIjNEVWZ3iBEiM0RVZneIESIzRFVmd4g")
        },
        xOtSessionId = "0123456789abcdef0123456789abcdef",
        createdAt = 1700000000000L,
    )

    @Test
    fun accountRoundTripsThroughJson() {
        // 全字段 JSON round-trip：encode → decode 后 data class 相等
        val account = sampleAccount()
        val encoded = json.encodeToString(Account.serializer(), account)
        val decoded = json.decodeFromString(Account.serializer(), encoded)
        assertEquals(account, decoded)
    }

    @Test
    fun accountSerializesAllElevenLeafFields() {
        // 序列化输出键名逐项对照（顶层 8 + credentials 5，含 @SerialName 映射）
        val encoded = json.encodeToString(Account.serializer(), sampleAccount())
        val root = json.parseToJsonElement(encoded).jsonObject
        assertEquals(
            setOf(
                "accountId", "label", "credentials", "refreshToken", "pkceVerifier",
                "dpopPrivateKeyJwk", "xOtSessionId", "createdAt",
            ),
            root.keys,
        )
        val creds = root["credentials"]!!.jsonObject
        assertEquals(
            setOf("access_key_id", "secret_access_key", "security_token", "expiration", "expiresAt"),
            creds.keys,
        )
        assertEquals("acc-0001", root["accountId"]!!.jsonPrimitive.content)
        assertEquals("RT_ROTATED_1", root["refreshToken"]!!.jsonPrimitive.content)
        assertEquals("ab".repeat(64), root["pkceVerifier"]!!.jsonPrimitive.content)
        assertEquals(1700000000000L, root["createdAt"]!!.jsonPrimitive.content.toLong())
        // 私钥 JWK 原样嵌入（含 d）
        val jwk = root["dpopPrivateKeyJwk"]!!.jsonObject
        assertEquals(setOf("kty", "crv", "x", "y", "d"), jwk.keys)
        // Credentials snake_case 映射
        assertEquals("AKTEST123", creds["access_key_id"]!!.jsonPrimitive.content)
        assertEquals("SKTEST456", creds["secret_access_key"]!!.jsonPrimitive.content)
        assertEquals("TOKEN789", creds["security_token"]!!.jsonPrimitive.content)
        assertEquals(1792135655696L, creds["expiration"]!!.jsonPrimitive.content.toLong())
        assertEquals("2026-10-16T07:27:35.696Z", creds["expiresAt"]!!.jsonPrimitive.content)
    }

    @Test
    fun accountIgnoresUnknownJsonFields() {
        // 存储格式前向兼容：未知字段不导致解码失败（老版本写入的新字段）
        val encoded = json.encodeToString(Account.serializer(), sampleAccount())
        val withExtra = encoded.dropLast(1) + ",\"unknownField\":123,\"future\":{\"a\":[1]}"
        val decoded = json.decodeFromString(Account.serializer(), withExtra)
        assertEquals(sampleAccount(), decoded)
    }

    @Test
    fun accountCopyPreservesRefreshChainMaterial() {
        // 续期回写场景：copy 更新凭证/refreshToken 后，pkceVerifier 与私钥 JWK 必须保持
        val old = sampleAccount()
        val renewed = old.copy(
            credentials = old.credentials.copy(
                accessKeyId = "AKNEW",
                expiration = 1792142855696L,
            ),
            refreshToken = "RT_ROTATED_2",
        )
        assertEquals(old.pkceVerifier, renewed.pkceVerifier)
        assertEquals(old.dpopPrivateKeyJwk, renewed.dpopPrivateKeyJwk)
        assertEquals(old.xOtSessionId, renewed.xOtSessionId)
        assertEquals(old.accountId, renewed.accountId)
        assertEquals("RT_ROTATED_2", renewed.refreshToken)
        assertEquals("AKNEW", renewed.credentials.accessKeyId)
    }

    @Test
    fun credentialsRoundTripAndDefaults() {
        // expiresAt 有默认值 ""：缺省解码与显式空串一致
        val minimal = """{"access_key_id":"AK","secret_access_key":"SK","security_token":"T","expiration":100}"""
        val creds = json.decodeFromString(Credentials.serializer(), minimal)
        assertEquals("", creds.expiresAt)
        val encoded = json.encodeToString(Credentials.serializer(), creds)
        assertTrue(encoded.contains("\"expiresAt\":\"\""))
    }

    @Test
    fun credentialsIsExpiredSemantics() {
        // isExpired(now)：expiration in 1..now（有值且已过才 true；0 视为未知不过期）
        val creds = Credentials("AK", "SK", "T", expiration = 1000L)
        assertTrue(creds.isExpired(1000L)) // 恰好在过期时刻：已过期
        assertTrue(creds.isExpired(2000L))
        assertFalse(creds.isExpired(999L))
        val unknown = Credentials("AK", "SK", "T", expiration = 0L)
        assertFalse(unknown.isExpired(Long.MAX_VALUE))
    }

    @Test
    fun accountFromStsGeneratesFixedSessionId() {
        // fromSts 工厂：xOtSessionId 登录时生成一次（32 hex），其余字段透传
        val account = Account.fromSts(
            accountId = "acc-2",
            label = "label",
            credentials = Credentials("AK", "SK", "T", 100L),
            refreshToken = "RT",
            pkceVerifier = "v".repeat(128),
            dpopPrivateKeyJwk = buildJsonObject { put("kty", "EC") },
            createdAt = 42L,
        )
        assertEquals(32, account.xOtSessionId.length)
        assertTrue(account.xOtSessionId.matches(Regex("^[0-9a-f]{32}$")))
        assertEquals("acc-2", account.accountId)
        assertEquals(42L, account.createdAt)
    }
}