package app.secfiles.core

/**
 * LZ4 block format (compatible with the reference lz4 block API). The decompressor treats its input as
 * hostile: every length and offset is bounds-checked and the output size must match exactly.
 */
internal object Lz4 {
    private const val MIN_MATCH = 4
    private const val LAST_LITERALS = 5
    private const val MF_LIMIT = 12
    private const val HASH_BITS = 14

    fun maxCompressedLength(n: Int) = n + n / 255 + 16

    private fun readInt(b: ByteArray, p: Int) =
        (b[p].toInt() and 0xFF) or ((b[p + 1].toInt() and 0xFF) shl 8) or
            ((b[p + 2].toInt() and 0xFF) shl 16) or ((b[p + 3].toInt() and 0xFF) shl 24)

    /** [table] must hold at least 1 shl 14 ints. Returns the compressed length written to [dst] (>= 1). */
    fun compress(src: ByteArray, off: Int, len: Int, dst: ByteArray, table: IntArray): Int {
        java.util.Arrays.fill(table, 0, 1 shl HASH_BITS, 0)
        var op = 0
        var anchor = off
        val end = off + len
        if (len >= MF_LIMIT + 1) {
            val mfLimit = end - MF_LIMIT
            val matchLimit = end - LAST_LITERALS
            var ip = off
            while (ip < mfLimit) {
                val seq = readInt(src, ip)
                val h = (seq * -1640531535) ushr (32 - HASH_BITS)
                val ref = table[h] - 1
                table[h] = ip + 1
                if (ref >= off && ip - ref <= 65535 && readInt(src, ref) == seq) {
                    var mlen = MIN_MATCH
                    while (ip + mlen < matchLimit && src[ref + mlen] == src[ip + mlen]) mlen++
                    op = emit(src, anchor, ip - anchor, ip - ref, mlen, dst, op)
                    ip += mlen
                    anchor = ip
                } else ip++
            }
        }
        return emitLastLiterals(src, anchor, end - anchor, dst, op)
    }

    private fun writeLen(dst: ByteArray, op0: Int, extra: Int): Int {
        var op = op0
        var rem = extra
        while (rem >= 255) { dst[op++] = 255.toByte(); rem -= 255 }
        dst[op++] = rem.toByte()
        return op
    }

    private fun emit(src: ByteArray, litStart: Int, litLen: Int, offset: Int, mlen: Int, dst: ByteArray, op0: Int): Int {
        var op = op0
        val tokenPos = op++
        var token = minOf(litLen, 15) shl 4
        if (litLen >= 15) op = writeLen(dst, op, litLen - 15)
        System.arraycopy(src, litStart, dst, op, litLen)
        op += litLen
        dst[op++] = offset.toByte()
        dst[op++] = (offset ushr 8).toByte()
        val m = mlen - MIN_MATCH
        token = token or minOf(m, 15)
        if (m >= 15) op = writeLen(dst, op, m - 15)
        dst[tokenPos] = token.toByte()
        return op
    }

    private fun emitLastLiterals(src: ByteArray, litStart: Int, litLen: Int, dst: ByteArray, op0: Int): Int {
        var op = op0
        dst[op++] = (minOf(litLen, 15) shl 4).toByte()
        if (litLen >= 15) op = writeLen(dst, op, litLen - 15)
        System.arraycopy(src, litStart, dst, op, litLen)
        return op + litLen
    }

    /** Decompresses exactly [outLen] bytes into [out] at 0, or throws [CorruptVolumeException]. */
    fun decompress(src: ByteArray, off: Int, len: Int, out: ByteArray, outLen: Int) {
        var ip = off
        val end = off + len
        var op = 0
        fun bad(): Nothing = throw CorruptVolumeException("Corrupt compressed data")
        while (true) {
            if (ip >= end) bad()
            val token = src[ip++].toInt() and 0xFF
            var lit = token ushr 4
            if (lit == 15) {
                while (true) {
                    if (ip >= end) bad()
                    val b = src[ip++].toInt() and 0xFF
                    lit += b
                    if (lit > outLen) bad()
                    if (b != 255) break
                }
            }
            if (lit > outLen - op || lit > end - ip) bad()
            System.arraycopy(src, ip, out, op, lit)
            ip += lit; op += lit
            if (ip == end) break
            if (end - ip < 2) bad()
            val offset = (src[ip].toInt() and 0xFF) or ((src[ip + 1].toInt() and 0xFF) shl 8)
            ip += 2
            if (offset == 0 || offset > op) bad()
            var ml = token and 15
            if (ml == 15) {
                while (true) {
                    if (ip >= end) bad()
                    val b = src[ip++].toInt() and 0xFF
                    ml += b
                    if (ml > outLen) bad()
                    if (b != 255) break
                }
            }
            ml += MIN_MATCH
            if (ml > outLen - op) bad()
            var from = op - offset
            repeat(ml) { out[op++] = out[from++] } // forward copy handles overlapping matches
        }
        if (op != outLen) bad()
    }
}
