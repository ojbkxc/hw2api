package com.lxseek.hw2api.store

import android.content.Context
import com.lxseek.hw2api.core.auth.StsClient
import com.lxseek.hw2api.core.auth.StsException
import com.lxseek.hw2api.core.auth.TokenRefresher
import com.lxseek.hw2api.core.model.Account
import com.lxseek.hw2api.util.Http
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** 未登录（账号池为空）时由 [CredentialManager.ensureFresh] 抛出 */
class NotLoggedInException : RuntimeException("未登录华为云账号")

/**
 * 凭证管理器：串联 StsClient（网络刷新）+ AccountRepository（持久化）+ TokenRefresher（单飞判定）。
 *
 * - TokenRefresher 通过 latestAccountLoader 在锁内重读 active（真双检），不依赖调用方传新引用；
 * - refresh 链死亡（STS.5.1806）后 [refreshChainDead] 持久短路：不再向 STS 发无效重试
 *   （对齐 Python `_mark_refresh_dead` / `_maybe_refresh` 前置短路），直至重新登录清除。
 */
class CredentialManager private constructor(context: Context) {

    private val repository = AccountRepository.get(context)
    private val stsClient = StsClient(Http.client)
    private val tokenRefresher = TokenRefresher(latestAccountLoader = { repository.activeAccount() })
    private val refreshGate = Mutex()

    /** refresh 链死亡标记：置位后 ensureFresh/forceRefresh 入口短路，saveAndActivate 清除 */
    @Volatile
    private var refreshChainDead = false

    /** 真实刷新动作：STS refresh_token 轮换 → 整体回写存储 → 返回新账号；链死亡异常时置位短路标记 */
    private val refresher = TokenRefresher.Refresher { account ->
        try {
            val fresh = stsClient.refresh(account)
            repository.save(fresh)
            fresh
        } catch (e: StsException) {
            if (e.isRefreshChainDead) refreshChainDead = true
            throw e
        }
    }

    /** 当前 active 账号（未登录返回 null） */
    fun activeOrNull(): Account? = repository.activeAccount()

    /** 是否临期（exp − now ≤ 3600s，core 判定） */
    fun needsRefresh(account: Account): Boolean = tokenRefresher.needsRefresh(account)

    /**
     * 取「可用」账号：未临期直接返回；临期则单飞刷新。
     * @throws NotLoggedInException 账号池为空
     * @throws com.lxseek.hw2api.core.auth.StsException refresh 链失效且 CAS 凭证已过期（需重新登录）
     */
    suspend fun ensureFresh(): Account {
        val current = repository.activeAccount() ?: throw NotLoggedInException()
        if (!tokenRefresher.needsRefresh(current)) return current
        // 链死亡短路（对齐 Python _maybe_refresh 的 refresh_dead 前置判断）：不发请求——
        // CAS 仍有效继续用旧凭证至自然过期，已过期抛链死亡异常引导重登
        if (refreshChainDead) return shortCircuitDeadChain(current)
        return refreshGate.withLock {
            val latest = repository.activeAccount() ?: throw NotLoggedInException()
            tokenRefresher.ensureFresh(latest, refresher)
        }
    }

    /**
     * 强制续期（无视 3600s 阈值；上游 401 时的 UpstreamClient 回调入口）。
     * @throws com.lxseek.hw2api.core.auth.StsException refresh 链失效（需重新登录）
     */
    suspend fun forceRefresh(account: Account): Account {
        // 链死亡短路：401 场景不再对 STS 发无效请求
        if (refreshChainDead) return shortCircuitDeadChain(account)
        return refreshGate.withLock {
            // 并发 401 场景：别的请求可能已完成 rotation 回写存储，传入旧引用的
            // refresh token 已被作废（强刷必然 STS.5.1806 误标链死亡）——锁内重读复用
            val latest = repository.activeAccount()
            if (latest != null && latest != account) return@withLock latest
            tokenRefresher.forceRefresh(latest ?: account, refresher)
        }
    }

    /** 链死亡短路：CAS 凭证仍有效 → 返回最新账号（不发请求）；已过期 → 抛链死亡异常引导重登 */
    private fun shortCircuitDeadChain(fallback: Account): Account {
        val latest = repository.activeAccount() ?: fallback
        if (!latest.credentials.isExpired(System.currentTimeMillis())) {
            return latest
        }
        throw refreshChainDeadException()
    }

    /** 链死亡异常：构造参数保持 isRefreshChainDead（STS.5.1806）判定为真，消费方据此引导重登 */
    private fun refreshChainDeadException(): StsException = StsException(
        statusCode = 400,
        responseBody = "STS.5.1806 refresh chain dead",
        message = "凭证续期链已失效（refresh token 无效），请重新登录",
    )

    /** 登录成功后入库并激活（新 refresh 链到手：清除死亡短路标记） */
    fun saveAndActivate(account: Account) {
        repository.save(account)
        repository.setActive(account.accountId)
        refreshChainDead = false
    }

    /** 退出登录：删除 active 账号（多账号池语义，数据层仍保留其余账号） */
    fun logoutActive() {
        repository.activeAccount()?.let { repository.delete(it.accountId) }
    }

    companion object {
        @Volatile
        private var instance: CredentialManager? = null

        fun get(context: Context): CredentialManager =
            instance ?: synchronized(this) {
                instance ?: CredentialManager(context.applicationContext).also { instance = it }
            }
    }
}