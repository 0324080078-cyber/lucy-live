package com.zeypher.fakecam

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Handler
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.SharedMemory
import com.zeypher.lucycam.IVcamBuffer
import de.robv.android.xposed.helpers.AndroidAppHelper
import java.nio.ByteBuffer

/**
 * Binds to the Lucy app's VcamService (system_server -> app) and obtains the real
 * Ashmem fd. Named SharedMemory does NOT cross processes, so we must take the fd
 * over the AIDL binding, not recreate the region.
 */
object VcamBufferClient {

    private const val ACTION = "com.zeypher.lucycam.VCAM_BUFFER"
    private const val PKG = "com.zeypher.lucycam"

    private const val OFF_SEQ = 12
    private const val OFF_FRAME = 16
    private const val WIDTH = 1280
    private const val HEIGHT = 720
    private const val Y_SIZE = WIDTH * HEIGHT
    private const val U_SIZE = WIDTH * HEIGHT / 4
    private const val V_SIZE = WIDTH * HEIGHT / 4
    private const val PLANE = Y_SIZE + U_SIZE + V_SIZE
    private const val HEADER = 1024
    private const val SIZE = HEADER + PLANE * 2
    private const val OFF_SLOT0 = HEADER
    private const val OFF_SLOT1 = HEADER + PLANE

    @Volatile private var buf: ByteBuffer? = null
    @Volatile private var ready = false
    private var bound = false
    private var last = -1

    fun ensure() {
        if (bound || ready) return
        bound = true
        val ctx = AndroidAppHelper.currentApplication() as Context
        val intent = Intent(ACTION).setPackage(PKG)
        try {
            ctx.bindService(intent, conn, Context.BIND_AUTO_CREATE)
        } catch (t: Throwable) {
            bound = false
            scheduleRetry()
        }
    }

    private fun scheduleRetry() {
        val ctx = AndroidAppHelper.currentApplication() as Context
        try {
            Handler(ctx.mainLooper).postDelayed({ bound = false; ensure() }, 3000)
        } catch (_: Throwable) { }
    }

    private val conn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            try {
                val stub = IVcamBuffer.Stub.asInterface(service)
                val pfd: ParcelFileDescriptor = stub.buffer
                val mem = SharedMemory.fromFileDescriptor(pfd)
                buf = mem.mapReadOnly()
                ready = true
                last = -1
            } catch (t: Throwable) {
                ready = false
                buf = null
                scheduleRetry()
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            ready = false
            buf = null
            scheduleRetry()
        }
    }

    fun lockFrame(): Triple<ByteBuffer, ByteBuffer, ByteBuffer>? {
        if (!ready) { ensure(); return null }
        val b = buf ?: return null
        val fc = b.getInt(OFF_FRAME)
        if (fc == last) return null
        last = fc
        val seq = b.getInt(OFF_SEQ)
        val base = if (seq == 0) OFF_SLOT0 else OFF_SLOT1
        return Triple(
            slice(b, base, Y_SIZE),
            slice(b, base + Y_SIZE, U_SIZE),
            slice(b, base + Y_SIZE + V_SIZE, V_SIZE)
        )
    }

    private fun slice(b: ByteBuffer, off: Int, len: Int): ByteBuffer {
        val s = b.duplicate(); s.position(off); s.limit(off + len); return s.slice()
    }
}
