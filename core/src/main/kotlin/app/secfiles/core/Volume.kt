package app.secfiles.core

import org.bouncycastle.crypto.modes.ChaCha20Poly1305
import org.bouncycastle.crypto.params.KeyParameter
import org.bouncycastle.crypto.params.ParametersWithIV
import java.io.IOException
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.SecureRandom

class WrongPasswordException : IOException("Wrong password/keyfiles, or not a SecFiles container")
class CorruptVolumeException(msg: String) : IOException(msg)
class CancelledException : IOException("Cancelled")
class HeaderTamperedException :
    IOException("The container header failed its integrity check. It is damaged or has been tampered with.")

/**
 * An encrypted container.
 *
 *     [ header 4096 ][ data area: N x 4096-byte XTS data units ][ backup header 4096 ]
 *
 * Header v2:
 *
 *     0     magic "SFCVOL02"
 *     8     argon2 memory KiB | iterations | parallelism | reserved   (4 x u32)
 *     24    salt (64)
 *     88    Falcon-512 public key   (u16 length + bytes, 1024-byte slot)
 *     1112  Falcon-512 signature   (u16 length + bytes, 1024-byte slot)
 *     2136  body (512)
 *     2648  random padding to 4096
 *
 * Opening checks, in this order: (1) the Falcon signature over [0,1112) + body, which needs no password
 * and so runs before the slow KDF and tells "damaged/tampered" apart from "wrong password"; (2) the
 * ChaCha20-Poly1305 tag of the sealed body, keyed from Argon2id(password, keyfiles, salt).
 *
 * Body = XTS cascade of the five header ciphers over ChaCha20-Poly1305(plaintext), plaintext (496 bytes):
 * magic "SFCBODY2" | version | sector size | data size | created ms | layers | compression id |
 * falcon seed (32) | cipher ids (5) | master keys (layers x 64) | random.
 *
 * The master keys are random and independent of the password, so changing the password only rewrites the
 * two headers instead of re-encrypting the data area.
 */
class Volume private constructor(
    private val raw: RandomAccess,
    val dataSize: Long,
    private val masterKeys: ByteArray,
    val structure: Structure,
    private val falconSeed: ByteArray,
    private val kdf: KdfParams,
) : BlockDevice {
    private val cascade = Cascade(structure.ciphers, masterKeys)
    override val blockSize get() = SECTOR_SIZE
    override val blockCount get() = dataSize / SECTOR_SIZE

    @Volatile
    private var closed = false

    /** Number of cipher layers (the only part of the structure the user chooses). */
    val layers get() = structure.ciphers.size

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

    /** Re-keys the headers (primary and backup) with new credentials. Data and structure are untouched. */
    fun changeCredentials(newCredentials: Credentials, newKdf: KdfParams = kdf) {
        check()
        val sealed = seal(newCredentials, newKdf, dataSize, masterKeys, structure, falconSeed)
        writeHeaders(raw, sealed.header, totalFileSize(dataSize))
        raw.flush()
    }

    fun close() {
        if (closed) return
        closed = true
        masterKeys.fill(0)
        falconSeed.fill(0)
        runCatching { raw.close() }
    }

    val isClosed get() = closed

    companion object {
        const val SECTOR_SIZE = 4096
        const val HEADER_SIZE = 4096L
        const val MIN_DATA_SIZE = 1L * 1024 * 1024

        private val MAGIC = "SFCVOL02".toByteArray()
        private val BODY_MAGIC = "SFCBODY2".toByteArray()
        private const val OFF_SALT = 24
        private const val OFF_PK = 88
        private const val OFF_SIG = 1112
        private const val SLOT = 1024
        private const val OFF_BODY = 2136
        private const val BODY_LEN = 512
        private const val PLAIN_LEN = BODY_LEN - 16 // ChaCha20-Poly1305 adds a 16-byte tag
        private const val SIGNED_PREFIX = OFF_SIG

        private val HEADER_KEYS = Cascade.keyBytes(Cascade.HEADER_KINDS.size) // 320
        private val DERIVED_LEN = HEADER_KEYS + 32 /* chacha key */ + 32 /* structure seed */

        fun totalFileSize(dataSize: Long) = HEADER_SIZE + dataSize + HEADER_SIZE

        /**
         * Writes a brand-new container into [raw] and returns it opened. The data area is filled with
         * random bytes (so free space is indistinguishable from encrypted data) and the headers are
         * written last. [layers] (1..5) is the cascade depth; which ciphers, in which order, and which
         * compression engine (if [compress]) are drawn from a hash of the derived key material.
         * [progress] receives 0..1 and may throw [CancelledException].
         */
        fun create(
            raw: RandomAccess,
            dataSize: Long,
            credentials: Credentials,
            kdf: KdfParams = KdfParams.MAXIMUM,
            layers: Int = 3,
            compress: Boolean = false,
            progress: (Double) -> Unit = {},
        ): Volume {
            require(dataSize >= MIN_DATA_SIZE && dataSize % SECTOR_SIZE == 0L) { "bad data size" }
            require(layers in 1..Cascade.MAX_LAYERS) { "layers must be 1..${Cascade.MAX_LAYERS}" }
            val rnd = SecureRandom()
            val keys = ByteArray(Cascade.keyBytes(layers)).also { rnd.nextBytes(it) }
            val seed = ByteArray(32).also { rnd.nextBytes(it) }
            val sealed = seal(credentials, kdf, dataSize, keys, null, seed, layers, compress)

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
            writeHeaders(raw, sealed.header, totalFileSize(dataSize))
            raw.flush()
            return Volume(raw, dataSize, keys, sealed.structure, seed, kdf)
        }

        /** Opens an existing container; falls back to the backup header only if the primary is damaged. */
        fun open(raw: RandomAccess, credentials: Credentials): Volume {
            val size = raw.size
            if (size < HEADER_SIZE * 2 + MIN_DATA_SIZE) throw WrongPasswordException()
            val primary = ByteArray(HEADER_SIZE.toInt()).also { raw.read(0, it, 0, it.size) }
            val backup = ByteArray(HEADER_SIZE.toInt()).also { raw.read(size - HEADER_SIZE, it, 0, it.size) }
            var sawSfc = false
            for (h in listOf(primary, backup)) {
                if (!MessageDigest.isEqual(h.copyOfRange(0, 8), MAGIC)) continue
                sawSfc = true
                if (!signatureOk(h)) continue // damaged or tampered: try the other copy
                // Signature is good, so any failure from here on is the password, not damage.
                val p = unseal(h, credentials) ?: throw WrongPasswordException()
                val expected = HEADER_SIZE + p.dataSize + HEADER_SIZE
                if (p.dataSize % SECTOR_SIZE != 0L || expected > size) {
                    p.keys.fill(0); p.seed.fill(0)
                    throw CorruptVolumeException("Container file is truncated")
                }
                return Volume(raw, p.dataSize, p.keys, p.structure, p.seed, p.kdf)
            }
            if (sawSfc) throw HeaderTamperedException()
            throw WrongPasswordException()
        }

        private class Parsed(val dataSize: Long, val keys: ByteArray, val structure: Structure, val seed: ByteArray, val kdf: KdfParams)
        private class Sealed(val header: ByteArray, val structure: Structure)

        private fun signedMessage(h: ByteArray): ByteArray =
            h.copyOfRange(0, SIGNED_PREFIX) + h.copyOfRange(OFF_BODY, OFF_BODY + BODY_LEN)

        private fun slot(h: ByteArray, off: Int): ByteArray? {
            val len = ((h[off].toInt() and 0xFF) shl 8) or (h[off + 1].toInt() and 0xFF)
            if (len == 0 || len > SLOT - 2) return null
            return h.copyOfRange(off + 2, off + 2 + len)
        }

        private fun putSlot(h: ByteArray, off: Int, data: ByteArray) {
            require(data.size <= SLOT - 2)
            h[off] = (data.size ushr 8).toByte(); h[off + 1] = data.size.toByte()
            System.arraycopy(data, 0, h, off + 2, data.size)
        }

        private fun signatureOk(h: ByteArray): Boolean {
            val pk = slot(h, OFF_PK) ?: return false
            val sig = slot(h, OFF_SIG) ?: return false
            return Integrity.verify(pk, signedMessage(h), sig)
        }

        private fun chacha(encrypt: Boolean, key: ByteArray, nonce: ByteArray, aad: ByteArray, input: ByteArray): ByteArray? {
            val c = ChaCha20Poly1305()
            c.init(encrypt, ParametersWithIV(KeyParameter(key), nonce))
            c.processAADBytes(aad, 0, aad.size)
            val out = ByteArray(c.getOutputSize(input.size))
            var n = c.processBytes(input, 0, input.size, out, 0)
            return try {
                n += c.doFinal(out, n)
                out.copyOf(n)
            } catch (e: org.bouncycastle.crypto.InvalidCipherTextException) {
                null
            }
        }

        /** Builds a signed header. [structure] null = derive a fresh one from the KDF output (creation). */
        private fun seal(
            cred: Credentials, kdf: KdfParams, dataSize: Long, keys: ByteArray, structure: Structure?, falconSeed: ByteArray,
            layers: Int = keys.size / 64, compress: Boolean = false,
        ): Sealed {
            val rnd = SecureRandom()
            val h = ByteArray(HEADER_SIZE.toInt()).also { rnd.nextBytes(it) }
            val bb = ByteBuffer.wrap(h)
            bb.put(MAGIC)
            bb.putInt(kdf.memoryKiB).putInt(kdf.iterations).putInt(kdf.parallelism).putInt(0)
            val salt = ByteArray(64).also { rnd.nextBytes(it) }
            System.arraycopy(salt, 0, h, OFF_SALT, 64)
            val fk = Integrity.keysFromSeed(falconSeed)
            putSlot(h, OFF_PK, fk.publicKey)

            val derived = Kdf.derive(cred, salt, kdf, DERIVED_LEN)
            val plain = ByteArray(PLAIN_LEN).also { rnd.nextBytes(it) }
            try {
                val st = structure ?: Structure.derive(derived.copyOfRange(HEADER_KEYS + 32, DERIVED_LEN), layers, compress)
                val b = ByteBuffer.wrap(plain)
                b.put(BODY_MAGIC).putInt(2).putInt(SECTOR_SIZE).putLong(dataSize).putLong(System.currentTimeMillis())
                b.putInt(st.ciphers.size).putInt(st.compression?.id ?: NO_COMPRESSION)
                b.put(falconSeed)
                for (i in 0 until Cascade.MAX_LAYERS) b.put((st.ciphers.getOrNull(i)?.id ?: 0xFF).toByte())
                b.position(80)
                b.put(keys)

                val sealedBody = chacha(true, derived.copyOfRange(HEADER_KEYS, HEADER_KEYS + 32), salt.copyOfRange(0, 12),
                    h.copyOfRange(0, SIGNED_PREFIX), plain)!!
                check(sealedBody.size == BODY_LEN)
                Cascade(Cascade.HEADER_KINDS, derived.copyOfRange(0, HEADER_KEYS)).encrypt(0, sealedBody, 0, BODY_LEN)
                System.arraycopy(sealedBody, 0, h, OFF_BODY, BODY_LEN)

                putSlot(h, OFF_SIG, Integrity.sign(fk, signedMessage(h)))
                return Sealed(h, st)
            } finally {
                derived.fill(0)
                plain.fill(0)
            }
        }

        private fun unseal(h: ByteArray, cred: Credentials): Parsed? {
            val bb = ByteBuffer.wrap(h, 8, 16)
            val kdf = KdfParams(memoryKiB = bb.int, iterations = bb.int, parallelism = bb.int)
            if (!KdfParams.isSane(kdf)) throw HeaderTamperedException()
            val salt = h.copyOfRange(OFF_SALT, OFF_SALT + 64)
            val derived = Kdf.derive(cred, salt, kdf, DERIVED_LEN)
            try {
                val body = h.copyOfRange(OFF_BODY, OFF_BODY + BODY_LEN)
                Cascade(Cascade.HEADER_KINDS, derived.copyOfRange(0, HEADER_KEYS)).decrypt(0, body, 0, BODY_LEN)
                val plain = chacha(false, derived.copyOfRange(HEADER_KEYS, HEADER_KEYS + 32), salt.copyOfRange(0, 12),
                    h.copyOfRange(0, SIGNED_PREFIX), body) ?: return null
                body.fill(0)
                try {
                    if (!MessageDigest.isEqual(plain.copyOfRange(0, 8), BODY_MAGIC)) return null
                    val b = ByteBuffer.wrap(plain, 8, plain.size - 8)
                    val version = b.int
                    val sector = b.int
                    val dataSize = b.long
                    b.long // created
                    val depth = b.int
                    val compId = b.int
                    if (version != 2 || sector != SECTOR_SIZE || dataSize < MIN_DATA_SIZE || depth !in 1..Cascade.MAX_LAYERS) {
                        throw CorruptVolumeException("Unsupported container version")
                    }
                    val seed = ByteArray(32).also { b.get(it) }
                    val kinds = (0 until Cascade.MAX_LAYERS).map { b.get().toInt() and 0xFF }.take(depth).map {
                        CipherKind.byId(it) ?: throw CorruptVolumeException("Unknown cipher in header")
                    }
                    val compression = if (compId == NO_COMPRESSION) null
                    else Compression.byId(compId) ?: throw CorruptVolumeException("Unknown compression in header")
                    b.position(80)
                    val keys = ByteArray(Cascade.keyBytes(depth)).also { b.get(it) }
                    return Parsed(dataSize, keys, Structure(kinds, compression), seed, kdf)
                } finally {
                    plain.fill(0)
                }
            } finally {
                derived.fill(0)
            }
        }

        private const val NO_COMPRESSION = 255

        private fun writeHeaders(raw: RandomAccess, header: ByteArray, totalSize: Long) {
            raw.write(0, header, 0, header.size)
            raw.write(totalSize - HEADER_SIZE, header, 0, header.size)
        }
    }
}
