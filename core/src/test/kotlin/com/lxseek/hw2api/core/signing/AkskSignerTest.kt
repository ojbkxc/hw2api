package com.lxseek.hw2api.core.signing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URI

/**
 * 华为云 SDK-HMAC-SHA256 签名测试（语义对拍 codearts_proxy.py::sign_request）。
 *
 * golden 向量来源：复刻 Python sign_request 算法（_url_encode / parse_qsl / sorted / HMAC），
 * 固定 X-Sdk-Date=20260101T000000Z（epoch 毫秒 1767225600000）后离线推导 Authorization
 * 全串与中间量，写死断言（推导脚本一次性运行后已删除）。
 *
 * 中间量备查（失败时诊断用，Python 侧推导）：
 * - 向量 A canonicalRequest =
 *   "POST\n/api/v2/chat/completions/\n\ncontent-type:application/json\n
 *    host:snap-access.cn-north-4.myhuaweicloud.com\nx-language:zh-cn\n
 *    x-ot-session-id:fedcba9876543210fedcba9876543210\n
 *    x-ot-trace-id:0123456789abcdef0123456789abcdef\n
 *    x-sdk-content-sha256:UNSIGNED-PAYLOAD\nx-sdk-date:20260101T000000Z\n
 *    x-security-token:TEST_SECURITY_TOKEN_123\n\n
 *    content-type;host;x-language;x-ot-session-id;x-ot-trace-id;x-sdk-content-sha256;x-sdk-date;x-security-token\n
 *    UNSIGNED-PAYLOAD"
 * - 向量 A creqHash = 348cc9ceb034967daf90c8e21d205954ba17f496beaf706bddccc0e2d14ae86d
 * - 向量 B payloadHash（空体缺省）= e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855
 * - 向量 B canonicalQuery = a=1&a=x%3Dy&b=2
 */
class AkskSignerTest {

    /** 固定签名时刻：2026-01-01T00:00:00Z */
    private val fixedNowMillis = 1767225600000L

    private val fixedDate = "20260101T000000Z"

    @Test
    fun encodeUrlKeepsSafeCharacters() {
        // 语义对齐 Python _url_encode：A-Za-z0-9-._~ 原样保留
        assertEquals("AZaz09-._~", AkskSigner.encodeUrl("AZaz09-._~"))
        assertEquals("plain", AkskSigner.encodeUrl("plain"))
    }

    @Test
    fun encodeUrlPercentEncodesOthersInUppercaseUtf8() {
        // 其余字符按 UTF-8 字节 %XX 大写转义（Python "%%%02X" 语义）
        assertEquals("a%20b", AkskSigner.encodeUrl("a b"))
        assertEquals("a%2Bb", AkskSigner.encodeUrl("a+b"))
        assertEquals("a%2Fb", AkskSigner.encodeUrl("a/b"))
        assertEquals("a%3Db", AkskSigner.encodeUrl("a=b"))
        assertEquals("a%26b", AkskSigner.encodeUrl("a&b"))
        // 中文（UTF-8 三字节）：E4 BD A0 / E5 A5 BD
        assertEquals("%E4%BD%A0%E5%A5%BD", AkskSigner.encodeUrl("你好"))
    }

    @Test
    fun buildCanonicalUriEncodesEachSegmentAndAppendsTrailingSlash() {
        // 末尾必补 /（Python：if not uri.endswith("/"): uri += "/"）
        assertEquals("/api/v2/chat/completions/", AkskSigner.buildCanonicalUri("/api/v2/chat/completions"))
        assertEquals("/v1/models/", AkskSigner.buildCanonicalUri("/v1/models"))
        // 已带尾斜杠：原样
        assertEquals("/a/b/", AkskSigner.buildCanonicalUri("/a/b/"))
        // 空路径与根路径："/"
        assertEquals("/", AkskSigner.buildCanonicalUri(""))
        assertEquals("/", AkskSigner.buildCanonicalUri("/"))
        // 每段独立 encode：空格转 %20，安全字符 ~ 保留
        assertEquals("/a%20b/c~d/", AkskSigner.buildCanonicalUri("/a b/c~d"))
    }

    @Test
    fun buildCanonicalQueryEmptyReturnsBlank() {
        // 空 query（null / 空串）→ canonicalRequest 中该行为空行
        assertEquals("", AkskSigner.buildCanonicalQuery(null))
        assertEquals("", AkskSigner.buildCanonicalQuery(""))
    }

    @Test
    fun buildCanonicalQuerySortsAndEncodes() {
        // 含 & 与 = 的参数：k/v 拆分、排序、encode（Python parse_qsl + sorted 语义）
        // "a=x=y&b=2&a=1" → ("a","x=y"),("b","2"),("a","1") → 排序 → a=1&a=x%3Dy&b=2
        assertEquals("a=1&a=x%3Dy&b=2", AkskSigner.buildCanonicalQuery("a=x=y&b=2&a=1"))
        // 字典序（k 优先，k 同比 v）
        assertEquals("a=1&b=2", AkskSigner.buildCanonicalQuery("b=2&a=1"))
        assertEquals("a=1&a=2", AkskSigner.buildCanonicalQuery("a=2&a=1"))
        // 裸 key（无 =）：等价空值（Python keep_blank_values=True → "flag="）
        assertEquals("flag=", AkskSigner.buildCanonicalQuery("flag"))
        // 值需转义的字符
        assertEquals("q=a%20b", AkskSigner.buildCanonicalQuery("q=a b"))
    }

    @Test
    fun buildHostOmitsStandardPorts() {
        // https:443 / http:80 不拼端口（Python：p.port and not (标准端口) 才拼）
        assertEquals("example.test", AkskSigner.buildHost(URI("https://example.test:443/path")))
        assertEquals("example.test", AkskSigner.buildHost(URI("http://example.test:80/path")))
        // 未显式写端口：host 原样
        assertEquals("example.test", AkskSigner.buildHost(URI("https://example.test/path")))
    }

    @Test
    fun buildHostAppendsNonStandardPort() {
        assertEquals("example.test:8443", AkskSigner.buildHost(URI("https://example.test:8443/path")))
        assertEquals("example.test:8080", AkskSigner.buildHost(URI("http://example.test:8080/path")))
        assertEquals("127.0.0.1:8789", AkskSigner.buildHost(URI("http://127.0.0.1:8789/oauth/callback")))
        // https 的 80 / http 的 443 属非标准端口，仍需拼接
        assertEquals("example.test:80", AkskSigner.buildHost(URI("https://example.test:80/path")))
    }

    @Test
    fun sdkDateNowFormatsUtcCompactPattern() {
        // X-Sdk-Date：UTC yyyyMMddTHHmmssZ
        assertEquals(fixedDate, AkskSigner.sdkDateNow(fixedNowMillis))
        // 年末边界
        assertEquals("20261231T235959Z", AkskSigner.sdkDateNow(1798761599000L))
    }

    @Test
    fun signRequestGoldenChatScenario() {
        // golden 向量 A：上游 chat 场景（UNSIGNED-PAYLOAD + 6 业务头 + 固定时刻）
        val result = AkskSigner.signRequest(
            method = "POST",
            url = "https://snap-access.cn-north-4.myhuaweicloud.com/api/v2/chat/completions",
            headers = linkedMapOf(
                "X-Security-Token" to "TEST_SECURITY_TOKEN_123",
                "X-Sdk-Content-Sha256" to "UNSIGNED-PAYLOAD",
                "x-ot-trace-id" to "0123456789abcdef0123456789abcdef",
                "x-ot-session-id" to "fedcba9876543210fedcba9876543210",
                "X-Language" to "zh-cn",
                "Content-Type" to "application/json",
            ),
            ak = "TESTAKIDEXAMPLE",
            sk = "TESTSKEXAMPLE0000000000000000000000",
            nowEpochMillis = fixedNowMillis,
        )
        // Authorization 全串逐字符断言（Python sign_request 同输入推导）
        assertEquals(
            "SDK-HMAC-SHA256 Access=TESTAKIDEXAMPLE, " +
                "SignedHeaders=content-type;host;x-language;x-ot-session-id;x-ot-trace-id;" +
                "x-sdk-content-sha256;x-sdk-date;x-security-token, " +
                "Signature=d33a65f4ea07becc6d9d24f6855e5c1f43c64bf1ee45f2a342b9d037da2591cd",
            result["Authorization"],
        )
        // 返回头集合补齐 X-Sdk-Date / Host，且原头保留
        assertEquals(fixedDate, result["X-Sdk-Date"])
        assertEquals("snap-access.cn-north-4.myhuaweicloud.com", result["Host"])
        assertEquals("zh-cn", result["X-Language"])
        assertEquals("UNSIGNED-PAYLOAD", result["X-Sdk-Content-Sha256"])
    }

    @Test
    fun signRequestGoldenDefaultPayloadHashAndQuery() {
        // golden 向量 B：GET + query 排序 + 无 X-Sdk-Content-Sha256（payloadHash 缺省空体 SHA256）
        val result = AkskSigner.signRequest(
            method = "GET",
            url = "https://example.test/v1/models?a=x=y&b=2&a=1",
            headers = linkedMapOf("X-Language" to "zh-cn"),
            ak = "AKIDB",
            sk = "SKB",
            nowEpochMillis = fixedNowMillis,
        )
        assertEquals(
            "SDK-HMAC-SHA256 Access=AKIDB, SignedHeaders=host;x-language;x-sdk-date, " +
                "Signature=4cdebd527e3de98add68783dcb9ba819592b2d82508b2b72d3aae72d681ca6f7",
            result["Authorization"],
        )
        assertEquals("example.test", result["Host"])
    }

    @Test
    fun signRequestIsDeterministicForFixedInputs() {
        // 固定输入（含固定 now 注入）下 Authorization 全串可复现
        val args = {
            AkskSigner.signRequest(
                "POST",
                "https://example.test/x",
                linkedMapOf("X-Language" to "zh-cn"),
                "AK", "SK", fixedNowMillis,
            )
        }
        assertEquals(args()["Authorization"], args()["Authorization"])
    }

    @Test
    fun signRequestHeaderNamesSortedCaseInsensitively() {
        // 头名混合大小写：SignedHeaders 全小写且字典序（Python sorted(key=str.lower) 语义）
        val result = AkskSigner.signRequest(
            method = "POST",
            url = "https://example.test/x",
            headers = linkedMapOf(
                "Z-Last-Header" to "v3",
                "a-First-Header" to "v1",
                "M-Middle-Header" to "v2",
            ),
            ak = "AK",
            sk = "SK",
            nowEpochMillis = fixedNowMillis,
        )
        val auth = result["Authorization"]!!
        val signedHeaders = Regex("SignedHeaders=([^,]+),").find(auth)!!.groupValues[1]
        assertEquals("a-first-header;host;m-middle-header;x-sdk-date;z-last-header", signedHeaders)
        // 头值中的 CR/LF 应被替换为空格（防头注入，Python replace 语义）
        val injected = AkskSigner.signRequest(
            method = "POST",
            url = "https://example.test/x",
            headers = linkedMapOf("X-Evil" to "a\r\nb"),
            ak = "AK", sk = "SK",
            nowEpochMillis = fixedNowMillis,
        )
        assertTrue(injected["Authorization"]!!.isNotBlank())
    }

    @Test
    fun unsignedPayloadConstantMatchesUpstreamRequirement() {
        // 上游实测必需：POST 网关要求显式 X-Sdk-Content-Sha256: UNSIGNED-PAYLOAD
        assertEquals("UNSIGNED-PAYLOAD", AkskSigner.UNSIGNED_PAYLOAD)
        assertEquals("SDK-HMAC-SHA256", AkskSigner.ALGORITHM)
    }
}