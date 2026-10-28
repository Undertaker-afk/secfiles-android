package app.secfiles.core

import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters
import java.security.MessageDigest

/** Argon2id cost parameters. Memory is in KiB. */
data class KdfParams(val memoryKiB: Int, val iterations: Int, val parallelism: Int) {
    companion object {
        /** Fast enough for low-end phones. */
        val STANDARD = KdfParams(memoryKiB = 128 * 1024, iterations = 3, parallelism = 4)

        /** 256 MiB, 4 passes. */
        val STRONG = KdfParams(memoryKiB = 256 * 1024, iterations = 4, parallelism = 4)

        /** Heavy: 512 MiB, 4 passes. Several seconds per unlock on a phone; needs a large heap. */
        val MAXIMUM = KdfParams(memoryKiB = 512 * 1024, iterations = 4, parallelism = 4)

        /** Upper bounds accepted when reading a header, so a tampered file cannot demand absurd resources. */
        const val MAX_MEMORY_KIB = 1024 * 1024
        const val MAX_ITERATIONS = 64
        const val MAX_PARALLELISM = 16

        fun isSane(p: KdfParams) =
            p.memoryKiB in 8..MAX_MEMORY_KIB && p.iterations in 1..MAX_ITERATIONS && p.parallelism in 1..MAX_PARALLELISM
    }
}

/** A password and/or keyfiles, folded into the single secret that is fed to Argon2id. */
class Credentials private constructor(internal val secret: ByteArray) {
    constructor(password: ByteArray, keyfiles: List<ByteArray> = emptyList()) : this(fold(password, keyfiles))

    /**
     * The folded secret, for wrapping under a hardware-backed biometric key. Anyone holding these
     * bytes can open the volume, so callers must wipe the copy as soon as it has been wrapped.
     */
    fun exportSecret(): ByteArray = secret.copyOf()

    /** Overwrites the secret. Call as soon as the volume is open. */
    fun wipe() = secret.fill(0)

    companion object {
        fun fromSecret(secret: ByteArray) = Credentials(secret.copyOf())

        private fun fold(password: ByteArray, keyfiles: List<ByteArray>): ByteArray {
            require(password.isNotEmpty() || keyfiles.isNotEmpty()) { "A password or at least one keyfile is required" }
            // Each keyfile is reduced to SHA-512; digests are sorted so the order the files are picked in
            // does not matter. Layout: password || 0x00 || digest1 || digest2 ...
            val digests = keyfiles.map { MessageDigest.getInstance("SHA-512").digest(it) }
                .sortedWith { a, b -> java.util.Arrays.compareUnsigned(a, b) }
            val out = java.io.ByteArrayOutputStream()
            out.write(password)
            out.write(0)
            digests.forEach { out.write(it) }
            return out.toByteArray()
        }

        fun ofPassword(password: CharArray, keyfiles: List<ByteArray> = emptyList()): Credentials {
            val bytes = Charsets.UTF_8.encode(java.nio.CharBuffer.wrap(password))
            val arr = ByteArray(bytes.remaining())
            bytes.get(arr)
            // Charsets.encode returns a heap buffer we cannot reach, so clear what we can.
            if (bytes.hasArray()) bytes.array().fill(0)
            return Credentials(arr, keyfiles).also { arr.fill(0) }
        }
    }
}

internal object Kdf {
    /** Derives [length] bytes with Argon2id v1.3. */
    fun derive(credentials: Credentials, salt: ByteArray, params: KdfParams, length: Int): ByteArray {
        val builder = Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
            .withVersion(Argon2Parameters.ARGON2_VERSION_13)
            .withSalt(salt)
            .withIterations(params.iterations)
            .withMemoryAsKB(params.memoryKiB)
            .withParallelism(params.parallelism)
        val gen = Argon2BytesGenerator()
        gen.init(builder.build())
        val out = ByteArray(length)
        gen.generateBytes(credentials.secret, out)
        return out
    }
}
