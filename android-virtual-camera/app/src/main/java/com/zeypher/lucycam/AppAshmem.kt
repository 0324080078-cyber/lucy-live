package com.zeypher.lucycam

import android.os.ParcelFileDescriptor
import android.os.SharedMemory
import java.nio.ByteBuffer

/**
 * Single owner of the named Ashmem region in the app process.
 * FramePump writes frames here; the VcamService exposes the fd to the system
 * (LSPosed module), and the in-app Streamer reads from it. One region, no dupes.
 */
object AppAshmem {
    val mem: SharedMemory = SharedMemory.create(AshmemBuffer.NAME, AshmemBuffer.SIZE)
    val buf: ByteBuffer = mem.mapReadWrite()

    @Volatile var curSeq = 0
    private var lastRead = -1

    init {
        buf.putInt(AshmemBuffer.OFF_MAGIC, AshmemBuffer.MAGIC)
        buf.putInt(AshmemBuffer.OFF_WIDTH, AshmemBuffer.WIDTH)
        buf.putInt(AshmemBuffer.OFF_HEIGHT, AshmemBuffer.HEIGHT)
        buf.putInt(AshmemBuffer.OFF_FRAME, 0)
    }

    fun fd(): ParcelFileDescriptor = ParcelFileDescriptor.fromFd(mem.fileDescriptor.fd)

    fun slotOffset(seq: Int) = AshmemBuffer.slotOffset(seq)

    /** Writer (FramePump): write I420 planes into the next slot. */
    fun writeSlot(
        src: org.webrtc.VideoFrame.I420Buffer
    ) {
        val slot = curSeq xor 1
        val base = slotOffset(slot)
        val w = src.width
        val h = src.height
        val y = src.dataY; val u = src.dataU; val v = src.dataV
        val sy = src.strideY; val su = src.strideU; val sv = src.strideV
        val halfW = w / 2; val halfH = h / 2
        var off = base
        for (row in 0 until h) { y.position(row * sy); y.get(rowY, 0, w); buf.position(off); buf.put(rowY, 0, w); off += w }
        for (row in 0 until halfH) { u.position(row * su); u.get(rowU, 0, halfW); buf.position(off); buf.put(rowU, 0, halfW); off += halfW }
        for (row in 0 until halfH) { v.position(row * sv); v.get(rowV, 0, halfW); buf.position(off); buf.put(rowV, 0, halfW); off += halfW }
        curSeq = slot
        buf.putInt(AshmemBuffer.OFF_SEQ, slot)
        buf.putInt(AshmemBuffer.OFF_FRAME, buf.getInt(AshmemBuffer.OFF_FRAME) + 1)
    }

    // reusable row temps
    private val rowY = ByteArray(AshmemBuffer.WIDTH)
    private val rowU = ByteArray(AshmemBuffer.WIDTH / 2)
    private val rowV = ByteArray(AshmemBuffer.WIDTH / 2)

    /** Reader (Streamer, same process): latest I420 frame or null if unchanged. */
    fun readLatest(): Triple<ByteBuffer, ByteBuffer, ByteBuffer>? {
        val fc = buf.getInt(AshmemBuffer.OFF_FRAME)
        if (fc == lastRead) return null
        lastRead = fc
        val seq = buf.getInt(AshmemBuffer.OFF_SEQ)
        val base = slotOffset(seq)
        return Triple(
            slice(base, AshmemBuffer.Y_SIZE),
            slice(base + AshmemBuffer.Y_SIZE, AshmemBuffer.U_SIZE),
            slice(base + AshmemBuffer.Y_SIZE + AshmemBuffer.V_SIZE, AshmemBuffer.V_SIZE)
        )
    }

    private fun slice(off: Int, len: Int): ByteBuffer {
        val s = buf.duplicate(); s.position(off); s.limit(off + len); return s.slice()
    }
}
