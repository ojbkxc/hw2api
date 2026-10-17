package com.lxseek.hw2api.core.util

import java.util.Base64

/** base64url 无 padding 编解码（语义对齐 Python `_b64url` / `_b64url_decode`） */
object Base64Url {

    private val encoder = Base64.getUrlEncoder().withoutPadding()
    private val decoder = Base64.getUrlDecoder()

    /** 字节数组 → base64url 字符串（无 '=' padding） */
    fun encode(bytes: ByteArray): String = encoder.encodeToString(bytes)

    /** base64url 字符串（可带或不带 padding）→ 字节数组 */
    fun decode(text: String): ByteArray = decoder.decode(text)
}