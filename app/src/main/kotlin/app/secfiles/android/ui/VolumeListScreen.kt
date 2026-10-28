package app.secfiles.android.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.fragment.app.FragmentActivity
import app.secfiles.android.*
import app.secfiles.core.KdfParams

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VolumeListScreen(vm: MainViewModel) {
    val context = LocalContext.current
    val activity = context as FragmentActivity
    val mounted by vm.mounts.mountedIds.collectAsState()
    var addMenu by remember { mutableStateOf(false) }
    var unlocking by remember { mutableStateOf<VolumeEntry?>(null) }
    val openExisting = rememberLauncherForActivityResult(OpenContainer()) { uri -> if (uri != null) vm.addExisting(uri) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("SecFiles") },
                actions = {
                    if (mounted.isNotEmpty()) {
                        TextButton(onClick = { vm.lockAll() }) {
                            Icon(Icons.Default.Lock, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text("Lock all")
                        }
                    }
                    IconButton(onClick = { vm.screen = Screen.Settings }) { Icon(Icons.Default.Settings, "Settings") }
                },
            )
        },
        floatingActionButton = {
            Box {
                ExtendedFloatingActionButton(
                    onClick = { addMenu = true },
                    icon = { Icon(Icons.Default.Add, null) },
                    text = { Text("Add volume") },
                )
                DropdownMenu(expanded = addMenu, onDismissRequest = { addMenu = false }) {
                    DropdownMenuItem(text = { Text("Create new volume") }, onClick = { addMenu = false; vm.screen = Screen.Create })
                    DropdownMenuItem(
                        text = { Text("Open existing container…") },
                        onClick = { addMenu = false; openExisting.launch(arrayOf("*/*")) },
                    )
                }
            }
        },
    ) { pad ->
        if (vm.volumes.isEmpty()) {
            EmptyState(Modifier.padding(pad))
        } else {
            LazyColumn(
                Modifier.padding(pad).fillMaxSize(),
                contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 96.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(vm.volumes, key = { it.id }) { e ->
                    VolumeCard(
                        vm, e, e.id in mounted,
                        onClick = { if (e.id in mounted) vm.openBrowser(e.id) else unlocking = e },
                    )
                }
            }
        }
    }

    unlocking?.let { e ->
        UnlockDialog(vm, e, activity, onDismiss = { unlocking = null })
    }
}

@Composable
private fun EmptyState(modifier: Modifier) {
    Column(
        modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(Icons.Default.Lock, null, Modifier.size(64.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(16.dp))
        Text("No encrypted volumes yet", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(8.dp))
        Text(
            "Create a container protected by a cascade of up to five ciphers and an Argon2id-hardened password. " +
                "While it is unlocked it appears as a drive in your Files app.",
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun VolumeCard(vm: MainViewModel, e: VolumeEntry, isMounted: Boolean, onClick: () -> Unit) {
    val rememberedIds by vm.mounts.rememberedIds.collectAsState()
    val bio = e.id in rememberedIds && vm.mounts.isRemembered(e.id)
    var menu by remember { mutableStateOf(false) }
    var dialog by remember { mutableStateOf<String?>(null) }
    Card(
        Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isMounted) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceVariant
        ),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(e.name, style = MaterialTheme.typography.titleMedium, maxLines = 1)
                Text(
                    buildString {
                        append(formatSize(e.sizeBytes))
                        append(if (e.inAppStorage) " · app storage" else " · external file")
                        if (bio) append(" · biometric active")
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(6.dp))
                AssistChip(
                    onClick = onClick,
                    label = { Text(if (isMounted) "Unlocked – tap to browse" else "Locked") },
                    leadingIcon = { Icon(Icons.Default.Lock, null, Modifier.size(16.dp)) },
                )
            }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "More") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    if (isMounted) {
                        DropdownMenuItem(text = { Text("Lock") }, onClick = { menu = false; vm.lock(e.id) })
                        DropdownMenuItem(text = { Text("Change password") }, onClick = { menu = false; dialog = "password" })
                    }
                    if (bio) {
                        DropdownMenuItem(text = { Text("Forget biometric unlock") }, onClick = { menu = false; vm.disableBiometric(e) })
                    }
                    DropdownMenuItem(text = { Text("Rename") }, onClick = { menu = false; dialog = "rename" })
                    DropdownMenuItem(text = { Text("Remove from list") }, onClick = { menu = false; dialog = "remove" })
                    DropdownMenuItem(
                        text = { Text("Delete container file", color = MaterialTheme.colorScheme.error) },
                        onClick = { menu = false; dialog = "delete" },
                    )
                }
            }
        }
    }
    when (dialog) {
        "rename" -> TextPromptDialog("Rename volume", e.name, "Rename", { dialog = null }) { vm.rename(e, it) }
        "remove" -> ConfirmDialog(
            "Remove ${e.name}?", "The container file is kept. You can add it again later with your password.",
            "Remove", onDismiss = { dialog = null },
        ) { vm.remove(e, deleteFile = false) }
        "delete" -> ConfirmDialog(
            "Delete ${e.name} permanently?", "The encrypted container file and everything in it will be erased. This cannot be undone.",
            "Delete", destructive = true, onDismiss = { dialog = null },
        ) { vm.remove(e, deleteFile = true) }
        "password" -> ChangePasswordDialog(vm, e) { dialog = null }
    }
}

@Composable
private fun ChangePasswordDialog(vm: MainViewModel, e: VolumeEntry, onDismiss: () -> Unit) {
    var pw by remember { mutableStateOf("") }
    var pw2 by remember { mutableStateOf("") }
    var keyfiles by remember { mutableStateOf(emptyList<android.net.Uri>()) }
    val ok = (pw.length >= MIN_PASSWORD || keyfiles.isNotEmpty()) && pw == pw2
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Change password") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                PasswordField(pw, { pw = it }, "New password")
                PasswordField(pw2, { pw2 = it }, "Repeat new password")
                KeyfilePicker(keyfiles, vm::displayName) { keyfiles = it }
                Text(
                    "Only the headers are re-encrypted; your files are untouched. Biometric unlock must be enabled again.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        },
        confirmButton = {
            TextButton(enabled = ok, onClick = {
                vm.changePassword(e, pw.toCharArray(), keyfiles, KdfParams.STRONG.takeIf { memoryOk(it) } ?: KdfParams.STANDARD)
                onDismiss()
            }) { Text("Change") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun UnlockDialog(vm: MainViewModel, entry: VolumeEntry, activity: FragmentActivity, onDismiss: () -> Unit) {
    var pw by remember { mutableStateOf("") }
    var keyfiles by remember { mutableStateOf(emptyList<android.net.Uri>()) }
    var enableBio by remember { mutableStateOf(false) }
    val bioAvailable = remember { BiometricVault.isAvailable(activity) }
    val bioActive = remember(entry.id) { vm.mounts.isRemembered(entry.id) }
    val canEnableBio = bioAvailable && !bioActive
    val working = vm.busy != null

    // Offer the fingerprint/face prompt straight away when it is set up.
    LaunchedEffect(entry.id) {
        if (bioActive && bioAvailable) vm.unlockWithBiometric(entry, activity) { ok -> if (ok) onDismiss() }
    }

    AlertDialog(
        onDismissRequest = { if (!working) onDismiss() },
        title = { Text("Unlock ${entry.name}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                PasswordField(pw, { pw = it }, "Password", enabled = !working)
                KeyfilePicker(keyfiles, vm::displayName) { keyfiles = it }
                if (bioActive && bioAvailable) {
                    OutlinedButton(onClick = { vm.unlockWithBiometric(entry, activity) { ok -> if (ok) onDismiss() } }) {
                        Text("Use biometrics")
                    }
                }
                if (canEnableBio) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(enableBio, { enableBio = it })
                        Column {
                            Text("Remember with biometrics for 3 days", style = MaterialTheme.typography.bodyMedium)
                            Text("Kept in memory only; cleared on restart or reboot", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = !working && (pw.isNotEmpty() || keyfiles.isNotEmpty()), onClick = {
                vm.unlockWithPassword(entry, pw.toCharArray(), keyfiles, enableBio, activity) { ok -> if (ok) onDismiss() }
                pw = ""
            }) { Text("Unlock") }
        },
        dismissButton = { TextButton(enabled = !working, onClick = onDismiss) { Text("Cancel") } },
    )
}

const val MIN_PASSWORD = 10

/** Argon2 needs roughly its memory cost plus overhead from the Java heap. */
fun memoryOk(p: KdfParams): Boolean = Runtime.getRuntime().maxMemory() > p.memoryKiB * 1024L * 1.5
