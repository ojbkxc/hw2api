package com.lxseek.hw2api.core.util

/** hex 编解码工具（小写输出，语义对齐 Python `bytes.hex()` / `bytes.fromhex()`） */
object HexUtil {

    private val HEX_CHARS = "0123456789abcdef".toCharArray()

    /** 字节数组 → 小写 hex 字符串 */
    fun encode(bytes: ByteArray): String {
        val out = CharArray(bytes.size * 2)
        for (i in bytes.indices) {
            val v = bytes[i].toInt() and 0xFF
            out[i * 2] = HEX_CHARS[v ushr 4]
            out[i * 2 + 1] = HEX_CHARS[v and 0x0F]
        }
        return String(out)
    }

    /** hex 字符串 → 字节数组；非法输入抛 [IllegalArgumentException] */
    fun decode(hex: String): ByteArray {
        require(hex.length % 2 == 0) { "hex 长度必须为偶数: ${hex.length}" }
        val out = ByteArray(hex.length / 2)
        for (i in out.indices) {
            val hi = hexDigit(hex, i * 2)
            val lo = hexDigit(hex, i * 2 + 1)
            out[i] = ((hi shl 4) or lo).toByte()
        }
        return out
    }

    private fun hexDigit(hex: String, index: Int): Int {
        val c = hex[index]
        val v = when (c) {
            in '0'..'9' -> c - '0'
            in 'a'..'f' -> c - 'a' + 10
            in 'A'..'F' -> c - 'A' + 10
            else -> throw IllegalArgumentException("非法 hex 字符: $c")
        }
        return v
    }
}