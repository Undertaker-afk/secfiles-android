package app.secfiles.core

import java.io.Closeable
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

/** Positional byte storage that backs a container file. */
interface RandomAccess : Closeable {
    val size: Long
    fun read(pos: Long, buf: ByteArray, off: Int, len: Int)
    fun write(pos: Long, buf: ByteArray, off: Int, len: Int)
    fun flush()
}

/** [RandomAccess] over a [FileChannel]; works for java.io files and Android ParcelFileDescriptors. */
class ChannelRandomAccess(private val channel: FileChannel, private val onClose: () -> Unit = {}) : RandomAccess {
    override val size: Long get() = channel.size()

    override fun read(pos: Long, buf: ByteArray, off: Int, len: Int) {
        val bb = ByteBuffer.wrap(buf, off, len)
        var p = pos
        while (bb.hasRemaining()) {
            val n = channel.read(bb, p)
            if (n < 0) throw IOException("Unexpected end of container at $p")
            p += n
        }
    }

    override fun write(pos: Long, buf: ByteArray, off: Int, len: Int) {
        val bb = ByteBuffer.wrap(buf, off, len)
        var p = pos
        while (bb.hasRemaining()) p += channel.write(bb, p)
    }

    override fun flush() = channel.force(false)

    override fun close() {
        try {
            channel.close()
        } finally {
            onClose()
        }
    }
}

/** In-memory storage, used by tests. */
class MemoryRandomAccess(initialSize: Long) : RandomAccess {
    private val data = ByteArray(initialSize.toInt())
    override val size: Long get() = data.size.toLong()
    override fun read(pos: Long, buf: ByteArray, off: Int, len: Int) =
        System.arraycopy(data, pos.toInt(), buf, off, len)

    override fun write(pos: Long, buf: ByteArray, off: Int, len: Int) =
        System.arraycopy(buf, off, data, pos.toInt(), len)

    override fun flush() {}
    override fun close() {}
}
