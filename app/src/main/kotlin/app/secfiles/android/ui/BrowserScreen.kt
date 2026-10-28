package app.secfiles.android.ui

import android.content.ClipData
import android.content.Intent
import android.webkit.MimeTypeMap
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.secfiles.android.MainViewModel
import app.secfiles.android.Screen
import app.secfiles.core.DirEntry
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowserScreen(vm: MainViewModel, volumeId: String) {
    val context = LocalContext.current
    val entry = vm.entry(volumeId)
    var addMenu by remember { mutableStateOf(false) }
    var newFolder by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<DirEntry?>(null) }
    var deleting by remember { mutableStateOf<DirEntry?>(null) }
    var exporting by remember { mutableStateOf<DirEntry?>(null) }

    BackHandler { if (vm.canGoUp) vm.goUp() else vm.screen = Screen.List }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) vm.importFiles(uris)
    }
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { uri ->
        val e = exporting
        if (uri != null && e != null) vm.exportFile(e, uri)
        exporting = null
    }

    fun launchView(e: DirEntry, send: Boolean) {
        val uri = vm.documentUri(volumeId, e.stat.id)
        val ext = e.name.substringAfterLast('.', "").lowercase()
        val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "*/*"
        val intent = if (send) {
            Intent(Intent.ACTION_SEND).setType(mime).putExtra(Intent.EXTRA_STREAM, uri)
        } else Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime)
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        intent.clipData = ClipData.newRawUri(e.name, uri)
        vm.mounts.touch()
        runCatching { context.startActivity(if (send) Intent.createChooser(intent, "Share decrypted copy") else intent) }
            .onFailure { vm.message = "No app can open this file type" }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(entry?.name ?: "Volume", maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            "/" + vm.pathNames.joinToString("/"),
                            style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = { if (vm.canGoUp) vm.goUp() else vm.screen = Screen.List }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
                actions = {
                    TextButton(onClick = { vm.lock(volumeId) }) {
                        Icon(Icons.Default.Lock, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text("Lock")
                    }
                },
            )
        },
        floatingActionButton = {
            Box {
                FloatingActionButton(onClick = { addMenu = true }) { Icon(Icons.Default.Add, "Add") }
                DropdownMenu(expanded = addMenu, onDismissRequest = { addMenu = false }) {
                    DropdownMenuItem(text = { Text("Encrypt files…") }, onClick = { addMenu = false; importLauncher.launch(arrayOf("*/*")) })
                    DropdownMenuItem(text = { Text("New folder") }, onClick = { addMenu = false; newFolder = true })
                }
            }
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            if (vm.totalBytes > 0) {
                val used = (vm.totalBytes - vm.freeBytes).toFloat() / vm.totalBytes
                LinearProgressIndicator(progress = { used }, modifier = Modifier.fillMaxWidth())
                Text(
                    "${formatSize(vm.freeBytes)} free of ${formatSize(vm.totalBytes)}",
                    Modifier.padding(horizontal = 16.dp, vertical = 4.dp), style = MaterialTheme.typography.bodySmall,
                )
            }
            if (vm.entries.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("This folder is empty.\nTap + to add files, or copy to it from any app via the Files drive.", Modifier.padding(32.dp))
                }
            } else {
                LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 96.dp)) {
                    items(vm.entries, key = { it.stat.id }) { e ->
                        EntryRow(
                            e,
                            onClick = { if (e.stat.isDir) vm.enter(e) else launchView(e, send = false) },
                            onShare = { launchView(e, send = true) },
                            onExport = { exporting = e; exportLauncher.launch(e.name) },
                            onRename = { renaming = e },
                            onDelete = { deleting = e },
                        )
                    }
                }
            }
        }
    }

    if (newFolder) TextPromptDialog("New folder", "", "Create", { newFolder = false }) { vm.newFolder(it) }
    renaming?.let { e -> TextPromptDialog("Rename", e.name, "Rename", { renaming = null }) { vm.renameEntry(e, it) } }
    deleting?.let { e ->
        ConfirmDialog(
            "Delete ${e.name}?",
            if (e.stat.isDir) "The folder and everything in it will be deleted." else "This file will be deleted from the volume.",
            "Delete", destructive = true, onDismiss = { deleting = null },
        ) { vm.deleteEntry(e) }
    }
}

@Composable
private fun EntryRow(
    e: DirEntry, onClick: () -> Unit, onShare: () -> Unit, onExport: () -> Unit, onRename: () -> Unit, onDelete: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            if (e.stat.isDir) Icons.Default.Folder else Icons.AutoMirrored.Filled.InsertDriveFile, null,
            tint = if (e.stat.isDir) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(28.dp),
        )
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(e.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                (if (e.stat.isDir) "Folder" else formatSize(e.stat.size)) + " · " +
                    DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(e.stat.mtimeMs)),
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Box {
            IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "More") }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                if (!e.stat.isDir) {
                    DropdownMenuItem(text = { Text("Share") }, onClick = { menu = false; onShare() })
                    DropdownMenuItem(text = { Text("Export decrypted copy…") }, onClick = { menu = false; onExport() })
                }
                DropdownMenuItem(text = { Text("Rename") }, onClick = { menu = false; onRename() })
                DropdownMenuItem(
                    text = { Text("Delete", color = MaterialTheme.colorScheme.error) },
                    onClick = { menu = false; onDelete() },
                )
            }
        }
    }
}
