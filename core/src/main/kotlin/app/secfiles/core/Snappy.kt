package app.secfiles.core

/**
 * Snappy raw block format (compatible with the reference implementation), written from scratch so the
 * decoder is strictly bounds-checked and never touches memory outside the output it was asked for.
 * The encoder only emits literals and 2-byte-offset copies, which every Snappy decoder accepts.
 */
internal object Snappy {
    private const val HASH_BITS = 14

    fun maxCompressedLength(n: Int) = 32 + n + n / 6

    private fun readInt(b: ByteArray, p: Int) =
        (b[p].toInt() and 0xFF) or ((b[p + 1].toInt() and 0xFF) shl 8) or
            ((b[p + 2].toInt() and 0xFF) shl 16) or ((b[p + 3].toInt() and 0xFF) shl 24)

    fun compress(src: ByteArray, off: Int, len: Int, table: IntArray): ByteArray {
        val dst = ByteArray(maxCompressedLength(len))
        var op = 0
        var v = len
        while (v >= 0x80) { dst[op++] = (v or 0x80).toByte(); v = v ushr 7 }
        dst[op++] = v.toByte()

        java.util.Arrays.fill(table, 0, 1 shl HASH_BITS, 0)
        val end = off + len
        var anchor = off
        var ip = off
        val limit = end - 4
        while (ip <= limit) {
            val seq = readInt(src, ip)
            val h = (seq * 0x1e35a7bd) ushr (32 - HASH_BITS)
            val ref = table[h] - 1
            table[h] = ip + 1
            if (ref >= off && ip - ref <= 65535 && readInt(src, ref) == seq) {
                var mlen = 4
                while (ip + mlen < end && src[ref + mlen] == src[ip + mlen]) mlen++
                op = literal(src, anchor, ip - anchor, dst, op)
                var remaining = mlen
                val offset = ip - ref
                while (remaining > 0) {
                    val n = minOf(remaining, 64)
                    dst[op++] = (((n - 1) shl 2) or 2).toByte()
                    dst[op++] = offset.toByte()
                    dst[op++] = (offset ushr 8).toByte()
                    remaining -= n
                }
                ip += mlen
                anchor = ip
            } else ip++
        }
        op = literal(src, anchor, end - anchor, dst, op)
        return dst.copyOf(op)
    }

    private fun literal(src: ByteArray, start: Int, n: Int, dst: ByteArray, op0: Int): Int {
        if (n == 0) return op0
        var op = op0
        val m = n - 1
        when {
            m < 60 -> dst[op++] = (m shl 2).toByte()
            m < 0x100 -> { dst[op++] = (60 shl 2).toByte(); dst[op++] = m.toByte() }
            m < 0x10000 -> { dst[op++] = (61 shl 2).toByte(); dst[op++] = m.toByte(); dst[op++] = (m ushr 8).toByte() }
            else -> {
                dst[op++] = (62 shl 2).toByte(); dst[op++] = m.toByte(); dst[op++] = (m ushr 8).toByte(); dst[op++] = (m ushr 16).toByte()
            }
        }
        System.arraycopy(src, start, dst, op, n)
        return op + n
    }

    /** Decompresses exactly [outLen] bytes into [out] at 0, or throws [CorruptVolumeException]. */
    fun decompress(src: ByteArray, off: Int, len: Int, out: ByteArray, outLen: Int) {
        fun bad(): Nothing = throw CorruptVolumeException("Corrupt compressed data")
        var ip = off
        val end = off + len
        // preamble: uncompressed length as a little-endian varint
        var declared = 0L
        var shift = 0
        while (true) {
            if (ip >= end || shift > 28) bad()
            val b = src[ip++].toInt() and 0xFF
            declared = declared or ((b and 0x7F).toLong() shl shift)
            if (b and 0x80 == 0) break
            shift += 7
        }
        if (declared != outLen.toLong()) bad()
        var op = 0
        while (ip < end) {
            val tag = src[ip++].toInt() and 0xFF
            when (tag and 3) {
                0 -> {
                    var n = tag ushr 2
                    if (n >= 60) {
                        val extra = n - 59
                        if (end - ip < extra) bad()
                        n = 0
                        for (i in 0 until extra) n = n or ((src[ip + i].toInt() and 0xFF) shl (8 * i))
                        ip += extra
                        if (n < 0) bad()
                    }
                    n += 1
                    if (n > end - ip || n > outLen - op) bad()
                    System.arraycopy(src, ip, out, op, n)
                    ip += n; op += n
                }
                else -> {
                    val n: Int
                    val offset: Int
                    when (tag and 3) {
                        1 -> {
                            if (ip >= end) bad()
                            n = ((tag ushr 2) and 7) + 4
                            offset = ((tag ushr 5) shl 8) or (src[ip++].toInt() and 0xFF)
                        }
                        2 -> {
                            if (end - ip < 2) bad()
                            n = (tag ushr 2) + 1
                            offset = (src[ip].toInt() and 0xFF) or ((src[ip + 1].toInt() and 0xFF) shl 8)
                            ip += 2
                        }
                        else -> {
                            if (end - ip < 4) bad()
                            n = (tag ushr 2) + 1
                            offset = readInt(src, ip)
                            ip += 4
                            if (offset < 0) bad()
                        }
                    }
                    if (offset == 0 || offset > op || n > outLen - op) bad()
                    var from = op - offset
                    repeat(n) { out[op++] = out[from++] }
                }
            }
        }
        if (op != outLen) bad()
    }
}
