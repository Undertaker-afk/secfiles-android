package app.secfiles.core

import kotlin.test.*

class CryptoTest {
    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }

    @Test
    fun aesXtsMatchesReferenceImplementation() {
        // Reference output produced independently with Python `cryptography` (OpenSSL) AES-256-XTS.
        val k1 = ByteArray(32) { it.toByte() }
        val k2 = ByteArray(32) { (100 + it).toByte() }
        val pt = ByteArray(512) { ((it * 7 + 3) % 256).toByte() }
        Xts(CipherKind.AES, k1, k2).encrypt(0x1122334455L, pt, 0, pt.size)
        val h = hex(pt)
        assertEquals("2ccb5b564579a36717162c1e1a5a1c518e2bcf74feb2d2c7e970043603e71c25", h.take(64))
        assertEquals("52c5e2256a141a96b7ef90904d6d693f62d94d17e69365c6df5c98d64d0b45ca", h.takeLast(64))
    }

    @Test
    fun everyCipherRoundTripsAndDependsOnUnitNumber() {
        for (kind in CipherKind.values()) {
            val xts = Xts(kind, ByteArray(32) { 1 }, ByteArray(32) { 2 })
            val plain = ByteArray(4096) { (it * 31).toByte() }
            val a = plain.copyOf().also { xts.encrypt(5, it, 0, it.size) }
            val b = plain.copyOf().also { xts.encrypt(6, it, 0, it.size) }
            assertFalse(a.contentEquals(plain), kind.name)
            assertFalse(a.contentEquals(b), kind.name)
            xts.decrypt(5, a, 0, a.size)
            assertContentEquals(plain, a, kind.name)
        }
    }

    @Test
    fun identicalBlocksInOneSectorEncryptDifferently() {
        val xts = Xts(CipherKind.SERPENT, ByteArray(32) { 9 }, ByteArray(32) { 8 })
        val buf = ByteArray(64) // four identical zero blocks
        xts.encrypt(0, buf, 0, 64)
        val blocks = (0 until 4).map { hex(buf.copyOfRange(it * 16, it * 16 + 16)) }
        assertEquals(4, blocks.toSet().size)
    }

    @Test
    fun cascadeRoundTripAtOffsetAndAllLayersContribute() {
        val keys = ByteArray(Cascade.KEY_MATERIAL_BYTES) { (it * 13 + 1).toByte() }
        val c = Cascade(keys)
        val plain = ByteArray(4096 + 32) { (it % 251).toByte() }
        val buf = plain.copyOf()
        c.encrypt(42, buf, 16, 4096)
        assertContentEquals(plain.copyOfRange(0, 16), buf.copyOfRange(0, 16))
        assertContentEquals(plain.copyOfRange(4112, 4128), buf.copyOfRange(4112, 4128))
        c.decrypt(42, buf, 16, 4096)
        assertContentEquals(plain, buf)

        // Changing only the Serpent layer's key must change the output.
        val other = keys.copyOf().also { it[130] = (it[130] + 1).toByte() }
        val x = plain.copyOf().also { c.encrypt(1, it, 0, 4096) }
        val y = plain.copyOf().also { Cascade(other).encrypt(1, it, 0, 4096) }
        assertFalse(x.contentEquals(y))
    }
}
