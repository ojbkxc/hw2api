package com.lxseek.hw2api.core.signing

import com.lxseek.hw2api.core.util.HexUtil
import java.net.URI
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * 华为云 SDK-HMAC-SHA256 签名（语义对齐 Python `sign_request`，复刻内核 AKSKSigner.sign）。
 *
 * canonicalRequest = method\n + uri\n + query\n + canonicalHeaders + \n + signedHeaders\n + payloadHash
 *   （canonicalHeaders 每行 name:value\n 自带末尾换行，之后再接一个显式 \n 才是 signedHeaders 行）
 * stringToSign = "SDK-HMAC-SHA256\n" + X-Sdk-Date + "\n" + hex(SHA256(canonicalRequest))
 * Authorization: SDK-HMAC-SHA256 Access={ak}, SignedHeaders={names}, Signature={hex(HMAC-SHA256(sk, stringToSign))}
 *
 * 细节铁律（附录 A.4）：
 * - canonicalURI 每段 URL encode（安全字符 A-Za-z0-9-._~，其余 %XX 大写 UTF-8），末尾必补 /
 * - canonicalHeaders：参与签名头按名字小写排序，Host 必含（https 443 不拼端口）
 * - payloadHash 取 X-Sdk-Content-Sha256 的值（chat 请求恒为 UNSIGNED-PAYLOAD），缺省空体 SHA256
 */
object AkskSigner {

    const val ALGORITHM = "SDK-HMAC-SHA256"
    const val UNSIGNED_PAYLOAD = "UNSIGNED-PAYLOAD"

    private val SDK_DATE_FORMAT = DateTimeFormatter
        .ofPattern("yyyyMMdd'T'HHmmss'Z'")
        .withZone(ZoneOffset.UTC)

    private const val SAFE = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~"

    /** X-Sdk-Date（UTC yyyyMMddTHHmmssZ）；调用方与签名保持同一时刻 */
    fun sdkDateNow(nowEpochMillis: Long): String = SDK_DATE_FORMAT.format(Instant.ofEpochMilli(nowEpochMillis))

    /**
     * 对给定头集合做签名，返回补齐后的完整头（新增 X-Sdk-Date / Host / Authorization）。
     * @param nowEpochMillis 签名时刻（单测可注入固定值）
     */
    fun signRequest(
        method: String,
        url: String,
        headers: Map<String, String>,
        ak: String,
        sk: String,
        nowEpochMillis: Long = System.currentTimeMillis(),
    ): Map<String, String> {
        val uri = URI(url)
        val dt = sdkDateNow(nowEpochMillis)

        val allHeaders = HashMap<String, String>(headers)
        allHeaders["X-Sdk-Date"] = dt
        allHeaders["Host"] = buildHost(uri)

        val canonicalUri = buildCanonicalUri(uri.rawPath ?: "/")
        val canonicalQuery = buildCanonicalQuery(uri.rawQuery)

        val sortedNames = allHeaders.keys.sortedBy { it.lowercase() }
        val signedNames = sortedNames.joinToString(";") { it.lowercase() }
        val sb = StringBuilder()
        for (name in sortedNames) {
            val v = allHeaders.getValue(name).replace("\r", " ").replace("\n", " ").trim()
            sb.append(name.lowercase()).append(':').append(v).append('\n')
        }
        val canonicalHeaders = sb.toString()

        val payloadHash = allHeaders["X-Sdk-Content-Sha256"] ?: HexUtil.encode(sha256(ByteArray(0)))

        val canonicalRequest = buildString {
            append(method.uppercase()).append('\n')
            append(canonicalUri).append('\n')
            append(canonicalQuery).append('\n')
            append(canonicalHeaders) // 自带末尾 \n
            append('\n')              // 显式空行分隔（join 语义）
            append(signedNames).append('\n')
            append(payloadHash)
        }

        val creqHash = HexUtil.encode(sha256(canonicalRequest.toByteArray(Charsets.UTF_8)))
        val stringToSign = "$ALGORITHM\n$dt\n$creqHash"
        val signature = HexUtil.encode(
            hmacSha256(sk.toByteArray(Charsets.UTF_8), stringToSign.toByteArray(Charsets.UTF_8))
        )

        val out = HashMap<String, String>(allHeaders)
        out["Authorization"] = "$ALGORITHM Access=$ak, SignedHeaders=$signedNames, Signature=$signature"
        return out
    }

    /** canonicalURI：路径每段 URL encode 后以 / 连接，末尾必补 /（对齐 Python sign_request） */
    internal fun buildCanonicalUri(path: String): String {
        val uri = (path.ifEmpty { "/" }).split("/").joinToString("/") { encodeUrl(it) }
        return if (uri.endsWith("/")) uri else "$uri/"
    }

    /** canonicalQueryString：k/v 各自 encode，按 k=v 字典序排序 & 连接；空 query 返回空串 */
    internal fun buildCanonicalQuery(rawQuery: String?): String {
        if (rawQuery.isNullOrEmpty()) return ""
        return rawQuery.split("&")
            .filter { it.isNotEmpty() }
            .map { kv ->
                val i = kv.indexOf('=')
                if (i < 0) encodeUrl(kv) to ""
                else encodeUrl(kv.substring(0, i)) to encodeUrl(kv.substring(i + 1))
            }
            .sortedWith(compareBy({ it.first }, { it.second }))
            .joinToString("&") { "${it.first}=${it.second}" }
    }

    /** Host 头：标准端口（https 443 / http 80）不拼端口，其余 host:port */
    internal fun buildHost(uri: URI): String {
        val port = uri.port
        return if (port > 0 && !((uri.scheme == "https" && port == 443) || (uri.scheme == "http" && port == 80))) {
            "${uri.host}:$port"
        } else {
            uri.host
        }
    }

    /** URL encode：安全字符 A-Za-z0-9-._~ 原样，其余按 UTF-8 字节 %XX 大写（对齐 Python `_url_encode`） */
    internal fun encodeUrl(s: String): String {
        val bytes = s.toByteArray(Charsets.UTF_8)
        val sb = StringBuilder(bytes.size)
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            // 安全字符集合成员判断（对齐 Python `c in _SAFE`）：SAFE 仅 66 字符，
            // 禁止把字节值当字符串下标（v∈[66,127] 时 SAFE[v] 越界必崩）
            if (SAFE.indexOf(v.toChar()) >= 0) {
                sb.append(v.toChar())
            } else {
                val hex = HexUtil.encode(byteArrayOf(b)).uppercase()
                sb.append('%').append(hex)
            }
        }
        return sb.toString()
    }

    private fun sha256(data: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(data)

    private fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray =
        Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(key, "HmacSHA256"))
            doFinal(data)
        }
}