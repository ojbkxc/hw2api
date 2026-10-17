package com.lxseek.hw2api.core.auth

import com.lxseek.hw2api.core.model.Account
import com.lxseek.hw2api.core.model.Credentials
import com.lxseek.hw2api.core.util.Iso8601
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * STS OAuth 客户端（语义对齐 Python `_token_request` / `_exchange_code` / `_refresh_session` / `_session_from_sts`）。
 *
 * - authorization_code 交换（登录）：五字段表单 client_id/code/code_verifier/grant_type/redirect_uri
 * - refresh_token 轮换：四字段表单 client_id/code_verifier(复用)/grant_type=refresh_token/refresh_token
 * - 请求带 DPoP 头（ES256 JWS，proof 的 htu 恒为 STS 端点）
 * - 响应解析出 credentials + 新 refresh_token（rotation 整体替换）；expiration ISO8601 毫秒
 * - 非 200 抛类型化 StsException（保留响应体）
 */
class StsClient(
    private val httpClient: OkHttpClient,
    private val baseUrl: String = com.lxseek.hw2api.core.model.ModelRegistry.STS_TOKEN_URL,
) {

    private val json = Json { ignoreUnknownKeys = true }

    /** 登录上下文：一次 OAuth 会话的 PKCE pair + DPoP 密钥对（登录成功/失败后即丢弃） */
    data class LoginContext(
        val pkce: Pkce.Pair,
        val dpopKeyPair: DpopKeyPair,
    ) {
        companion object {
            /** 为一次新的登录会话生成上下文 */
            fun new(): LoginContext = LoginContext(Pkce.generate(), DpopKeyPair.generate())
        }
    }

    /**
     * 用 authorization code 换凭证（grant_type=authorization_code）。
     * @param code WebView 拦截回调拿到的授权码
     * @param port 登录 authorize URL 中携带的 callback 端口（Android 固定 8789）
     * @return 新账号（含轮换出的 refresh_token、固定 xOtSessionId、账号标签）
     */
    suspend fun exchangeCode(
        code: String,
        context: LoginContext,
        port: Int = com.lxseek.hw2api.core.model.ModelRegistry.LOGIN_CALLBACK_PORT,
        nowEpochMillis: Long = System.currentTimeMillis(),
    ): Account = withContext(Dispatchers.IO) {
        val redirectUri = "http://127.0.0.1:$port${com.lxseek.hw2api.core.model.ModelRegistry.OAUTH_REDIRECT_PATH}"
        val form = FormBody.Builder()
            .add("client_id", com.lxseek.hw2api.core.model.ModelRegistry.CLIENT_ID)
            .add("code", code)
            .add("code_verifier", context.pkce.codeVerifier)
            .add("grant_type", "authorization_code")
            .add("redirect_uri", redirectUri)
            .build()
        val data = postForm(form, context)
        // 登录响应带 account.label；续期响应没有
        val label = data["account"]?.jsonObject?.get("label")?.jsonPrimitive?.content ?: ""
        accountFromSts(data, label, context, newAccountId = true, nowEpochMillis = nowEpochMillis)
    }

    /**
     * refresh_token 轮换取新凭证（grant_type=refresh_token）。
     * PKCE verifier 与 DPoP 密钥对复用原值（附录 A.2），响应新 refresh_token 整体替换。
     * @return 续期后的新 Account（accountId/label/xOtSessionId/createdAt 保持不变）
     */
    suspend fun refresh(account: Account, nowEpochMillis: Long = System.currentTimeMillis()): Account =
        withContext(Dispatchers.IO) {
            require(account.refreshToken.isNotEmpty()) { "缺少 refresh_token，无法续期（需重新登录）" }
            require(account.pkceVerifier.isNotEmpty()) { "缺少 PKCE verifier，无法续期（需重新登录）" }
            val keyPair = DpopKeyPair.fromPrivateKeyJwk(
                account.dpopPrivateKeyJwk.mapValues { it.value.jsonPrimitive.content }
            )
            val context = LoginContext(Pkce.Pair(account.pkceVerifier, ""), keyPair)
            val form = FormBody.Builder()
                .add("client_id", com.lxseek.hw2api.core.model.ModelRegistry.CLIENT_ID)
                .add("code_verifier", account.pkceVerifier)
                .add("grant_type", "refresh_token")
                .add("refresh_token", account.refreshToken)
                .build()
            val data = postForm(form, context)
            // 续期响应没有 account.label：保留旧值；新 refresh_token 整体替换
            val newRefresh = data["refresh_token"]?.jsonPrimitive?.content ?: account.refreshToken
            account.copy(
                credentials = credentialsFromSts(data),
                refreshToken = newRefresh,
            )
        }

    /** POST 表单 + DPoP 头，返回响应 JSON；非 2xx 抛 StsException（保留响应体） */
    private fun postForm(form: FormBody, context: LoginContext): JsonObject {
        val dpopProof = DpopSigner.sign(
            keyPair = context.dpopKeyPair,
            method = "POST",
            url = baseUrl,
            iatEpochSec = System.currentTimeMillis() / 1000,
        )
        val request = Request.Builder()
            .url(baseUrl)
            .header("Content-Type", "application/x-www-form-urlencoded")
            .header("DPoP", dpopProof)
            .post(form)
            .build()
        httpClient.newCall(request).execute().use { resp ->
            val body = resp.body?.string() ?: ""
            if (!resp.isSuccessful) {
                throw StsException(
                    statusCode = resp.code,
                    responseBody = body.take(300),
                    message = "STS ${resp.code}: ${body.take(300).ifEmpty { resp.message }}",
                )
            }
            return json.parseToJsonElement(body).jsonObject
        }
    }

    /** STS 响应 → Credentials（语义对齐 `_session_from_sts`：无 access_key_id 即抛错） */
    internal fun credentialsFromSts(data: JsonObject): Credentials {
        val creds = data["credentials"]?.jsonObject
            ?: throw StsException(0, data.toString(), "STS 响应无 credentials: ${data.toString().take(300)}")
        val ak = creds["access_key_id"]?.jsonPrimitive?.content
        if (ak.isNullOrEmpty()) {
            throw StsException(0, data.toString(), "STS 响应无 credentials: ${data.toString().take(300)}")
        }
        val expText = creds["expiration"]?.jsonPrimitive?.content ?: ""
        return Credentials(
            accessKeyId = ak,
            secretAccessKey = creds["secret_access_key"]?.jsonPrimitive?.content ?: "",
            securityToken = creds["security_token"]?.jsonPrimitive?.content ?: "",
            expiration = Iso8601.parseEpochMillis(expText),
            expiresAt = expText,
        )
    }

    /** STS 响应 → Account（登录路径用；xOtSessionId 登录时生成一次固定） */
    private fun accountFromSts(
        data: JsonObject,
        label: String,
        context: LoginContext,
        newAccountId: Boolean,
        nowEpochMillis: Long,
    ): Account {
        val creds = credentialsFromSts(data)
        return Account(
            accountId = com.lxseek.hw2api.core.util.RandomHex.next32(),
            label = label,
            credentials = creds,
            refreshToken = data["refresh_token"]?.jsonPrimitive?.content ?: "",
            pkceVerifier = context.pkce.codeVerifier,
            dpopPrivateKeyJwk = context.dpopKeyPair.privateKeyJwk.let { jwk ->
                buildJsonObject { jwk.forEach { (k, v) -> put(k, v) } }
            },
            xOtSessionId = com.lxseek.hw2api.core.util.RandomHex.next32(),
            createdAt = nowEpochMillis,
        )
    }
}