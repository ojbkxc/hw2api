package com.lxseek.hw2api.core.auth

import com.lxseek.hw2api.core.model.Account
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 凭证续期判定 + 单飞去重（语义对齐 Python `_maybe_refresh` 的并发语义，plan.md §3.1）。
 * 纯逻辑：真实网络刷新由 [refresher] 回调执行（内部调 StsClient.refresh + 回写存储），
 * 调度落地在 app 层（RefreshScheduler）。
 *
 * 单飞语义：Mutex 保证同一时刻仅一个真实刷新在飞；
 * 锁后双检通过 [latestAccountLoader] 在锁内重读存储最新账号——并发等待者据此复用
 * 刚刷新的结果，不重复发请求（旧引用的 refresh token 已被 rotation 作废，也不能再刷）。
 */
class TokenRefresher(
    /** 临期阈值：exp − now ≤ 该值（秒）时触发续期 */
    private val refreshAheadSeconds: Long = DEFAULT_REFRESH_AHEAD_SEC,
    private val clock: () -> Long = { System.currentTimeMillis() },
    /** 锁内重读最新账号（真双检）：缺省 null 退回传入引用（纯逻辑单测形态） */
    private val latestAccountLoader: (suspend () -> Account?)? = null,
) {

    companion object {
        /** CAS 凭证 2h 有效，对齐客户端提前 1h 续期 */
        const val DEFAULT_REFRESH_AHEAD_SEC = 3600L
    }

    /** 真实刷新动作：输入旧账号，返回续期后的新账号（由 app 层串联 StsClient + AccountStoreApi） */
    fun interface Refresher {
        suspend fun refresh(account: Account): Account
    }

    private val mutex = Mutex()

    /** 是否临期（exp − now ≤ refreshAheadSeconds）；无 exp 视为需刷新 */
    fun needsRefresh(account: Account): Boolean {
        val exp = account.credentials.expiration
        if (exp <= 0) return true
        return exp - clock() <= refreshAheadSeconds * 1000
    }

    /**
     * 取"可用"账号：未临期直接返回；临期则单飞刷新。
     * 并发调用时仅第一个真实执行刷新，其余等待后复用其结果。
     * 刷新失败且 CAS 凭证仍有效 → 返回旧账号（继续用到自然过期）；
     * 刷新失败且 CAS 已过期 → 抛出原异常（上层引导重新登录）。
     */
    suspend fun ensureFresh(account: Account, refresher: Refresher): Account {
        if (!needsRefresh(account)) return account
        return mutex.withLock {
            // 真双检：锁内从存储重读最新账号再判临期。双检若仍基于调用方传入的旧引用，
            // 并发者各持旧引用会串行重复刷新（且旧 refresh token 已被 rotation 作废，必然失败）
            val latest = latestAccountLoader?.invoke() ?: account
            if (!needsRefresh(latest)) return@withLock latest
            try {
                refresher.refresh(latest)
            } catch (e: StsException) {
                // CAS 凭证独立于 refresh 链（2h 寿命）：仍有效则继续用，抛错引导重登留给 CAS 过期场景
                if (!latest.credentials.isExpired(clock())) {
                    latest
                } else {
                    throw e
                }
            }
        }
    }

    /**
     * 上游 401 强制续期入口（无视 3600s 阈值）。
     * 同样受单飞保护：并发触发只发一次真实请求。
     */
    suspend fun forceRefresh(account: Account, refresher: Refresher): Account = mutex.withLock {
        refresher.refresh(account)
    }
}