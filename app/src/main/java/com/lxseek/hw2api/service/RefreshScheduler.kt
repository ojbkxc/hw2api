package com.lxseek.hw2api.service

import android.os.Handler
import android.os.Looper
import com.lxseek.hw2api.core.auth.StsException
import com.lxseek.hw2api.core.model.Account
import com.lxseek.hw2api.store.CredentialManager
import com.lxseek.hw2api.store.NotLoggedInException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * 凭证续期调度器（plan.md §3.2）：Handler 主循环定时检查 exp，
 * `exp − now ≤ 3600s`（core TokenRefresher 判定）时触发续期（单飞，调 CredentialManager）。
 *
 * - 续期成功 → [onAccountRefreshed]（服务刷新通知倒计时 + UI 状态）
 * - refresh 链失效（STS.5.1806）或 CAS 已过期 → [onNeedsRelogin]（通知栏 + UI 引导重新登录）
 * - 链死亡短路由 CredentialManager.ensureFresh 入口承担（refreshChainDead 标记）：
 *   tick 调用不再向 STS 发无效重试，仅 CAS 过期后每分钟回调一次引导重登（幂等）
 * - 每 tick 无论是否续期都回调 [onAccountRefreshed]（通知倒计时每分钟刷新）
 */
class RefreshScheduler(
    private val scope: CoroutineScope,
    private val credentials: CredentialManager,
    private val onAccountRefreshed: (Account?) -> Unit,
    private val onNeedsRelogin: (String) -> Unit,
) {

    private val handler = Handler(Looper.getMainLooper())

    @Volatile
    private var stopped = false

    private val tick = object : Runnable {
        override fun run() {
            if (stopped) return
            try {
                val account = credentials.activeOrNull()
                if (account == null) {
                    onNeedsRelogin("未登录华为云账号")
                } else {
                    if (credentials.needsRefresh(account)) {
                        scope.launch {
                            try {
                                credentials.ensureFresh()
                                // 成功：交给回调读最新账号刷新通知（避免传递过期快照）
                                onAccountRefreshed(credentials.activeOrNull())
                            } catch (e: StsException) {
                                val fresh = credentials.activeOrNull()
                                if (e.isRefreshChainDead ||
                                    fresh == null ||
                                    fresh.credentials.isExpired(System.currentTimeMillis())
                                ) {
                                    onNeedsRelogin("凭证续期失败：${e.message}")
                                }
                                // refresh 失败但 CAS 仍有效：继续用到自然过期（core 语义）
                            } catch (_: NotLoggedInException) {
                                onNeedsRelogin("未登录华为云账号")
                            } catch (_: Exception) {
                                // 网络瞬断等：下一 tick 重试
                            }
                        }
                    }
                    onAccountRefreshed(account)
                }
            } finally {
                if (!stopped) {
                    handler.postDelayed(tick, TICK_INTERVAL_MS)
                }
            }
        }
    }

    fun start() {
        stopped = false
        handler.removeCallbacksAndMessages(null)
        handler.post(tick)
    }

    fun stop() {
        stopped = true
        handler.removeCallbacksAndMessages(null)
    }

    companion object {
        /** 心跳间隔：凭证 2h 寿命 + 3600s 提前量，60s 粒度足够 */
        private const val TICK_INTERVAL_MS = 60_000L
    }
}