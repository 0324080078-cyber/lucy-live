package com.zeypher.lucycam

import android.app.Service
import android.content.Intent
import android.os.IBinder

class VcamService : Service() {

    // FramePump is created in MainActivity and shared here via a singleton holder.
    override fun onBind(intent: Intent?): IBinder = binder

    private val binder = object : IVcamBuffer.Stub() {
        override fun getBuffer() = AppAshmem.fd()
        override fun getWidth() = AshmemBuffer.WIDTH
        override fun getHeight() = AshmemBuffer.HEIGHT
        override fun getFormat() = 2 // I420
    }
}
