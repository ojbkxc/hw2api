package com.lxseek.hw2api.core.auth

import com.lxseek.hw2api.core.model.Account
import com.lxseek.hw2api.core.model.Credentials
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull

import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/**
 * STS OAuth 客户端测试（语义对拍 codearts_proxy.py::
 * _token_request / _exchange_code / _session_from_sts / _refresh_session）。
 *
 * 不触真网络：请求打向 MockWebServer 本地回环（plan.md 6.3-4.4 允许的唯一网络形态），
 * 断言表单五字段、Content-Type、DPoP 头存在、响应解析（毫秒 expiration、refresh_token 轮换）。
 * golden 值来源："2026-10-16T07:27:35.696Z" → epoch 毫秒 1792135655696
 * （Python `datetime.strptime(...).replace(tzinfo=utc)` + timegm 精确推导）。
 */
class StsClientTest {

    private lateinit var server: MockWebServer
    private lateinit var client: StsClient

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        client = StsClient(OkHttpClient(), server.url("/v1/oauth2/tokens").toString())
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    // ── 测试夹具 ──

    /** 登录成功的 STS 响应（含 account.label 与 refresh_token） */
    private fun enqueueTokenOk(): MockResponse = MockResponse()
        .setResponseCode(200)
        .setHeader("Content-Type", "application/json")
        .setBody(
            """
            {"credentials":{"access_key_id":"AKTEST123","secret_access_key":"SKTEST456",
            "security_token":"TOKEN789","expiration":"2026-10-16T07:27:35.696Z"},
            "refresh_token":"RT_NEW_1","account":{"label":"hw-test-account"}}
            """.trimIndent(),
        )
        .also { server.enqueue(it) }

    /** 续期成功的 STS 响应（无 account.label，refresh_token 已轮换） */
    private fun enqueueRefreshOk(): MockResponse = MockResponse()
        .setResponseCode(200)
        .setHeader("Content-Type", "application/json")
        .setBody(
            """
            {"credentials":{"access_key_id":"AKNEW789","secret_access_key":"SKNEW012",
            "security_token":"TOKEN345","expiration":"2026-10-16T09:27:35.696Z"},
            "refresh_token":"RT_NEW_2"}
            """.trimIndent(),
        )
        .also { server.enqueue(it) }

    /** 一次性登录上下文（PKCE pair + DPoP 密钥对） */
    private fun loginContext(): StsClient.LoginContext = StsClient.LoginContext(
        Pkce.Pair("ab".repeat(64), "challenge-placeholder"),
        DpopKeyPair.generate(),
    )

    /** 构造带完整 refresh 链材料的旧账号（续期测试入参） */
    private fun makeAccount(
        refreshToken: String = "RT_OLD",
        pkceVerifier: String = "cd".repeat(64),
    ): Account {
        val kp = DpopKeyPair.generate()
        return Account(
            accountId = "account-1",
            label = "old-label",
            credentials = Credentials(
                accessKeyId = "AKOLD",
                secretAccessKey = "SKOLD",
                securityToken = "TOKENOLD",
                expiration = 1792135655696L,
                expiresAt = "2026-10-16T07:27:35.696Z",
            ),
            refreshToken = refreshToken,
            pkceVerifier = pkceVerifier,
            dpopPrivateKeyJwk = buildJsonObject {
                kp.privateKeyJwk.forEach { (k, v) -> put(k, v) }
            },
            xOtSessionId = "session-fixed-32-hex-aaaaaaaaaaaaaa",
            createdAt = 1700000000000L,
        )
    }

    /** 解析 MockWebServer 记录的请求体为可查询的表单参数（OkHttp 解码语义） */
    private fun recordedForm(): Map<String, String?> {
        val recorded = server.takeRequest()
        // 表单在 POST body 中，借 HttpUrl 的 query 语义做 form-urlencode 解码
        val url = ("http://localhost/?" + recorded.body.readUtf8()).toHttpUrl()
        return url.queryParameterNames.associateWith { url.queryParameter(it) }
    }

    // ── exchangeCode（grant_type=authorization_code）──

    @Test
    fun exchangeCodePostsFiveFieldForm() = runTest {
        enqueueTokenOk()
        val context = loginContext()

        client.exchangeCode("AUTHCODE123", context, port = 8789, nowEpochMillis = 1700000000000L)

        // 表单五字段（附录 A.1）：client_id/code/code_verifier/grant_type/redirect_uri
        val form = recordedForm()
        assertEquals(mapOf(
            "client_id" to "codearts-agent",
            "code" to "AUTHCODE123",
            "code_verifier" to "ab".repeat(64),
            "grant_type" to "authorization_code",
            "redirect_uri" to "http://127.0.0.1:8789/oauth/callback",
        ), form)
    }

    @Test
    fun exchangeCodeSendsFormContentTypeAndDpopHeader() = runTest {
        enqueueTokenOk()

        client.exchangeCode("AUTHCODE123", loginContext())

        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        assertEquals("application/x-www-form-urlencoded", recorded.getHeader("Content-Type"))
        // DPoP 头存在且为三段 JWS
        val dpop = recorded.getHeader("DPoP")
        assertNotNull(dpop)
        assertEquals(3, dpop!!.split(".").size)
    }

    @Test
    fun exchangeCodeParsesAccountFromStsResponse() = runTest {
        enqueueTokenOk()

        val account = client.exchangeCode(
            "AUTHCODE123", loginContext(), port = 8789, nowEpochMillis = 1700000000000L,
        )

        // 响应解析（Python _session_from_sts 语义）：credentials + refresh_token + account.label
        assertEquals("hw-test-account", account.label)
        assertEquals("AKTEST123", account.credentials.accessKeyId)
        assertEquals("SKTEST456", account.credentials.secretAccessKey)
        assertEquals("TOKEN789", account.credentials.securityToken)
        // golden：毫秒精度 expiration 解析
        assertEquals(1792135655696L, account.credentials.expiration)
        assertEquals("2026-10-16T07:27:35.696Z", account.credentials.expiresAt)
        assertEquals("RT_NEW_1", account.refreshToken)
        // PKCE verifier 与 DPoP 私钥 JWK 落入账号（续期链材料）
        assertEquals("ab".repeat(64), account.pkceVerifier)
        assertTrue(account.dpopPrivateKeyJwk.containsKey("d"))
        // xOtSessionId 登录时生成一次固定（32 hex）、createdAt 取注入时刻
        assertEquals(32, account.xOtSessionId.length)
        assertTrue(account.xOtSessionId.matches(Regex("^[0-9a-f]{32}$")))
        assertEquals(1700000000000L, account.createdAt)
    }

    // ── refresh（grant_type=refresh_token 轮换）──

    @Test
    fun refreshPostsFourFieldFormReusingVerifier() = runTest {
        enqueueRefreshOk()
        val old = makeAccount(pkceVerifier = "cd".repeat(64), refreshToken = "RT_OLD")

        client.refresh(old)

        // 表单四字段（附录 A.2）：复用原 PKCE verifier，无 code/redirect_uri
        val form = recordedForm()
        assertEquals(mapOf(
            "client_id" to "codearts-agent",
            "code_verifier" to "cd".repeat(64),
            "grant_type" to "refresh_token",
            "refresh_token" to "RT_OLD",
        ), form)
    }

    @Test
    fun refreshRotatesCredentialsAndTokenKeepingIdentity() = runTest {
        enqueueRefreshOk()
        val old = makeAccount()

        val new = client.refresh(old)

        // 新凭证 + 新 refresh_token 整体替换
        assertEquals("AKNEW789", new.credentials.accessKeyId)
        assertEquals("SKNEW012", new.credentials.secretAccessKey)
        assertEquals("TOKEN345", new.credentials.securityToken)
        assertEquals(1792142855696L, new.credentials.expiration)
        assertEquals("RT_NEW_2", new.refreshToken)
        // 账号身份字段不变（accountId/label/xOtSessionId/createdAt/pkceVerifier/私钥 JWK）
        assertEquals(old.accountId, new.accountId)
        assertEquals(old.label, new.label)
        assertEquals(old.xOtSessionId, new.xOtSessionId)
        assertEquals(old.createdAt, new.createdAt)
        assertEquals(old.pkceVerifier, new.pkceVerifier)
        assertEquals(old.dpopPrivateKeyJwk, new.dpopPrivateKeyJwk)
    }

    @Test
    fun refreshKeepsOldTokenWhenResponseHasNone() = runTest {
        // 响应缺 refresh_token：保留旧值（Python data.get("refresh_token", base.get(...)) 语义）
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"credentials":{"access_key_id":"AKX","secret_access_key":"SKX",
                "security_token":"TX","expiration":"2026-10-16T09:27:35.696Z"}}""",
            ),
        )
        val old = makeAccount(refreshToken = "RT_KEEP")

        val new = client.refresh(old)
        assertEquals("RT_KEEP", new.refreshToken)
    }

    @Test
    fun refreshRequiresRefreshTokenAndVerifier() = runTest {
        // 缺 refresh_token / PKCE verifier：快速失败（不发请求）
        run {
            val noToken = makeAccount(refreshToken = "")
            try {
                client.refresh(noToken)
                fail("缺 refresh_token 应抛 IllegalArgumentException")
            } catch (expected: IllegalArgumentException) {
                assertTrue(expected.message!!.contains("refresh_token"))
            }
        }
        run {
            val noVerifier = makeAccount(pkceVerifier = "")
            try {
                client.refresh(noVerifier)
                fail("缺 PKCE verifier 应抛 IllegalArgumentException")
            } catch (expected: IllegalArgumentException) {
                assertTrue(expected.message!!.contains("PKCE"))
            }
        }
        // 两个 case 都不应发出网络请求
        assertEquals(0, server.requestCount)
    }

    // ── 非 200 → 类型化 StsException ──

    @Test
    fun non200ThrowsStsExceptionWithBodyPreserved() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(400).setBody(
                """{"error_code":"API.STS.5.1806","error_msg":"invalid refresh token"}""",
            ),
        )
        try {
            client.refresh(makeAccount())
            fail("STS 400 应抛 StsException")
        } catch (e: StsException) {
            assertEquals(400, e.statusCode)
            // 响应体保留（诊断语义，Python RuntimeError 不丢 detail）
            assertTrue(e.responseBody.contains("STS.5.1806"))
            assertTrue(e.isRefreshChainDead)
        }
    }

    @Test
    fun http500ThrowsPlainStsException() = runTest {
        server.enqueue(MockResponse().setResponseCode(500).setBody("internal error"))
        try {
            client.refresh(makeAccount())
            fail("STS 500 应抛 StsException")
        } catch (e: StsException) {
            assertEquals(500, e.statusCode)
            assertTrue(e.responseBody.contains("internal error"))
            // isRefreshChainDead 仅对 400 且链失效码为 true
            assertTrue(!e.isRefreshChainDead)
        }
    }

    // ── credentialsFromSts（internal，响应解析直测）──

    @Test
    fun credentialsFromStsRejectsMissingCredentials() {
        val data = Json.parseToJsonElement("""{"foo":"bar"}""").jsonObject
        try {
            client.credentialsFromSts(data)
            fail("无 credentials 应抛 StsException")
        } catch (expected: StsException) {
            assertTrue(expected.message!!.contains("credentials"))
        }
    }

    @Test
    fun credentialsFromStsRejectsEmptyAccessKeyId() {
        // Python：if not creds.get("access_key_id") → 抛错（空串也算缺失）
        val data = Json.parseToJsonElement(
            """{"credentials":{"access_key_id":"","secret_access_key":"s","expiration":""}}""",
        ).jsonObject
        try {
            client.credentialsFromSts(data)
            fail("空 access_key_id 应抛 StsException")
        } catch (expected: StsException) {
            assertTrue(expected.message!!.contains("credentials"))
        }
    }

    @Test
    fun credentialsFromStsDefaultsMissingOptionalFields() {
        // sk/security_token/expiration 缺省：空串/0（Python creds.get(...) or "" 语义）
        val data = Json.parseToJsonElement(
            """{"credentials":{"access_key_id":"AKONLY"}}""",
        ).jsonObject
        val creds = client.credentialsFromSts(data)
        assertEquals("AKONLY", creds.accessKeyId)
        assertEquals("", creds.secretAccessKey)
        assertEquals("", creds.securityToken)
        assertEquals(0L, creds.expiration)
        assertEquals("", creds.expiresAt)
    }

    @Test
    fun dpopProofTargetsStsEndpointWithPost() = runTest {
        // DPoP proof 的 htu 恒为 STS 端点、htm 为 POST（附录 A.3；Python _token_request 固定值）
        enqueueTokenOk()
        client.exchangeCode("AUTHCODE123", loginContext())
        val recorded = server.takeRequest()
        val dpop = recorded.getHeader("DPoP")!!
        val payload = String(
            com.lxseek.hw2api.core.util.Base64Url.decode(dpop.split(".")[1]),
            Charsets.UTF_8,
        )
        val payloadObj = Json.parseToJsonElement(payload).jsonObject
        assertEquals("POST", payloadObj["htm"]!!.jsonPrimitive.content)
        // baseUrl 指向 MockWebServer，htu 应与其完全一致
        assertTrue(payloadObj["htu"]!!.jsonPrimitive.content.endsWith("/v1/oauth2/tokens"))
    }
}