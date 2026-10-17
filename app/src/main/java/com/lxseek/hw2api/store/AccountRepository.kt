package com.lxseek.hw2api.store

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.lxseek.hw2api.core.model.Account
import com.lxseek.hw2api.core.model.AccountStoreApi
import kotlinx.serialization.json.Json

/**
 * 账号存储实现（plan.md §3.2 / §4）：EncryptedSharedPreferences + MasterKey(AES256_GCM)
 * 实现 core 的 [AccountStoreApi]。
 *
 * - 每个账号一个条目：key = `account_{accountId}`，value = Account 整体 JSON（含敏感全量字段）
 * - 元数据 `active_account_id` 记录当前账号；数据层 map 语义多账号，UI 首版仅展示 active
 * - 写入用 commit()（同步落盘，保证续期后紧接着的读看到新凭证）
 */
class AccountRepository private constructor(context: Context) : AccountStoreApi {

    private val json = Json { ignoreUnknownKeys = true }

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
        // Keystore 主密钥损坏（极少数系统重置场景）：清空密文重建，引导用户重新登录
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

    override fun save(account: Account) {
        prefs.edit()
            .putString(accountKey(account.accountId), json.encodeToString(Account.serializer(), account))
            .commit()
    }

    override fun load(accountId: String): Account? {
        val text = prefs.getString(accountKey(accountId), null) ?: return null
        return runCatching { json.decodeFromString(Account.serializer(), text) }.getOrNull()
    }

    override fun list(): List<Account> {
        return prefs.all.keys
            .filter { it.startsWith(KEY_PREFIX_ACCOUNT) }
            .mapNotNull { key -> load(key.removePrefix(KEY_PREFIX_ACCOUNT)) }
            .sortedBy { it.createdAt }
    }

    override fun activeAccount(): Account? {
        val activeId = prefs.getString(KEY_ACTIVE_ACCOUNT_ID, null)
        val active = activeId?.let { load(it) }
        if (active != null) return active
        // active 指向失效（被删 / 损坏）：回退到最早入池的账号并回写，避免池非空却报未登录
        val fallback = list().firstOrNull() ?: return null
        setActive(fallback.accountId)
        return fallback
    }

    override fun setActive(accountId: String): Boolean {
        if (load(accountId) == null) return false
        prefs.edit().putString(KEY_ACTIVE_ACCOUNT_ID, accountId).commit()
        return true
    }

    override fun delete(accountId: String): Boolean {
        if (load(accountId) == null) return false
        prefs.edit().remove(accountKey(accountId)).commit()
        if (prefs.getString(KEY_ACTIVE_ACCOUNT_ID, null) == accountId) {
            // 删除的是 active：置空并回退到池内下一个账号（保持单账号 UI 有值可显）
            val next = list().firstOrNull()
            if (next != null) setActive(next.accountId)
            else prefs.edit().remove(KEY_ACTIVE_ACCOUNT_ID).commit()
        }
        return true
    }

    companion object {
        private const val FILE_NAME = "hw2api_accounts"
        private const val KEY_PREFIX_ACCOUNT = "account_"
        private const val KEY_ACTIVE_ACCOUNT_ID = "active_account_id"

        @Volatile
        private var instance: AccountRepository? = null

        /** 进程内单例（EncryptedSharedPreferences 创建开销较高，避免重复初始化） */
        fun get(context: Context): AccountRepository =
            instance ?: synchronized(this) {
                instance ?: AccountRepository(context.applicationContext).also { instance = it }
            }

        private fun accountKey(accountId: String) = KEY_PREFIX_ACCOUNT + accountId
    }
}