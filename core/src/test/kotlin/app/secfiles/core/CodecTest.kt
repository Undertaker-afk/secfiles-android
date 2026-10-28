package app.secfiles.core

import kotlin.random.Random
import kotlin.test.*

class CodecTest {
    private fun resource(name: String) = javaClass.getResourceAsStream("/$name")!!.readBytes()

    private val samples: Map<String, ByteArray> by lazy {
        val r = Random(7)
        mapOf(
            "empty-ish" to ByteArray(1) { 5 },
            "tiny" to "hello".toByteArray(),
            "zeros" to ByteArray(65536),
            "text" to "the quick brown fox jumps over the lazy dog. ".repeat(1500).toByteArray().copyOf(65536),
            "random" to r.nextBytes(65536),
            "mixed" to ByteArray(30000) { if (it % 100 < 60) 'a'.code.toByte() else r.nextInt().toByte() },
            "odd-size" to ByteArray(4097) { (it % 13).toByte() },
        )
    }

    @Test
    fun everyEngineRoundTripsEverySample() {
        for (c in Compression.values()) {
            val codec = Codec.of(c)
            for ((name, data) in samples) {
                val z = codec.compress(data, 0, data.size)
                val out = ByteArray(65536)
                codec.decompress(z, 0, z.size, out, data.size)
                assertContentEquals(data, out.copyOf(data.size), "$c / $name")
            }
        }
    }

    @Test
    fun decodersNeverWritePastTheRequestedLength() {
        // Regression: the Snappy library overran its output by a few bytes, which broke zero-padding.
        for (c in Compression.values()) {
            val codec = Codec.of(c)
            for ((name, data) in samples) {
                for (cut in listOf(data.size, data.size / 2 + 1, minOf(data.size, 4464))) {
                    val z = codec.compress(data, 0, cut)
                    val out = ByteArray(cut + 64) { 0x7E }
                    codec.decompress(z, 0, z.size, out, cut)
                    assertContentEquals(data.copyOf(cut), out.copyOf(cut), "$c / $name / $cut")
                    assertTrue((cut until cut + 64).all { out[it] == 0x7E.toByte() }, "$c / $name / $cut wrote past the end")
                }
            }
        }
    }

    @Test
    fun compressibleDataActuallyShrinks() {
        for (c in Compression.values()) {
            val z = Codec.of(c).compress(samples["text"]!!, 0, 65536)
            assertTrue(z.size < 20000, "$c produced ${z.size} bytes for repetitive text")
        }
    }

    @Test
    fun hostileInputNeverCrashesAnyDecompressor() {
        val r = Random(99)
        for (c in Compression.values()) {
            val codec = Codec.of(c)
            val good = codec.compress(samples["text"]!!, 0, 65536)
            val inputs = mutableListOf<ByteArray>()
            repeat(40) { inputs += r.nextBytes(r.nextInt(1, 300)) }                    // pure noise
            repeat(40) { inputs += good.copyOf().also { g -> g[r.nextInt(g.size)] = r.nextInt().toByte() } } // bit rot
            repeat(10) { inputs += good.copyOf(r.nextInt(1, good.size)) }               // truncation
            for (bad in inputs) {
                try {
                    codec.decompress(bad, 0, bad.size, ByteArray(65536), 65536)
                } catch (e: CorruptVolumeException) {
                    // expected; anything else (AIOOBE, OOM, NPE...) fails the test
                }
            }
        }
    }

    @Test
    fun lz4DecodesStreamsProducedByTheReferenceImplementation() {
        for (name in listOf("text", "mixed")) {
            val expect = resource("lz4_$name.bin")
            val out = ByteArray(expect.size)
            val z = resource("lz4_$name.lz4")
            Lz4.decompress(z, 0, z.size, out, expect.size)
            assertContentEquals(expect, out, name)
        }
    }

    @Test
    fun lz4RejectsWrongOutputLengthAndBadOffsets() {
        val z = Codec.of(Compression.LZ4).compress(samples["text"]!!, 0, 65536)
        assertFailsWith<CorruptVolumeException> { Lz4.decompress(z, 0, z.size, ByteArray(70000), 65537) }
        assertFailsWith<CorruptVolumeException> { Lz4.decompress(z, 0, z.size, ByteArray(70000), 65535) }
        // token: 0 literals + match, offset 5 but nothing written yet
        assertFailsWith<CorruptVolumeException> { Lz4.decompress(byteArrayOf(0x00, 5, 0), 0, 3, ByteArray(100), 10) }
    }
}
