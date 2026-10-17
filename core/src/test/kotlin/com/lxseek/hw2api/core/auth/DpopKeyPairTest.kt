package com.lxseek.hw2api.core.auth

import com.lxseek.hw2api.core.util.Base64Url
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigInteger

/**
 * DPoP P-256 密钥对测试（语义对拍 codearts_proxy.py::_gen_dpop_keypair）。
 *
 * golden 向量来源（纯 Python P-256 标量乘 d*G 离线推导，推导脚本一次性运行后已删除）：
 * - d = 0x1122334455667788...5667788（固定测试私钥）→ 公钥点 (x, y) 十六进制/base64url
 * - d = 1 → 公钥点即 NIST P-256 生成元 G（标准常量）
 */
class DpopKeyPairTest {

    /** golden：固定测试私钥 d = 0x112233...5667788 */
    private val fixedD = "ESIzRFVmd4gRIjNEVWZ3iBEiM0RVZneIESIzRFVmd4g"

    /** golden：d*G 的仿射坐标（Python 纯 EC 点乘推导，32 字节大端 base64url） */
    private val expectedX = "QfZI-TM8DKDAXqESNxVLzppy1D7RFSeAUPLpMtU5GXw"

    private val expectedY = "n8nq-_gIe-q2S49hnJDudXR0VMe8licGF9e-V1riisM"

    /** NIST P-256 生成元 G 的坐标（标准常量，d=1 的公钥点） */
    private val generatorX = "axfR8uEsQkf4vOblY6RA8ncDfYEt6zOg9KE5RdiYwpY"

    private val generatorY = "T-NC4v4af5uO5-tKfA-eFivOM1drMV7Oy7ZAaDe_UfU"

    /** 用 golden 公钥坐标与给定 d 组装私钥 JWK（模拟存储 JSON 恢复入参） */
    private fun privateJwkOf(d: String): Map<String, String> = linkedMapOf(
        DpopKeyPair.JWK_KTY to DpopKeyPair.KTY_EC,
        DpopKeyPair.JWK_CRV to DpopKeyPair.CRV_P256,
        DpopKeyPair.JWK_X to expectedX,
        DpopKeyPair.JWK_Y to expectedY,
        DpopKeyPair.JWK_D to d,
    )

    @Test
    fun generateExportsCompleteJwkFields() {
        // 语义对齐 Python _gen_dpop_keypair：kty/crv/x/y(/d) 字段齐全
        val kp = DpopKeyPair.generate()
        assertEquals("EC", kp.publicKeyJwk[DpopKeyPair.JWK_KTY])
        assertEquals("P-256", kp.publicKeyJwk[DpopKeyPair.JWK_CRV])
        assertNotNull(kp.publicKeyJwk[DpopKeyPair.JWK_X])
        assertNotNull(kp.publicKeyJwk[DpopKeyPair.JWK_Y])
        assertEquals("EC", kp.privateKeyJwk[DpopKeyPair.JWK_KTY])
        assertEquals("P-256", kp.privateKeyJwk[DpopKeyPair.JWK_CRV])
        assertEquals(kp.publicKeyJwk[DpopKeyPair.JWK_X], kp.privateKeyJwk[DpopKeyPair.JWK_X])
        assertEquals(kp.publicKeyJwk[DpopKeyPair.JWK_Y], kp.privateKeyJwk[DpopKeyPair.JWK_Y])
        assertNotNull(kp.privateKeyJwk[DpopKeyPair.JWK_D])
    }

    @Test
    fun publicKeyJwkDoesNotContainPrivateKeyMaterial() {
        // 公钥 JWK 仅 x/y：不得泄漏 d（RFC7517 语义，Python jwk_key(None) 同）
        val kp = DpopKeyPair.generate()
        assertFalse(kp.publicKeyJwk.containsKey(DpopKeyPair.JWK_D))
    }

    @Test
    fun jwkCoordinatesAre32ByteBase64Url() {
        // P-256 坐标/私钥标量恒 32 字节大端 base64url 无 padding
        val kp = DpopKeyPair.generate()
        val x = Base64Url.decode(kp.publicKeyJwk[DpopKeyPair.JWK_X]!!)
        val y = Base64Url.decode(kp.publicKeyJwk[DpopKeyPair.JWK_Y]!!)
        val d = Base64Url.decode(kp.privateKeyJwk[DpopKeyPair.JWK_D]!!)
        assertEquals(32, x.size)
        assertEquals(32, y.size)
        assertEquals(32, d.size)
        assertFalse(kp.publicKeyJwk[DpopKeyPair.JWK_X]!!.contains('='))
    }

    @Test
    fun jwkFieldOrderIsStable() {
        // JWK 以 LinkedHashMap 保序导出（紧凑 JSON 字段顺序稳定，DPoP header 依赖此顺序）
        val kp = DpopKeyPair.generate()
        assertEquals(
            listOf("kty", "crv", "x", "y"),
            kp.publicKeyJwk.keys.toList(),
        )
        assertEquals(
            listOf("kty", "crv", "x", "y", "d"),
            kp.privateKeyJwk.keys.toList(),
        )
        // privateKeyJwkMap() 返回防御性拷贝，内容与顺序一致
        assertEquals(kp.privateKeyJwk, kp.privateKeyJwkMap())
    }

    @Test
    fun fromPrivateKeyJwkRecoversPublicKeyMatchingPythonVector() {
        // golden 断言：固定 d 恢复出的公钥点与 Python 纯 EC 标量乘 d*G 结果逐字节一致
        val restored = DpopKeyPair.fromPrivateKeyJwk(privateJwkOf(fixedD))
        assertEquals(expectedX, restored.publicKeyJwk[DpopKeyPair.JWK_X])
        assertEquals(expectedY, restored.publicKeyJwk[DpopKeyPair.JWK_Y])
        assertEquals(fixedD, restored.privateKeyJwk[DpopKeyPair.JWK_D])
    }

    @Test
    fun fromPrivateKeyJwkWithDEqualsOneReturnsGeneratorPoint() {
        // d = 1 → 公钥点必为生成元 G（任意 EC 实现的数学底线）
        val dOne = Base64Url.encode(BigInteger.ONE.toByteArray())
        val restored = DpopKeyPair.fromPrivateKeyJwk(privateJwkOf(dOne))
        assertEquals(generatorX, restored.publicKeyJwk[DpopKeyPair.JWK_X])
        assertEquals(generatorY, restored.publicKeyJwk[DpopKeyPair.JWK_Y])
    }

    @Test
    fun fromPrivateKeyJwkRoundTripMatchesGeneratedJwk() {
        // 生成 → 存 JWK → 恢复：公钥/私钥 JWK 应与原值完全一致（refresh 链复用依赖此性质）
        val original = DpopKeyPair.generate()
        val restored = DpopKeyPair.fromPrivateKeyJwk(original.privateKeyJwk)
        assertEquals(original.publicKeyJwk, restored.publicKeyJwk)
        assertEquals(original.privateKeyJwk, restored.privateKeyJwk)
    }

    @Test(expected = IllegalArgumentException::class)
    fun fromPrivateKeyJwkRejectsNonP256Curve() {
        // 仅支持 EC P-256（防御：存储脏数据快速失败）
        val bad = linkedMapOf(
            "kty" to "EC", "crv" to "P-384",
            "x" to expectedX, "y" to expectedY, "d" to fixedD,
        )
        DpopKeyPair.fromPrivateKeyJwk(bad)
    }

    @Test(expected = IllegalArgumentException::class)
    fun fromPrivateKeyJwkRejectsMissingD() {
        val bad = linkedMapOf(
            "kty" to "EC", "crv" to "P-256",
            "x" to expectedX, "y" to expectedY,
        )
        DpopKeyPair.fromPrivateKeyJwk(bad)
    }

    @Test
    fun to32BytesPadsShortValueWithLeadingZeros() {
        // 2 字节正数 → 左补零到 32 字节大端
        val v = BigInteger(1, byteArrayOf(0x01, 0x02))
        val out = DpopKeyPair.to32Bytes(v)
        assertEquals(32, out.size)
        assertEquals(0, out[0].toInt())
        assertEquals(0x01, out[30].toInt())
        assertEquals(0x02, out[31].toInt())
    }

    @Test
    fun to32BytesTruncatesSignByteForHighBitValues() {
        // 最高位为 1 的 32 字节正数：BigInteger.toByteArray() 会补符号位成 33 字节，需截掉
        val input = ByteArray(32) { 0x00 }
        input[0] = 0x80.toByte()
        val out = DpopKeyPair.to32Bytes(BigInteger(1, input))
        assertEquals(32, out.size)
        assertArrayEquals(input, out)
    }

    @Test
    fun to32BytesKeepsExact32ByteValue() {
        // 恰 32 字节且最高位 < 0x80：toByteArray() 无符号位，应原样返回
        val input = ByteArray(32) { 0x7F }
        val out = DpopKeyPair.to32Bytes(BigInteger(1, input))
        assertEquals(32, out.size)
        assertArrayEquals(input, out)
    }

    @Test
    fun to32BytesHandlesZeroAndOne() {
        // 边界：0 → 32 个零；1 → 末字节 1
        val zero = DpopKeyPair.to32Bytes(BigInteger.ZERO)
        assertEquals(32, zero.size)
        assertTrue(zero.all { it == 0.toByte() })
        val one = DpopKeyPair.to32Bytes(BigInteger.ONE)
        assertEquals(32, one.size)
        assertEquals(1, one[31].toInt())
        assertTrue(one.dropLast(1).all { it == 0.toByte() })
    }

    @Test
    fun restoredKeyPairProducesVerifiableSignature() {
        // 恢复的密钥对与生成的密钥同权：能对固定输入做 DPoP 签名且公钥自验签通过
        val restored = DpopKeyPair.fromPrivateKeyJwk(privateJwkOf(fixedD))
        val proof = DpopSigner.sign(
            keyPair = restored,
            method = "POST",
            url = "https://sts.example.test/v1/oauth2/tokens",
            iatEpochSec = 1700000000L,
        )
        val pub = restored.keyPair.public as java.security.interfaces.ECPublicKey
        assertTrue(DpopSigner.verify(proof, pub))
    }
}