package com.zeypher.lucycam

import android.os.SharedMemory
import java.nio.ByteBuffer

/**
 * Shared frame-buffer contract between the Lucy app and the FakeCamera module.
 * Format is I420 (YUV420 planar) — written straight from WebRTC's I420Buffer,
 * so the app does no color conversion. The module converts YUV->RGB on the GPU.
 *
 * Layout per slot: [Y: W*H] [U: W*H/4] [V: W*H/4]
 *
 * Header:
 *   [0..3]   magic   = 0x4C554359 ("LUCY")
 *   [4..7]   width
 *   [8..11]  height
 *   [12..15] seq     = completed slot index (0 or 1)
 *   [16..19] frameCounter
 *   [20..1023] reserved
 *   slot0 @ 1024
 *   slot1 @ 1024 + PLANE
 */
object AshmemBuffer {
    const val NAME = "LUCYVCAM"
    const val MAGIC = 0x4C554359
    const val WIDTH = 1280
    const val HEIGHT = 720
    const val FORMAT = 2 // 2 = I420

    const val Y_SIZE = WIDTH * HEIGHT
    const val U_SIZE = WIDTH * HEIGHT / 4
    const val V_SIZE = WIDTH * HEIGHT / 4
    const val PLANE = Y_SIZE + U_SIZE + V_SIZE

    const val HEADER = 1024
    const val SIZE = HEADER + PLANE * 2

    const val OFF_MAGIC = 0
    const val OFF_WIDTH = 4
    const val OFF_HEIGHT = 8
    const val OFF_SEQ = 12
    const val OFF_FRAME = 16
    const val OFF_SLOT0 = HEADER
    const val OFF_SLOT1 = HEADER + PLANE

    fun create(): SharedMemory = SharedMemory.create(NAME, SIZE)

    fun map(mem: SharedMemory): ByteBuffer = mem.mapReadWrite()

    fun slotOffset(seq: Int): Int = if (seq == 0) OFF_SLOT0 else OFF_SLOT1
}
