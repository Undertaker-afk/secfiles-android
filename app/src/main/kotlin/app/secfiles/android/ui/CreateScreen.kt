package app.secfiles.android.ui

import android.net.Uri
import android.os.StatFs
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import app.secfiles.android.*
import app.secfiles.core.KdfParams

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreateScreen(vm: MainViewModel) {
    val context = LocalContext.current
    val activity = context as FragmentActivity
    BackHandler { vm.screen = Screen.List }

    var name by remember { mutableStateOf("My Vault") }
    var sizeMb by remember { mutableStateOf("512") }
    var pw by remember { mutableStateOf("") }
    var pw2 by remember { mutableStateOf("") }
    var keyfiles by remember { mutableStateOf(emptyList<Uri>()) }
    var external by remember { mutableStateOf(false) }
    var biometric by remember { mutableStateOf(false) }
    var layers by remember { mutableIntStateOf(3) }
    var compress by remember { mutableStateOf(true) }
    val kdfOptions = listOf(
        Triple("Standard", KdfParams.STANDARD, "128 MiB · quick unlock"),
        Triple("Strong", KdfParams.STRONG, "256 MiB · recommended"),
        Triple("Maximum", KdfParams.MAXIMUM, "512 MiB · slowest, needs a lot of RAM"),
    )
    // Default to the strongest preset this device has the heap for.
    var kdfIndex by remember { mutableIntStateOf(kdfOptions.indexOfLast { memoryOk(it.second) }.coerceAtLeast(0)) }
    val bioAvailable = remember { BiometricVault.isAvailable(context) }

    val size = sizeMb.toLongOrNull()
    val freeBytes = remember { runCatching { StatFs(context.filesDir.path).availableBytes }.getOrDefault(Long.MAX_VALUE) }
    val sizeError = when {
        size == null || size < 2 -> "Enter at least 2 MB"
        size > 4096L * 1024 -> "Maximum is 4 TB"
        !external && size * 1024 * 1024 > freeBytes - 64L * 1024 * 1024 -> "Not enough free space (${formatSize(freeBytes)} available)"
        else -> null
    }
    val pwError = when {
        keyfiles.isEmpty() && pw.length < MIN_PASSWORD -> "Use at least $MIN_PASSWORD characters (or add a keyfile)"
        pw != pw2 -> "Passwords do not match"
        else -> null
    }
    val valid = name.isNotBlank() && sizeError == null && pwError == null && (pw.isNotEmpty() || keyfiles.isNotEmpty())
    val (strength, label) = passwordStrength(pw)

    fun start(location: Uri?) {
        vm.createVolume(
            CreateRequest(
                name = name.trim(), sizeBytes = size!! * 1024 * 1024, password = pw.toCharArray(), keyfiles = keyfiles,
                kdf = kdfOptions[kdfIndex].second, location = location, enableBiometric = biometric && bioAvailable,
                layers = layers, compress = compress,
            ),
            activity,
        )
    }

    val pickLocation = rememberLauncherForActivityResult(CreateContainer()) { uri -> if (uri != null) start(uri) }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text("New encrypted volume") },
            navigationIcon = { IconButton(onClick = { vm.screen = Screen.List }) { Icon(Icons.Default.ArrowBack, "Back") } },
        )
    }) { pad ->
        Column(
            Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())

            Text("Size", style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("64" to "64 MB", "256" to "256 MB", "1024" to "1 GB", "4096" to "4 GB").forEach { (v, l) ->
                    FilterChip(selected = sizeMb == v, onClick = { sizeMb = v }, label = { Text(l) })
                }
            }
            OutlinedTextField(
                sizeMb, { sizeMb = it.filter(Char::isDigit) }, label = { Text("Size in MB") }, singleLine = true,
                isError = sizeError != null, supportingText = { Text(sizeError ?: "The file is created at full size and filled with random data") },
                modifier = Modifier.fillMaxWidth(),
            )

            Text("Location", style = MaterialTheme.typography.titleSmall)
            Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(!external, { external = false }); Text("App storage (private)")
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(external, { external = true }); Text("Choose a file or SD card…")
            }

            Text("Password", style = MaterialTheme.typography.titleSmall)
            PasswordField(pw, { pw = it }, "Password or passphrase")
            if (pw.isNotEmpty()) {
                LinearProgressIndicator(progress = { strength }, modifier = Modifier.fillMaxWidth())
                Text(label, style = MaterialTheme.typography.bodySmall)
            }
            PasswordField(pw2, { pw2 = it }, "Repeat password")
            pwError?.let { if (pw.isNotEmpty() || keyfiles.isNotEmpty()) Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            KeyfilePicker(keyfiles, vm::displayName) { keyfiles = it }

            Text("Encryption cascade", style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                (1..5).forEach { n ->
                    FilterChip(selected = layers == n, onClick = { layers = n }, label = { Text("$n layer" + if (n > 1) "s" else "") })
                }
            }
            Text(
                "You choose how many. Which ciphers are used, and in what order, is decided by a hash of your password " +
                    "and this container's random salt. It is never shown, and it is sealed inside the header. More layers " +
                    "are slower: each one adds a full pass over every block.",
                style = MaterialTheme.typography.bodySmall,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(compress, { compress = it })
                Column {
                    Text("Compress before encrypting")
                    Text(
                        "Saves space on text, documents and databases. The engine is picked the same hidden way. " +
                            "Photos and video do not shrink and are stored as-is.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }

            Text("Key derivation (Argon2id)", style = MaterialTheme.typography.titleSmall)
            kdfOptions.forEachIndexed { i, (n, p, d) ->
                val ok = memoryOk(p)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(kdfIndex == i, { kdfIndex = i }, enabled = ok)
                    Column {
                        Text(n)
                        Text(if (ok) d else "Not enough memory on this device", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }

            if (bioAvailable) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(biometric, { biometric = it })
                    Column {
                        Text("Remember with biometrics")
                        Text(
                            "Remembered in memory for 3 days, then your password is required again. Your password stays the master key.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }

            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                Text(
                    "There is no password recovery. If you forget the password and have no keyfile, the data is gone for good. " +
                        "Keep a backup copy of the container file.",
                    Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall,
                )
            }

            Button(
                enabled = valid && vm.busy == null,
                modifier = Modifier.fillMaxWidth(),
                onClick = { if (external) pickLocation.launch("${name.trim()}.sfc") else start(null) },
            ) { Text(if (external) "Choose location and create" else "Create volume") }
            Spacer(Modifier.height(24.dp))
        }
    }
}
