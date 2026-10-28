package app.secfiles.android

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.core.content.ContextCompat
import app.secfiles.core.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

class Mounted(val entry: VolumeEntry, val volume: Volume, val device: CachedBlockDevice, val fs: SecFs)

/**
 * Owns every unlocked volume. A volume is "mounted" only while its keys are in this process's memory;
 * [unmount] zeroes them. Process death locks everything.
 */
class MountManager(private val context: Context) {
    private val mounted = ConcurrentHashMap<String, Mounted>()
    private val _mountedIds = MutableStateFlow<Set<String>>(emptySet())
    val mountedIds: StateFlow<Set<String>> = _mountedIds

    @Volatile
    private var lastActivity = System.currentTimeMillis()

    val authority get() = context.packageName + ".documents"

    fun get(volumeId: String): Mounted? = mounted[volumeId]

    fun all(): List<Mounted> = mounted.values.toList()

    /** Records user/provider activity so the inactivity auto-lock does not fire during use. */
    fun touch() {
        lastActivity = System.currentTimeMillis()
    }

    fun idleMillis() = System.currentTimeMillis() - lastActivity

    /** Opens the container and mounts it. Blocking and memory hungry (Argon2id); call off the main thread. */
    fun mount(entry: VolumeEntry, credentials: Credentials): Mounted {
        mounted[entry.id]?.let { return it }
        val pfd = context.contentResolver.openFileDescriptor(entry.parsedUri, "rw")
            ?: throw IOException("Cannot open the container file. Was it moved or deleted?")
        val raw = FdRandomAccess(pfd)
        val volume = try {
            Volume.open(raw, credentials)
        } catch (t: Throwable) {
            runCatching { raw.close() }
            throw t
        }
        val device = CachedBlockDevice(volume)
        val fs = try {
            SecFs.open(device)
        } catch (t: Throwable) {
            volume.close()
            throw t
        }
        val m = Mounted(entry, volume, device, fs)
        mounted[entry.id] = m
        touch()
        publish()
        return m
    }

    /** Registers a volume that was just created and is already open, avoiding a second key derivation. */
    fun adopt(entry: VolumeEntry, volume: Volume, device: CachedBlockDevice, fs: SecFs): Mounted {
        val m = Mounted(entry, volume, device, fs)
        mounted[entry.id] = m
        touch()
        publish()
        return m
    }

    fun unmount(volumeId: String) {
        val m = mounted.remove(volumeId) ?: return
        try {
            m.fs.flush()
        } catch (_: Throwable) {
        }
        m.device.wipe()
        m.volume.close()
        publish()
    }

    fun lockAll() {
        mounted.keys.toList().forEach { unmount(it) }
    }

    private fun publish() {
        _mountedIds.value = mounted.keys.toSet()
        // Tell the system Files app that a drive appeared / disappeared.
        context.contentResolver.notifyChange(DocumentsContract.buildRootsUri(authority), null)
        if (mounted.isNotEmpty()) {
            ContextCompat.startForegroundService(context, Intent(context, MountService::class.java))
        } else {
            context.stopService(Intent(context, MountService::class.java))
        }
    }

    // ---- container files ---------------------------------------------------------------------

    fun newInAppFile(): Uri {
        val dir = File(context.filesDir, "volumes").apply { mkdirs() }
        return Uri.fromFile(File(dir, java.util.UUID.randomUUID().toString() + ".sfc"))
    }

    /** Deletes the container file itself. Returns false if the provider refused. */
    fun deleteContainerFile(entry: VolumeEntry): Boolean {
        val uri = entry.parsedUri
        return runCatching {
            if (uri.scheme == "file") File(uri.path!!).delete()
            else DocumentsContract.deleteDocument(context.contentResolver, uri)
        }.getOrDefault(false)
    }
}
