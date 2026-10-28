package app.secfiles.android

import android.database.Cursor
import android.database.MatrixCursor
import android.os.CancellationSignal
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelFileDescriptor
import android.os.ProxyFileDescriptorCallback
import android.os.storage.StorageManager
import android.provider.DocumentsContract.Document
import android.provider.DocumentsContract.Root
import android.provider.DocumentsProvider
import android.system.ErrnoException
import android.system.OsConstants
import android.webkit.MimeTypeMap
import app.secfiles.core.*
import java.io.FileNotFoundException
import java.io.IOException

/**
 * Exposes every unlocked volume to the rest of Android as a storage root, like an SD card or a network
 * share: it shows up in the system Files app and in every app's file picker, and other apps can read and
 * write into it. Data is decrypted on the fly; nothing plaintext ever touches disk.
 *
 * Document ids are "<volumeId>:<inode>".
 */
class VaultDocumentsProvider : DocumentsProvider() {
    private val mounts get() = (context!!.applicationContext as SecFilesApp).mounts

    private val ioThread by lazy { HandlerThread("vault-io").apply { start() } }
    private val ioHandler by lazy { Handler(ioThread.looper) }

    override fun onCreate() = true

    // ---- roots -------------------------------------------------------------------------------

    override fun queryRoots(projection: Array<out String>?): Cursor {
        val c = MatrixCursor(projection ?: DEFAULT_ROOT_PROJECTION)
        for (m in mounts.all()) {
            val free = runCatching { m.fs.freeBytes() }.getOrDefault(0L)
            c.newRow().apply {
                add(Root.COLUMN_ROOT_ID, m.entry.id)
                add(Root.COLUMN_DOCUMENT_ID, docId(m.entry.id, m.fs.rootId))
                add(Root.COLUMN_TITLE, m.entry.name)
                add(Root.COLUMN_SUMMARY, "Encrypted volume")
                add(Root.COLUMN_MIME_TYPES, "*/*")
                add(Root.COLUMN_AVAILABLE_BYTES, free)
                add(Root.COLUMN_ICON, R.drawable.ic_lock)
                add(
                    Root.COLUMN_FLAGS,
                    Root.FLAG_SUPPORTS_CREATE or Root.FLAG_SUPPORTS_IS_CHILD or Root.FLAG_SUPPORTS_SEARCH or Root.FLAG_LOCAL_ONLY
                )
            }
        }
        return c
    }

    // ---- documents ---------------------------------------------------------------------------

    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor {
        val (m, id) = resolve(documentId)
        val c = MatrixCursor(projection ?: DEFAULT_DOC_PROJECTION)
        addRow(c, m, id, if (id == m.fs.rootId) m.entry.name else null, documentId)
        return c
    }

    override fun queryChildDocuments(parentDocumentId: String, projection: Array<out String>?, sortOrder: String?): Cursor {
        val (m, id) = resolve(parentDocumentId)
        val c = MatrixCursor(projection ?: DEFAULT_DOC_PROJECTION)
        c.setNotificationUri(context!!.contentResolver, childrenUri(m, id))
        for (e in guarded { m.fs.list(id) }) {
            addRow(c, m, e.stat.id, e.name, null, e.stat)
        }
        return c
    }

    override fun querySearchDocuments(rootId: String, query: String, projection: Array<out String>?): Cursor {
        val m = mounts.get(rootId) ?: throw FileNotFoundException("Volume is locked")
        mounts.touch()
        val c = MatrixCursor(projection ?: DEFAULT_DOC_PROJECTION)
        val q = query.lowercase()
        var found = 0
        fun walk(dir: Int) {
            for (e in m.fs.list(dir)) {
                if (found >= MAX_SEARCH_RESULTS) return
                if (e.name.lowercase().contains(q)) { addRow(c, m, e.stat.id, e.name, null, e.stat); found++ }
                if (e.stat.isDir) walk(e.stat.id)
            }
        }
        guarded { walk(m.fs.rootId) }
        return c
    }

    override fun isChildDocument(parentDocumentId: String, documentId: String): Boolean {
        val (pm, pid) = resolve(parentDocumentId)
        val (cm, cid) = resolve(documentId)
        if (pm !== cm) return false
        if (pid == cid) return true
        return guarded { contains(pm.fs, pid, cid) }
    }

    private fun contains(fs: SecFs, dir: Int, target: Int): Boolean {
        var cur = target
        while (cur != fs.rootId) {
            cur = fs.parentOf(cur)
            if (cur == dir) return true
        }
        return dir == fs.rootId
    }

    private fun addRow(c: MatrixCursor, m: Mounted, id: Int, name: String?, docId: String?, known: Stat? = null) {
        val st = known ?: guarded { m.fs.stat(id) }
        var display = name
        if (display == null) display = findName(m, id) ?: "file"
        var flags = Document.FLAG_SUPPORTS_DELETE or Document.FLAG_SUPPORTS_RENAME or Document.FLAG_SUPPORTS_MOVE
        flags = flags or if (st.isDir) Document.FLAG_DIR_SUPPORTS_CREATE else Document.FLAG_SUPPORTS_WRITE
        val isRoot = id == m.fs.rootId
        if (isRoot) flags = Document.FLAG_DIR_SUPPORTS_CREATE
        c.newRow().apply {
            add(Document.COLUMN_DOCUMENT_ID, docId ?: docId(m.entry.id, id))
            add(Document.COLUMN_DISPLAY_NAME, display)
            add(Document.COLUMN_SIZE, if (st.isDir) null else st.size)
            add(Document.COLUMN_MIME_TYPE, if (st.isDir) Document.MIME_TYPE_DIR else mimeFor(display))
            add(Document.COLUMN_LAST_MODIFIED, st.mtimeMs)
            add(Document.COLUMN_FLAGS, flags)
        }
    }

    private fun findName(m: Mounted, id: Int): String? = guarded { m.fs.nameOf(id) }

    // ---- content -----------------------------------------------------------------------------

    override fun openDocument(documentId: String, mode: String, signal: CancellationSignal?): ParcelFileDescriptor {
        val (m, id) = resolve(documentId)
        val st = guarded { m.fs.stat(id) }
        if (st.isDir) throw FileNotFoundException("$documentId is a directory")
        val flags = ParcelFileDescriptor.parseMode(mode)
        val writable = flags and ParcelFileDescriptor.MODE_WRITE_ONLY != 0
        if (flags and ParcelFileDescriptor.MODE_TRUNCATE != 0) guarded { m.fs.truncate(id, 0) }
        mounts.touch()
        val sm = context!!.getSystemService(StorageManager::class.java)
        // The proxy only understands the access mode; CREATE/TRUNCATE were handled above.
        val access = flags and ParcelFileDescriptor.MODE_READ_WRITE
        return sm.openProxyFileDescriptor(access, FsCallback(m, id, writable), ioHandler)
    }

    private inner class FsCallback(val m: Mounted, val id: Int, val writable: Boolean) : ProxyFileDescriptorCallback() {
        override fun onGetSize(): Long = errno { m.fs.stat(id).size }

        override fun onRead(offset: Long, size: Int, data: ByteArray): Int = errno {
            mounts.touch()
            var total = 0
            while (total < size) {
                val n = m.fs.read(id, offset + total, data, total, size - total)
                if (n <= 0) break
                total += n
            }
            total
        }

        override fun onWrite(offset: Long, size: Int, data: ByteArray): Int = errno {
            if (!writable) throw ErrnoException("write", OsConstants.EBADF)
            mounts.touch()
            m.fs.write(id, offset, data, 0, size)
            size
        }

        override fun onFsync() = errno { m.fs.flush() }

        override fun onRelease() {
            runCatching { m.fs.flush() }
        }

        private inline fun <T> errno(block: () -> T): T = try {
            block()
        } catch (e: ErrnoException) {
            throw e
        } catch (e: VolumeFullException) {
            throw ErrnoException("fs", OsConstants.ENOSPC)
        } catch (e: IOException) {
            throw ErrnoException("fs", OsConstants.EIO)
        }
    }

    // ---- mutation ----------------------------------------------------------------------------

    override fun createDocument(parentDocumentId: String, mimeType: String, displayName: String): String {
        val (m, parent) = resolve(parentDocumentId)
        mounts.touch()
        return guarded {
            val name = uniqueName(m.fs, parent, sanitize(displayName))
            val id = if (mimeType == Document.MIME_TYPE_DIR) m.fs.createDir(parent, name) else m.fs.createFile(parent, name)
            docId(m.entry.id, id)
        }.also { notifyChildren(m, parent) }
    }

    override fun deleteDocument(documentId: String) {
        val (m, id) = resolve(documentId)
        mounts.touch()
        val parent = guarded {
            val p = parentOf(m, id) ?: throw FileNotFoundException(documentId)
            m.fs.delete(p.first, p.second)
            p.first
        }
        notifyChildren(m, parent)
    }

    override fun renameDocument(documentId: String, displayName: String): String? {
        val (m, id) = resolve(documentId)
        mounts.touch()
        val parent = guarded {
            val p = parentOf(m, id) ?: throw FileNotFoundException(documentId)
            m.fs.rename(p.first, p.second, sanitize(displayName))
            p.first
        }
        notifyChildren(m, parent)
        return documentId // inode numbers do not change on rename
    }

    override fun moveDocument(sourceDocumentId: String, sourceParentDocumentId: String, targetParentDocumentId: String): String {
        val (m, id) = resolve(sourceDocumentId)
        val (_, src) = resolve(sourceParentDocumentId)
        val (_, dst) = resolve(targetParentDocumentId)
        mounts.touch()
        guarded {
            val name = m.fs.list(src).firstOrNull { it.stat.id == id }?.name ?: throw FileNotFoundException(sourceDocumentId)
            m.fs.move(src, name, dst, uniqueName(m.fs, dst, name))
        }
        notifyChildren(m, src); notifyChildren(m, dst)
        return sourceDocumentId
    }

    override fun getDocumentType(documentId: String): String {
        val (m, id) = resolve(documentId)
        val st = guarded { m.fs.stat(id) }
        return if (st.isDir) Document.MIME_TYPE_DIR else mimeFor(findName(m, id) ?: "")
    }

    // ---- helpers -----------------------------------------------------------------------------

    private fun childrenUri(m: Mounted, dir: Int) =
        android.provider.DocumentsContract.buildChildDocumentsUri(mounts.authority, docId(m.entry.id, dir))

    private fun notifyChildren(m: Mounted, dir: Int) = context!!.contentResolver.notifyChange(childrenUri(m, dir), null)

    /** (parent inode, name) of [id]. */
    private fun parentOf(m: Mounted, id: Int): Pair<Int, String>? {
        val name = m.fs.nameOf(id) ?: return null
        return m.fs.parentOf(id) to name
    }

    private fun resolve(documentId: String): Pair<Mounted, Int> {
        val i = documentId.lastIndexOf(':')
        if (i <= 0) throw FileNotFoundException(documentId)
        val m = mounts.get(documentId.substring(0, i)) ?: throw FileNotFoundException("Volume is locked")
        val inode = documentId.substring(i + 1).toIntOrNull() ?: throw FileNotFoundException(documentId)
        return m to inode
    }

    private inline fun <T> guarded(block: () -> T): T = try {
        block()
    } catch (e: NoSuchEntryException) {
        throw FileNotFoundException(e.message)
    } catch (e: IOException) {
        // Locked volumes and filesystem errors surface to the caller as a missing file / I/O error.
        throw if (e is FileNotFoundException) e else FileNotFoundException(e.message ?: "I/O error")
    }

    private fun docId(volumeId: String, inode: Int) = "$volumeId:$inode"

    private fun sanitize(name: String) = name.replace('/', '_').replace('\u0000', '_').ifBlank { "untitled" }.take(200)

    private fun mimeFor(name: String): String {
        val ext = name.substringAfterLast('.', "").lowercase()
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
    }

    companion object {
        private const val MAX_SEARCH_RESULTS = 200
        private val DEFAULT_ROOT_PROJECTION = arrayOf(
            Root.COLUMN_ROOT_ID, Root.COLUMN_DOCUMENT_ID, Root.COLUMN_TITLE, Root.COLUMN_SUMMARY,
            Root.COLUMN_AVAILABLE_BYTES, Root.COLUMN_ICON, Root.COLUMN_FLAGS,
        )
        private val DEFAULT_DOC_PROJECTION = arrayOf(
            Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_SIZE,
            Document.COLUMN_MIME_TYPE, Document.COLUMN_LAST_MODIFIED, Document.COLUMN_FLAGS,
        )

        /** "name.ext" -> "name (1).ext" until it no longer collides in [dir]. */
        fun uniqueName(fs: SecFs, dir: Int, name: String): String {
            if (fs.lookup(dir, name) == null) return name
            val dot = name.lastIndexOf('.').takeIf { it > 0 } ?: name.length
            val base = name.substring(0, dot)
            val ext = name.substring(dot)
            var n = 1
            while (true) {
                val candidate = "$base ($n)$ext"
                if (fs.lookup(dir, candidate) == null) return candidate
                n++
            }
        }
    }
}
