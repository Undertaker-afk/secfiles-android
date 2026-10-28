package app.secfiles.core

import org.bouncycastle.crypto.BlockCipher
import org.bouncycastle.crypto.engines.ARIAEngine
import org.bouncycastle.crypto.engines.CAST6Engine
import org.bouncycastle.crypto.engines.CamelliaEngine
import org.bouncycastle.crypto.engines.GOST3412_2015Engine
import org.bouncycastle.crypto.engines.RC6Engine
import org.bouncycastle.crypto.engines.SerpentEngine
import org.bouncycastle.crypto.engines.TwofishEngine
import org.bouncycastle.crypto.params.KeyParameter
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/** Raw single-block-at-a-time ECB primitive over 16-byte blocks, in bulk. */
internal interface EcbBulk {
    /** Encrypts or decrypts [len] bytes (a multiple of 16) in place. */
    fun process(buf: ByteArray, off: Int, len: Int)
}

internal fun interface EcbFactory {
    fun create(key: ByteArray, encrypt: Boolean): EcbBulk
}

/**
 * The pool of block ciphers a cascade is drawn from. All use 256-bit keys and 128-bit blocks, which is
 * what XTS needs. [id] values are stored in container headers and must never change.
 */
enum class CipherKind(val id: Int, val displayName: String, internal val factory: EcbFactory) {
    AES(0, "AES-256", EcbFactory { key, enc ->
        val c = Cipher.getInstance("AES/ECB/NoPadding")
        c.init(if (enc) Cipher.ENCRYPT_MODE else Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"))
        object : EcbBulk {
            override fun process(buf: ByteArray, off: Int, len: Int) {
                // Cipher.doFinal with an offset output into the same array is safe for ECB/NoPadding.
                val n = c.doFinal(buf, off, len, buf, off)
                check(n == len)
            }
        }
    }),
    TWOFISH(1, "Twofish-256", EcbFactory { key, enc -> bcEcb(TwofishEngine(), key, enc) }),
    SERPENT(2, "Serpent-256", EcbFactory { key, enc -> bcEcb(SerpentEngine(), key, enc) }),
    CAMELLIA(3, "Camellia-256", EcbFactory { key, enc -> bcEcb(CamelliaEngine(), key, enc) }),
    KUZNYECHIK(4, "Kuznyechik", EcbFactory { key, enc -> bcEcb(GOST3412_2015Engine(), key, enc) }),
    ARIA(5, "ARIA-256", EcbFactory { key, enc -> bcEcb(ARIAEngine(), key, enc) }),
    CAST6(6, "CAST-256", EcbFactory { key, enc -> bcEcb(CAST6Engine(), key, enc) }),
    RC6(7, "RC6-256", EcbFactory { key, enc -> bcEcb(RC6Engine(), key, enc) });

    companion object {
        const val KEY_BYTES = 32
        fun byId(id: Int) = values().firstOrNull { it.id == id }
    }
}

private fun bcEcb(engine: BlockCipher, key: ByteArray, enc: Boolean): EcbBulk {
    engine.init(enc, KeyParameter(key))
    return object : EcbBulk {
        override fun process(buf: ByteArray, off: Int, len: Int) {
            var p = off
            val end = off + len
            while (p < end) {
                engine.processBlock(buf, p, buf, p)
                p += 16
            }
        }
    }
}

/**
 * IEEE 1619 XTS mode for any 128-bit block cipher, operating on whole data units
 * (no ciphertext stealing; the unit length must be a multiple of 16).
 */
class Xts(kind: CipherKind, dataKey: ByteArray, tweakKey: ByteArray) {
    private val enc = kind.factory.create(dataKey, true)
    private val dec = kind.factory.create(dataKey, false)
    private val tweakEnc = kind.factory.create(tweakKey, true)
    private val tweaks = ByteArray(MAX_UNIT)

    init {
        require(dataKey.size == CipherKind.KEY_BYTES && tweakKey.size == CipherKind.KEY_BYTES)
    }

    fun encrypt(unitNumber: Long, buf: ByteArray, off: Int, len: Int) = run(true, unitNumber, buf, off, len)
    fun decrypt(unitNumber: Long, buf: ByteArray, off: Int, len: Int) = run(false, unitNumber, buf, off, len)

    @Synchronized
    private fun run(encrypt: Boolean, unitNumber: Long, buf: ByteArray, off: Int, len: Int) {
        require(len >= 16 && len % 16 == 0 && len <= MAX_UNIT) { "bad XTS unit length $len" }
        // T_0 = E_K2(unit number as 128-bit little endian), T_{j+1} = T_j * alpha in GF(2^128).
        val t = tweaks
        java.util.Arrays.fill(t, 0, 16, 0)
        var n = unitNumber
        for (i in 0 until 8) {
            t[i] = n.toByte(); n = n ushr 8
        }
        tweakEnc.process(t, 0, 16)
        var p = 16
        while (p < len) {
            mulAlpha(t, p - 16, p)
            p += 16
        }
        xor(buf, off, len)
        (if (encrypt) enc else dec).process(buf, off, len)
        xor(buf, off, len)
    }

    private fun xor(buf: ByteArray, off: Int, len: Int) {
        val t = tweaks
        for (i in 0 until len) buf[off + i] = (buf[off + i].toInt() xor t[i].toInt()).toByte()
    }

    private fun mulAlpha(t: ByteArray, from: Int, to: Int) {
        var carry = 0
        for (i in 0 until 16) {
            val b = t[from + i].toInt() and 0xFF
            t[to + i] = (((b shl 1) and 0xFF) or carry).toByte()
            carry = b ushr 7
        }
        if (carry != 0) t[to] = (t[to].toInt() xor 0x87).toByte()
    }

    companion object {
        const val MAX_UNIT = 4096
    }
}
