package app.secfiles.android.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Dark = darkColorScheme(
    primary = Color(0xFF4FC3F7), onPrimary = Color(0xFF00344A),
    background = Color(0xFF0F1B2D), surface = Color(0xFF14233A),
    surfaceVariant = Color(0xFF1C2E49), onSurface = Color(0xFFE3EAF4), onBackground = Color(0xFFE3EAF4),
    secondaryContainer = Color(0xFF244066), onSecondaryContainer = Color(0xFFD6E6FF),
    error = Color(0xFFFF8A80),
)
private val Light = lightColorScheme(
    primary = Color(0xFF0B6A94), onPrimary = Color.White,
    background = Color(0xFFF5F8FC), surface = Color.White,
)

@Composable
fun SecFilesTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) Dark else Light, content = content)
}

fun formatSize(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val units = arrayOf("KB", "MB", "GB", "TB")
    var v = bytes.toDouble()
    var i = -1
    while (v >= 1024 && i < units.lastIndex) { v /= 1024; i++ }
    return if (v >= 100 || i == 0) "%.0f %s".format(v, units[i]) else "%.1f %s".format(v, units[i])
}
