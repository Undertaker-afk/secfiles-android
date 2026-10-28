package app.secfiles.android

import android.os.ParcelFileDescriptor
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import app.secfiles.core.RandomAccess
import java.io.IOException

/** Positional I/O on a container opened through a ContentResolver (app storage, SD card, SAF). */
class FdRandomAccess(private val pfd: ParcelFileDescriptor) : RandomAccess {
    private val fd = pfd.fileDescriptor

    override val size: Long
        get() = try {
            Os.fstat(fd).st_size
        } catch (e: ErrnoException) {
            throw io(e)
        }

    override fun read(pos: Long, buf: ByteArray, off: Int, len: Int) {
        var done = 0
        try {
            while (done < len) {
                val n = Os.pread(fd, buf, off + done, len - done, pos + done)
                if (n <= 0) throw IOException("Unexpected end of container at ${pos + done}")
                done += n
            }
        } catch (e: ErrnoException) {
            throw io(e)
        }
    }

    override fun write(pos: Long, buf: ByteArray, off: Int, len: Int) {
        var done = 0
        try {
            while (done < len) done += Os.pwrite(fd, buf, off + done, len - done, pos + done)
        } catch (e: ErrnoException) {
            throw io(e)
        }
    }

    override fun flush() {
        try {
            Os.fsync(fd)
        } catch (e: ErrnoException) {
            // Some providers do not support fsync; data is already handed to the kernel.
            if (e.errno != OsConstants.EINVAL && e.errno != OsConstants.EROFS) throw io(e)
        }
    }

    override fun close() = pfd.close()

    private fun io(e: ErrnoException): IOException = when (e.errno) {
        OsConstants.ESPIPE -> IOException("This storage location does not support random access. Use device storage or an SD card.")
        OsConstants.ENOSPC -> IOException("The storage holding the container is full")
        else -> IOException("Container I/O error: ${e.message}")
    }
}
