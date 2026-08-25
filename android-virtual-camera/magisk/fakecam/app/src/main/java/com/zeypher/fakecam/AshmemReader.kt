package com.zeypher.fakecam

import java.nio.ByteBuffer

/** Reads I420 frames for the virtual camera. Backed by the app's Ashmem fd (VcamBufferClient). */
object AshmemReader {
    fun lockFrame(): Triple<ByteBuffer, ByteBuffer, ByteBuffer>? = VcamBufferClient.lockFrame()
    val width get() = 1280
    val height get() = 720
}
