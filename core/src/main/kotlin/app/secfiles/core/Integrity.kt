package app.secfiles.core

import org.bouncycastle.crypto.digests.SHAKEDigest
import org.bouncycastle.pqc.crypto.falcon.FalconKeyGenerationParameters
import org.bouncycastle.pqc.crypto.falcon.FalconKeyPairGenerator
import org.bouncycastle.pqc.crypto.falcon.FalconParameters
import org.bouncycastle.pqc.crypto.falcon.FalconPrivateKeyParameters
import org.bouncycastle.pqc.crypto.falcon.FalconPublicKeyParameters
import org.bouncycastle.pqc.crypto.falcon.FalconSigner
import java.security.SecureRandom

/**
 * Falcon-512 (post-quantum) signature over the container header.
 *
 * The public key and signature sit in the clear in the header, so tampering or damage is detected
 * *before* the expensive key derivation runs, and a damaged primary header can be told apart from a wrong
 * password. The private key never touches disk: it is regenerated from a seed that lives only inside the
 * encrypted header body.
 *
 * Scope: this protects the header. The data area has no per-block authentication (XTS is malleable), so a
 * modified ciphertext block decrypts to garbage rather than being rejected.
 */
internal object Integrity {
    private val PARAMS = FalconParameters.falcon_512

    /** Deterministic RNG so the same seed always yields the same Falcon key pair. */
    private class SeededRandom(seed: ByteArray) : SecureRandom() {
        private val xof = SHAKEDigest(256).also { it.update(seed, 0, seed.size) }

        @Synchronized
        override fun nextBytes(bytes: ByteArray) {
            xof.doOutput(bytes, 0, bytes.size)
        }
    }

    class Keys(val publicKey: ByteArray, val private: FalconPrivateKeyParameters)

    fun keysFromSeed(seed: ByteArray): Keys {
        val gen = FalconKeyPairGenerator()
        gen.init(FalconKeyGenerationParameters(SeededRandom(seed), PARAMS))
        val pair = gen.generateKeyPair()
        return Keys((pair.public as FalconPublicKeyParameters).h, pair.private as FalconPrivateKeyParameters)
    }

    fun sign(keys: Keys, message: ByteArray): ByteArray {
        val s = FalconSigner()
        s.init(true, org.bouncycastle.crypto.params.ParametersWithRandom(keys.private, SecureRandom()))
        return s.generateSignature(message)
    }

    fun verify(publicKey: ByteArray, message: ByteArray, signature: ByteArray): Boolean = try {
        val s = FalconSigner()
        s.init(false, FalconPublicKeyParameters(PARAMS, publicKey))
        s.verifySignature(message, signature)
    } catch (e: Exception) {
        false
    }
}
