package com.lxseek.hw2api.login

import com.lxseek.hw2api.core.auth.Pkce
import com.lxseek.hw2api.core.auth.StsClient
import com.lxseek.hw2api.core.model.ModelRegistry
import com.lxseek.hw2api.core.util.RandomHex

/**
 * 一次 OAuth 登录会话的临时状态（plan.md §3.2 LoginSession）：
 * PKCE pair + DPoP 密钥对 + 随机 ticket_id + 回调端口。
 * 登录成功/失败后即整体丢弃；authorize URL 参数逐项对齐 plan.md 附录 A.1。
 */
class LoginSession {

    /** STS 交换上下文（PKCE pair + DPoP keypair），exchangeCode 时原样传入 */
    val context: StsClient.LoginContext = StsClient.LoginContext.new()

    /** authorize URL 的 ticket_id（32 位 hex 随机） */
    val ticketId: String = RandomHex.next(16)

    /** 回调端口：Android 固定 8789（WebView 拦截无需真监听，redirect_uri 必须带该端口） */
    val callbackPort: Int = ModelRegistry.LOGIN_CALLBACK_PORT

    /** 构造 authorize URL（附录 A.1，参数顺序与逆向结果一致） */
    fun authorizeUrl(): String =
        "${ModelRegistry.PORTAL_AUTHORIZE_URL}" +
            "?theme=Dark" +
            "&locale=zh-cn" +
            "&uri_scheme=codearts-agent" +
            "&client_id=${ModelRegistry.CLIENT_ID}" +
            "&port=$callbackPort" +
            "&code_challenge=${context.pkce.codeChallenge}" +
            "&code_challenge_method=${Pkce.CHALLENGE_METHOD}" +
            "&ticket_id=$ticketId" +
            "&plugin-name=${ModelRegistry.LOGIN_PLUGIN_NAME}" +
            "&plugin-version=${ModelRegistry.EXTENSION_VERSION}"
}