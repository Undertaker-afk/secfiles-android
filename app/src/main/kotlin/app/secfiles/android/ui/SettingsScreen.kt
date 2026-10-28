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
    val context = androidx.compose.ui.platform.LocalContext.current
    var overlay by remember { mutableStateOf(s.ejectOverlay) }
    var overlayGranted by remember { mutableStateOf(android.provider.Settings.canDrawOverlays(context)) }
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) { // re-check after the user comes back from the system permission screen
        val obs = androidx.lifecycle.LifecycleEventObserver { _, e ->
            if (e == androidx.lifecycle.Lifecycle.Event.ON_RESUME) overlayGranted = android.provider.Settings.canDrawOverlays(context)
        }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs) }
    }
    fun refreshOverlay() {
        if (vm.mounts.all().isNotEmpty()) {
            context.startService(android.content.Intent(context, app.secfiles.android.MountService::class.java)
                .setAction(app.secfiles.android.MountService.ACTION_REFRESH))
        }
    }

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
            SwitchRow(
                "Eject button over other apps", overlay && overlayGranted,
                if (overlayGranted) "A grey X at the right edge while a volume is unlocked. One tap locks everything."
                else "Needs the 'display over other apps' permission. Turn on to grant it.",
            ) { on ->
                overlay = on; s.ejectOverlay = on
                if (on && !overlayGranted) {
                    context.startActivity(
                        android.content.Intent(
                            android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            android.net.Uri.parse("package:" + context.packageName),
                        )
                    )
                }
                refreshOverlay()
            }
            SwitchRow("Lock when the screen turns off", screenOff) { screenOff = it; s.lockOnScreenOff = it }
            SwitchRow("Lock when I leave the app", onLeave, "Opening a file picker or another app counts as leaving") { onLeave = it; s.lockOnLeave = it }
            SwitchRow("Allow screenshots", screenshots, "Applies the next time the app is opened") { screenshots = it; s.allowScreenshots = it }
            HorizontalDivider()
            Text("How your data is protected", style = MaterialTheme.typography.titleSmall)
            Text(
                "• A cascade of one to five independent ciphers, each in XTS mode with its own keys. You choose the depth; the ciphers " +
                    "and their order are drawn from a pool of eight by a hash of your password and the container salt, and are sealed " +
                    "in the header. This is obscurity on top of real strength, not a replacement for it.\n" +
                    "• Optional compression happens before encryption, with one of five engines picked the same way.\n" +
                    "• Your password (and optional keyfiles) go through Argon2id, a memory-hard function that makes guessing slow and expensive.\n" +
                    "• The header is sealed with ChaCha20-Poly1305 and signed with Falcon-512, so damage or tampering is detected before any " +
                    "password work is done. A backup header is stored at the end of the file.\n" +
                    "• Biometric unlock is a convenience: the credential is wrapped by a hardware-backed key, kept in memory only, and expires " +
                    "after three days.\n" +
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
