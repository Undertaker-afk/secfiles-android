package app.secfiles.core

import kotlin.test.*

class VolumeTest {
    private val fast = KdfParams(memoryKiB = 64, iterations = 1, parallelism = 1)
    private val dataSize = 2L * 1024 * 1024

    private fun cred(pw: String, vararg keyfiles: ByteArray) = Credentials.ofPassword(pw.toCharArray(), keyfiles.toList())

    private fun newRaw() = MemoryRandomAccess(Volume.totalFileSize(dataSize))

    @Test
    fun createThenOpenRoundTripsData() {
        val raw = newRaw()
        val v = Volume.create(raw, dataSize, cred("correct horse"), fast)
        val block = ByteArray(4096) { (it % 200).toByte() }
        v.write(3, block)
        v.close()

        val v2 = Volume.open(raw, cred("correct horse"))
        assertEquals(dataSize, v2.dataSize)
        val got = ByteArray(4096)
        v2.read(3, got)
        assertContentEquals(block, got)
    }

    @Test
    fun ciphertextOnDiskDiffersFromPlaintext() {
        val raw = newRaw()
        val v = Volume.create(raw, dataSize, cred("pw"), fast)
        val block = ByteArray(4096) // all zero
        v.write(0, block); v.write(1, block)
        val a = ByteArray(4096).also { raw.read(Volume.HEADER_SIZE, it, 0, 4096) }
        val b = ByteArray(4096).also { raw.read(Volume.HEADER_SIZE + 4096, it, 0, 4096) }
        assertFalse(a.all { it == 0.toByte() })
        assertFalse(a.contentEquals(b)) // same plaintext, different sector => different ciphertext
    }

    @Test
    fun wrongPasswordIsRejected() {
        val raw = newRaw()
        Volume.create(raw, dataSize, cred("right"), fast).close()
        assertFailsWith<WrongPasswordException> { Volume.open(raw, cred("wrong")) }
        assertFailsWith<WrongPasswordException> { Volume.open(raw, cred("right", byteArrayOf(1, 2, 3))) }
    }

    @Test
    fun keyfilesAreRequiredAndOrderIndependent() {
        val raw = newRaw()
        val k1 = ByteArray(100) { it.toByte() }
        val k2 = ByteArray(50) { (it * 3).toByte() }
        Volume.create(raw, dataSize, cred("pw", k1, k2), fast).close()
        Volume.open(raw, cred("pw", k2, k1)).close()
        assertFailsWith<WrongPasswordException> { Volume.open(raw, cred("pw", k1)) }
        assertFailsWith<WrongPasswordException> { Volume.open(raw, cred("pw")) }
    }

    @Test
    fun keyfileOnlyVolumeWorks() {
        val raw = newRaw()
        val k = ByteArray(64) { 7 }
        Volume.create(raw, dataSize, Credentials(ByteArray(0), listOf(k)), fast).close()
        Volume.open(raw, Credentials(ByteArray(0), listOf(k))).close()
    }

    @Test
    fun backupHeaderRecoversFromCorruptPrimary() {
        val raw = newRaw()
        Volume.create(raw, dataSize, cred("pw"), fast).close()
        raw.write(0, ByteArray(4096), 0, 4096) // wipe the primary header
        Volume.open(raw, cred("pw")).close()
    }

    @Test
    fun tamperedHeaderFailsAuthentication() {
        val raw = newRaw()
        Volume.create(raw, dataSize, cred("pw"), fast).close()
        for (pos in listOf(12L, 30L, 100L, 1200L, 2300L)) { // kdf params, salt, falcon key, signature, body
            val t = MemoryRandomAccess(raw.size)
            val all = ByteArray(raw.size.toInt()).also { raw.read(0, it, 0, it.size) }
            t.write(0, all, 0, all.size)
            val last = Volume.totalFileSize(dataSize) - 4096
            for (base in listOf(0L, last)) { // damage both copies
                val one = ByteArray(1).also { t.read(base + pos, it, 0, 1) }
                t.write(base + pos, byteArrayOf((one[0] + 1).toByte()), 0, 1)
            }
            assertFailsWith<HeaderTamperedException>("pos=$pos") { Volume.open(t, cred("pw")) }
        }
    }

    @Test
    fun bitFlipInPrimaryBodyFallsBackToBackup() {
        val raw = newRaw()
        Volume.create(raw, dataSize, cred("pw"), fast).close()
        val b = ByteArray(1).also { raw.read(200, it, 0, 1) }
        raw.write(200, byteArrayOf((b[0] + 1).toByte()), 0, 1)
        Volume.open(raw, cred("pw")).close()
    }

    @Test
    fun wrongPasswordOnIntactHeaderDoesNotTryBackup() {
        val raw = newRaw()
        Volume.create(raw, dataSize, cred("pw"), fast).close()
        // Make the backup a different, valid container header protected by another password: if the
        // opener (wrongly) fell through to it, the "other" password would succeed against the primary.
        val other = MemoryRandomAccess(Volume.totalFileSize(dataSize))
        Volume.create(other, dataSize, cred("other"), fast).close()
        val backup = ByteArray(4096).also { other.read(other.size - 4096, it, 0, 4096) }
        raw.write(raw.size - 4096, backup, 0, 4096)
        assertFailsWith<WrongPasswordException> { Volume.open(raw, cred("other")) }
        Volume.open(raw, cred("pw")).close()
    }

    @Test
    fun hostileKdfParametersAreRefused() {
        val raw = newRaw()
        Volume.create(raw, dataSize, cred("pw"), fast).close()
        // Claim 4 TiB of Argon2 memory in the primary header; must be ignored, not allocated.
        val evil = byteArrayOf(0x7f, 0, 0, 0)
        raw.write(8, evil, 0, 4)
        Volume.open(raw, cred("pw")).close() // falls back to the intact backup header
    }

    @Test
    fun changePasswordKeepsDataReadable() {
        val raw = newRaw()
        val v = Volume.create(raw, dataSize, cred("old"), fast)
        val block = ByteArray(4096) { 0x5a }
        v.write(7, block)
        v.changeCredentials(cred("new"), fast)
        v.close()
        assertFailsWith<WrongPasswordException> { Volume.open(raw, cred("old")) }
        val v2 = Volume.open(raw, cred("new"))
        val got = ByteArray(4096).also { v2.read(7, it) }
        assertContentEquals(block, got)
    }

    @Test
    fun everyDepthCreatesOpensAndPersistsItsStructure() {
        for (layers in 1..5) {
            val raw = newRaw()
            val v = Volume.create(raw, dataSize, cred("pw"), fast, layers = layers, compress = layers % 2 == 0)
            val block = ByteArray(4096) { (it * layers).toByte() }
            v.write(9, block)
            val kinds = v.structure.ciphers
            val comp = v.structure.compression
            assertEquals(layers, v.layers)
            assertEquals(layers % 2 == 0, comp != null)
            v.close()
            val v2 = Volume.open(raw, cred("pw"))
            assertEquals(kinds, v2.structure.ciphers, "cipher order is restored from the header")
            assertEquals(comp, v2.structure.compression)
            assertContentEquals(block, ByteArray(4096).also { v2.read(9, it) })
        }
    }

    @Test
    fun structureVariesBetweenContainersButNotWithPasswordChange() {
        val seen = mutableSetOf<List<CipherKind>>()
        repeat(6) { seen += Volume.create(newRaw(), dataSize, cred("same password"), fast, layers = 5).structure.ciphers }
        assertTrue(seen.size > 1, "per-container salt should vary the cipher order")

        val raw = newRaw()
        val v = Volume.create(raw, dataSize, cred("old"), fast, layers = 4, compress = true)
        val before = v.structure.ciphers to v.structure.compression
        val block = ByteArray(4096) { 0x33 }
        v.write(2, block)
        v.changeCredentials(cred("new"), fast)
        v.close()
        val v2 = Volume.open(raw, cred("new"))
        assertEquals(before, v2.structure.ciphers to v2.structure.compression)
        assertContentEquals(block, ByteArray(4096).also { v2.read(2, it) })
    }

    @Test
    fun damagedPrimaryUsesBackupButBothDamagedIsReportedAsTampering() {
        val raw = newRaw()
        Volume.create(raw, dataSize, cred("pw"), fast, layers = 2).close()
        raw.write(1500, byteArrayOf(1, 2, 3), 0, 3) // inside the Falcon public key of the primary
        Volume.open(raw, cred("pw")).close()
        raw.write(raw.size - 4096 + 1500, byteArrayOf(1, 2, 3), 0, 3)
        assertFailsWith<HeaderTamperedException> { Volume.open(raw, cred("pw")) }
    }

    @Test
    fun layersOutOfRangeAreRejected() {
        assertFailsWith<IllegalArgumentException> { Volume.create(newRaw(), dataSize, cred("pw"), fast, layers = 0) }
        assertFailsWith<IllegalArgumentException> { Volume.create(newRaw(), dataSize, cred("pw"), fast, layers = 6) }
    }

    @Test
    fun closedVolumeRefusesIo() {
        val v = Volume.create(newRaw(), dataSize, cred("pw"), fast)
        v.close()
        assertFailsWith<java.io.IOException> { v.read(0, ByteArray(4096)) }
    }

    @Test
    fun exportedSecretReopensVolume() {
        val raw = newRaw()
        val c = cred("pw", byteArrayOf(9, 9, 9))
        Volume.create(raw, dataSize, c, fast).close()
        val restored = Credentials.fromSecret(c.exportSecret())
        Volume.open(raw, restored).close()
    }
}
