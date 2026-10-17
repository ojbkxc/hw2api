package com.lxseek.hw2api.ui

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.lxseek.hw2api.core.auth.StsException
import com.lxseek.hw2api.core.model.Account
import com.lxseek.hw2api.login.LoginWebViewActivity
import com.lxseek.hw2api.service.ProxyForegroundService
import com.lxseek.hw2api.store.CredentialManager
import com.lxseek.hw2api.store.SettingsStore
import com.lxseek.hw2api.ui.theme.Hw2ApiTheme
import com.lxseek.hw2api.util.NetUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Compose 入口（plan.md §3.2）：串联登录流 / 服务开关 / 设置 / 主界面状态。
 * 轻量手写状态（无 ViewModel/DI）：Activity 持有账号与设置快照，
 * 服务运行态由 ProxyForegroundService 的伴生 Compose 状态驱动重组。
 */
class MainActivity : ComponentActivity() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var credentials: CredentialManager
    private lateinit var settings: SettingsStore

    // ── UI 状态（mutableStateOf 驱动 Compose 重组）──
    private var account by mutableStateOf<Account?>(null)
    private var settingsPort by mutableStateOf(SettingsStore.DEFAULT_PORT)
    private var settingsLan by mutableStateOf(false)
    private var settingsAuthEnabled by mutableStateOf(true)
    private var lanIp by mutableStateOf<String?>(null)
    private var refreshing = false

    // 登录页返回：无论成败都刷新账号快照（成功时 Repository 已入库激活）
    private val loginLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            refreshAccountState()
            // 登录成功带回新凭证：清除「需重新登录」标记与错误提示
            if (ProxyForegroundService.needsRelogin && account != null) {
                ProxyForegroundService.clearNeedsRelogin()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        credentials = CredentialManager.get(this)
        settings = SettingsStore.get(this)
        refreshAccountState()
        refreshSettingsState()
        lanIp = NetUtils.getLanIpv4()

        setContent {
            Hw2ApiTheme {
                RequestNotificationPermission()
                AppRoot()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // 从登录页/系统设置返回时刷新（服务可能在后台完成续期）
        refreshAccountState()
        lanIp = NetUtils.getLanIpv4()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    /** Android 13+ 通知权限（前台服务通知展示需要；未授权时服务照常运行，仅通知不展示） */
    @Composable
    private fun RequestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val launcher = rememberLauncherForActivityResult(
                ActivityResultContracts.RequestPermission()
            ) { }
            LaunchedEffect(Unit) { launcher.launch(Manifest.permission.POST_NOTIFICATIONS) }
        }
    }

    @Composable
    private fun AppRoot() {
        var showSettings by remember { mutableStateOf(false) }
        if (showSettings) {
            SettingsScreen(
                port = settingsPort,
                apiKey = settings.apiKey,
                lanListen = settingsLan,
                authEnabled = settingsAuthEnabled,
                serviceRunning = ProxyForegroundService.isRunning,
                configDirty = configDirty(),
                onPortChange = { value ->
                    settings.port = value
                    refreshSettingsState()
                },
                onApiKeyChange = { value ->
                    settings.apiKey = value
                    refreshSettingsState()
                },
                onLanListenChange = { value ->
                    settings.lanListen = value
                    refreshSettingsState()
                },
                onAuthEnabledChange = { value ->
                    settings.authEnabled = value
                    refreshSettingsState()
                },
                onRestartService = { restartService() },
                onBack = { showSettings = false },
            )
        } else {
            HomeScreen(
                account = account,
                serviceRunning = ProxyForegroundService.isRunning,
                needsRelogin = ProxyForegroundService.needsRelogin,
                lastError = ProxyForegroundService.lastError,
                runningPort = ProxyForegroundService.runningPort,
                runningLan = ProxyForegroundService.runningLan,
                settingsPort = settingsPort,
                settingsLan = settingsLan,
                authEnabled = settingsAuthEnabled && settings.apiKey.isNotBlank(),
                lanIp = lanIp,
                onLogin = { loginLauncher.launch(Intent(this, LoginWebViewActivity::class.java)) },
                onLogout = { logout() },
                onToggleService = { enabled -> toggleService(enabled) },
                onManualRefresh = { manualRefresh() },
                onOpenSettings = { showSettings = true },
            )
        }
    }

    // ── 动作 ──

    private fun toggleService(enabled: Boolean) {
        if (enabled) {
            if (account == null) {
                Toast.makeText(this, "请先登录华为云账号", Toast.LENGTH_SHORT).show()
                return
            }
            ProxyForegroundService.start(this)
        } else {
            ProxyForegroundService.stop(this)
        }
    }

    /** 端口/监听变更后：先停后起（服务运行中才需要；延时避开新旧 server 抢端口） */
    private fun restartService() {
        if (!ProxyForegroundService.isRunning) return
        ProxyForegroundService.stop(this)
        Toast.makeText(this, "正在按新配置重启服务…", Toast.LENGTH_SHORT).show()
        scope.launch {
            delay(500)
            ProxyForegroundService.start(this@MainActivity)
        }
    }

    private fun manualRefresh() {
        val current = account ?: return
        if (refreshing) return
        refreshing = true
        scope.launch {
            try {
                // 切 IO：forceRefresh 链路含 AccountRepository.save 的同步 commit 落盘，
                // 避免在 Main.immediate 协程上主线程写盘
                withContext(Dispatchers.IO) {
                    credentials.forceRefresh(current)
                }
                refreshAccountState()
                Toast.makeText(this@MainActivity, "凭证已续期", Toast.LENGTH_SHORT).show()
            } catch (e: StsException) {
                Toast.makeText(this@MainActivity, "续期失败：${e.message}", Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                Toast.makeText(this@MainActivity, "续期失败：${e.message}", Toast.LENGTH_SHORT).show()
            } finally {
                refreshing = false
            }
        }
    }

    private fun logout() {
        credentials.logoutActive()
        refreshAccountState()
        if (ProxyForegroundService.isRunning) {
            Toast.makeText(this, "账号已移除，服务失去凭证，请重新登录", Toast.LENGTH_LONG).show()
        }
    }

    // ── 状态刷新 ──

    private fun refreshAccountState() {
        account = credentials.activeOrNull()
    }

    private fun refreshSettingsState() {
        settingsPort = settings.port
        settingsLan = settings.lanListen
        settingsAuthEnabled = settings.authEnabled
    }

    private fun configDirty(): Boolean =
        ProxyForegroundService.isRunning &&
            (ProxyForegroundService.runningPort != settingsPort || ProxyForegroundService.runningLan != settingsLan)
}
