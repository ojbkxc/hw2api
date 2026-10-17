package com.lxseek.hw2api.core.auth

import com.lxseek.hw2api.core.util.Base64Url
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.interfaces.ECPublicKey

/**
 * RFC9449 DPoP proof 构造测试（语义对拍 codearts_proxy.py::_dpop_sign）。
 *
 * 签名含 ECDSA 随机数 k，无法做整串 golden 对拍；本测试采用：
 * 1. 结构断言（三段、header/payload 字段、紧凑 JSON）
 * 2. 公钥自验签（DpopSigner.verify 内部做 r||s → DER 恢复 + SHA256withECDSA 验签）
 * 3. DER ↔ r||s 转换的确定性向量（手工构造 DER 字节）
 */
class DpopSignerTest {

    private const val STS_URL = "https://sts.cn-north-4.myhuaweicloud.com/v1/oauth2/tokens"

    /** 紧凑 JSON 解析器（校验 header/payload 序列化形态） */
    private val json = Json

    private fun publicKeyOf(kp: DpopKeyPair): ECPublicKey = kp.keyPair.public as ECPublicKey

    @Test
    fun proofHasThreeDotSeparatedSegments() {
        val kp = DpopKeyPair.generate()
        val proof = DpopSigner.sign(kp, "POST", STS_URL, iatEpochSec = 1700000000L)
        val parts = proof.split(".")
        assertEquals(3, parts.size)
        // 三段均为合法 base64url（无 padding）
        parts.forEach { part ->
            assertFalse(part.contains('='))
            Base64Url.decode(part) // 非法字符会抛异常
        }
    }

    @Test
    fun headerContainsAlgTypAndJwkInCompactJson() {
        // header 紧凑 JSON：{"alg":"ES256","typ":"dpop+jwt","jwk":publicKeyJwk}
        val kp = DpopKeyPair.generate()
        val proof = DpopSigner.sign(kp, "POST", STS_URL, iatEpochSec = 1700000000L)
        val headerText = String(Base64Url.decode(proof.split(".")[0]), Charsets.UTF_8)
        // 紧凑序列化：不含空白字符（Python json.dumps(separators=(",", ":")) 语义）
        assertFalse("header 应为紧凑 JSON: $headerText", headerText.contains(' '))
        val header = json.parseToJsonElement(headerText).jsonObject
        assertEquals("ES256", header["alg"]!!.jsonPrimitive.content)
        assertEquals("dpop+jwt", header["typ"]!!.jsonPrimitive.content)
        val jwk = header["jwk"]!!.jsonObject
        assertEquals("EC", jwk["kty"]!!.jsonPrimitive.content)
        assertEquals("P-256", jwk["crv"]!!.jsonPrimitive.content)
        assertEquals(kp.publicKeyJwk["x"], jwk["x"]!!.jsonPrimitive.content)
        assertEquals(kp.publicKeyJwk["y"], jwk["y"]!!.jsonPrimitive.content)
        // jwk 内不得出现私钥材料 d
        assertFalse(jwk.containsKey("d"))
    }

    @Test
    fun payloadCarriesHtmHtuIatJti() {
        // payload：{"htm":"POST","htu":url,"iat":epoch秒,"jti":64hex随机}
        val kp = DpopKeyPair.generate()
        val proof = DpopSigner.sign(kp, "POST", STS_URL, iatEpochSec = 1700001234L)
        val payloadText = String(Base64Url.decode(proof.split(".")[1]), Charsets.UTF_8)
        val payload = json.parseToJsonElement(payloadText).jsonObject
        assertEquals("POST", payload["htm"]!!.jsonPrimitive.content)
        assertEquals(STS_URL, payload["htu"]!!.jsonPrimitive.content)
        assertEquals(1700001234L, payload["iat"]!!.jsonPrimitive.content.toLong())
        val jti = payload["jti"]!!.jsonPrimitive.content
        assertEquals(64, jti.length)
        assertTrue(jti.matches(Regex("^[0-9a-f]{64}$")))
    }

    @Test
    fun jtiDiffersBetweenTwoSignings() {
        // jti 每次签名随机（64 hex）
        val kp = DpopKeyPair.generate()
        val p1 = DpopSigner.sign(kp, "POST", STS_URL, iatEpochSec = 1700000000L)
        val p2 = DpopSigner.sign(kp, "POST", STS_URL, iatEpochSec = 1700000000L)
        val j1 = json.parseToJsonElement(String(Base64Url.decode(p1.split(".")[1]))).jsonObject["jti"]!!.jsonPrimitive.content
        val j2 = json.parseToJsonElement(String(Base64Url.decode(p2.split(".")[1]))).jsonObject["jti"]!!.jsonPrimitive.content
        assertFalse(j1 == j2)
    }

    @Test
    fun signatureVerifiesWithPublicKey() {
        // 固定密钥 + 固定 payload（iat 注入）：签名可被公钥验签（SHA256withECDSA）
        val kp = DpopKeyPair.generate()
        val proof = DpopSigner.sign(kp, "POST", STS_URL, iatEpochSec = 1700000000L)
        assertTrue(DpopSigner.verify(proof, publicKeyOf(kp)))
    }

    @Test
    fun signatureVerifiesForRestoredKeyPair() {
        // 从私钥 JWK 恢复的密钥对签名，generate 时的公钥 JWK 同源公钥可验
        val original = DpopKeyPair.generate()
        val restored = DpopKeyPair.fromPrivateKeyJwk(original.privateKeyJwk)
        val proof = DpopSigner.sign(restored, "POST", STS_URL, iatEpochSec = 1700000000L)
        assertTrue(DpopSigner.verify(proof, publicKeyOf(original)))
    }

    @Test
    fun verifyRejectsTamperedProof() {
        // 篡改 payload 一字符 → 验签失败
        val kp = DpopKeyPair.generate()
        val proof = DpopSigner.sign(kp, "POST", STS_URL, iatEpochSec = 1700000000L)
        val parts = proof.split(".").toMutableList()
        val payload = String(Base64Url.decode(parts[1]))
        val tampered = if (payload.contains("POST")) payload.replace("POST", "GET") else payload + " "
        parts[1] = Base64Url.encode(tampered.toByteArray(Charsets.UTF_8))
        assertFalse(DpopSigner.verify(parts.joinToString("."), publicKeyOf(kp)))
    }

    @Test
    fun verifyRejectsWrongKey() {
        // 用另一把密钥的公钥验签 → 失败
        val kp1 = DpopKeyPair.generate()
        val kp2 = DpopKeyPair.generate()
        val proof = DpopSigner.sign(kp1, "POST", STS_URL, iatEpochSec = 1700000000L)
        assertFalse(DpopSigner.verify(proof, publicKeyOf(kp2)))
    }

    @Test
    fun derToRawPadsAndStripsCorrectly() {
        // 向量：r 内容 31 字节（0x7F 开头，无符号位前缀）；s 内容 32 字节（0x80 开头，DER 需 0x00 符号位前缀）
        val rContent = ByteArray(31) { if (it == 0) 0x7F else 0x11 }
        val sContent = ByteArray(32) { if (it == 0) 0x80.toByte() else 0x22 }
        val rDer = byteArrayOf(0x02, 0x1F) + rContent // INTEGER，31 字节
        val sDer = byteArrayOf(0x02, 0x21, 0x00) + sContent // INTEGER，0x00 符号位 + 32 字节
        val der = byteArrayOf(0x30, (rDer.size + sDer.size).toByte()) + rDer + sDer

        val raw = DpopSigner.derToRaw(der)
        assertEquals(64, raw.size)
        // r：左补 1 个零 + 31 字节内容
        val expectedR = ByteArray(32).also { rContent.copyInto(it, 1) }
        assertArrayEquals(expectedR, raw.copyOfRange(0, 32))
        // s：strip 掉 DER 符号位 0x00 后恰好 32 字节原样
        assertArrayEquals(sContent, raw.copyOfRange(32, 64))
    }

    @Test
    fun derToRawHandlesShortIntegers() {
        // 向量：r = 1（DER 02 01 01）、s = 2（DER 02 01 02）→ 各左补 31 个零
        val der = byteArrayOf(0x30, 0x06, 0x02, 0x01, 0x01, 0x02, 0x01, 0x02)
        val raw = DpopSigner.derToRaw(der)
        assertEquals(64, raw.size)
        assertEquals(1, raw[31].toInt())
        assertEquals(2, raw[63].toInt())
        assertTrue(raw.copyOfRange(0, 31).all { it == 0.toByte() })
    }

    @Test
    fun derToRawHandlesHighBitContent() {
        // 向量：r 内容为 [0x00, 0xFF]（DER 规范形式：符号位前缀 + 0xFF）→ strip 符号位后数值 255 落在第 32 字节
        val der = byteArrayOf(0x30, 0x08, 0x02, 0x02, 0x00, 0xFF, 0x02, 0x01, 0x02)
        val raw = DpopSigner.derToRaw(der)
        assertEquals(64, raw.size)
        assertEquals(0xFF, raw[31].toInt() and 0xFF)
        assertTrue(raw.copyOfRange(0, 31).all { it == 0.toByte() })
        assertEquals(2, raw[63].toInt())
    }

    @Test
    fun derToRawThenRawToDerRoundTrip() {
        // 规范 DER → raw → DER：应还原为同一规范形式（前导零归一化幂等）
        val rContent = ByteArray(31) { if (it == 0) 0x7F else 0x11 }
        val sContent = ByteArray(32) { if (it == 0) 0x80.toByte() else 0x22 }
        val rDer = byteArrayOf(0x02, 0x1F) + rContent
        val sDer = byteArrayOf(0x02, 0x21, 0x00) + sContent
        val der = byteArrayOf(0x30, (rDer.size + sDer.size).toByte()) + rDer + sDer
        assertArrayEquals(der, DpopSigner.rawToDer(DpopSigner.derToRaw(der)))
    }

    @Test
    fun rawToDerRestoresSignByteForHighBitValues() {
        // raw 中 r 高位为 1（首字节 0x80）→ DER 整数须补 0x00 符号位
        val raw = ByteArray(64)
        raw[0] = 0x80.toByte()
        raw[31] = 0x01
        raw[32] = 0x7F
        raw[63] = 0x02
        val der = DpopSigner.rawToDer(raw)
        // SEQUENCE 头：body = r(0x02,0x21,00,80..01=35字节) + s(0x02,0x20,7F..02=34字节) = 69 = 0x45
        assertEquals(0x30, der[0].toInt())
        assertEquals(0x45, der[1].toInt() and 0xFF)
        assertEquals(0x02, der[2].toInt())
        assertEquals(0x21, der[3].toInt() and 0xFF)
        assertEquals(0x00, der[4].toInt()) // r 的符号位前缀
        assertEquals(0x80, der[5].toInt() and 0xFF)
        // r 段后是 s 的 INTEGER 头
        assertEquals(0x02, der[2 + 35].toInt())
        assertEquals(0x20, der[2 + 35 + 1].toInt() and 0xFF)
    }

    @Test
    fun jwkInHeaderIsStableAcrossSignings() {
        // 同一密钥多次签名：header 中 jwk 完全一致（JWK thumbprint 稳定性的等价公开行为；
        // main 源码未暴露独立 thumbprint API，以 jwk 序列化稳定性替代验证）
        val kp = DpopKeyPair.generate()
        val header1 = String(Base64Url.decode(DpopSigner.sign(kp, "POST", STS_URL, 1700000000L).split(".")[0]))
        val header2 = String(Base64Url.decode(DpopSigner.sign(kp, "POST", STS_URL, 1700009999L).split(".")[0]))
        val jwk1 = json.parseToJsonElement(header1).jsonObject["jwk"]!!.jsonObject
        val jwk2 = json.parseToJsonElement(header2).jsonObject["jwk"]!!.jsonObject
        assertEquals(jwk1, jwk2)
        // 字段顺序固定（kty, crv, x, y）
        assertEquals(listOf("kty", "crv", "x", "y"), jwk1.keys.toList())
    }
}