package app.secfiles.core

/** Fixed-size block storage that the filesystem sits on. */
interface BlockDevice {
    val blockSize: Int
    val blockCount: Long
    fun read(block: Long, dst: ByteArray, off: Int = 0)
    fun write(block: Long, src: ByteArray, off: Int = 0)
    fun flush()
}

/** Write-through LRU cache of plaintext blocks. */
class CachedBlockDevice(private val inner: BlockDevice, private val capacity: Int = 512) : BlockDevice {
    override val blockSize get() = inner.blockSize
    override val blockCount get() = inner.blockCount

    private val cache = object : LinkedHashMap<Long, ByteArray>(capacity * 2, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, ByteArray>): Boolean {
            if (size > capacity) {
                eldest.value.fill(0)
                return true
            }
            return false
        }
    }

    @Synchronized
    override fun read(block: Long, dst: ByteArray, off: Int) {
        val hit = cache[block]
        if (hit != null) {
            System.arraycopy(hit, 0, dst, off, blockSize)
            return
        }
        inner.read(block, dst, off)
        cache[block] = dst.copyOfRange(off, off + blockSize)
    }

    @Synchronized
    override fun write(block: Long, src: ByteArray, off: Int) {
        inner.write(block, src, off)
        val existing = cache[block]
        if (existing != null) System.arraycopy(src, off, existing, 0, blockSize)
        else cache[block] = src.copyOfRange(off, off + blockSize)
    }

    override fun flush() = inner.flush()

    @Synchronized
    fun wipe() {
        cache.values.forEach { it.fill(0) }
        cache.clear()
    }
}

class MemoryBlockDevice(override val blockCount: Long, override val blockSize: Int = Volume.SECTOR_SIZE) : BlockDevice {
    private val data = Array(blockCount.toInt()) { ByteArray(blockSize) }
    override fun read(block: Long, dst: ByteArray, off: Int) = System.arraycopy(data[block.toInt()], 0, dst, off, blockSize)
    override fun write(block: Long, src: ByteArray, off: Int) = System.arraycopy(src, off, data[block.toInt()], 0, blockSize)
    override fun flush() {}
}
