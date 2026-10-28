package app.secfiles.core

import org.itadaki.bzip2.BZip2InputStream
import org.itadaki.bzip2.BZip2OutputStream
import org.tukaani.xz.LZMA2Options
import org.tukaani.xz.LZMAInputStream
import org.tukaani.xz.LZMAOutputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.zip.Deflater
import java.util.zip.Inflater

/** The compression engines a volume can use. Which one a volume gets is decided by its password hash. */
enum class Compression(val id: Int) {
    LZ4(0), DEFLATE(1), LZMA(2), BZIP2(3), SNAPPY(4);

    companion object {
        fun byId(id: Int) = values().firstOrNull { it.id == id }
    }
}

/** Stateless-per-call chunk compressor. Instances are not thread-safe; [SecFs] serialises access. */
internal abstract class Codec {
    /** Returns the compressed bytes. May be larger than the input for incompressible data. */
    abstract fun compress(src: ByteArray, off: Int, len: Int): ByteArray

    /** Fills exactly [outLen] bytes of [out] or throws [CorruptVolumeException]. Input is untrusted. */
    abstract fun decompress(src: ByteArray, off: Int, len: Int, out: ByteArray, outLen: Int)

    companion object {
        fun of(c: Compression): Codec = when (c) {
            Compression.LZ4 -> Lz4Codec()
            Compression.DEFLATE -> DeflateCodec()
            Compression.LZMA -> LzmaCodec()
            Compression.BZIP2 -> Bzip2Codec()
            Compression.SNAPPY -> SnappyCodec()
        }

        fun corrupt(cause: Throwable? = null): Nothing =
            throw CorruptVolumeException("Corrupt compressed data").also { if (cause != null) it.initCause(cause) }

        fun readFully(input: InputStream, out: ByteArray, outLen: Int) {
            var n = 0
            while (n < outLen) {
                val r = input.read(out, n, outLen - n)
                if (r <= 0) corrupt()
                n += r
            }
        }
    }
}

private class Lz4Codec : Codec() {
    private val table = IntArray(1 shl 14)
    private val buf = ByteArray(Lz4.maxCompressedLength(65536 + 64))
    override fun compress(src: ByteArray, off: Int, len: Int): ByteArray {
        val dst = if (len + len / 255 + 16 <= buf.size) buf else ByteArray(Lz4.maxCompressedLength(len))
        return dst.copyOf(Lz4.compress(src, off, len, dst, table))
    }

    override fun decompress(src: ByteArray, off: Int, len: Int, out: ByteArray, outLen: Int) =
        Lz4.decompress(src, off, len, out, outLen)
}

private class DeflateCodec : Codec() {
    override fun compress(src: ByteArray, off: Int, len: Int): ByteArray {
        val d = Deflater(Deflater.BEST_COMPRESSION, true)
        try {
            d.setInput(src, off, len); d.finish()
            val out = ByteArrayOutputStream(len / 2 + 64)
            val tmp = ByteArray(8192)
            while (!d.finished()) out.write(tmp, 0, d.deflate(tmp))
            return out.toByteArray()
        } finally { d.end() }
    }

    override fun decompress(src: ByteArray, off: Int, len: Int, out: ByteArray, outLen: Int) {
        val inf = Inflater(true)
        try {
            inf.setInput(src, off, len)
            var n = 0
            while (n < outLen) {
                val r = try { inf.inflate(out, n, outLen - n) } catch (e: java.util.zip.DataFormatException) { corrupt(e) }
                if (r == 0 && (inf.finished() || inf.needsInput() || inf.needsDictionary())) corrupt()
                n += r
            }
        } finally { inf.end() }
    }
}

private class LzmaCodec : Codec() {
    private fun options() = LZMA2Options().apply { setPreset(3); dictSize = 1 shl 17 } // preset first: it resets dictSize

    override fun compress(src: ByteArray, off: Int, len: Int): ByteArray {
        val bos = ByteArrayOutputStream(len / 2 + 64)
        LZMAOutputStream(bos, options(), len.toLong()).use { it.write(src, off, len) }
        return bos.toByteArray()
    }

    override fun decompress(src: ByteArray, off: Int, len: Int, out: ByteArray, outLen: Int) {
        try {
            // 4 MiB memory cap: the stream header is untrusted and could name a huge dictionary.
            LZMAInputStream(ByteArrayInputStream(src, off, len), 4096).use { readFully(it, out, outLen) }
        } catch (e: CorruptVolumeException) { throw e } catch (e: Exception) { corrupt(e) }
    }
}

private class Bzip2Codec : Codec() {
    override fun compress(src: ByteArray, off: Int, len: Int): ByteArray {
        val bos = ByteArrayOutputStream(len / 2 + 64)
        BZip2OutputStream(bos, 1).use { it.write(src, off, len) }
        return bos.toByteArray()
    }

    override fun decompress(src: ByteArray, off: Int, len: Int, out: ByteArray, outLen: Int) {
        try {
            BZip2InputStream(ByteArrayInputStream(src, off, len), false).use { readFully(it, out, outLen) }
        } catch (e: CorruptVolumeException) { throw e } catch (e: Exception) { corrupt(e) }
    }
}

private class SnappyCodec : Codec() {
    private val table = IntArray(1 shl 14)
    override fun compress(src: ByteArray, off: Int, len: Int): ByteArray = Snappy.compress(src, off, len, table)
    override fun decompress(src: ByteArray, off: Int, len: Int, out: ByteArray, outLen: Int) =
        Snappy.decompress(src, off, len, out, outLen)
}
