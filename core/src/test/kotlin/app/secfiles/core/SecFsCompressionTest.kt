package app.secfiles.core

import kotlin.random.Random
import kotlin.test.*

class SecFsCompressionTest {
    private fun fs(c: Compression?, blocks: Long = 2048) = SecFs.format(MemoryBlockDevice(blocks), c)

    private fun SecFs.readAll(id: Int): ByteArray {
        val out = ByteArray(stat(id).size.toInt())
        var off = 0
        while (off < out.size) {
            val n = read(id, off.toLong(), out, off, minOf(out.size - off, 30_000))
            assertTrue(n > 0, "short read at $off of ${out.size}")
            off += n
        }
        return out
    }

    private fun text(n: Int) = "lorem ipsum dolor sit amet, consectetur adipiscing elit. ".repeat(n / 50 + 1).toByteArray().copyOf(n)

    @Test
    fun compressedFileRoundTripsAndUsesLessSpace() {
        for (c in Compression.values()) {
            val plain = fs(null)
            val comp = fs(c)
            val data = text(400_000)
            for (f in listOf(plain, comp)) {
                val id = f.createFile(f.rootId, "t.txt")
                f.write(id, 0, data, 0, data.size)
                f.flush()
                assertContentEquals(data, f.readAll(id), "$c")
            }
            val savedBytes = plain.totalBytes() - plain.freeBytes() - (comp.totalBytes() - comp.freeBytes())
            assertTrue(savedBytes > 200_000, "$c saved only $savedBytes bytes on repetitive text")
            assertEquals(c, comp.compression)
        }
    }

    @Test
    fun incompressibleDataCostsNoMoreThanRaw() {
        for (c in Compression.values()) {
            val plain = fs(null)
            val comp = fs(c)
            val data = Random(3).nextBytes(300_000)
            val used = listOf(plain, comp).map { f ->
                val id = f.createFile(f.rootId, "r.bin")
                f.write(id, 0, data, 0, data.size)
                f.flush()
                assertContentEquals(data, f.readAll(id), "$c")
                f.totalBytes() - f.freeBytes()
            }
            assertEquals(used[0], used[1], "$c must fall back to raw blocks for random data")
        }
    }

    @Test
    fun zeroChunksBecomeHolesAndSizeIsExact() {
        val f = fs(Compression.LZ4)
        val id = f.createFile(f.rootId, "z")
        val before = f.freeBytes()
        f.write(id, 0, ByteArray(300_000), 0, 300_000)
        f.flush()
        assertEquals(300_000L, f.stat(id).size)
        assertTrue(before - f.freeBytes() <= 4096 * 2, "all-zero data should not occupy blocks")
        assertTrue(f.readAll(id).all { it == 0.toByte() })
    }

    @Test
    fun sizeAndContentAreVisibleBeforeFlush() {
        val f = fs(Compression.DEFLATE)
        val id = f.createFile(f.rootId, "w")
        val data = text(10_000)
        f.write(id, 0, data, 0, 3000)
        f.write(id, 3000, data, 3000, 7000)
        assertEquals(10_000L, f.stat(id).size)
        assertEquals(10_000L, f.list(f.rootId).single().stat.size)
        assertContentEquals(data, f.readAll(id))
    }

    @Test
    fun deleteAndTruncateReturnAllSpace() {
        for (c in Compression.values()) {
            val f = fs(c, 4096)
            f.createDir(f.rootId, "warm"); f.delete(f.rootId, "warm")
            val before = f.freeBytes()
            val id = f.createFile(f.rootId, "a")
            val data = text(500_000)
            f.write(id, 0, data, 0, data.size)
            f.flush()
            f.truncate(id, 70_000)
            assertContentEquals(data.copyOf(70_000), f.readAll(id), "$c after shrink")
            f.truncate(id, 200_000)
            val grown = f.readAll(id)
            assertContentEquals(data.copyOf(70_000), grown.copyOf(70_000), "$c grown prefix")
            assertTrue(grown.copyOfRange(70_000, 200_000).all { it == 0.toByte() }, "$c grown tail must be zero")
            f.delete(f.rootId, "a")
            assertEquals(before, f.freeBytes(), "$c leaked space")
        }
    }

    @Test
    fun persistsAcrossReopen() {
        for (c in Compression.values()) {
            val dev = MemoryBlockDevice(2048)
            val f = SecFs.format(dev, c)
            val id = f.createFile(f.rootId, "p")
            val data = text(150_000) + Random(1).nextBytes(20_000)
            f.write(id, 0, data, 0, data.size)
            f.flush()
            val g = SecFs.open(dev)
            assertEquals(c, g.compression, "engine is remembered in the superblock")
            assertContentEquals(data, g.readAll(g.lookup(g.rootId, "p")!!), "$c")
            // files created after reopen keep being compressed
            val id2 = g.createFile(g.rootId, "q")
            g.write(id2, 0, data, 0, data.size)
            g.flush()
            assertContentEquals(data, g.readAll(id2))
        }
    }

    @Test
    fun directoriesStayReadableWhenCompressionIsOn() {
        val f = fs(Compression.LZMA, 4096)
        repeat(300) { f.createFile(f.rootId, "name-$it") }
        assertEquals(300, f.list(f.rootId).size)
        f.delete(f.rootId, "name-17")
        assertNull(f.lookup(f.rootId, "name-17"))
    }

    @Test
    fun tamperedCompressedChunkIsReportedNotCrashing() {
        val dev = MemoryBlockDevice(2048)
        val f = SecFs.format(dev, Compression.LZ4)
        val id = f.createFile(f.rootId, "x")
        val data = text(65536)
        f.write(id, 0, data, 0, data.size)
        f.flush()
        // Scribble over every data block; reading must fail cleanly (or return wrong bytes), never crash.
        val junk = Random(5).nextBytes(4096)
        for (b in 40L until 2048L) dev.write(b, junk, 0)
        val g = SecFs.open(dev)
        try {
            g.read(g.lookup(g.rootId, "x")!!, 0, ByteArray(65536), 0, 65536)
        } catch (e: java.io.IOException) {
            // CorruptVolumeException / FsException are both fine
        }
    }

    /** Random writes, truncates and reopen cycles checked against a plain byte-array model. */
    @Test
    fun randomOperationsMatchAModelOnEveryEngine() {
        for (c in listOf<Compression?>(null) + Compression.values()) {
            val dev = MemoryBlockDevice(2048)
            var f = SecFs.format(dev, c)
            val id = f.createFile(f.rootId, "m")
            var model = ByteArray(0)
            val rnd = Random(1234 + (c?.id ?: 9))
            fun content(n: Int): ByteArray = when (rnd.nextInt(4)) {
                0 -> ByteArray(n)                                  // zeros
                1 -> Random(rnd.nextInt()).nextBytes(n)            // incompressible
                else -> rnd.nextInt(0, 7).let { o -> text(n + 7).copyOfRange(o, o + n) }       // compressible
            }
            repeat(160) { step ->
                when (rnd.nextInt(10)) {
                    in 0..5 -> { // write at a random offset, sometimes past EOF, sometimes spanning chunks
                        val pos = if (rnd.nextInt(5) == 0) model.size + rnd.nextInt(0, 90_000) else rnd.nextInt(0, model.size + 1)
                        val len = rnd.nextInt(1, if (rnd.nextBoolean()) 5_000 else 150_000)
                        if (pos + len <= 700_000) {
                            val d = content(len)
                            f.write(id, pos.toLong(), d, 0, len)
                            if (model.size < pos + len) model = model.copyOf(pos + len)
                            System.arraycopy(d, 0, model, pos, len)
                        }
                    }
                    6, 7 -> { // truncate (shrink or grow)
                        val n = rnd.nextInt(0, 400_000)
                        f.truncate(id, n.toLong())
                        model = model.copyOf(n)
                    }
                    8 -> { // flush + reopen
                        f.flush()
                        f = SecFs.open(dev)
                    }
                    else -> { // sequential small appends, like a streaming writer
                        var pos = model.size
                        repeat(rnd.nextInt(1, 40)) {
                            val d = content(rnd.nextInt(1, 4096))
                            if (pos + d.size <= 700_000) {
                                f.write(id, pos.toLong(), d, 0, d.size)
                                model = model.copyOf(pos + d.size)
                                System.arraycopy(d, 0, model, pos, d.size)
                                pos += d.size
                            }
                        }
                    }
                }
                assertEquals(model.size.toLong(), f.stat(id).size, "$c step $step size")
                assertContentEquals(model, f.readAll(id), "$c step $step content")
            }
            f.flush()
            f = SecFs.open(dev)
            assertContentEquals(model, f.readAll(id), "$c after final reopen")
            f.delete(f.rootId, "m")
        }
    }

    @Test
    fun fullVolumeFailsCleanlyOnEveryEngine() {
        for (c in Compression.values()) {
            val f = fs(c, 300)
            val id = f.createFile(f.rootId, "fill")
            var pos = 0L
            val chunk = Random(1).nextBytes(65536)
            assertFailsWith<VolumeFullException>("$c") {
                while (true) { f.write(id, pos, chunk, 0, chunk.size); pos += chunk.size; f.flush() }
            }
            f.delete(f.rootId, "fill")
            val id2 = f.createFile(f.rootId, "after")
            f.write(id2, 0, text(20_000), 0, 20_000)
            f.flush()
            assertContentEquals(text(20_000), f.readAll(id2))
        }
    }
}
