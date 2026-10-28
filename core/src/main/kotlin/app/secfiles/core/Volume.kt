package app.secfiles.core

import java.io.IOException
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

class WrongPasswordException : IOException("Wrong password/keyfiles, or not a SecFiles container")
class CorruptVolumeException(msg: String) : IOException(msg)
class CancelledException : IOException("Cancelled")

/**
 * An encrypted container.
 *
 *     [ header 4096 ][ data area: N x 4096-byte XTS data units ][ backup header 4096 ]
 *
 * Header (cleartext fields are authenticated, never trusted before the MAC passes):
 *
 *     0   magic "SFCVOL01"
 *     8   argon2 memory KiB (u32) | 12 iterations (u32) | 16 parallelism (u32) | 20 reserved (u32)
 *     24  salt (64)
 *     88  body (512) : encrypted with the AES>Twofish>Serpent cascade keyed from Argon2id output [0,192)
 *     600 HMAC-SHA-512 over bytes [0,600) keyed with Argon2id output [192,256)
 *     664 random padding to 4096
 *
 * Body plaintext: "SFCBODY1" | version u32 | sector size u32 | data size u64 | created ms u64 |
 * master keys (192) | random padding.
 *
 * The master keys are random and independent of the password, so changing the password only rewrites
 * the two headers rather than re-encrypting the data area.
 */
class Volume private constructor(
    private val raw: RandomAccess,
    val dataSize: Long,
    private var masterKeys: ByteArray,
    private val kdf: KdfParams,
) : BlockDevice {
    private var cascade = Cascade(masterKeys)
    override val blockSize get() = SECTOR_SIZE
    override val blockCount get() = dataSize / SECTOR_SIZE
    @Volatile
    private var closed = false

    private fun check() {
        if (closed) throw IOException("Volume is locked")
    }

    override fun read(block: Long, dst: ByteArray, off: Int) {
        check()
        require(block in 0 until blockCount) { "block $block out of range" }
        raw.read(HEADER_SIZE + block * SECTOR_SIZE, dst, off, SECTOR_SIZE)
        cascade.decrypt(block, dst, off, SECTOR_SIZE)
    }

    private val scratch = ThreadLocal.withInitial { ByteArray(SECTOR_SIZE) }

    override fun write(block: Long, src: ByteArray, off: Int) {
        check()
        require(block in 0 until blockCount) { "block $block out of range" }
        val tmp = scratch.get()!!
        System.arraycopy(src, off, tmp, 0, SECTOR_SIZE)
        cascade.encrypt(block, tmp, 0, SECTOR_SIZE)
        raw.write(HEADER_SIZE + block * SECTOR_SIZE, tmp, 0, SECTOR_SIZE)
        tmp.fill(0)
    }

    override fun flush() {
        check()
        raw.flush()
    }

    /** Re-keys the headers (primary and backup) with new credentials. Data is untouched. */
    fun changeCredentials(newCredentials: Credentials, newKdf: KdfParams = kdf) {
        check()
        val header = buildHeader(masterKeys, dataSize, newCredentials, newKdf)
        writeHeaders(raw, header, totalFileSize(dataSize))
        raw.flush()
    }

    fun close() {
        if (closed) return
        closed = true
        masterKeys.fill(0)
        runCatching { raw.close() }
    }

    val isClosed get() = closed

    companion object {
        const val SECTOR_SIZE = 4096
        const val HEADER_SIZE = 4096L
        const val MIN_DATA_SIZE = 1L * 1024 * 1024

        private val MAGIC = "SFCVOL01".toByteArray()
        private val BODY_MAGIC = "SFCBODY1".toByteArray()
        private const val OFF_SALT = 24
        private const val OFF_BODY = 88
        private const val BODY_LEN = 512
        private const val OFF_MAC = OFF_BODY + BODY_LEN
        private const val MAC_LEN = 64
        private const val HEADER_USED = OFF_MAC + MAC_LEN
        private val DERIVED_LEN = Cascade.KEY_MATERIAL_BYTES + 64

        fun totalFileSize(dataSize: Long) = HEADER_SIZE + dataSize + HEADER_SIZE

        /**
         * Writes a brand-new container into [raw] and returns it opened. The data area is filled with
         * random bytes (so free space is indistinguishable from encrypted data) and the headers are
         * written last. [progress] receives 0..1 and may throw [CancelledException].
         */
        fun create(
            raw: RandomAccess,
            dataSize: Long,
            credentials: Credentials,
            kdf: KdfParams = KdfParams.MAXIMUM,
            progress: (Double) -> Unit = {},
        ): Volume {
            require(dataSize >= MIN_DATA_SIZE && dataSize % SECTOR_SIZE == 0L) { "bad data size" }
            val rnd = SecureRandom()
            val keys = ByteArray(Cascade.KEY_MATERIAL_BYTES).also { rnd.nextBytes(it) }
            val header = buildHeader(keys, dataSize, credentials, kdf)

            val chunk = ByteArray(1 shl 20)
            var pos = HEADER_SIZE
            val end = HEADER_SIZE + dataSize
            while (pos < end) {
                val n = minOf(chunk.size.toLong(), end - pos).toInt()
                rnd.nextBytes(chunk)
                raw.write(pos, chunk, 0, n)
                pos += n
                progress((pos - HEADER_SIZE).toDouble() / dataSize)
            }
            writeHeaders(raw, header, totalFileSize(dataSize))
            raw.flush()
            return Volume(raw, dataSize, keys, kdf)
        }

        /** Opens an existing container; tries the primary header and then the backup. */
        fun open(raw: RandomAccess, credentials: Credentials): Volume {
            val size = raw.size
            if (size < HEADER_SIZE * 2 + MIN_DATA_SIZE) throw WrongPasswordException()
            val primary = ByteArray(HEADER_SIZE.toInt()).also { raw.read(0, it, 0, it.size) }
            val backup = ByteArray(HEADER_SIZE.toInt()).also { raw.read(size - HEADER_SIZE, it, 0, it.size) }
            for (h in listOf(primary, backup)) {
                val parsed = parseHeader(h, credentials) ?: continue
                val expected = HEADER_SIZE + parsed.dataSize + HEADER_SIZE
                if (parsed.dataSize % SECTOR_SIZE != 0L || expected > size) {
                    parsed.keys.fill(0)
                    throw CorruptVolumeException("Container file is truncated")
                }
                return Volume(raw, parsed.dataSize, parsed.keys, parsed.kdf)
            }
            throw WrongPasswordException()
        }

        private class Parsed(val dataSize: Long, val keys: ByteArray, val kdf: KdfParams)

        private fun buildHeader(keys: ByteArray, dataSize: Long, cred: Credentials, kdf: KdfParams): ByteArray {
            val rnd = SecureRandom()
            val h = ByteArray(HEADER_SIZE.toInt())
            rnd.nextBytes(h)
            val bb = ByteBuffer.wrap(h)
            bb.put(MAGIC)
            bb.putInt(kdf.memoryKiB).putInt(kdf.iterations).putInt(kdf.parallelism).putInt(0)
            val salt = ByteArray(64).also { rnd.nextBytes(it) }
            System.arraycopy(salt, 0, h, OFF_SALT, 64)

            val body = ByteArray(BODY_LEN).also { rnd.nextBytes(it) }
            val bodyBuf = ByteBuffer.wrap(body)
            bodyBuf.put(BODY_MAGIC).putInt(1).putInt(SECTOR_SIZE).putLong(dataSize).putLong(System.currentTimeMillis())
            bodyBuf.put(keys)

            val derived = Kdf.derive(cred, salt, kdf, DERIVED_LEN)
            try {
                Cascade(derived.copyOfRange(0, Cascade.KEY_MATERIAL_BYTES)).encrypt(0, body, 0, BODY_LEN)
                System.arraycopy(body, 0, h, OFF_BODY, BODY_LEN)
                val mac = hmac(derived.copyOfRange(Cascade.KEY_MATERIAL_BYTES, DERIVED_LEN), h, 0, OFF_MAC)
                System.arraycopy(mac, 0, h, OFF_MAC, MAC_LEN)
            } finally {
                derived.fill(0)
                body.fill(0)
            }
            return h
        }

        private fun parseHeader(h: ByteArray, cred: Credentials): Parsed? {
            if (!MessageDigest.isEqual(h.copyOfRange(0, 8), MAGIC)) return null
            val bb = ByteBuffer.wrap(h, 8, 16)
            val kdf = KdfParams(memoryKiB = bb.int, iterations = bb.int, parallelism = bb.int)
            if (!KdfParams.isSane(kdf)) return null
            val salt = h.copyOfRange(OFF_SALT, OFF_SALT + 64)
            val derived = Kdf.derive(cred, salt, kdf, DERIVED_LEN)
            try {
                val mac = hmac(derived.copyOfRange(Cascade.KEY_MATERIAL_BYTES, DERIVED_LEN), h, 0, OFF_MAC)
                if (!MessageDigest.isEqual(mac, h.copyOfRange(OFF_MAC, OFF_MAC + MAC_LEN))) return null
                val body = h.copyOfRange(OFF_BODY, OFF_BODY + BODY_LEN)
                Cascade(derived.copyOfRange(0, Cascade.KEY_MATERIAL_BYTES)).decrypt(0, body, 0, BODY_LEN)
                if (!MessageDigest.isEqual(body.copyOfRange(0, 8), BODY_MAGIC)) return null
                val b = ByteBuffer.wrap(body, 8, BODY_LEN - 8)
                val version = b.int
                val sector = b.int
                val dataSize = b.long
                b.long // created
                if (version != 1 || sector != SECTOR_SIZE || dataSize < MIN_DATA_SIZE) {
                    throw CorruptVolumeException("Unsupported container version")
                }
                val keys = ByteArray(Cascade.KEY_MATERIAL_BYTES).also { b.get(it) }
                body.fill(0)
                return Parsed(dataSize, keys, kdf)
            } finally {
                derived.fill(0)
            }
        }

        private fun writeHeaders(raw: RandomAccess, header: ByteArray, totalSize: Long) {
            raw.write(0, header, 0, header.size)
            raw.write(totalSize - HEADER_SIZE, header, 0, header.size)
        }

        private fun hmac(key: ByteArray, data: ByteArray, off: Int, len: Int): ByteArray {
            val m = Mac.getInstance("HmacSHA512")
            m.init(SecretKeySpec(key, "HmacSHA512"))
            m.update(data, off, len)
            return m.doFinal()
        }
    }
}
