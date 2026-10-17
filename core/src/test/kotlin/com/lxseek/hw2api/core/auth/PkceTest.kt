package com.lxseek.hw2api.core.auth

import com.lxseek.hw2api.core.util.Base64Url
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest

/**
 * PKCE 生成测试（语义对拍 codearts_proxy.py::_gen_pkce）。
 *
 * golden 向量来源：固定 verifier（"0123456789abcdef" 重复 8 次，编造测试数据），
 * 用 Python `base64.urlsafe_b64encode(hashlib.sha256(verifier.encode("ascii")).digest()).rstrip(b"=")`
 * 离线推导 challenge 后写死（推导脚本一次性运行后已删除，语义与 _gen_pkce 逐行一致）。
 *
 * 说明：Pkce.generate() 的随机源为 SecureRandom，无种子注入 API，
 * 确定性验证采用「固定 verifier 复算 challenge」方式（challengeOf 复刻算法），
 * 无法直接断言 generate() 对固定种子输出固定值。
 */
class PkceTest {

    /** 固定 verifier：128 个 hex 字符（编造测试数据） */
    private val fixedVerifier = "0123456789abcdef".repeat(8)

    /** golden challenge：SHA256(fixedVerifier ASCII) 的 base64url 无 padding 形态 */
    private val expectedChallenge = "syDoWXjbBRNAA6KRTuvd2NO4cmgY8uLGeeGJjHIVYqk"

    /** 测试辅助：按 Pkce 语义复算 challenge（等价 Python `_b64url(sha256(verifier ascii))`） */
    private fun challengeOf(verifier: String): String =
        Base64Url.encode(
            MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII))
        )

    @Test
    fun verifierIs128LowercaseHexChars() {
        // 语义对齐 Python secrets.token_hex(64)：恒为 128 个 [0-9a-f] 字符
        val pair = Pkce.generate()
        assertEquals(128, pair.codeVerifier.length)
        assertTrue(
            "verifier 应为 128 个小写 hex 字符: ${pair.codeVerifier}",
            pair.codeVerifier.matches(Regex("^[0-9a-f]{128}$")),
        )
    }

    @Test
    fun challengeIs43CharBase64UrlWithoutPadding() {
        // SHA256 摘要 32 字节 → base64url 无 padding 恒 43 字符，且不含 + / =
        val pair = Pkce.generate()
        assertEquals(43, pair.codeChallenge.length)
        assertFalse(pair.codeChallenge.contains('='))
        assertFalse(pair.codeChallenge.contains('+'))
        assertFalse(pair.codeChallenge.contains('/'))
    }

    @Test
    fun challengeAlgorithmMatchesPythonReference() {
        // golden 断言：固定 verifier 的 challenge 与 Python 参考实现逐字符一致
        assertEquals(expectedChallenge, challengeOf(fixedVerifier))
    }

    @Test
    fun challengeAlgorithmMatchesPythonReferenceSecondVector() {
        // 第二组 golden：全零 verifier（"00" 重复 64 次）
        val zeroVerifier = "00".repeat(64)
        assertEquals("RXJXkcR7MmGMxXuIND4rzuw7CgG4O8l9FEosvBGiDD0", challengeOf(zeroVerifier))
    }

    @Test
    fun generatedPairIsSelfConsistent() {
        // generate() 内联算法与复刻算法一致：用返回的 verifier 复算 challenge 应相等
        val pair = Pkce.generate()
        assertEquals(challengeOf(pair.codeVerifier), pair.codeChallenge)
    }

    @Test
    fun challengeMethodIsSha256() {
        // method 恒为 SHA-256（表单 code_challenge_method 字段）
        assertEquals("SHA-256", Pkce.CHALLENGE_METHOD)
        assertEquals("SHA-256", Pkce.generate().codeChallengeMethod)
    }

    @Test
    fun twoGenerationsProduceDifferentVerifiers() {
        // SecureRandom 语义：两次生成不应碰撞
        val first = Pkce.generate()
        val second = Pkce.generate()
        assertFalse(first.codeVerifier == second.codeVerifier)
        assertFalse(first.codeChallenge == second.codeChallenge)
    }
}