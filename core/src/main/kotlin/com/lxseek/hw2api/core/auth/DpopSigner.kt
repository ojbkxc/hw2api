package com.lxseek.hw2api.core.auth

import com.lxseek.hw2api.core.util.Base64Url
import com.lxseek.hw2api.core.util.RandomHex

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.security.Signature
import java.security.interfaces.ECPrivateKey
import java.security.interfaces.ECPublicKey

/**
 * RFC9449 DPoP proof 构造器（语义对齐 Python `_dpop_sign`，复刻插件 generateDpopJWE）。
 *
 * proof = base64url(header) + "." + base64url(payload) + "." + base64url(signature)
 * - header 紧凑 JSON：{"alg":"ES256","typ":"dpop+jwt","jwk":publicKeyJwk}
 * - payload：{"htm":"POST","htu":url,"iat":epoch秒,"jti":64hex随机}
 * - 签名：SHA256withECDSA 对 "header.payload" 的 ASCII 做 DER 签名，
 *   解出 r/s 各 32 字节大端拼 64 字节裸签名（JOSE JWS 形态），base64url 无 padding
 */
object DpopSigner {


    /** 公钥自验签（单测用）：用公钥验证 64 字节裸签名是否匹配 signing input */
    fun verify(proof: String, publicKey: ECPublicKey): Boolean = try {
        val parts = proof.split(".")
        require(parts.size == 3) { "proof 必须为三段" }
        val signingInput = (parts[0] + "." + parts[1]).toByteArray(Charsets.US_ASCII)
        val rawSig = Base64Url.decode(parts[2])
        require(rawSig.size == 64) { "裸签名必须为 64 字节" }
        val der = rawToDer(rawSig)
        Signature.getInstance("SHA256withECDSA").run {
            initVerify(publicKey)
            update(signingInput)
            verify(der)
        }
    } catch (_: RuntimeException) {
        false
    }

    /**
     * 生成 DPoP proof。
     * @param method HTTP 方法（如 "POST"）
     * @param url 完整目标 URL（如 STS token 端点）
     * @param iatEpochSec 签发时刻（epoch 秒）
     */
    fun sign(keyPair: DpopKeyPair, method: String, url: String, iatEpochSec: Long): String {
        val header = buildJsonObject {
            put("alg", "ES256")
            put("typ", "dpop+jwt")
            putJsonObject("jwk") {
                // JWK 字段按 kty/crv/x/y 固定顺序（Map 保序，紧凑序列化）
                keyPair.publicKeyJwk.forEach { (k, v) -> put(k, v) }
            }
        }
        val payload = buildJsonObject {
            put("htm", method)
            put("htu", url)
            put("iat", iatEpochSec)
            put("jti", RandomHex.next64())
        }
        // JsonObject.toString() 即紧凑 JSON（kx-json JsonElement 覆写，key 保序），
        // 等价 Python json.dumps(separators=(",", ":"))；serialization 1.7.3 的
        // StringFormat 无 encodeToString(value, charset) 两参扩展（1.8.0 才引入），禁止使用
        val headerB64 = Base64Url.encode(header.toString().toByteArray(Charsets.UTF_8))
        val payloadB64 = Base64Url.encode(payload.toString().toByteArray(Charsets.UTF_8))
        val signingInput = "$headerB64.$payloadB64"
        val derSig = Signature.getInstance("SHA256withECDSA").run {
            initSign(keyPair.keyPair.private as ECPrivateKey)
            update(signingInput.toByteArray(Charsets.US_ASCII))
            sign()
        }
        val rawSig = derToRaw(derSig)
        return "$signingInput.${Base64Url.encode(rawSig)}"
    }

    /** DER ECDSA 签名 → 64 字节 r||s 裸签名（前导零补齐到 32 字节大端） */
    internal fun derToRaw(der: ByteArray): ByteArray {
        var idx = 0
        require(der[idx].toInt() == 0x30) { "DER 序列头异常" }
        idx += 1
        // 可能是长-form 长度（P-256 签名恒 < 128 字节，短-form 即可，但防御性处理）
        if ((der[idx].toInt() and 0xFF) and 0x80 != 0) {
            val lenBytes = (der[idx].toInt() and 0xFF) and 0x7F
            idx += 1 + lenBytes
        } else {
            idx += 1
        }
        require(der[idx].toInt() == 0x02) { "r 整数标记异常" }
        val rLen = der[idx + 1].toInt() and 0xFF
        val rBytes = der.copyOfRange(idx + 2, idx + 2 + rLen)
        idx += 2 + rLen
        require(der[idx].toInt() == 0x02) { "s 整数标记异常" }
        val sLen = der[idx + 1].toInt() and 0xFF
        val sBytes = der.copyOfRange(idx + 2, idx + 2 + sLen)
        // 去掉 DER 整数的 0x00 符号位前缀（仅 33 字节 = 32 字节内容 + 符号位时），
        // 然后左补零到固定 32 字节。此方向绝不补符号位——stripLeadingZero 会把
        // 最高位 ≥0x80 的内容重新补回 0x00（那是 rawToDer 方向的逻辑），复用会导致
        // r/s 变 33 字节触发长度断言，DPoP 签名约 75% 概率崩溃
        val r = stripSignByte(rBytes)
        val s = stripSignByte(sBytes)
        require(r.size <= 32 && s.size <= 32) { "r/s 长度异常" }
        val out = ByteArray(64)
        r.copyInto(out, 32 - r.size)
        s.copyInto(out, 64 - s.size)
        return out
    }

    /** 64 字节 r||s 裸签名 → DER（verify 自验签用） */
    internal fun rawToDer(raw: ByteArray): ByteArray {
        val r = stripLeadingZero(raw.copyOfRange(0, 32))
        val s = stripLeadingZero(raw.copyOfRange(32, 64))
        val rDer = intToDer(r)
        val sDer = intToDer(s)
        val bodyLen = rDer.size + sDer.size
        val out = ByteArray(2 + bodyLen)
        out[0] = 0x30
        out[1] = bodyLen.toByte()
        rDer.copyInto(out, 2)
        sDer.copyInto(out, 2 + rDer.size)
        return out
    }

    /** DER→raw 方向专用：仅当 33 字节（32 字节内容 + 1 符号位前缀）且首字节 0x00 时去掉首字节；
     *  其余形态原样返回（前导零不影响数值，由调用方统一左补零到 32 字节） */
    private fun stripSignByte(bytes: ByteArray): ByteArray =
        if (bytes.size == 33 && bytes[0].toInt() == 0x00) bytes.copyOfRange(1, bytes.size) else bytes

    /** raw→DER 方向专用：去前导零 + 最高位 ≥0x80 时补 0x00 符号位（DER 正整数规范形式） */
    private fun stripLeadingZero(bytes: ByteArray): ByteArray {
        var start = 0
        while (start < bytes.size - 1 && bytes[start].toInt() == 0) start++
        val stripped = bytes.copyOfRange(start, bytes.size)
        // DER 正整数最高位为 1 时需补 0x00 符号位
        return if (stripped[0].toInt() and 0x80 != 0) {
            ByteArray(stripped.size + 1).also { stripped.copyInto(it, 1) }
        } else {
            stripped
        }
    }

    private fun intToDer(intBytes: ByteArray): ByteArray {
        val out = ByteArray(2 + intBytes.size)
        out[0] = 0x02
        out[1] = intBytes.size.toByte()
        intBytes.copyInto(out, 2)
        return out
    }
}