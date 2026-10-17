package com.lxseek.hw2api.core.auth

import com.lxseek.hw2api.core.util.Base64Url
import com.lxseek.hw2api.core.util.RandomHex
import java.security.MessageDigest

/**
 * PKCE pair 生成（语义对齐 Python `_gen_pkce`，复刻客户端 PKCEGenerator.generate）：
 * - verifier：128 个 hex 字符（= SecureRandom 64 字节，等价 `secrets.token_hex(64)`）
 * - challenge：base64url 无 padding 的 SHA256(verifier 的 ASCII 字节)
 */
object Pkce {

    const val CHALLENGE_METHOD = "SHA-256"

    /** 一次性生成的 PKCE 凭证对 */
    data class Pair(val codeVerifier: String, val codeChallenge: String) {
        val codeChallengeMethod: String get() = CHALLENGE_METHOD
    }

    /** 生成新的 PKCE pair */
    fun generate(): Pair {
        val verifier = RandomHex.next(64) // 128 hex 字符
        val challenge = Base64Url.encode(
            MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII))
        )
        return Pair(codeVerifier = verifier, codeChallenge = challenge)
    }
}