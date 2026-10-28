package app.secfiles.android.ui

import android.content.Intent
import android.net.Uri
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import app.secfiles.android.Busy

/** Picks a container with a persistable read+write grant, so it can be reopened after a restart. */
class OpenContainer : ActivityResultContracts.OpenDocument() {
    override fun createIntent(context: android.content.Context, input: Array<String>): Intent =
        super.createIntent(context, input).addFlags(
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
        )
}

class CreateContainer : ActivityResultContracts.CreateDocument("application/octet-stream") {
    override fun createIntent(context: android.content.Context, input: String): Intent =
        super.createIntent(context, input).addFlags(
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
        )
}

@Composable
fun PasswordField(
    value: String, onChange: (String) -> Unit, label: String, modifier: Modifier = Modifier,
    enabled: Boolean = true, imeNext: Boolean = false,
) {
    var visible by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        enabled = enabled,
        modifier = modifier.fillMaxWidth(),
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
        trailingIcon = { TextButton(onClick = { visible = !visible }) { Text(if (visible) "Hide" else "Show") } },
    )
}

/** Keyfile chooser with the picked files listed as removable chips. */
@Composable
fun KeyfilePicker(files: List<Uri>, names: (Uri) -> String, onChange: (List<Uri>) -> Unit) {
    val picker = rememberLauncherForActivityResultCompat { uris: List<Uri> -> onChange((files + uris).distinct()) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = { picker.launch(arrayOf("*/*")) }) { Text("Add keyfile") }
            Spacer(Modifier.width(8.dp))
            Text(
                if (files.isEmpty()) "Optional: any file acts as an extra key" else "${files.size} keyfile(s)",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        files.forEach { f ->
            AssistChip(
                onClick = { onChange(files - f) },
                label = { Text(names(f), maxLines = 1) },
                trailingIcon = { Icon(Icons.Default.Close, "Remove keyfile", Modifier.size(16.dp)) },
            )
        }
    }
}

@Composable
private fun rememberLauncherForActivityResultCompat(onResult: (List<Uri>) -> Unit) =
    androidx.activity.compose.rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments(), onResult)

@Composable
fun BusyOverlay(busy: Busy) {
    AlertDialog(
        onDismissRequest = {},
        title = { Text(busy.title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (busy.detail.isNotEmpty()) Text(busy.detail, maxLines = 2)
                val p = busy.progress
                if (p == null) LinearProgressIndicator(Modifier.fillMaxWidth())
                else {
                    LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth())
                    Text("${(p * 100).toInt()}%", style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {},
        dismissButton = busy.cancel?.let { c -> { TextButton(onClick = c) { Text("Cancel") } } },
    )
}

@Composable
fun TextPromptDialog(
    title: String, initial: String = "", confirm: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit,
) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { OutlinedTextField(text, { text = it }, singleLine = true, modifier = Modifier.fillMaxWidth()) },
        confirmButton = { TextButton(enabled = text.isNotBlank(), onClick = { onConfirm(text); onDismiss() }) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun ConfirmDialog(
    title: String, body: String, confirm: String, destructive: Boolean = false,
    onDismiss: () -> Unit, onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(body) },
        confirmButton = {
            TextButton(onClick = { onConfirm(); onDismiss() }) {
                Text(confirm, color = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Crude but honest: length and character variety only. Passphrases of 4+ random words score well. */
fun passwordStrength(pw: String): Pair<Float, String> {
    if (pw.isEmpty()) return 0f to ""
    var pool = 0
    if (pw.any { it.isLowerCase() }) pool += 26
    if (pw.any { it.isUpperCase() }) pool += 26
    if (pw.any { it.isDigit() }) pool += 10
    if (pw.any { !it.isLetterOrDigit() }) pool += 33
    val bits = pw.length * (Math.log(pool.coerceAtLeast(2).toDouble()) / Math.log(2.0))
    return when {
        bits < 45 -> 0.2f to "Weak"
        bits < 65 -> 0.45f to "Fair"
        bits < 85 -> 0.7f to "Good"
        else -> 1f to "Strong"
    }
}
