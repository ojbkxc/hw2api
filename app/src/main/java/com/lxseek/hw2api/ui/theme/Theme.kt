package com.lxseek.hw2api.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

/**
 * App 主题（Material3）：Android 12+ 取系统动态色（minSdk 31 恒可用），
 * 兜底静态蓝灰配色。深浅色随系统。
 */

private val FallbackDark = darkColorScheme(
    primary = Color(0xFF8AB4F8),
    onPrimary = Color(0xFF002A60),
    secondary = Color(0xFF9AC6FF),
    surface = Color(0xFF131417),
    background = Color(0xFF0E0F12),
    surfaceVariant = Color(0xFF1E1F24),
)

private val FallbackLight = lightColorScheme(
    primary = Color(0xFF0B57D0),
    onPrimary = Color.White,
    secondary = Color(0xFF4768B8),
    surface = Color(0xFFF8F9FC),
    background = Color(0xFFF8F9FC),
    surfaceVariant = Color(0xFFE8EAF0),
)

@Composable
fun Hw2ApiTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val colorScheme = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(LocalContext.current)
            else dynamicLightColorScheme(LocalContext.current)
        dark -> FallbackDark
        else -> FallbackLight
    }
    MaterialTheme(colorScheme = colorScheme, content = content)
}