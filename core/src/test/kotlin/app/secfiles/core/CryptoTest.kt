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
    fun cascadeRoundTripsAtEveryDepthAndLayerOrderMatters() {
        for (depth in 1..Cascade.MAX_LAYERS) {
            val kinds = CipherKind.values().take(depth)
            val keys = ByteArray(Cascade.keyBytes(depth)) { (it * 13 + depth).toByte() }
            val c = Cascade(kinds, keys)
            val plain = ByteArray(4096 + 32) { (it % 251).toByte() }
            val buf = plain.copyOf()
            c.encrypt(42, buf, 16, 4096)
            assertContentEquals(plain.copyOfRange(0, 16), buf.copyOfRange(0, 16)) // untouched around the unit
            assertContentEquals(plain.copyOfRange(4112, 4128), buf.copyOfRange(4112, 4128))
            assertFalse(plain.copyOfRange(16, 4112).contentEquals(buf.copyOfRange(16, 4112)))
            c.decrypt(42, buf, 16, 4096)
            assertContentEquals(plain, buf, "depth $depth")
        }
        val keys = ByteArray(128) { (it + 1).toByte() }
        val a = ByteArray(4096).also { Cascade(listOf(CipherKind.AES, CipherKind.RC6), keys).encrypt(0, it, 0, 4096) }
        val b = ByteArray(4096).also { Cascade(listOf(CipherKind.RC6, CipherKind.AES), keys).encrypt(0, it, 0, 4096) }
        assertFalse(a.contentEquals(b))
    }

    @Test
    fun changingAnyOneLayerKeyChangesTheOutput() {
        val kinds = CipherKind.values().take(5)
        val keys = ByteArray(Cascade.keyBytes(5)) { (it * 7 + 1).toByte() }
        val base = ByteArray(4096).also { Cascade(kinds, keys).encrypt(1, it, 0, 4096) }
        for (layer in 0 until 5) {
            val k = keys.copyOf().also { it[layer * 64 + 40] = (it[layer * 64 + 40] + 1).toByte() } // a tweak-key byte
            val o = ByteArray(4096).also { Cascade(kinds, k).encrypt(1, it, 0, 4096) }
            assertFalse(base.contentEquals(o), "layer $layer")
        }
    }

    @Test
    fun cascadeRejectsDuplicateCiphersAndBadSizes() {
        assertFailsWith<IllegalArgumentException> { Cascade(listOf(CipherKind.AES, CipherKind.AES), ByteArray(128)) }
        assertFailsWith<IllegalArgumentException> { Cascade(listOf(CipherKind.AES), ByteArray(63)) }
        assertFailsWith<IllegalArgumentException> { Cascade(emptyList(), ByteArray(0)) }
    }

    @Test
    fun cipherIdsAreStableAndUnique() {
        assertEquals((0..7).toList(), CipherKind.values().map { it.id })
        CipherKind.values().forEach { assertEquals(it, CipherKind.byId(it.id)) }
    }

    @Test
    fun structureIsDeterministicDistinctAndDependsOnSeed() {
        val seed = ByteArray(32) { it.toByte() }
        for (depth in 1..5) {
            val a = Structure.derive(seed, depth, true)
            assertEquals(depth, a.ciphers.size)
            assertEquals(depth, a.ciphers.toSet().size)
            assertEquals(a.ciphers, Structure.derive(seed, depth, true).ciphers)
        }
        assertNull(Structure.derive(seed, 3, false).compression)
        // Different seeds should explore the pool: over many seeds we must see every cipher and every engine.
        val seen = mutableSetOf<CipherKind>()
        val engines = mutableSetOf<Compression?>()
        val orders = mutableSetOf<List<CipherKind>>()
        for (i in 0 until 400) {
            val st = Structure.derive(ByteArray(32) { (it * 31 + i * 7 + i / 3).toByte() }, 5, true)
            seen += st.ciphers; engines += st.compression; orders += st.ciphers
        }
        assertEquals(CipherKind.values().toSet(), seen)
        assertEquals(Compression.values().toSet(), engines.filterNotNull().toSet())
        assertTrue(orders.size > 100)
    }

    @Test
    fun falconSignsVerifiesAndRejectsTampering() {
        val seed = ByteArray(32) { (it + 9).toByte() }
        val keys = Integrity.keysFromSeed(seed)
        assertContentEquals(keys.publicKey, Integrity.keysFromSeed(seed).publicKey) // deterministic from the seed
        assertFalse(keys.publicKey.contentEquals(Integrity.keysFromSeed(ByteArray(32)).publicKey))
        val msg = ByteArray(1500) { it.toByte() }
        val sig = Integrity.sign(keys, msg)
        assertTrue(sig.size in 1..1022, "signature must fit its header slot, was ${sig.size}")
        assertTrue(keys.publicKey.size <= 1022)
        assertTrue(Integrity.verify(keys.publicKey, msg, sig))
        assertFalse(Integrity.verify(keys.publicKey, msg.copyOf().also { it[700] = (it[700] + 1).toByte() }, sig))
        assertFalse(Integrity.verify(keys.publicKey, msg, sig.copyOf().also { it[10] = (it[10] + 1).toByte() }))
        assertFalse(Integrity.verify(Integrity.keysFromSeed(ByteArray(32)).publicKey, msg, sig))
        assertFalse(Integrity.verify(ByteArray(5), msg, sig)) // garbage key must not throw
    }
}
