package app.secfiles.core

/**
 * Three XTS layers stacked: AES-256, then Twofish-256, then Serpent-256 on encryption (reverse on
 * decryption). Each layer has its own independent 256-bit data key and 256-bit tweak key, so a
 * break of any one or two ciphers still leaves the third protecting the data.
 *
 * Key material layout, 64 bytes per layer: dataKey(32) || tweakKey(32), in the order of [ORDER].
 */
class Cascade(keyMaterial: ByteArray) {
    private val layers: List<Xts>

    init {
        require(keyMaterial.size == KEY_MATERIAL_BYTES) { "cascade needs $KEY_MATERIAL_BYTES key bytes" }
        layers = ORDER.mapIndexed { i, kind ->
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
        val ORDER = listOf(CipherKind.AES, CipherKind.TWOFISH, CipherKind.SERPENT)
        val KEY_MATERIAL_BYTES = ORDER.size * 64
    }
}
