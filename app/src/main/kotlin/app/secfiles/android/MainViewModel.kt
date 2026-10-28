package app.secfiles.android

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.secfiles.core.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

sealed interface Screen {
    data object List : Screen
    data object Create : Screen
    data object Settings : Screen
    data class Browser(val volumeId: String) : Screen
}

/** A long-running operation shown as a blocking overlay. [progress] null = indeterminate. */
class Busy(val title: String, val detail: String = "", val progress: Float? = null, val cancel: (() -> Unit)? = null)

class CreateRequest(
    val name: String,
    val sizeBytes: Long,
    val password: CharArray,
    val keyfiles: List<Uri>,
    val kdf: KdfParams,
    val location: Uri?, // null = app-private storage
    val enableBiometric: Boolean,
    /** Cascade depth 1..5. Which ciphers and in what order is decided by the password hash, not the user. */
    val layers: Int,
    val compress: Boolean,
)

class MainViewModel(private val app: Application) : AndroidViewModel(app) {
    private val sf = app as SecFilesApp
    val mounts get() = sf.mounts
    val settings get() = sf.settings

    var screen by mutableStateOf<Screen>(Screen.List)
    var volumes by mutableStateOf(sf.registry.load())
        private set
    var busy by mutableStateOf<Busy?>(null)
        private set
    var message by mutableStateOf<String?>(null)

    // ---- browser state ------------------------------------------------------------------------
    /** Stack of directory inodes from the root to the current folder. */
    private val dirStack = mutableStateListOf<Int>()
    var entries by mutableStateOf<List<DirEntry>>(emptyList())
        private set
    var pathNames by mutableStateOf<List<String>>(emptyList())
        private set
    var freeBytes by mutableStateOf(0L)
        private set
    var totalBytes by mutableStateOf(0L)
        private set

    fun entry(id: String) = volumes.firstOrNull { it.id == id }

    private fun saveVolumes(list: List<VolumeEntry>) {
        volumes = list
        sf.registry.save(list)
    }

    private fun replace(updated: VolumeEntry) = saveVolumes(volumes.map { if (it.id == updated.id) updated else it })

    // ---- unlock / lock ------------------------------------------------------------------------

    fun readKeyfiles(uris: List<Uri>): List<ByteArray> = uris.map { uri ->
        app.contentResolver.openInputStream(uri)?.use { input ->
            val out = java.io.ByteArrayOutputStream()
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                out.write(buf, 0, n)
                if (out.size() > MAX_KEYFILE) throw IOException("Keyfile is larger than 16 MB")
            }
            out.toByteArray()
        } ?: throw IOException("Cannot read keyfile")
    }

    fun unlockWithPassword(
        entry: VolumeEntry, password: CharArray, keyfiles: List<Uri>, enableBiometric: Boolean,
        activity: FragmentActivity, onDone: (Boolean) -> Unit,
    ) {
        busy = Busy("Unlocking ${entry.name}", "Deriving keys. This is deliberately slow.")
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val creds = Credentials.ofPassword(password, readKeyfiles(keyfiles))
                    password.fill('\u0000')
                    try {
                        mounts.mount(entry, creds)
                        if (enableBiometric) creds.exportSecret() else null
                    } finally {
                        creds.wipe()
                    }
                }
            }
            busy = null
            result.onSuccess { secret ->
                onDone(true)
                if (secret != null) enrollBiometric(activity, entry, secret)
                openBrowser(entry.id)
            }.onFailure {
                message = describe(it)
                onDone(false)
            }
        }
    }

    fun unlockWithBiometric(entry: VolumeEntry, activity: FragmentActivity, onDone: (Boolean) -> Unit) {
        val wrapped = mounts.rememberedFor(entry.id) ?: run {
            message = "Biometric unlock has expired. Enter your password."
            onDone(false); return
        }
        BiometricVault.unwrap(activity, entry.id, entry.name, wrapped) { r ->
            r.onFailure {
                if (it is BiometricVault.CancelledAuth && it.userCancelled) return@onFailure
                if (it is BiometricVault.Invalidated) mounts.forget(entry.id)
                message = it.message ?: "Biometric unlock failed"
            }.onSuccess { secret ->
                busy = Busy("Unlocking ${entry.name}", "Deriving keys. This is deliberately slow.")
                viewModelScope.launch {
                    val res = withContext(Dispatchers.IO) {
                        runCatching {
                            val creds = Credentials.fromSecret(secret)
                            secret.fill(0)
                            try { mounts.mount(entry, creds) } finally { creds.wipe() }
                        }
                    }
                    busy = null
                    res.onSuccess { onDone(true); openBrowser(entry.id) }
                        .onFailure { message = describe(it); onDone(false) }
                }
            }
            if (r.isFailure) onDone(false)
        }
    }

    private fun enrollBiometric(activity: FragmentActivity, entry: VolumeEntry, secret: ByteArray) {
        BiometricVault.enroll(activity, entry.id, entry.name, secret) { r ->
            secret.fill(0)
            r.onSuccess { w ->
                mounts.remember(entry.id, w)
                message = "Biometric unlock enabled for 3 days (until restart)"
            }.onFailure {
                BiometricVault.delete(entry.id)
                if (!(it is BiometricVault.CancelledAuth && it.userCancelled)) message = it.message ?: "Could not enable biometrics"
            }
        }
    }

    fun disableBiometric(entry: VolumeEntry) = mounts.forget(entry.id)

    fun lock(volumeId: String) {
        viewModelScope.launch(Dispatchers.IO) { mounts.unmount(volumeId) }
        val s = screen
        if (s is Screen.Browser && s.volumeId == volumeId) screen = Screen.List
    }

    fun lockAll() {
        viewModelScope.launch(Dispatchers.IO) { mounts.lockAll() }
        if (screen is Screen.Browser) screen = Screen.List
    }

    /** Called when the screen sees that the volume it is showing has been locked behind its back. */
    fun onMountedChanged(ids: Set<String>) {
        val s = screen
        if (s is Screen.Browser && s.volumeId !in ids) screen = Screen.List
    }

    // ---- create / add / remove ----------------------------------------------------------------

    fun createVolume(req: CreateRequest, activity: FragmentActivity) {
        val cancelled = AtomicBoolean(false)
        busy = Busy("Creating ${req.name}", "Filling with random data", 0f, cancel = { cancelled.set(true) })
        viewModelScope.launch {
            val uri = req.location ?: mounts.newInAppFile()
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val creds = Credentials.ofPassword(req.password, readKeyfiles(req.keyfiles))
                    req.password.fill('\u0000')
                    var pfd: android.os.ParcelFileDescriptor? = null
                    try {
                        pfd = app.contentResolver.openFileDescriptor(uri, "rw") ?: throw IOException("Cannot create container file")
                        val raw = FdRandomAccess(pfd)
                        val vol = Volume.create(raw, req.sizeBytes, creds, req.kdf, req.layers, req.compress) { p ->
                            if (cancelled.get()) throw CancelledException()
                            busy = Busy("Creating ${req.name}", "Filling with random data", p.toFloat()) { cancelled.set(true) }
                        }
                        busy = Busy("Creating ${req.name}", "Formatting")
                        val dev = CachedBlockDevice(vol)
                        val fs = SecFs.format(dev, vol.structure.compression)
                        fs.flush()
                        val entry = VolumeEntry(
                            name = req.name, uri = uri.toString(), sizeBytes = req.sizeBytes,
                            inAppStorage = req.location == null,
                        )
                        mounts.adopt(entry, vol, dev, fs)
                        entry to (if (req.enableBiometric) creds.exportSecret() else null)
                    } catch (t: Throwable) {
                        runCatching { pfd?.close() }
                        // Do not leave a half-written container behind.
                        if (uri.scheme == "file") runCatching { java.io.File(uri.path!!).delete() }
                        else runCatching { android.provider.DocumentsContract.deleteDocument(app.contentResolver, uri) }
                        throw t
                    } finally {
                        creds.wipe()
                    }
                }
            }
            busy = null
            result.onSuccess { (entry, secret) ->
                saveVolumes(volumes + entry)
                if (secret != null) enrollBiometric(activity, entry, secret)
                openBrowser(entry.id)
            }.onFailure {
                if (it !is CancelledException) message = describe(it)
                screen = Screen.Create
            }
        }
    }

    fun addExisting(uri: Uri) {
        viewModelScope.launch {
            val r = withContext(Dispatchers.IO) {
                runCatching {
                    app.contentResolver.takePersistableUriPermission(
                        uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    )
                    if (volumes.any { it.uri == uri.toString() }) throw IOException("This container is already in the list")
                    val pfd = app.contentResolver.openFileDescriptor(uri, "rw") ?: throw IOException("Cannot open file")
                    val size = pfd.use { it.statSize }
                    VolumeEntry(name = displayName(uri).substringBeforeLast('.'), uri = uri.toString(), sizeBytes = size, inAppStorage = false)
                }
            }
            r.onSuccess { saveVolumes(volumes + it) }.onFailure { message = describe(it) }
        }
    }

    fun remove(entry: VolumeEntry, deleteFile: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            mounts.unmount(entry.id)
            mounts.forget(entry.id)
            val ok = !deleteFile || mounts.deleteContainerFile(entry)
            withContext(Dispatchers.Main) {
                saveVolumes(volumes.filter { it.id != entry.id })
                if (!ok) message = "Removed from the list, but the file could not be deleted"
            }
        }
    }

    fun rename(entry: VolumeEntry, name: String) {
        if (name.isNotBlank()) replace(entry.copy(name = name.trim()))
    }

    fun changePassword(entry: VolumeEntry, newPassword: CharArray, keyfiles: List<Uri>, kdf: KdfParams) {
        val m = mounts.get(entry.id) ?: run { message = "Unlock the volume first"; return }
        busy = Busy("Changing password", "Re-encrypting the volume headers")
        viewModelScope.launch {
            val r = withContext(Dispatchers.IO) {
                runCatching {
                    val creds = Credentials.ofPassword(newPassword, readKeyfiles(keyfiles))
                    newPassword.fill('\u0000')
                    try { m.volume.changeCredentials(creds, kdf) } finally { creds.wipe() }
                }
            }
            busy = null
            r.onSuccess {
                // The remembered credential wraps the old secret and is now useless.
                disableBiometric(entry)
                message = "Password changed. Re-enable biometric unlock if you use it."
            }.onFailure { message = describe(it) }
        }
    }

    // ---- browsing -----------------------------------------------------------------------------

    fun openBrowser(volumeId: String) {
        val m = mounts.get(volumeId) ?: return
        dirStack.clear()
        dirStack.add(m.fs.rootId)
        screen = Screen.Browser(volumeId)
        refresh()
    }

    val currentDir get() = dirStack.lastOrNull() ?: SecFs.ROOT

    val canGoUp get() = dirStack.size > 1

    fun goUp() {
        if (dirStack.size > 1) {
            dirStack.removeAt(dirStack.size - 1)
            refresh()
        }
    }

    fun enter(dir: DirEntry) {
        dirStack.add(dir.stat.id)
        refresh()
    }

    fun refresh() {
        val s = screen as? Screen.Browser ?: return
        val m = mounts.get(s.volumeId) ?: return
        mounts.touch()
        viewModelScope.launch {
            val r = withContext(Dispatchers.IO) {
                runCatching {
                    val list = m.fs.list(currentDir).sortedWith(compareBy({ !it.stat.isDir }, { it.name.lowercase() }))
                    val names = dirStack.drop(1).map { m.fs.nameOf(it) ?: "?" }
                    Triple(list, names, m.fs.freeBytes() to m.fs.totalBytes())
                }
            }
            r.onSuccess { (list, names, space) ->
                entries = list; pathNames = names; freeBytes = space.first; totalBytes = space.second
            }.onFailure { message = describe(it) }
        }
    }

    private inline fun fsOp(crossinline block: (Mounted) -> Unit) {
        val s = screen as? Screen.Browser ?: return
        val m = mounts.get(s.volumeId) ?: return
        viewModelScope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { mounts.touch(); block(m) } }
            r.onFailure { message = describe(it) }
            refresh()
        }
    }

    fun newFolder(name: String) = fsOp { it.fs.createDir(currentDir, name.trim()) }
    fun renameEntry(e: DirEntry, name: String) = fsOp { it.fs.rename(currentDir, e.name, name.trim()) }
    fun deleteEntry(e: DirEntry) = fsOp { it.fs.delete(currentDir, e.name); it.fs.flush() }

    fun importFiles(uris: List<Uri>) {
        val s = screen as? Screen.Browser ?: return
        val m = mounts.get(s.volumeId) ?: return
        val dir = currentDir
        val cancelled = AtomicBoolean(false)
        viewModelScope.launch {
            val failures = mutableListOf<String>()
            withContext(Dispatchers.IO) {
                for ((i, uri) in uris.withIndex()) {
                    if (cancelled.get()) break
                    val name = displayName(uri)
                    val size = querySize(uri)
                    var created: String? = null
                    try {
                        val unique = VaultDocumentsProvider.uniqueName(m.fs, dir, name.replace('/', '_'))
                        val id = m.fs.createFile(dir, unique)
                        created = unique
                        val buf = ByteArray(256 * 1024)
                        var pos = 0L
                        (app.contentResolver.openInputStream(uri) ?: throw IOException("Cannot read $name")).use { input ->
                            while (true) {
                                if (cancelled.get()) throw CancelledException()
                                val n = input.read(buf)
                                if (n < 0) break
                                m.fs.write(id, pos, buf, 0, n)
                                pos += n
                                mounts.touch()
                                busy = Busy(
                                    "Encrypting ${i + 1} of ${uris.size}", name,
                                    if (size > 0) (pos.toFloat() / size).coerceIn(0f, 1f) else null,
                                ) { cancelled.set(true) }
                            }
                        }
                        m.fs.flush() // compressed volumes buffer the last chunk; surface a full-volume error here
                    } catch (e: Throwable) {
                        // Never leave a truncated copy behind.
                        created?.let { runCatching { m.fs.delete(dir, it) } }
                        if (e !is CancelledException) failures += "$name: ${describe(e)}"
                    }
                }
                runCatching { m.fs.flush() }
            }
            busy = null
            if (failures.isNotEmpty()) message = failures.joinToString("\n")
            refresh()
        }
    }

    fun exportFile(e: DirEntry, dest: Uri) {
        val s = screen as? Screen.Browser ?: return
        val m = mounts.get(s.volumeId) ?: return
        val cancelled = AtomicBoolean(false)
        viewModelScope.launch {
            val r = withContext(Dispatchers.IO) {
                runCatching {
                    val buf = ByteArray(256 * 1024)
                    var pos = 0L
                    (app.contentResolver.openOutputStream(dest, "wt") ?: throw IOException("Cannot write destination")).use { out ->
                        while (true) {
                            if (cancelled.get()) throw CancelledException()
                            val n = m.fs.read(e.stat.id, pos, buf, 0, buf.size)
                            if (n <= 0) break
                            out.write(buf, 0, n)
                            pos += n
                            mounts.touch()
                            busy = Busy("Exporting (decrypted copy)", e.name, (pos.toFloat() / e.stat.size.coerceAtLeast(1)).coerceIn(0f, 1f)) { cancelled.set(true) }
                        }
                    }
                }
            }
            busy = null
            r.onSuccess { message = "Exported ${e.name}. The copy is NOT encrypted." }
                .onFailure { if (it !is CancelledException) message = describe(it) }
        }
    }

    fun documentUri(volumeId: String, inode: Int): Uri =
        android.provider.DocumentsContract.buildDocumentUri(mounts.authority, "$volumeId:$inode")

    // ---- helpers ------------------------------------------------------------------------------

    fun displayName(uri: Uri): String =
        runCatching {
            app.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0) else null
            }
        }.getOrNull() ?: uri.lastPathSegment?.substringAfterLast('/') ?: "file"

    private fun querySize(uri: Uri): Long =
        runCatching {
            app.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use {
                if (it.moveToFirst() && !it.isNull(0)) it.getLong(0) else -1L
            }
        }.getOrNull() ?: -1L

    fun describe(t: Throwable): String = when (t) {
        is WrongPasswordException -> "Wrong password or keyfiles (or this is not a SecFiles container)."
        is OutOfMemoryError -> "Not enough memory for this volume's key derivation. Close other apps and retry."
        is VolumeFullException -> "The volume is full."
        else -> t.message ?: t.javaClass.simpleName
    }

    companion object {
        private const val MAX_KEYFILE = 16 * 1024 * 1024
    }
}
