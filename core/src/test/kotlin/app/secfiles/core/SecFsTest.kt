package app.secfiles.core

import kotlin.random.Random
import kotlin.test.*

class SecFsTest {
    private fun fs(blocks: Long = 2048) = SecFs.format(MemoryBlockDevice(blocks))

    private fun SecFs.put(dir: Int, name: String, data: ByteArray): Int {
        val id = createFile(dir, name)
        var off = 0
        while (off < data.size) {
            val n = minOf(65536, data.size - off)
            write(id, off.toLong(), data, off, n)
            off += n
        }
        return id
    }

    private fun SecFs.get(id: Int): ByteArray {
        val size = stat(id).size.toInt()
        val out = ByteArray(size)
        var off = 0
        while (off < size) {
            val n = read(id, off.toLong(), out, off, size - off)
            assertTrue(n > 0)
            off += n
        }
        return out
    }

    @Test
    fun createListReadWrite() {
        val f = fs()
        val data = Random(1).nextBytes(10_000)
        val id = f.put(f.rootId, "a.bin", data)
        assertContentEquals(data, f.get(id))
        assertEquals(listOf("a.bin"), f.list(f.rootId).map { it.name })
        assertEquals(10_000L, f.stat(id).size)
        assertEquals(id, f.lookup(f.rootId, "a.bin"))
        assertNull(f.lookup(f.rootId, "nope"))
    }

    @Test
    fun largeFileCrossesIndirectAndDoubleIndirectBlocks() {
        val f = fs(blocks = 4096) // 16 MiB
        val data = Random(2).nextBytes(6 * 1024 * 1024) // > 12 + 1024 blocks (4.2 MB)
        val id = f.put(f.rootId, "big", data)
        assertContentEquals(data, f.get(id))
        // overwrite a span in the middle, straddling block boundaries
        val patch = Random(3).nextBytes(20_000)
        f.write(id, 5_000_123, patch, 0, patch.size)
        System.arraycopy(patch, 0, data, 5_000_123, patch.size)
        assertContentEquals(data, f.get(id))
    }

    @Test
    fun deleteReturnsAllSpace() {
        val f = fs(blocks = 4096)
        // The root directory keeps its own table block once it has held an entry; measure after that.
        f.createDir(f.rootId, "warmup"); f.delete(f.rootId, "warmup")
        val before = f.freeBytes()
        val d = f.createDir(f.rootId, "dir")
        f.put(d, "x", Random(4).nextBytes(6 * 1024 * 1024))
        f.put(d, "y", Random(5).nextBytes(100))
        assertTrue(f.freeBytes() < before)
        f.delete(f.rootId, "dir")
        assertEquals(before, f.freeBytes())
        assertTrue(f.list(f.rootId).isEmpty())
    }

    @Test
    fun truncateShrinksFreesAndZeroFillsOnRegrow() {
        val f = fs()
        val id = f.put(f.rootId, "t", ByteArray(20_000) { 0x11 })
        val used = f.freeBytes()
        f.truncate(id, 5_000)
        assertTrue(f.freeBytes() > used)
        f.truncate(id, 12_000) // grow: bytes past the old EOF must read as zero, not stale data
        val got = f.get(id)
        assertEquals(12_000, got.size)
        assertTrue(got.copyOfRange(0, 5_000).all { it == 0x11.toByte() })
        assertTrue(got.copyOfRange(5_000, 12_000).all { it == 0.toByte() })
    }

    @Test
    fun sparseWriteLeavesZeroHole() {
        val f = fs()
        val id = f.createFile(f.rootId, "sparse")
        f.write(id, 100_000, byteArrayOf(1, 2, 3), 0, 3)
        val got = f.get(id)
        assertEquals(100_003, got.size)
        assertTrue(got.copyOfRange(0, 100_000).all { it == 0.toByte() })
        assertEquals(3, got[100_002].toInt())
    }

    @Test
    fun renameMoveAndFolderCycleProtection() {
        val f = fs()
        val a = f.createDir(f.rootId, "a")
        val b = f.createDir(a, "b")
        val file = f.put(b, "f.txt", "hello".toByteArray())
        f.rename(b, "f.txt", "g.txt")
        assertEquals(file, f.lookup(b, "g.txt"))
        f.move(b, "g.txt", f.rootId, "moved.txt")
        assertEquals(file, f.lookup(f.rootId, "moved.txt"))
        assertNull(f.lookup(b, "g.txt"))
        assertEquals(f.rootId, f.parentOf(f.rootId))
        assertEquals("moved.txt", f.nameOf(file))
        assertEquals(f.rootId, f.parentOf(file))
        assertEquals(a, f.parentOf(b))
        assertNull(f.nameOf(f.rootId))
        assertFailsWith<FsException> { f.move(f.rootId, "a", b, "a") } // folder into its own child
        assertFailsWith<AlreadyExistsException> { f.createDir(f.rootId, "a") }
        assertFailsWith<AlreadyExistsException> { f.rename(f.rootId, "moved.txt", "a") }
    }

    @Test
    fun invalidNamesAreRejected() {
        val f = fs()
        for (n in listOf("", ".", "..", "a/b", "x".repeat(256), "nul\u0000")) {
            assertFailsWith<FsException>(n) { f.createFile(f.rootId, n) }
        }
        f.createFile(f.rootId, "ünïcødé 文件 📁.txt")
        assertEquals("ünïcødé 文件 📁.txt", f.list(f.rootId).single().name)
    }

    @Test
    fun fillingTheVolumeFailsCleanlyAndRecovers() {
        val f = fs(blocks = 512) // 2 MiB
        val id = f.createFile(f.rootId, "fill")
        val chunk = ByteArray(65536) { 1 }
        var off = 0L
        assertFailsWith<VolumeFullException> {
            while (true) {
                f.write(id, off, chunk, 0, chunk.size); off += chunk.size
            }
        }
        // size reflects only what was actually written, and the file is still readable
        val size = f.stat(id).size
        assertTrue(size in 1 until 2L * 1024 * 1024)
        f.delete(f.rootId, "fill")
        assertTrue(f.freeBytes() > size)
        f.put(f.rootId, "after", ByteArray(1000))
    }

    @Test
    fun persistsAcrossReopenOnTheSameDevice() {
        val dev = MemoryBlockDevice(2048)
        val f = SecFs.format(dev)
        val d = f.createDir(f.rootId, "docs")
        val data = Random(6).nextBytes(50_000)
        f.put(d, "r.bin", data)
        f.flush()

        val g = SecFs.open(dev)
        val id = g.lookup(g.lookup(g.rootId, "docs")!!, "r.bin")!!
        assertContentEquals(data, g.get(id))
        assertEquals(f.freeBytes(), g.freeBytes())
        // allocation state was restored, so a new file does not clobber the old one
        g.put(g.rootId, "new", Random(7).nextBytes(50_000))
        assertContentEquals(data, g.get(id))
    }

    @Test
    fun manyFilesInOneDirectory() {
        val f = fs(blocks = 4096)
        repeat(500) { f.put(f.rootId, "file-$it", "n$it".toByteArray()) }
        assertEquals(500, f.list(f.rootId).size)
        assertEquals("n321", String(f.get(f.lookup(f.rootId, "file-321")!!)))
        repeat(500) { if (it % 2 == 0) f.delete(f.rootId, "file-$it") }
        assertEquals(250, f.list(f.rootId).size)
    }

    @Test
    fun endToEndThroughEncryptedVolume() {
        val kdf = KdfParams(64, 1, 1)
        val size = 4L * 1024 * 1024
        val raw = MemoryRandomAccess(Volume.totalFileSize(size))
        val v = Volume.create(raw, size, Credentials.ofPassword("pw".toCharArray()), kdf)
        val fs = SecFs.format(CachedBlockDevice(v))
        val secret = "TOP SECRET PLAINTEXT MARKER".toByteArray()
        val id = fs.put(fs.rootId, "s.txt", secret.copyOf(secret.size))
        assertContentEquals(secret, fs.get(id))
        fs.flush(); v.close()

        // The marker must not appear anywhere in the raw container.
        val all = ByteArray(raw.size.toInt()).also { raw.read(0, it, 0, it.size) }
        assertEquals(-1, String(all, Charsets.ISO_8859_1).indexOf(String(secret, Charsets.ISO_8859_1)))

        val v2 = Volume.open(raw, Credentials.ofPassword("pw".toCharArray()))
        val fs2 = SecFs.open(CachedBlockDevice(v2))
        assertContentEquals(secret, fs2.get(fs2.lookup(fs2.rootId, "s.txt")!!))
    }
}
