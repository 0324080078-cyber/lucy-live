package com.zeypher.lucycam

import org.webrtc.VideoFrame
import org.webrtc.VideoSink

/**
 * Receives Lucy's remote VideoTrack frames and writes the I420 planes straight
 * into the shared Ashmem region (no color conversion). Exposes the region's fd
 * via the VcamService AIDL binding.
 */
class FramePump : VideoSink {

    fun init() {
        // AppAshmem owns the region; just make sure it's initialized.
        AppAshmem.buf
    }

    fun release() {
        // region lives for the app process; nothing to close here.
    }

    override fun onFrame(frame: VideoFrame) {
        val i420 = frame.buffer.toI420() ?: return
        try {
            AppAshmem.writeSlot(i420)
        } finally {
            i420.release()
        }
    }
}

/** Single shared FramePump instance (a VideoSink) wired to the Ashmem region. */
object FramePumpHolder {
    val pump = FramePump()
}
