package app.secfiles.core

/**
 * A stack of XTS layers. Encryption applies the layers first to last, decryption last to first. Each layer
 * has its own independent 256-bit data key and 256-bit tweak key, so a break of any single cipher still
 * leaves the others protecting the data.
 *
 * Key material: 64 bytes per layer, dataKey(32) || tweakKey(32), in the order of [kinds].
 */
class Cascade(val kinds: List<CipherKind>, keyMaterial: ByteArray) {
    private val layers: List<Xts>

    init {
        require(kinds.size in 1..MAX_LAYERS) { "cascade depth must be 1..$MAX_LAYERS" }
        require(kinds.toSet().size == kinds.size) { "a cipher may appear only once in a cascade" }
        require(keyMaterial.size == keyBytes(kinds.size)) { "cascade needs ${keyBytes(kinds.size)} key bytes" }
        layers = kinds.mapIndexed { i, kind ->
            val base = i * 64
            Xts(kind, keyMaterial.copyOfRange(base, base + 32), keyMaterial.copyOfRange(base + 32, base + 64))
        }
    }

    fun encrypt(unit: Long, buf: ByteArray, off: Int, len: Int) {
        for (l in layers) l.encrypt(unit, buf, off, len)
    }

    fun decrypt(unit: Long, buf: ByteArray, off: Int, len: Int) {
        for (i in layers.indices.reversed()) layers[i].decrypt(unit, buf, off, len)
    }

    companion object {
        const val MAX_LAYERS = 5
        fun keyBytes(depth: Int) = depth * 64

        /** Fixed, public cascade used only to encrypt container headers. */
        val HEADER_KINDS = listOf(
            CipherKind.AES, CipherKind.TWOFISH, CipherKind.SERPENT, CipherKind.CAMELLIA, CipherKind.KUZNYECHIK,
        )
    }
}
