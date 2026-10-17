package com.lxseek.hw2api.core.util

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * ISO8601 毫秒解析测试（语义对拍 codearts_proxy.py::_parse_exp，
 * plan.md 2.2 验收：`2026-10-16T07:27:35.696Z` → epoch 毫秒往返无损）。
 *
 * golden 向量来源：Python `calendar.timegm(datetime.strptime(...).timetuple()) * 1000
 * + microsecond // 1000` 精确推导（无浮点误差）：
 * - "2026-10-16T07:27:35.696Z" → 1792135655696
 * - "2026-10-16T07:27:35Z"     → 1792135655000
 */
class Iso8601Test {

    @Test
    fun parsesMillisecondPrecisionUtc() {
        // 附录 A.1 expiration 实际形态（毫秒）
        assertEquals(1792135655696L, Iso8601.parseEpochMillis("2026-10-16T07:27:35.696Z"))
    }

    @Test
    fun parsesSecondPrecisionUtc() {
        // 无毫秒形态（Python 兜底 "%Y-%m-%dT%H:%M:%SZ"）
        assertEquals(1792135655000L, Iso8601.parseEpochMillis("2026-10-16T07:27:35Z"))
    }

    @Test
    fun parsesOffsetFormAsSameInstant() {
        // +08:00 偏移写法：与 UTC 同一瞬间等价
        assertEquals(
            Iso8601.parseEpochMillis("2026-10-16T07:27:35.696Z"),
            Iso8601.parseEpochMillis("2026-10-16T15:27:35.696+08:00"),
        )
        assertEquals(1792135655696L, Iso8601.parseEpochMillis("2026-10-16T15:27:35.696+08:00"))
    }

    @Test
    fun returnsZeroForInvalidInput() {
        // 解析失败返回 0（对齐 Python _parse_exp 失败返回 0.0 的语义）
        assertEquals(0L, Iso8601.parseEpochMillis("not-a-date"))
        assertEquals(0L, Iso8601.parseEpochMillis(""))
        assertEquals(0L, Iso8601.parseEpochMillis("2026-10-16"))
    }

    @Test
    fun formatsEpochMillisBackToUtcIso() {
        // epoch 毫秒 → ISO8601 UTC 字符串（毫秒精度）
        assertEquals("2026-10-16T07:27:35.696Z", Iso8601.formatEpochMillis(1792135655696L))
        // 整秒：Instant.toString 不带毫秒尾巴
        assertEquals("2026-10-16T07:27:35Z", Iso8601.formatEpochMillis(1792135655000L))
    }

    @Test
    fun roundTripPreservesEpochMillis() {
        // 往返无损：parse → format → parse 得同一 epoch 毫秒
        val original = 1792135655696L
        val formatted = Iso8601.formatEpochMillis(original)
        assertEquals(original, Iso8601.parseEpochMillis(formatted))
    }

    @Test
    fun epochZeroBoundary() {
        assertEquals(0L, Iso8601.parseEpochMillis("1970-01-01T00:00:00Z"))
        assertEquals(1L, Iso8601.parseEpochMillis("1970-01-01T00:00:00.001Z"))
        assertEquals(-1L, Iso8601.parseEpochMillis("1969-12-31T23:59:59.999Z"))
    }
}