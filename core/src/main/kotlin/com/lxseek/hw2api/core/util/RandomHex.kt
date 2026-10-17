package com.lxseek.hw2api.core.util

import java.security.SecureRandom

/** 安全随机 hex 字符串生成（语义对齐 Python `secrets.token_hex(n)`） */
object RandomHex {

    private val random = SecureRandom()

    /** 生成 nBytes 字节的随机数据并编码为 2*nBytes 个 hex 字符（小写） */
    fun next(nBytes: Int): String {
        require(nBytes > 0) { "nBytes 必须为正数" }
        val buf = ByteArray(nBytes)
        random.nextBytes(buf)
        return HexUtil.encode(buf)
    }

    /** 32 位 hex 随机串（16 字节，语义对齐 Python `uuid4().hex`，用于 trace/session id） */
    fun next32(): String = next(16)

    /** 64 位 hex 随机串（32 字节，语义对齐 Python `secrets.token_hex(32)`，用于 DPoP jti） */
    fun next64(): String = next(32)
}