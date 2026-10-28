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
        for (pos in listOf(30L, 100L, 640L)) { // salt, body, mac
            val t = MemoryRandomAccess(raw.size)
            val all = ByteArray(raw.size.toInt()).also { raw.read(0, it, 0, it.size) }
            t.write(0, all, 0, all.size)
            val last = Volume.totalFileSize(dataSize) - 4096
            for (base in listOf(0L, last)) { // damage both copies
                val one = ByteArray(1).also { t.read(base + pos, it, 0, 1) }
                t.write(base + pos, byteArrayOf((one[0] + 1).toByte()), 0, 1)
            }
            assertFailsWith<WrongPasswordException>("pos=$pos") { Volume.open(t, cred("pw")) }
        }
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
