package com.lxseek.hw2api.core.util

import java.time.Instant
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException

/**
 * ISO8601 时间解析（语义对齐 Python `_parse_exp`）：
 * 支持 `2026-10-16T07:27:35.696Z`（毫秒）与 `2026-10-16T07:27:35Z`（秒）两种形态，
 * 兼容带偏移的 `+08:00` 写法。全部转 epoch 毫秒（UTC），与本地时区无关。
 */
object Iso8601 {

    /** 解析失败返回 0（对齐 Python 侧 `_parse_exp` 失败返回 0.0 的语义） */
    fun parseEpochMillis(text: String): Long = try {
        Instant.parse(text).toEpochMilli()
    } catch (_: DateTimeParseException) {
        try {
            OffsetDateTime.parse(text).toInstant().toEpochMilli()
        } catch (_: DateTimeParseException) {
            0L
        }
    } catch (_: RuntimeException) {
        0L
    }

    /** epoch 毫秒 → ISO8601 UTC 字符串（毫秒精度，用于存储/展示） */
    fun formatEpochMillis(epochMillis: Long): String =
        Instant.ofEpochMilli(epochMillis).toString()
}