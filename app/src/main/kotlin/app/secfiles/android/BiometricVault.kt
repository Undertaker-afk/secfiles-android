package app.secfiles.android

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Optional biometric unlock. The volume's secret is wrapped with an AES-256-GCM key that lives in the
 * Android Keystore (StrongBox when the device has it). The key can only be used after a fresh
 * Class 3 / "strong" biometric match for that single operation, and is destroyed by Android if the
 * set of enrolled biometrics changes. The wrapped blob is useless without the device's secure hardware.
 */
object BiometricVault {
    private const val PROVIDER = "AndroidKeyStore"
    private const val TRANSFORM = "AES/GCM/NoPadding"
    private const val AUTH = BiometricManager.Authenticators.BIOMETRIC_STRONG

    class Wrapped(val ciphertext: ByteArray, val iv: ByteArray)

    class Invalidated : Exception("Biometrics changed on this device. Unlock with your password and re-enable biometric unlock.")

    fun isAvailable(context: Context): Boolean =
        BiometricManager.from(context).canAuthenticate(AUTH) == BiometricManager.BIOMETRIC_SUCCESS

    private fun alias(volumeId: String) = "secfiles.vol.$volumeId"

    private fun keyStore() = KeyStore.getInstance(PROVIDER).apply { load(null) }

    fun delete(volumeId: String) {
        runCatching { keyStore().deleteEntry(alias(volumeId)) }
    }

    private fun createKey(context: Context, volumeId: String): SecretKey {
        fun spec(strongBox: Boolean): KeyGenParameterSpec {
            val b = KeyGenParameterSpec.Builder(alias(volumeId), KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setUserAuthenticationRequired(true)
                .setInvalidatedByBiometricEnrollment(true)
            if (Build.VERSION.SDK_INT >= 30) {
                b.setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG)
            } else {
                @Suppress("DEPRECATION")
                b.setUserAuthenticationValidityDurationSeconds(-1)
            }
            if (strongBox &&
                context.packageManager.hasSystemFeature(PackageManager.FEATURE_STRONGBOX_KEYSTORE)
            ) b.setIsStrongBoxBacked(true)
            return b.build()
        }

        fun gen(strongBox: Boolean): SecretKey {
            val g = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER)
            g.init(spec(strongBox))
            return g.generateKey()
        }
        return try {
            gen(strongBox = true)
        } catch (e: StrongBoxUnavailableException) {
            gen(strongBox = false)
        }
    }

    private fun prompt(
        activity: FragmentActivity,
        title: String,
        subtitle: String,
        cipher: Cipher,
        onAuthed: (Cipher) -> Unit,
        onError: (Throwable) -> Unit,
    ) {
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .setSubtitle(subtitle)
            .setNegativeButtonText("Use password")
            .setAllowedAuthenticators(AUTH)
            .setConfirmationRequired(false)
            .build()
        val p = BiometricPrompt(activity, ContextCompat.getMainExecutor(activity), object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                val c = result.cryptoObject?.cipher
                if (c == null) onError(IllegalStateException("No cipher returned")) else {
                    try { onAuthed(c) } catch (t: Throwable) { onError(t) }
                }
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                onError(CancelledAuth(errString.toString(), errorCode))
            }
        })
        p.authenticate(info, BiometricPrompt.CryptoObject(cipher))
    }

    class CancelledAuth(message: String, val code: Int) : Exception(message) {
        val userCancelled get() =
            code == BiometricPrompt.ERROR_USER_CANCELED || code == BiometricPrompt.ERROR_NEGATIVE_BUTTON ||
                code == BiometricPrompt.ERROR_CANCELED
    }

    /** Creates a fresh key for [volumeId] and wraps [secret] under it after a biometric check. */
    fun enroll(activity: FragmentActivity, volumeId: String, volumeName: String, secret: ByteArray, onResult: (Result<Wrapped>) -> Unit) {
        val cipher = try {
            delete(volumeId)
            val key = createKey(activity, volumeId)
            Cipher.getInstance(TRANSFORM).also { it.init(Cipher.ENCRYPT_MODE, key) }
        } catch (t: Throwable) {
            onResult(Result.failure(t)); return
        }
        prompt(activity, "Enable biometric unlock", volumeName, cipher, onAuthed = { c ->
            c.updateAAD(volumeId.toByteArray())
            val ct = c.doFinal(secret)
            onResult(Result.success(Wrapped(ct, c.iv)))
        }, onError = { onResult(Result.failure(it)) })
    }

    /** Releases the secret for [volumeId] after a biometric check. The caller must wipe the result. */
    fun unwrap(activity: FragmentActivity, volumeId: String, volumeName: String, wrapped: Wrapped, onResult: (Result<ByteArray>) -> Unit) {
        val cipher = try {
            val key = keyStore().getKey(alias(volumeId), null) as? SecretKey ?: throw Invalidated()
            Cipher.getInstance(TRANSFORM).also { it.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, wrapped.iv)) }
        } catch (e: KeyPermanentlyInvalidatedException) {
            delete(volumeId)
            onResult(Result.failure(Invalidated())); return
        } catch (t: Throwable) {
            onResult(Result.failure(t)); return
        }
        prompt(activity, "Unlock $volumeName", "Confirm your identity", cipher, onAuthed = { c ->
            c.updateAAD(volumeId.toByteArray())
            onResult(Result.success(c.doFinal(wrapped.ciphertext)))
        }, onError = { onResult(Result.failure(it)) })
    }
}
