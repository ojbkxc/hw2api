package com.lxseek.hw2api.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.lxseek.hw2api.R
import com.lxseek.hw2api.core.model.Account
import com.lxseek.hw2api.server.ProxyServer
import com.lxseek.hw2api.store.CredentialManager
import com.lxseek.hw2api.store.SettingsStore
import com.lxseek.hw2api.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/**
 * 前台服务（plan.md §3.2 / §5.4）：connectedDevice 类型，承载 ProxyServer 生命周期
 * 与 RefreshScheduler（凭证 exp−now≤3600s 自动 refresh rotation）。
 *
 * - START_STICKY 兜底（vivo OriginOS 后台清理后系统尝试拉起，plan.md R3）
 * - 通知：监听端口 + 凭证到期倒计时（每分钟随 scheduler tick 刷新）
 * - 服务一律由前台 Activity 触发启动（规避 Android 12 后台 FGS 限制，R5）
 * - UI 通过伴生对象的 Compose 状态观察运行情况（无 DI，手写单例状态）
 */
class ProxyForegroundService : Service() {

    private var server: ProxyServer? = null
    private var scheduler: RefreshScheduler? = null
    private lateinit var serviceScope: CoroutineScope

    override fun onCreate() {
        super.onCreate()
        serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 立即进前台（startForegroundService 的 5s 约束），再启 server
        startForeground(NOTIFICATION_ID, buildNotification(null), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        if (isRunning) {
            updateNotification()
            return START_STICKY
        }
        startServer()
        return START_STICKY
    }

    private fun startServer() {
        val settings = SettingsStore.get(this)
        val credentials = CredentialManager.get(this)
        val newServer = ProxyServer.fromSettings(settings, credentials)
        try {
            newServer.start()
        } catch (e: Exception) {
            // 端口占用等启动失败：暴露错误并停止自身
            lastError = "服务启动失败：${e.message}（端口 ${settings.port} 可能被占用）"
            stopSelf()
            return
        }
        server = newServer
        runningPort = settings.port
        runningLan = settings.lanListen
        lastError = null

        val refreshScheduler = RefreshScheduler(
            scope = serviceScope,
            credentials = credentials,
            onAccountRefreshed = { account -> updateNotification(account) },
            onNeedsRelogin = { message ->
                needsRelogin = true
                lastError = message
                updateNotification(credentials.activeOrNull())
            },
        )
        refreshScheduler.start()
        scheduler = refreshScheduler
        isRunning = true
        updateNotification(credentials.activeOrNull())
    }

    private fun stopServer() {
        scheduler?.stop()
        scheduler = null
        server?.stop()
        server = null
        isRunning = false
        runningPort = 0
        runningLan = false
    }

    override fun onDestroy() {
        stopServer()
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ── 通知 ──

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.notification_channel_desc)
            setShowBadge(false)
        }
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(channel)
    }

    /** 通知内容：端口 + 监听范围 + 凭证到期倒计时 */
    private fun buildNotification(account: Account?): Notification {
        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val settings = SettingsStore.get(this)
        val scopeText = if (settings.lanListen) "局域网" else "仅本机"
        val countdownText = when {
            needsRelogin -> "凭证已失效，请重新登录"
            account == null -> "未登录账号"
            else -> "凭证剩余 ${formatRemaining(account)}"
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText("端口 ${settings.port}（$scopeText）· $countdownText")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openIntent)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun updateNotification(account: Account? = null) {
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, buildNotification(account ?: CredentialManager.get(this).activeOrNull()))
    }

    private fun formatRemaining(account: Account): String {
        val seconds = (account.credentials.expiration - System.currentTimeMillis()) / 1000
        if (seconds <= 0) return "已过期"
        val h = seconds / 3600
        val m = (seconds % 3600) / 60
        return if (h > 0) "${h}小时${m}分" else "${m}分${seconds % 60}秒"
    }

    companion object {
        const val CHANNEL_ID = "hw2api_service"
        const val NOTIFICATION_ID = 8788

        // ── UI 可观察状态（Compose mutableStateOf，Activity 侧自动重组）──
        var isRunning by mutableStateOf(false)
            private set
        var needsRelogin by mutableStateOf(false)
            private set
        var lastError by mutableStateOf<String?>(null)
            private set
        var runningPort by mutableStateOf(0)
            private set
        var runningLan by mutableStateOf(false)
            private set

        /** 前台启动服务（Android 12+ 要求调用方处于前台） */
        fun start(context: Context) {
            val intent = Intent(context, ProxyForegroundService::class.java)
            context.startForegroundService(intent)
        }

        /** 停止服务（stopService → onDestroy 清理一切） */
        fun stop(context: Context) {
            context.stopService(Intent(context, ProxyForegroundService::class.java))
        }

        /** 重新登录成功后清除重登标记与错误提示（登录页返回时调用） */
        fun clearNeedsRelogin() {
            needsRelogin = false
            lastError = null
        }

        /** server 层发现凭证链失效时置位（OpenAiRoutes 调用；外部不可直接赋值 private set 状态） */
        fun markNeedsRelogin() {
            needsRelogin = true
        }
    }
}