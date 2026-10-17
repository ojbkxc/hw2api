package com.lxseek.hw2api.core.auth

import com.lxseek.hw2api.core.util.Base64Url
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.interfaces.ECPrivateKey
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPrivateKeySpec
import java.security.spec.ECPublicKeySpec

/**
 * DPoP P-256 密钥对（语义对齐 Python `_gen_dpop_keypair`，复刻插件 generateDpopKeyPair）。
 * JWK 以 LinkedHashMap 保序导出：字段顺序 kty, crv, x, y(, d)——紧凑 JSON 序列化时顺序稳定。
 * 公钥 JWK 仅 x/y，私钥 JWK 额外含 d；存库只存私钥 JWK，公钥可由私钥推导恢复。
 */
class DpopKeyPair private constructor(
    val keyPair: KeyPair,
    val publicKeyJwk: Map<String, String>,
    val privateKeyJwk: Map<String, String>,
) {
    companion object {

        private const val CURVE = "secp256r1"

        /** JWK 字段名常量 */
        const val JWK_KTY = "kty"
        const val JWK_CRV = "crv"
        const val JWK_X = "x"
        const val JWK_Y = "y"
        const val JWK_D = "d"
        const val KTY_EC = "EC"
        const val CRV_P256 = "P-256"

        /** 生成新的 P-256 密钥对并导出 JWK */
        fun generate(): DpopKeyPair {
            val kpg = KeyPairGenerator.getInstance("EC")
            kpg.initialize(ECGenParameterSpec(CURVE))
            val kp = kpg.generateKeyPair()
            return fromKeyPair(kp)
        }

        /** 从存储的私钥 JWK（含 d）恢复密钥对；公钥 JWK 由椭圆曲线点乘推导 */
        fun fromPrivateKeyJwk(privateJwk: Map<String, String>): DpopKeyPair {
            require(privateJwk[JWK_KTY] == KTY_EC && privateJwk[JWK_CRV] == CRV_P256) {
                "仅支持 EC P-256 JWK"
            }
            val d = BigInteger(1, Base64Url.decode(requireNotNull(privateJwk[JWK_D]) { "私钥 JWK 缺少 d" }))
            // 用标准 NIST P-256 参数域恢复（JCA 内置 secp256r1 域参数）
            val tmp = KeyPairGenerator.getInstance("EC").apply {
                initialize(ECGenParameterSpec(CURVE))
            }.generateKeyPair()
            val params: ECParameterSpec = (tmp.public as ECPublicKey).params
            // 公钥点 = d * G（标量乘法由 KeyFactory 代劳：先建私钥再提公钥点）
            val priv = KeyFactory.getInstance("EC")
                .generatePrivate(ECPrivateKeySpec(d, params))
            // 通过椭圆曲线参数反推公钥：EC KeyFactory 不支持直接从私钥导公钥，
            // 用 BigInteger 点乘（JCA 内部算法）：
            val pubPoint = multiplyGenerator(params, d)
            val pub = KeyFactory.getInstance("EC")
                .generatePublic(ECPublicKeySpec(pubPoint, params))
            val kp = KeyPair(pub, priv)
            return fromKeyPair(kp)
        }

        /** NIST P-256（secp256r1）素域模数 p = 2^256 - 2^224 + 2^192 + 2^96 - 1 */
        private val P256_PRIME = BigInteger(
            "ffffffff00000001000000000000000000000000ffffffffffffffffffffffff", 16
        )

        /** 椭圆曲线标量乘 d*G（素域 double-and-add，纯 JCA 数学，无第三方库） */
        private fun multiplyGenerator(params: ECParameterSpec, d: BigInteger): java.security.spec.ECPoint {
            val g = params.generator
            val p = P256_PRIME
            val n = params.order
            // 简洁实现：反复倍加（double-and-add），曲线为素域短 Weierstrass 形式
            var rx = BigInteger.ZERO
            var ry = BigInteger.ZERO
            var gx = g.affineX
            var gy = g.affineY
            var k = d.mod(n)
            var started = false
            while (k.signum() > 0) {
                if (k.testBit(0)) {
                    if (!started) {
                        rx = gx; ry = gy; started = true
                    } else {
                        val s = pointAdd(rx, ry, gx, gy, p)
                        rx = s.first; ry = s.second
                    }
                }
                val dbl = pointAdd(gx, gy, gx, gy, p)
                gx = dbl.first; gy = dbl.second
                k = k.shiftRight(1)
            }
            return java.security.spec.ECPoint(rx, ry)
        }

        /** 素域短 Weierstrass 曲线点加/倍（P-256: y² = x³ - 3x + b） */
        private fun pointAdd(
            x1: BigInteger, y1: BigInteger,
            x2: BigInteger, y2: BigInteger,
            prime: BigInteger,
        ): Pair<BigInteger, BigInteger> {
            if (x1.signum() == 0 && y1.signum() == 0) return x2 to y2
            if (x2.signum() == 0 && y2.signum() == 0) return x1 to y1
            return if (x1 == x2 && y1 == y2) {
                // 点倍：slope = (3x² + a) / (2y)，a = -3
                val num = x1.multiply(x1).multiply(BigInteger.valueOf(3)).subtract(BigInteger.valueOf(3))
                val den = y1.shiftLeft(1)
                val slope = num.multiply(den.modInverse(prime)).mod(prime)
                val x3 = slope.multiply(slope).subtract(x1.shiftLeft(1)).mod(prime)
                val y3 = slope.multiply(x1.subtract(x3)).subtract(y1).mod(prime)
                x3 to y3
            } else {
                val slope = y2.subtract(y1).multiply(x2.subtract(x1).modInverse(prime)).mod(prime)
                val x3 = slope.multiply(slope).subtract(x1).subtract(x2).mod(prime)
                val y3 = slope.multiply(x1.subtract(x3)).subtract(y1).mod(prime)
                x3 to y3
            }
        }

        private fun fromKeyPair(kp: KeyPair): DpopKeyPair {
            val pub = kp.public as ECPublicKey
            val priv = kp.private as ECPrivateKey
            val x = pub.w.affineX
            val y = pub.w.affineY
            val d = priv.s
            // 固定 32 字节大端（P-256 坐标恒为 32 字节，不足补零、去掉符号位）
            val xBytes = to32Bytes(x)
            val yBytes = to32Bytes(y)
            val dBytes = to32Bytes(d)
            val publicJwk = linkedMapOf(
                JWK_KTY to KTY_EC,
                JWK_CRV to CRV_P256,
                JWK_X to Base64Url.encode(xBytes),
                JWK_Y to Base64Url.encode(yBytes),
            )
            val privateJwk = linkedMapOf(
                JWK_KTY to KTY_EC,
                JWK_CRV to CRV_P256,
                JWK_X to Base64Url.encode(xBytes),
                JWK_Y to Base64Url.encode(yBytes),
                JWK_D to Base64Url.encode(dBytes),
            )
            return DpopKeyPair(kp, publicJwk, privateJwk)
        }

        /** BigInteger → 固定 32 字节大端（不足左补零，超出则截断符号位后的有效位） */
        internal fun to32Bytes(v: BigInteger): ByteArray {
            val raw = v.toByteArray()
            return when {
                raw.size == 32 -> raw
                raw.size > 32 -> raw.copyOfRange(raw.size - 32, raw.size) // 去掉符号位前导零
                else -> ByteArray(32).also { raw.copyInto(it, 32 - raw.size) }
            }
        }
    }

    /** 私钥 JWK 的 JSON 字符串形态（Map 保序，供 kotlinx.serialization 序列化） */
    fun privateKeyJwkMap(): Map<String, String> = LinkedHashMap(privateKeyJwk)
}