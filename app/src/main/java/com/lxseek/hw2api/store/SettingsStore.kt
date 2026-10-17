package com.lxseek.hw2api.store

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.lxseek.hw2api.server.ServerConfig

/**
 * 运行设置存储（plan.md §3.2 SettingsScreen 对应）：端口（默认 8788）、API Key、
 * 局域网监听开关（默认关 = 仅 127.0.0.1）、鉴权开关。
 *
 * API Key 属敏感口令，与账号库一样走 EncryptedSharedPreferences（独立文件）。
 * 语义对齐源项目 CODEARTS_PROXY_API_KEY：key 为空（或鉴权开关关闭）时不鉴权。
 */
class SettingsStore private constructor(context: Context) {

    private val prefs: SharedPreferences = try {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            FILE_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    } catch (e: Exception) {
        context.deleteSharedPreferences(FILE_NAME)
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            FILE_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    /** 监听端口（默认 8788）：setter 原样落盘不钳制（钳制下放 serverConfig() 读取侧，
     *  避免 UI 输入中间态被 coerce 改写打断；设置页仅在合法区间内提交） */
    var port: Int
        get() = prefs.getInt(KEY_PORT, ServerConfig.DEFAULT_PORT)
        set(value) {
            prefs.edit().putInt(KEY_PORT, value).apply()
        }

    /** 局域网鉴权口令（空 = 不鉴权） */
    var apiKey: String
        get() = prefs.getString(KEY_API_KEY, "") ?: ""
        set(value) {
            prefs.edit().putString(KEY_API_KEY, value).apply()
        }

    /** 鉴权开关（与 apiKey 联合生效：开关开且 key 非空才鉴权） */
    var authEnabled: Boolean
        get() = prefs.getBoolean(KEY_AUTH_ENABLED, true)
        set(value) {
            prefs.edit().putBoolean(KEY_AUTH_ENABLED, value).apply()
        }

    /** 监听范围：false = 仅本机 127.0.0.1（默认），true = 0.0.0.0 局域网可接入 */
    var lanListen: Boolean
        get() = prefs.getBoolean(KEY_LAN_LISTEN, false)
        set(value) {
            prefs.edit().putBoolean(KEY_LAN_LISTEN, value).apply()
        }

    /** 当前设置快照 → server 启动配置（端口钳制在此执行：兜底异常存量值到合法监听区间） */
    fun serverConfig(): ServerConfig = ServerConfig(
        port = port.coerceIn(1024, 65535),
        listenAllInterfaces = lanListen,
        apiKey = if (authEnabled) apiKey.trim() else "",
    )

    companion object {
        /** 默认端口（与 ServerConfig.DEFAULT_PORT 一致；UI 初始化状态引用） */
        const val DEFAULT_PORT = ServerConfig.DEFAULT_PORT

        private const val FILE_NAME = "hw2api_settings"
        private const val KEY_PORT = "settings_port"
        private const val KEY_API_KEY = "settings_api_key"
        private const val KEY_AUTH_ENABLED = "settings_auth_enabled"
        private const val KEY_LAN_LISTEN = "settings_lan_listen"

        @Volatile
        private var instance: SettingsStore? = null

        fun get(context: Context): SettingsStore =
            instance ?: synchronized(this) {
                instance ?: SettingsStore(context.applicationContext).also { instance = it }
            }
    }
}