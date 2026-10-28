package app.secfiles.android.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.secfiles.android.MainViewModel
import app.secfiles.android.Screen

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(vm: MainViewModel) {
    BackHandler { vm.screen = Screen.List }
    val s = vm.settings
    var autoLock by remember { mutableIntStateOf(s.autoLockMinutes) }
    var screenOff by remember { mutableStateOf(s.lockOnScreenOff) }
    var onLeave by remember { mutableStateOf(s.lockOnLeave) }
    var screenshots by remember { mutableStateOf(s.allowScreenshots) }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text("Settings") },
            navigationIcon = { IconButton(onClick = { vm.screen = Screen.List }) { Icon(Icons.Default.ArrowBack, "Back") } },
        )
    }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Auto-lock after inactivity", style = MaterialTheme.typography.titleSmall)
            listOf(1 to "1 minute", 5 to "5 minutes", 15 to "15 minutes", 60 to "1 hour", 0 to "Never").forEach { (m, l) ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(autoLock == m, { autoLock = m; s.autoLockMinutes = m }); Text(l)
                }
            }
            HorizontalDivider()
            SwitchRow("Lock when the screen turns off", screenOff) { screenOff = it; s.lockOnScreenOff = it }
            SwitchRow("Lock when I leave the app", onLeave, "Opening a file picker or another app counts as leaving") { onLeave = it; s.lockOnLeave = it }
            SwitchRow("Allow screenshots", screenshots, "Applies the next time the app is opened") { screenshots = it; s.allowScreenshots = it }
            HorizontalDivider()
            Text("How your data is protected", style = MaterialTheme.typography.titleSmall)
            Text(
                "• Three independent ciphers in a cascade – AES-256, Twofish-256 and Serpent-256, each in XTS mode with its own " +
                    "key – so the data stays protected even if one of them is ever broken.\n" +
                    "• Your password (and optional keyfiles) go through Argon2id, a memory-hard function that makes guessing slow and expensive.\n" +
                    "• The container headers are authenticated with HMAC-SHA-512; a backup header is stored at the end of the file.\n" +
                    "• Biometric unlock keeps the key in the phone's secure hardware, gated by a strong biometric, and is wiped by Android " +
                    "if fingerprints/faces are added.\n" +
                    "• This app has no internet permission. Keys live only in memory while a volume is unlocked.\n\n" +
                    "Limits: files you open in other apps, or export, are decrypted copies those apps control. " +
                    "A rooted or compromised phone can read keys from memory while a volume is unlocked.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun SwitchRow(title: String, checked: Boolean, subtitle: String? = null, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall)
        }
        Switch(checked, onChange)
    }
}
