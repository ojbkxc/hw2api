package com.lxseek.hw2api.util

import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Collections

/**
 * 局域网 IPv4 探测（plan.md §3.2 NetUtils）。
 * 枚举网卡（无需特殊权限）：优先返回站点本地地址（192.168.x.x / 10.x.x.x / 172.16-31.x.x），
 * 找不到时回退任意非回环 IPv4（移动数据 / 热点场景），仅用于主界面展示接入地址。
 */
object NetUtils {

    /** 探测本机局域网 IPv4；无可用网卡返回 null */
    fun getLanIpv4(): String? {
        return try {
            // Kotlin stdlib 无 Enumeration 扩展，用 Collections.list 转换
            val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
            val all = interfaces.asSequence()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { nic -> Collections.list(nic.inetAddresses).asSequence() }
                .filterIsInstance<Inet4Address>()
                .filter { !it.isLoopbackAddress }
                .map { it.hostAddress ?: "" }
                .filter { it.isNotBlank() }
                .toList()
            // 优先站点本地（典型 Wi-Fi 局域网），其次任意（运营商 / 热点网段）
            all.firstOrNull { it.isSiteLocalAddressText() } ?: all.firstOrNull()
        } catch (_: Exception) {
            null
        }
    }

    /** 192.168 / 10 / 172.16-31 网段判断（Inet4Address.isSiteLocalAddress 的字符串版，避免重复解析） */
    private fun String.isSiteLocalAddressText(): Boolean {
        val parts = split(".")
        if (parts.size != 4) return false
        val a = parts[0].toIntOrNull() ?: return false
        val b = parts[1].toIntOrNull() ?: return false
        return a == 10 || a == 192 && b == 168 || a == 172 && b in 16..31
    }
}
