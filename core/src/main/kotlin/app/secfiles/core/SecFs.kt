package app.secfiles.core

import java.io.IOException
import java.nio.ByteBuffer
import java.util.BitSet

class FsException(message: String) : IOException(message)
class NoSuchEntryException(message: String) : IOException(message)
class AlreadyExistsException(message: String) : IOException(message)
class VolumeFullException : IOException("Not enough free space in the volume")

data class Stat(val id: Int, val isDir: Boolean, val size: Long, val mtimeMs: Long)
data class DirEntry(val name: String, val stat: Stat)

/**
 * A small ext2-style filesystem that lives inside a [BlockDevice] (the decrypted view of a [Volume]).
 *
 *  block 0                superblock
 *  1 .. 1+nb              allocation bitmap (1 bit per block)
 *  .. +ni                 inode table, 32 inodes of 128 bytes per block
 *  rest                   data
 *
 * Files use 12 direct pointers, one indirect and one double-indirect block (max ~4 GiB per file); holes
 * read as zeros. Directories are files holding a serialised name table. There is no journal: allocations
 * are persisted before the inode that references them, frees after, so a crash can leak blocks but not
 * hand the same block to two files.
 *
 * All public operations are serialised on the instance lock.
 */
class SecFs private constructor(private val dev: BlockDevice) {
    private val bs = dev.blockSize
    private val ptrsPerBlock = bs / 4

    private var blockCount = 0L
    private var inodeCount = 0
    private var bitmapStart = 0L
    private var bitmapBlocks = 0
    private var inodeStart = 0L
    private var dataStart = 0L

    private lateinit var bitmap: ByteArray
    private val dirtyBitmap = BitSet()
    private lateinit var inodeUsed: BitSet
    private var freeBlockCount = 0L
    private var allocHint = 0L
    private var justAllocated = false

    /** Compression engine for newly created files, or null if the volume is uncompressed. */
    var compression: Compression? = null
        private set
    private var codec: Codec? = null

    private class Inode(var mode: Int = 0, var size: Long = 0, var mtime: Long = 0, var parent: Int = 0, var flags: Int = 0) {
        val ptr = IntArray(PTR_SLOTS)
    }

    // ---- public API -------------------------------------------------------------------------

    val rootId get() = ROOT

    @Synchronized
    fun totalBytes() = (blockCount - dataStart) * bs

    @Synchronized
    fun freeBytes() = freeBlockCount * bs

    @Synchronized
    fun stat(id: Int): Stat = toStat(id, readInode(id).also { requireUsed(id, it) })

    @Synchronized
    fun list(dirId: Int): List<DirEntry> =
        readDir(dirId).map { (name, id) -> DirEntry(name, toStat(id, readInode(id))) }

    /** Directory that contains [id] (the root is its own parent). */
    @Synchronized
    fun parentOf(id: Int): Int = if (id == ROOT) ROOT else readInode(id).parent

    /** File name of [id] within its parent; null for the root. */
    @Synchronized
    fun nameOf(id: Int): String? =
        if (id == ROOT) null else readDir(readInode(id).parent).firstOrNull { it.second == id }?.first

    @Synchronized
    fun lookup(dirId: Int, name: String): Int? = readDir(dirId).firstOrNull { it.first == name }?.second

    @Synchronized
    fun createFile(dirId: Int, name: String): Int = create(dirId, name, MODE_FILE)

    @Synchronized
    fun createDir(dirId: Int, name: String): Int = create(dirId, name, MODE_DIR)

    @Synchronized
    fun read(id: Int, pos: Long, dst: ByteArray, off: Int, len: Int): Int {
        val ino = readInode(id)
        if (ino.mode != MODE_FILE) throw FsException("Not a file")
        if (ino.flags and F_COMPRESSED != 0) return readCompressed(id, ino, pos, dst, off, len)
        if (pos >= ino.size || len == 0) return 0
        val total = minOf(len.toLong(), ino.size - pos).toInt()
        val tmp = ByteArray(bs)
        var done = 0
        while (done < total) {
            val p = pos + done
            val idx = p / bs
            val inBlock = (p % bs).toInt()
            val n = minOf(bs - inBlock, total - done)
            val blk = mapBlock(ino, idx, false)
            if (blk == 0L) java.util.Arrays.fill(dst, off + done, off + done + n, 0)
            else {
                dev.read(blk, tmp, 0)
                System.arraycopy(tmp, inBlock, dst, off + done, n)
            }
            done += n
        }
        tmp.fill(0)
        return total
    }

    @Synchronized
    fun write(id: Int, pos: Long, src: ByteArray, off: Int, len: Int) {
        val ino = readInode(id)
        if (ino.mode != MODE_FILE) throw FsException("Not a file")
        if (len == 0) return
        if (pos + len > MAX_FILE_BLOCKS * bs) throw FsException("File too large")
        if (ino.flags and F_COMPRESSED != 0) {
            writeCompressed(id, pos, src, off, len)
            return
        }
        val tmp = ByteArray(bs)
        var done = 0
        try {
            while (done < len) {
                val p = pos + done
                val idx = p / bs
                val inBlock = (p % bs).toInt()
                val n = minOf(bs - inBlock, len - done)
                val blk = mapBlock(ino, idx, true)
                if (n == bs) {
                    dev.write(blk, src, off + done)
                } else {
                    if (justAllocated) tmp.fill(0) else dev.read(blk, tmp, 0)
                    System.arraycopy(src, off + done, tmp, inBlock, n)
                    dev.write(blk, tmp, 0)
                }
                done += n
            }
        } finally {
            tmp.fill(0)
            // Persist whatever was written even if we ran out of space part-way.
            if (pos + done > ino.size) ino.size = pos + done
            ino.mtime = System.currentTimeMillis()
            flushBitmap()
            writeInode(id, ino)
        }
    }

    @Synchronized
    fun truncate(id: Int, newSize: Long) {
        val ino = readInode(id)
        if (ino.mode != MODE_FILE) throw FsException("Not a file")
        if (newSize > MAX_FILE_BLOCKS * bs) throw FsException("File too large")
        if (ino.flags and F_COMPRESSED != 0) {
            truncateCompressed(id, newSize)
            return
        }
        if (newSize < ino.size) shrink(ino, newSize)
        ino.size = newSize
        ino.mtime = System.currentTimeMillis()
        writeInode(id, ino)
        flushBitmap()
    }

    @Synchronized
    fun delete(dirId: Int, name: String) {
        val entries = readDir(dirId).toMutableList()
        val i = entries.indexOfFirst { it.first == name }
        if (i < 0) throw NoSuchEntryException(name)
        val id = entries[i].second
        entries.removeAt(i)
        writeDir(dirId, entries)
        destroy(id)
        flushBitmap()
    }

    @Synchronized
    fun rename(dirId: Int, oldName: String, newName: String) = move(dirId, oldName, dirId, newName)

    @Synchronized
    fun move(srcDir: Int, name: String, dstDir: Int, newName: String) {
        checkName(newName)
        val src = readDir(srcDir).toMutableList()
        val i = src.indexOfFirst { it.first == name }
        if (i < 0) throw NoSuchEntryException(name)
        val id = src[i].second
        if (srcDir == dstDir) {
            if (name == newName) return
            if (src.any { it.first == newName }) throw AlreadyExistsException(newName)
            src[i] = newName to id
            writeDir(srcDir, src)
            return
        }
        if (readInode(dstDir).mode != MODE_DIR) throw FsException("Destination is not a directory")
        if (readInode(id).mode == MODE_DIR && (id == dstDir || isInside(id, dstDir))) {
            throw FsException("Cannot move a folder into itself")
        }
        val dst = readDir(dstDir).toMutableList()
        if (dst.any { it.first == newName }) throw AlreadyExistsException(newName)
        dst.add(newName to id)
        writeDir(dstDir, dst)
        src.removeAt(i)
        writeDir(srcDir, src)
        writeInode(id, readInode(id).also { it.parent = dstDir })
    }

    @Synchronized
    fun flush() {
        commit()
        flushBitmap()
        dev.flush()
    }

    /** Zeroes plaintext held in memory. Call after [flush] when locking. */
    @Synchronized
    fun wipe() {
        cc.buf.fill(0); rc.buf.fill(0)
        cc.idx = -1; rc.idx = -1; cc.dirty = false
    }

    // ---- directories ------------------------------------------------------------------------

    private fun readDir(dirId: Int): List<Pair<String, Int>> {
        val ino = readInode(dirId)
        if (ino.mode != MODE_DIR) throw FsException("Not a directory")
        if (ino.size == 0L) return emptyList()
        val buf = ByteArray(ino.size.toInt())
        readRaw(ino, buf)
        val bb = ByteBuffer.wrap(buf)
        val count = bb.int
        val out = ArrayList<Pair<String, Int>>(count)
        repeat(count) {
            val id = bb.int
            val len = bb.short.toInt() and 0xFFFF
            val nameBytes = ByteArray(len)
            bb.get(nameBytes)
            out.add(String(nameBytes, Charsets.UTF_8) to id)
        }
        return out
    }

    private fun writeDir(dirId: Int, entries: List<Pair<String, Int>>) {
        val ino = readInode(dirId)
        val names = entries.map { it.first.toByteArray(Charsets.UTF_8) }
        val bb = ByteBuffer.allocate(4 + names.sumOf { 6 + it.size })
        bb.putInt(entries.size)
        entries.forEachIndexed { i, e ->
            bb.putInt(e.second).putShort(names[i].size.toShort()).put(names[i])
        }
        val data = bb.array()
        try {
            writeRaw(ino, data)
            if (ino.size > data.size) shrink(ino, data.size.toLong())
            ino.size = data.size.toLong()
            ino.mtime = System.currentTimeMillis()
        } finally {
            flushBitmap()
            writeInode(dirId, ino)
        }
    }

    private fun isInside(ancestor: Int, candidate: Int): Boolean {
        for ((_, id) in readDir(ancestor)) {
            if (id == candidate) return true
            if (readInode(id).mode == MODE_DIR && isInside(id, candidate)) return true
        }
        return false
    }

    private fun create(dirId: Int, name: String, mode: Int): Int {
        checkName(name)
        val entries = readDir(dirId).toMutableList()
        if (entries.any { it.first == name }) throw AlreadyExistsException(name)
        val id = allocInode()
        val ino = Inode(mode, 0, System.currentTimeMillis(), dirId, if (mode == MODE_FILE && compression != null) F_COMPRESSED else 0)
        writeInode(id, ino)
        entries.add(name to id)
        try {
            writeDir(dirId, entries)
        } catch (e: IOException) {
            freeInode(id)
            throw e
        }
        return id
    }

    private fun destroy(id: Int) {
        if (cc.id == id) { cc.idx = -1; cc.dirty = false }
        if (rc.id == id) rc.idx = -1
        val ino = readInode(id)
        if (ino.mode == MODE_DIR) {
            for ((_, child) in readDir(id)) destroy(child)
        }
        shrink(ino, 0)
        freeInode(id)
    }

    private fun checkName(name: String) {
        val bytes = name.toByteArray(Charsets.UTF_8)
        if (name.isEmpty() || bytes.size > 255 || name == "." || name == ".." || name.contains('/') || name.contains('\u0000')) {
            throw FsException("Invalid file name")
        }
    }

    // ---- file data helpers ------------------------------------------------------------------

    /** Reads the entire (small) file into [out]. */
    private fun readRaw(ino: Inode, out: ByteArray) {
        val tmp = ByteArray(bs)
        var done = 0
        while (done < out.size) {
            val idx = (done / bs).toLong()
            val n = minOf(bs, out.size - done)
            val blk = mapBlock(ino, idx, false)
            if (blk == 0L) java.util.Arrays.fill(out, done, done + n, 0)
            else {
                dev.read(blk, tmp, 0)
                System.arraycopy(tmp, 0, out, done, n)
            }
            done += n
        }
    }

    private fun writeRaw(ino: Inode, data: ByteArray) {
        val tmp = ByteArray(bs)
        var done = 0
        while (done < data.size) {
            val idx = (done / bs).toLong()
            val n = minOf(bs, data.size - done)
            val blk = mapBlock(ino, idx, true)
            java.util.Arrays.fill(tmp, 0)
            System.arraycopy(data, done, tmp, 0, n)
            dev.write(blk, tmp, 0)
            done += n
        }
    }

    // ---- block mapping ----------------------------------------------------------------------

    /** Returns the device block for file block [idx] (0 for a hole). Sets [justAllocated] when it created it. */
    private fun mapBlock(ino: Inode, idx: Long, allocate: Boolean): Long {
        justAllocated = false
        if (idx < DIRECT) return slot(ino.ptr, idx.toInt(), allocate, zero = false)
        var i = idx - DIRECT
        if (i < ptrsPerBlock) {
            val ind = slot(ino.ptr, IND_SLOT, allocate, zero = true)
            if (ind == 0L) return 0
            return indirectSlot(ind, i.toInt(), allocate, zero = false)
        }
        i -= ptrsPerBlock
        if (i >= ptrsPerBlock.toLong() * ptrsPerBlock) throw FsException("File too large")
        val dind = slot(ino.ptr, DIND_SLOT, allocate, zero = true)
        if (dind == 0L) return 0
        val l1 = indirectSlot(dind, (i / ptrsPerBlock).toInt(), allocate, zero = true)
        if (l1 == 0L) return 0
        return indirectSlot(l1, (i % ptrsPerBlock).toInt(), allocate, zero = false)
    }

    private fun slot(arr: IntArray, i: Int, allocate: Boolean, zero: Boolean): Long {
        if (arr[i] != 0) return arr[i].toLong() and 0xFFFFFFFFL
        if (!allocate) return 0
        val b = allocBlock(zero)
        arr[i] = b.toInt()
        return b
    }

    private fun indirectSlot(blk: Long, i: Int, allocate: Boolean, zero: Boolean): Long {
        val buf = ByteArray(bs)
        dev.read(blk, buf, 0)
        val bb = ByteBuffer.wrap(buf)
        val cur = bb.getInt(i * 4)
        if (cur != 0) {
            justAllocated = false
            return cur.toLong() and 0xFFFFFFFFL
        }
        if (!allocate) return 0
        val b = allocBlock(zero)
        bb.putInt(i * 4, b.toInt())
        dev.write(blk, buf, 0)
        return b
    }

    private fun shrink(ino: Inode, newSize: Long) {
        val first = (newSize + bs - 1) / bs // first file block index to drop
        for (i in 0 until DIRECT) {
            if (i >= first && ino.ptr[i] != 0) {
                freeBlock(ino.ptr[i].toLong() and 0xFFFFFFFFL); ino.ptr[i] = 0
            }
        }
        if (ino.ptr[IND_SLOT] != 0) {
            val blk = ino.ptr[IND_SLOT].toLong() and 0xFFFFFFFFL
            if (freeIndirect(blk, DIRECT.toLong(), first, 1)) ino.ptr[IND_SLOT] = 0
        }
        if (ino.ptr[DIND_SLOT] != 0) {
            val blk = ino.ptr[DIND_SLOT].toLong() and 0xFFFFFFFFL
            if (freeIndirect(blk, DIRECT.toLong() + ptrsPerBlock, first, 2)) ino.ptr[DIND_SLOT] = 0
        }
        // Keep the invariant "bytes past EOF inside the last block are zero" so later growth reads zeros.
        val tail = (newSize % bs).toInt()
        if (newSize > 0 && tail != 0 && newSize < ino.size) {
            val blk = mapBlock(ino, newSize / bs, false)
            if (blk != 0L) {
                val buf = ByteArray(bs)
                dev.read(blk, buf, 0)
                java.util.Arrays.fill(buf, tail, bs, 0)
                dev.write(blk, buf, 0)
            }
        }
    }

    /**
     * Frees every file block >= [first] under pointer block [blk], whose first entry maps file block
     * [base]. [level] 1 = entries are data blocks, 2 = entries are level-1 pointer blocks.
     * Returns true when [blk] itself became empty and was freed.
     */
    private fun freeIndirect(blk: Long, base: Long, first: Long, level: Int): Boolean {
        val buf = ByteArray(bs)
        dev.read(blk, buf, 0)
        val bb = ByteBuffer.wrap(buf)
        val span = if (level == 1) 1L else ptrsPerBlock.toLong()
        var changed = false
        var empty = true
        for (j in 0 until ptrsPerBlock) {
            val e = bb.getInt(j * 4)
            if (e == 0) continue
            val childBase = base + j * span
            val child = e.toLong() and 0xFFFFFFFFL
            if (level == 1) {
                if (childBase >= first) {
                    freeBlock(child); bb.putInt(j * 4, 0); changed = true
                } else empty = false
            } else {
                if (childBase + span <= first) {
                    empty = false
                } else if (freeIndirect(child, childBase, first, 1)) {
                    bb.putInt(j * 4, 0); changed = true
                } else empty = false
            }
        }
        if (empty) {
            freeBlock(blk)
            return true
        }
        if (changed) dev.write(blk, buf, 0)
        return false
    }

    // ---- compressed files ---------------------------------------------------------------------
    //
    // A compressed file is cut into 64 KiB logical chunks; chunk c owns the 16 block slots
    // [16c, 16c+16) of the normal pointer tree. How a chunk is stored is inferred from how many slots are
    // populated, with L = the chunk's logical length (min(64 KiB, size - c*64 KiB)):
    //   0 slots                         hole, reads as zeros
    //   ceil(L/4096) slots              raw bytes
    //   fewer slots                     [u32 length][compressed bytes]
    // A chunk is only stored compressed when that saves at least one block, so the rule is unambiguous.
    // Because L comes from the file size, the size and the chunk holding the end of file are always
    // committed together.

    private class Chunk {
        var id = 0
        var idx = -1L
        var len = 0
        var dirty = false
        var pendingSize = 0L
        val buf = ByteArray(CHUNK)
    }

    private val cc = Chunk() // single write-back chunk; idx >= 0 only while dirty
    private val rc = Chunk() // clean read cache
    private val slots get() = CHUNK / bs

    private fun chunkLen(size: Long, c: Long): Int = minOf(CHUNK.toLong(), size - c * CHUNK).coerceAtLeast(0).toInt()

    private fun codecOrFail() = codec ?: throw FsException("This volume has no compression engine")

    private fun allZero(b: ByteArray, len: Int): Boolean {
        for (i in 0 until len) if (b[i].toInt() != 0) return false
        return true
    }

    /** Decodes chunk [c] (whose on-disk logical length is [lc]) into out[0, CHUNK), zero-padded. */
    private fun decodeChunk(ino: Inode, c: Long, lc: Int, out: ByteArray) {
        java.util.Arrays.fill(out, 0, CHUNK, 0)
        if (lc == 0) return
        val first = c * slots
        var k = 0
        while (k < slots && mapBlock(ino, first + k, false) != 0L) k++
        if (k == 0) return
        val need = (lc + bs - 1) / bs
        val tmp = ByteArray(k * bs)
        for (i in 0 until k) dev.read(mapBlock(ino, first + i, false), tmp, i * bs)
        try {
            when {
                k == need -> System.arraycopy(tmp, 0, out, 0, lc)
                k < need -> {
                    val clen = ByteBuffer.wrap(tmp).int
                    if (clen <= 0 || clen > k * bs - 4) throw CorruptVolumeException("Corrupt compressed chunk")
                    codecOrFail().decompress(tmp, 4, clen, out, lc)
                }
                else -> throw CorruptVolumeException("Corrupt compressed chunk")
            }
        } finally {
            tmp.fill(0)
        }
    }

    /** Writes chunk [c] (logical length [l]) and sets the file size to [newSize] in the same inode update. */
    private fun storeChunk(id: Int, c: Long, data: ByteArray, l: Int, newSize: Long) {
        val ino = readInode(id)
        val first = c * slots
        var comp: ByteArray? = null
        var kNew = 0
        if (l > 0 && !allZero(data, l)) {
            val need = (l + bs - 1) / bs
            val z = codecOrFail().compress(data, 0, l)
            val kc = (4 + z.size + bs - 1) / bs
            if (kc < need) { comp = z; kNew = kc } else kNew = need
        }
        var have = 0
        while (have < slots && mapBlock(ino, first + have, false) != 0L) have++
        // Check space up front so a full volume never leaves a half-overwritten chunk behind.
        if (kNew > have && freeBlockCount < (kNew - have) + 3) throw VolumeFullException()
        val stream: ByteArray? = comp?.let {
            ByteArray(kNew * bs).also { s -> ByteBuffer.wrap(s).putInt(it.size); System.arraycopy(it, 0, s, 4, it.size) }
        }
        val blk = ByteArray(bs)
        for (i in 0 until kNew) {
            java.util.Arrays.fill(blk, 0)
            if (stream != null) System.arraycopy(stream, i * bs, blk, 0, bs)
            else System.arraycopy(data, i * bs, blk, 0, minOf(bs, l - i * bs))
            dev.write(mapBlock(ino, first + i, true), blk, 0)
        }
        blk.fill(0)
        for (i in kNew until have) clearSlot(ino, first + i)
        ino.size = newSize
        ino.mtime = System.currentTimeMillis()
        flushBitmap()
        writeInode(id, ino)
        if (rc.id == id && rc.idx == c) rc.idx = -1
    }

    private fun clearSlot(ino: Inode, idx: Long) {
        if (idx < DIRECT) {
            freeBlock(ino.ptr[idx.toInt()].toLong() and 0xFFFFFFFFL); ino.ptr[idx.toInt()] = 0
            return
        }
        var i = idx - DIRECT
        if (i < ptrsPerBlock) {
            freeEntry(ino.ptr[IND_SLOT].toLong() and 0xFFFFFFFFL, i.toInt())
            return
        }
        i -= ptrsPerBlock
        val l1 = ByteBuffer.wrap(ByteArray(bs).also { dev.read(ino.ptr[DIND_SLOT].toLong() and 0xFFFFFFFFL, it, 0) })
            .getInt((i / ptrsPerBlock).toInt() * 4).toLong() and 0xFFFFFFFFL
        freeEntry(l1, (i % ptrsPerBlock).toInt())
    }

    private fun freeEntry(blk: Long, i: Int) {
        val buf = ByteArray(bs)
        dev.read(blk, buf, 0)
        val bb = ByteBuffer.wrap(buf)
        val e = bb.getInt(i * 4)
        if (e != 0) {
            freeBlock(e.toLong() and 0xFFFFFFFFL)
            bb.putInt(i * 4, 0)
            dev.write(blk, buf, 0)
        }
    }

    /** Flushes the write-back chunk, if any. */
    private fun commit() {
        if (!cc.dirty) { cc.idx = -1; return }
        val id = cc.id
        val c = cc.idx
        cc.dirty = false
        cc.idx = -1
        storeChunk(id, c, cc.buf, cc.len, cc.pendingSize)
    }

    /** Re-stores chunk [c] with a new logical length, zero-extending or cutting; size becomes c*64K + lNew. */
    private fun rewriteChunk(id: Int, c: Long, lOld: Int, lNew: Int) {
        val ino = readInode(id)
        val tmp = ByteArray(CHUNK)
        try {
            decodeChunk(ino, c, lOld, tmp)
            storeChunk(id, c, tmp, lNew, c * CHUNK + lNew)
        } finally {
            tmp.fill(0)
        }
    }

    private fun readCompressed(id: Int, ino: Inode, pos: Long, dst: ByteArray, off: Int, len: Int): Int {
        val size = sizeOf(id, ino)
        if (pos >= size || len == 0) return 0
        val total = minOf(len.toLong(), size - pos).toInt()
        var done = 0
        while (done < total) {
            val p = pos + done
            val c = p / CHUNK
            val inC = (p % CHUNK).toInt()
            val n = minOf(CHUNK - inC, total - done)
            val src = when {
                cc.idx == c && cc.id == id -> cc
                rc.idx == c && rc.id == id -> rc
                else -> {
                    val leff = chunkLen(size, c)
                    decodeChunk(ino, c, minOf(chunkLen(ino.size, c), leff), rc.buf)
                    rc.id = id; rc.idx = c; rc.len = leff
                    rc
                }
            }
            System.arraycopy(src.buf, inC, dst, off + done, n)
            done += n
        }
        return total
    }

    private fun writeCompressed(id: Int, pos: Long, src: ByteArray, off: Int, len: Int) {
        if (rc.id == id) rc.idx = -1
        val base = sizeOf(id, readInode(id))
        val newSize = maxOf(base, pos + len)
        if (base % CHUNK != 0L && (newSize - 1) / CHUNK > (base - 1) / CHUNK) {
            // The old partial last chunk stops being last: make it a full, zero-padded chunk first.
            commit()
            val c = (base - 1) / CHUNK
            rewriteChunk(id, c, chunkLen(readInode(id).size, c), CHUNK)
        }
        var done = 0
        try {
            while (done < len) {
                val p = pos + done
                val c = p / CHUNK
                val inC = (p % CHUNK).toInt()
                val n = minOf(CHUNK - inC, len - done)
                val lNew = chunkLen(newSize, c)
                if (cc.idx == c && cc.id == id) {
                    if (lNew > cc.len) { java.util.Arrays.fill(cc.buf, cc.len, lNew, 0); cc.len = lNew }
                } else {
                    commit()
                    val ino = readInode(id)
                    if (inC == 0 && n >= lNew) java.util.Arrays.fill(cc.buf, 0, CHUNK, 0)
                    else decodeChunk(ino, c, minOf(chunkLen(ino.size, c), lNew), cc.buf)
                    cc.id = id; cc.idx = c; cc.len = lNew
                    cc.pendingSize = maxOf(ino.size, c * CHUNK + lNew)
                }
                System.arraycopy(src, off + done, cc.buf, inC, n)
                cc.dirty = true
                cc.pendingSize = maxOf(cc.pendingSize, c * CHUNK + cc.len)
                done += n
            }
        } catch (t: Throwable) {
            cc.idx = -1; cc.dirty = false
            throw t
        }
    }

    private fun truncateCompressed(id: Int, newSize: Long) {
        commit()
        if (rc.id == id) rc.idx = -1
        val old = readInode(id).size
        if (newSize == old) return
        if (newSize < old) {
            if (newSize % CHUNK != 0L) {
                val c = newSize / CHUNK
                rewriteChunk(id, c, chunkLen(old, c), (newSize - c * CHUNK).toInt())
            }
            val ino = readInode(id)
            shrink(ino, (newSize + CHUNK - 1) / CHUNK * CHUNK)
            ino.size = newSize
            ino.mtime = System.currentTimeMillis()
            writeInode(id, ino)
        } else {
            if (old % CHUNK != 0L) {
                val c = (old - 1) / CHUNK
                rewriteChunk(id, c, chunkLen(old, c), chunkLen(newSize, c))
            }
            val ino = readInode(id)
            ino.size = newSize
            ino.mtime = System.currentTimeMillis()
            writeInode(id, ino)
        }
        flushBitmap()
    }

    // ---- allocation -------------------------------------------------------------------------

    private fun allocBlock(zero: Boolean): Long {
        var b = allocHint
        var scanned = 0L
        val total = blockCount - dataStart
        if (b < dataStart || b >= blockCount) b = dataStart
        while (scanned < total) {
            if (!bit(b)) {
                setBit(b, true)
                allocHint = b + 1
                freeBlockCount--
                if (zero) dev.write(b, ByteArray(bs), 0)
                justAllocated = true
                return b
            }
            b++; scanned++
            if (b >= blockCount) b = dataStart
        }
        throw VolumeFullException()
    }

    private fun freeBlock(b: Long) {
        if (b < dataStart || b >= blockCount || !bit(b)) throw CorruptVolumeException("Bad block reference $b")
        setBit(b, false)
        freeBlockCount++
        if (b < allocHint) allocHint = b
    }

    private fun bit(b: Long) = (bitmap[(b shr 3).toInt()].toInt() shr (b and 7).toInt()) and 1 != 0

    private fun setBit(b: Long, v: Boolean) {
        val i = (b shr 3).toInt()
        val m = 1 shl (b and 7).toInt()
        bitmap[i] = (if (v) bitmap[i].toInt() or m else bitmap[i].toInt() and m.inv()).toByte()
        dirtyBitmap.set(i / bs)
    }

    private fun flushBitmap() {
        var i = dirtyBitmap.nextSetBit(0)
        while (i >= 0) {
            dev.write(bitmapStart + i, bitmap, i * bs)
            i = dirtyBitmap.nextSetBit(i + 1)
        }
        dirtyBitmap.clear()
    }

    private fun allocInode(): Int {
        val i = inodeUsed.nextClearBit(1)
        if (i >= inodeCount) throw FsException("Too many files in this volume")
        inodeUsed.set(i)
        return i
    }

    private fun freeInode(id: Int) {
        writeInode(id, Inode())
        inodeUsed.clear(id)
    }

    // ---- inode table ------------------------------------------------------------------------

    private fun readInode(id: Int): Inode {
        if (id <= 0 || id >= inodeCount) throw NoSuchEntryException("inode $id")
        val buf = ByteArray(bs)
        dev.read(inodeStart + id / INODES_PER_BLOCK, buf, 0)
        val bb = ByteBuffer.wrap(buf, (id % INODES_PER_BLOCK) * INODE_SIZE, INODE_SIZE)
        val ino = Inode(bb.short.toInt() and 0xFFFF)
        ino.flags = bb.short.toInt() and 0xFFFF
        ino.parent = bb.int
        ino.size = bb.long
        ino.mtime = bb.long
        for (i in 0 until PTR_SLOTS) ino.ptr[i] = bb.int
        return ino
    }

    private fun writeInode(id: Int, ino: Inode) {
        val blk = inodeStart + id / INODES_PER_BLOCK
        val buf = ByteArray(bs)
        dev.read(blk, buf, 0)
        val bb = ByteBuffer.wrap(buf)
        bb.position((id % INODES_PER_BLOCK) * INODE_SIZE)
        bb.putShort(ino.mode.toShort()).putShort(ino.flags.toShort())
        bb.putInt(ino.parent)
        bb.putLong(ino.size).putLong(ino.mtime)
        for (i in 0 until PTR_SLOTS) bb.putInt(ino.ptr[i])
        dev.write(blk, buf, 0)
    }

    private fun requireUsed(id: Int, ino: Inode) {
        if (ino.mode == MODE_FREE) throw NoSuchEntryException("inode $id")
    }

    private fun toStat(id: Int, ino: Inode) = Stat(id, ino.mode == MODE_DIR, sizeOf(id, ino), ino.mtime)

    /** Size including bytes still sitting in the write-back chunk. */
    private fun sizeOf(id: Int, ino: Inode) = if (cc.idx >= 0 && cc.id == id) cc.pendingSize else ino.size

    // ---- format / open ----------------------------------------------------------------------

    private fun layout(count: Long) {
        blockCount = count
        inodeCount = (((count / 8).coerceAtLeast(128) + INODES_PER_BLOCK - 1) / INODES_PER_BLOCK * INODES_PER_BLOCK).toInt()
        bitmapStart = 1
        bitmapBlocks = ((count + bs * 8L - 1) / (bs * 8L)).toInt()
        inodeStart = bitmapStart + bitmapBlocks
        dataStart = inodeStart + inodeCount / INODES_PER_BLOCK
        if (dataStart + 8 >= count) throw FsException("Volume too small")
    }

    private fun doFormat() {
        layout(dev.blockCount)
        bitmap = ByteArray(bitmapBlocks * bs)
        for (b in 0 until dataStart) setBit(b, true)
        freeBlockCount = blockCount - dataStart
        val zero = ByteArray(bs)
        for (b in inodeStart until dataStart) dev.write(b, zero, 0)
        inodeUsed = BitSet(inodeCount)
        inodeUsed.set(0)
        inodeUsed.set(ROOT)
        writeInode(ROOT, Inode(MODE_DIR, 0, System.currentTimeMillis(), ROOT))
        // Mark every bitmap block dirty so the whole (random-initialised) bitmap area is written.
        dirtyBitmap.set(0, bitmapBlocks)
        flushBitmap()
        val sb = ByteBuffer.allocate(bs)
        sb.put(MAGIC).putInt(2).putLong(blockCount).putInt(inodeCount).putInt(compression?.id ?: NO_COMPRESSION)
        dev.write(0, sb.array(), 0)
        dev.flush()
    }

    private fun doOpen() {
        val sbBuf = ByteArray(bs)
        dev.read(0, sbBuf, 0)
        val sb = ByteBuffer.wrap(sbBuf)
        val magic = ByteArray(MAGIC.size).also { sb.get(it) }
        if (!magic.contentEquals(MAGIC)) throw CorruptVolumeException("Volume is not formatted (or the filesystem is damaged)")
        if (sb.int != 2) throw CorruptVolumeException("Unsupported filesystem version")
        val count = sb.long
        val inodes = sb.int
        val compId = sb.int
        if (compId != NO_COMPRESSION) {
            compression = Compression.byId(compId) ?: throw CorruptVolumeException("Unknown compression engine")
            codec = Codec.of(compression!!)
        }
        if (count != dev.blockCount) throw CorruptVolumeException("Filesystem size mismatch")
        layout(count)
        if (inodes != inodeCount) throw CorruptVolumeException("Filesystem layout mismatch")
        bitmap = ByteArray(bitmapBlocks * bs)
        for (i in 0 until bitmapBlocks) dev.read(bitmapStart + i, bitmap, i * bs)
        var used = 0L
        for (b in dataStart until blockCount) if (bit(b)) used++
        freeBlockCount = blockCount - dataStart - used
        inodeUsed = BitSet(inodeCount)
        inodeUsed.set(0)
        val buf = ByteArray(bs)
        for (blk in 0 until inodeCount / INODES_PER_BLOCK) {
            dev.read(inodeStart + blk, buf, 0)
            for (k in 0 until INODES_PER_BLOCK) {
                val mode = ((buf[k * INODE_SIZE].toInt() and 0xFF) shl 8) or (buf[k * INODE_SIZE + 1].toInt() and 0xFF)
                if (mode != 0) inodeUsed.set(blk * INODES_PER_BLOCK + k)
            }
        }
        if (readInode(ROOT).mode != MODE_DIR) throw CorruptVolumeException("Root directory missing")
    }

    companion object {
        const val ROOT = 1
        private const val MODE_FREE = 0
        private const val MODE_FILE = 1
        private const val MODE_DIR = 2
        private const val INODE_SIZE = 128
        private const val INODES_PER_BLOCK = 4096 / INODE_SIZE
        private const val DIRECT = 12
        private const val IND_SLOT = 12
        private const val DIND_SLOT = 13
        private const val PTR_SLOTS = 14
        private const val MAX_FILE_BLOCKS = 12L + 1024 + 1024L * 1024
        private val MAGIC = "SFS2".toByteArray()
        private const val NO_COMPRESSION = 255
        private const val F_COMPRESSED = 1
        private const val CHUNK = 65536

        fun format(dev: BlockDevice, compression: Compression? = null): SecFs {
            require(dev.blockSize == 4096) { "unsupported block size" }
            return SecFs(dev).also {
                it.compression = compression
                it.codec = compression?.let { c -> Codec.of(c) }
                it.doFormat()
            }
        }

        fun open(dev: BlockDevice): SecFs {
            require(dev.blockSize == 4096) { "unsupported block size" }
            return SecFs(dev).also { it.doOpen() }
        }
    }
}
