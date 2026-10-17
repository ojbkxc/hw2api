package com.lxseek.hw2api.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lxseek.hw2api.core.model.Account
import com.lxseek.hw2api.core.model.ModelRegistry
import com.lxseek.hw2api.core.util.Iso8601
import kotlinx.coroutines.delay

/**
 * 主界面（plan.md §3.2 HomeScreen）：服务开关 / 账号卡（标签 + 倒计时 + 手动续期）/
 * API 地址展示 / 鉴权提示 / 模型列表（ModelRegistry）。
 *
 * 倒计时每秒刷新（LaunchedEffect 心跳）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    account: Account?,
    serviceRunning: Boolean,
    needsRelogin: Boolean,
    lastError: String?,
    runningPort: Int,
    runningLan: Boolean,
    settingsPort: Int,
    settingsLan: Boolean,
    authEnabled: Boolean,
    lanIp: String?,
    onLogin: () -> Unit,
    onLogout: () -> Unit,
    onToggleService: (Boolean) -> Unit,
    onManualRefresh: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val clipboard = LocalClipboardManager.current
    var nowMillis by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var showLogoutConfirm by remember { mutableStateOf(false) }

    // 倒计时心跳：账号存在时每秒刷新当前时刻
    LaunchedEffect(account?.accountId, account?.credentials?.expiration) {
        while (true) {
            nowMillis = System.currentTimeMillis()
            delay(1_000)
        }
    }

    // 设置与运行态不一致 → 提示重启服务生效
    val configDirty = serviceRunning && (runningPort != settingsPort || runningLan != settingsLan)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("HW API", fontWeight = FontWeight.Bold) },
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "设置")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (needsRelogin) {
                ReloginBanner(onLogin = onLogin)
            }
            if (lastError != null) {
                Text(
                    lastError,
                    color = MaterialTheme.colorScheme.error,
                    fontSize = 12.sp,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            ServiceCard(
                running = serviceRunning,
                configDirty = configDirty,
                onToggle = onToggleService,
            )

            AccountCard(
                account = account,
                nowMillis = nowMillis,
                onLogin = onLogin,
                onManualRefresh = onManualRefresh,
                onLogout = { showLogoutConfirm = true },
            )

            EndpointCard(
                lanIp = lanIp,
                port = if (serviceRunning) runningPort else settingsPort,
                lanListen = if (serviceRunning) runningLan else settingsLan,
                authEnabled = authEnabled,
                onCopy = { text -> clipboard.setText(AnnotatedString(text)) },
            )

            ModelsCard()

            Spacer(Modifier.height(16.dp))
        }
    }

    if (showLogoutConfirm && account != null) {
        AlertDialog(
            onDismissRequest = { showLogoutConfirm = false },
            title = { Text("退出登录") },
            text = { Text("删除账号「${account.label}」的凭证？API 服务将无法转发请求，需重新登录华为云。") },
            confirmButton = {
                TextButton(onClick = {
                    showLogoutConfirm = false
                    onLogout()
                }) { Text("退出登录") }
            },
            dismissButton = {
                TextButton(onClick = { showLogoutConfirm = false }) { Text("取消") }
            },
        )
    }
}

/** 重新登录引导横幅（refresh 链失效时） */
@Composable
private fun ReloginBanner(onLogin: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                "凭证已失效，需要重新登录华为云账号",
                color = MaterialTheme.colorScheme.error,
                fontWeight = FontWeight.Medium,
            )
            Button(onClick = onLogin) { Text("重新登录") }
        }
    }
}

/** 服务开关卡 */
@Composable
private fun ServiceCard(running: Boolean, configDirty: Boolean, onToggle: (Boolean) -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("API 服务", fontWeight = FontWeight.Medium)
                Text(
                    if (running) "运行中 · 局域网设备可接入" else "已停止",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (configDirty) {
                    Text(
                        "端口/监听设置已变更，重启服务后生效",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
            Switch(checked = running, onCheckedChange = onToggle)
        }
    }
}

/** 账号卡：标签 / 过期时间 / 倒计时 / 手动续期 / 退出登录 */
@Composable
private fun AccountCard(
    account: Account?,
    nowMillis: Long,
    onLogin: () -> Unit,
    onManualRefresh: () -> Unit,
    onLogout: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        if (account == null) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("未登录", style = MaterialTheme.typography.titleMedium)
                Text(
                    "登录华为云账号后，手机即变成 OpenAI 兼容 API 服务器",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                Button(onClick = onLogin) { Text("登录华为云") }
            }
        } else {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        account.label.ifBlank { "华为云账号" },
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedButton(onClick = onManualRefresh) { Text("续期") }
                    Spacer(Modifier.width(8.dp))
                    OutlinedButton(onClick = onLogout) { Text("退出") }
                }
                val remaining = account.credentials.expiration - nowMillis
                val remainText = if (remaining <= 0) "已过期" else formatCountdown(remaining)
                Text(
                    "凭证剩余 $remainText",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 14.sp,
                    color = if (remaining <= 3600_000L) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.primary
                    },
                )
                Text(
                    "到期：${
                        account.credentials.expiresAt.ifBlank {
                            Iso8601.formatEpochMillis(account.credentials.expiration)
                        }
                    }",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** 接入地址卡：http://<手机IP>:8788/v1（一键复制）+ 鉴权提示 */
@Composable
private fun EndpointCard(
    lanIp: String?,
    port: Int,
    lanListen: Boolean,
    authEnabled: Boolean,
    onCopy: (String) -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("接入地址", style = MaterialTheme.typography.titleMedium)
            val host = if (lanListen) (lanIp ?: "<手机IP>") else "127.0.0.1"
            val baseUrl = "http://$host:$port/v1"
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    baseUrl,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 14.sp,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { onCopy(baseUrl) }) { Text("复制") }
            }
            if (lanListen && lanIp == null) {
                Text(
                    "未探测到局域网 IP：请连接 Wi-Fi 或开启热点",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            if (!lanListen) {
                Text(
                    "当前仅本机监听：在设置中打开「局域网接入」后，局域网设备才能访问",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                if (authEnabled) "鉴权已启用：请求需携带 Authorization: Bearer <API Key>"
                else "鉴权未启用：局域网内任何设备均可直接访问",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 模型列表卡（ModelRegistry 模型表 + 别名） */
@Composable
private fun ModelsCard() {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("可用模型", style = MaterialTheme.typography.titleMedium)
            ModelRegistry.MODELS.forEach { (id, info) ->
                Column(Modifier.fillMaxWidth()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            id,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.weight(1f),
                        )
                        if (info.streamOnly) {
                            Text(
                                "仅流式",
                                fontSize = 10.sp,
                                color = MaterialTheme.colorScheme.tertiary,
                                fontWeight = FontWeight.Medium,
                            )
                        }
                    }
                    Text(
                        "${info.desc} · ctx ${info.ctx} · max ${info.maxTokens}",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    HorizontalDivider(Modifier.padding(top = 8.dp))
                }
            }
            Text(
                "别名：" + ModelRegistry.MODEL_ALIAS.keys.joinToString(" / "),
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 毫秒 → "1小时23分" 形态 */
private fun formatCountdown(remainingMillis: Long): String {
    val seconds = remainingMillis / 1000
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    val s = seconds % 60
    return when {
        h > 0 -> "%d小时%02d分%02d秒".format(h, m, s)
        m > 0 -> "%d分%02d秒".format(m, s)
        else -> "%d秒".format(s)
    }
}
