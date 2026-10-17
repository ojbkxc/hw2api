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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 设置页（plan.md §3.2 SettingsScreen）：端口（默认 8788）/ API Key /
 * 局域网监听开关（默认关 = 仅 127.0.0.1）/ 鉴权开关。
 * 端口与监听改动需重启服务生效（页内提示 + 重启按钮）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    port: Int,
    apiKey: String,
    lanListen: Boolean,
    authEnabled: Boolean,
    serviceRunning: Boolean,
    configDirty: Boolean,
    onPortChange: (Int) -> Unit,
    onApiKeyChange: (String) -> Unit,
    onLanListenChange: (Boolean) -> Unit,
    onAuthEnabledChange: (Boolean) -> Unit,
    onRestartService: () -> Unit,
    onBack: () -> Unit,
) {
    // 文本输入用本地草稿：不带 key（草稿独立于外部值，仅进入页面时初始化一次），
    // 失焦/键盘确认时才回写——每键回写会被 coerce 重置打断逐位输入
    var portDraft by rememberSaveable { mutableStateOf(port.toString()) }
    var apiKeyDraft by rememberSaveable { mutableStateOf(apiKey) }
    // onFocusChanged 首次组合会以初始未聚焦状态回调一次，用「曾聚焦」标志过滤掉该次
    var portFieldFocused by rememberSaveable { mutableStateOf(false) }
    var apiKeyFieldFocused by rememberSaveable { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current

    /** 端口草稿提交：合法区间（1024-65535）才落盘；非法/空恢复为当前生效值 */
    fun commitPortDraft() {
        val value = portDraft.toIntOrNull()
        if (value != null && value in 1024..65535) {
            onPortChange(value)
        } else {
            portDraft = port.toString()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("设置") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
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
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("服务", style = MaterialTheme.typography.titleMedium)
                    OutlinedTextField(
                        value = portDraft,
                        onValueChange = { text ->
                            // 仅更新草稿不回写（中间态如 "9" 会被钳制重置打断输入）
                            portDraft = text.filter { it.isDigit() }.take(5)
                        },
                        label = { Text("监听端口（1024-65535，默认 8788）") },
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Number,
                            imeAction = ImeAction.Done,
                        ),
                        keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .onFocusChanged { state ->
                                if (state.isFocused) {
                                    portFieldFocused = true
                                } else if (portFieldFocused) {
                                    portFieldFocused = false
                                    commitPortDraft()
                                }
                            },
                    )
                    SettingSwitch(
                        title = "局域网接入",
                        subtitle = "开启后监听 0.0.0.0，局域网设备可访问；关闭仅本机 127.0.0.1",
                        checked = lanListen,
                        onCheckedChange = onLanListenChange,
                    )
                    if (configDirty && serviceRunning) {
                        Text(
                            "端口/监听已变更，需重启服务生效",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.error,
                        )
                        Button(onClick = onRestartService) { Text("重启服务") }
                    }
                }
            }

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("鉴权", style = MaterialTheme.typography.titleMedium)
                    SettingSwitch(
                        title = "启用 API Key",
                        subtitle = "对齐 CODEARTS_PROXY_API_KEY：开启且 Key 非空时校验请求头",
                        checked = authEnabled,
                        onCheckedChange = onAuthEnabledChange,
                    )
                    OutlinedTextField(
                        value = apiKeyDraft,
                        onValueChange = { apiKeyDraft = it },
                        label = { Text("API Key（留空 = 不鉴权）") },
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .onFocusChanged { state ->
                                // 失焦时回写（m8：避免每键 ESP 主线程写盘；无合法性约束，留空即关闭鉴权）
                                if (state.isFocused) {
                                    apiKeyFieldFocused = true
                                } else if (apiKeyFieldFocused) {
                                    apiKeyFieldFocused = false
                                    onApiKeyChange(apiKeyDraft)
                                }
                            },
                    )
                    Text(
                        "请求携带 Authorization: Bearer <Key> 或 X-Api-Key: <Key>；/health 不鉴权",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("说明", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "凭证存储在 EncryptedSharedPreferences（Android Keystore 托管主密钥）；" +
                            "服务运行期间自动续期（提前 1 小时），refresh 链失效时需重新登录。" +
                            "若锁屏后服务被系统终止，请在系统设置中允许本应用后台运行（电池优化白名单）。",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

/** 单行开关项 */
@Composable
private fun SettingSwitch(title: String, subtitle: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.Medium)
            Text(subtitle, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(8.dp))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}