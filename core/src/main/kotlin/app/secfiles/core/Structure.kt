package app.secfiles.core

import org.bouncycastle.crypto.digests.SHAKEDigest

/**
 * Which ciphers (and in which order) and which compression engine protect a volume's data.
 *
 * The user only chooses how many layers. The selection itself is drawn from a hash of the key material
 * derived from their password and the container's random salt, so it differs per container and is never
 * shown. It is then stored (encrypted) in the header, so changing the password does not change the
 * structure and re-encrypt nothing. This is obscurity layered on top of the real security (Argon2id and
 * the ciphers themselves); the design assumes an attacker knows the algorithms.
 */
class Structure(val ciphers: List<CipherKind>, val compression: Compression?) {
    init {
        require(ciphers.size in 1..Cascade.MAX_LAYERS)
    }

    companion object {
        fun derive(seed: ByteArray, layers: Int, compress: Boolean): Structure {
            require(layers in 1..Cascade.MAX_LAYERS)
            val xof = SHAKEDigest(256)
            val tag = "secfiles.structure.v1".toByteArray()
            xof.update(tag, 0, tag.size)
            xof.update(seed, 0, seed.size)
            val stream = ByteArray(64).also { xof.doOutput(it, 0, it.size) }

            // Fisher-Yates over the pool, driven by the hash stream.
            val pool = CipherKind.values().toMutableList()
            for (i in pool.size - 1 downTo 1) {
                val j = (stream[i].toInt() and 0xFF) % (i + 1)
                val t = pool[i]; pool[i] = pool[j]; pool[j] = t
            }
            val comp = if (compress) Compression.values()[(stream[32].toInt() and 0xFF) % Compression.values().size] else null
            return Structure(pool.take(layers), comp)
        }
    }
}
