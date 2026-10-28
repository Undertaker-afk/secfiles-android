package app.secfiles.android.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Dark = darkColorScheme(
    primary = Color(0xFF4FC3F7), onPrimary = Color(0xFF00344A),
    background = Color(0xFF0B1220), surface = Color(0xFF111B2E),
    surfaceVariant = Color(0xFF1A2740), onSurface = Color(0xFFE3EAF4), onBackground = Color(0xFFE3EAF4),
    onSurfaceVariant = Color(0xFFB4C0D3),
    secondaryContainer = Color(0xFF223B61), onSecondaryContainer = Color(0xFFD6E6FF),
    error = Color(0xFFFF8A80),
)

/** The app is dark only, regardless of the system setting. */
@Composable
fun SecFilesTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Dark, content = content)
}

fun formatSize(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val units = arrayOf("KB", "MB", "GB", "TB")
    var v = bytes.toDouble()
    var i = -1
    while (v >= 1024 && i < units.lastIndex) { v /= 1024; i++ }
    return if (v >= 100 || i == 0) "%.0f %s".format(v, units[i]) else "%.1f %s".format(v, units[i])
}
