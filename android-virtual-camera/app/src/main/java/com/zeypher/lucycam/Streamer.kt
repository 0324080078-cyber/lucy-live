package com.zeypher.lucycam

import android.media.MediaCodec
import android.media.MediaFormat
import android.opengl.EGL14
import android.opengl.GLES20
import android.view.Surface
import com.pedro.common.ConnectChecker
import com.pedro.encoder.Frame
import com.pedro.encoder.audio.AudioEncoder
import com.pedro.encoder.audio.GetAudioData
import com.pedro.encoder.video.FormatVideoEncoder
import com.pedro.encoder.video.GetVideoData
import com.pedro.encoder.video.VideoEncoder
import com.pedro.rtmp.rtmp.RtmpClient
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * On-device RTMP publisher for the Lucy avatar.
 *  - Video: Ashmem I420 frames -> EGL -> video encoder input surface (H.264).
 *  - Audio: mic -> MicPitch (voice changer) -> AAC encoder.
 * Sends straight to an RTMP ingest (Twitch/YouTube/TikTok/own nginx-rtmp).
 *
 * Uses rtmp-rtsp-stream-client-java (com.github.pedroSG94:rtmp-rtsp-stream-client-java:2.5.0).
 * The low-level RtmpClient/VideoEncoder/AudioEncoder API (verified against the
 * 2.5.0 sources) is used directly so we can render WebRTC frames to the encoder
 * surface ourselves instead of going through the Camera helpers.
 */
class Streamer(private val url: String) {

    private var client: RtmpClient? = null
    private var videoEncoder: VideoEncoder? = null
    private var audioEncoder: AudioEncoder? = null
    private var renderer: EncoderRenderer? = null
    private var mic: MicPitch? = null

    var onError: ((String) -> Unit)? = null

    fun start(semitones: Double, formant: Double) {
        try {
            val checker = object : ConnectChecker {
                override fun onConnectionStarted(url: String) {}
                override fun onConnectionSuccess() {}
                override fun onConnectionFailed(reason: String) { onError?.invoke("rtmp: $reason") }
                override fun onDisconnect() {}
                override fun onAuthError() { onError?.invoke("rtmp auth error") }
                override fun onAuthSuccess() {}
            }
            val client = RtmpClient(checker)
            this.client = client

            val width = 1280
            val height = 720

            val ve = VideoEncoder(object : GetVideoData {
                override fun onVideoInfo(sps: ByteBuffer, pps: ByteBuffer?, vps: ByteBuffer?) {
                    client.setVideoInfo(sps, pps, vps)
                }
                override fun getVideoData(videoBuffer: ByteBuffer, info: MediaCodec.BufferInfo) {
                    client.sendVideo(videoBuffer, info)
                }
                override fun onVideoFormat(mediaFormat: MediaFormat) {}
            })
            if (!ve.prepareVideoEncoder(width, height, 30, 2_500_000, 0, 2, FormatVideoEncoder.SURFACE)) {
                throw IllegalStateException("video encoder prepare failed")
            }
            val surface = ve.inputSurface
                ?: throw IllegalStateException("encoder has no input surface")
            this.videoEncoder = ve

            val ae = AudioEncoder(object : GetAudioData {
                override fun getAudioData(audioBuffer: ByteBuffer, info: MediaCodec.BufferInfo) {
                    client.sendAudio(audioBuffer, info)
                }
                override fun onAudioFormat(mediaFormat: MediaFormat) {}
            })
            val sampleRate = 48000
            val stereo = false
            if (!ae.prepareAudioEncoder(128_000, sampleRate, stereo, 4096)) {
                throw IllegalStateException("audio encoder prepare failed")
            }
            this.audioEncoder = ae

            client.setVideoResolution(width, height)
            client.setFps(30)
            client.setAudioInfo(sampleRate, stereo)
            client.setOnlyVideo(false)
            client.connect(url)

            ve.start(true)
            ae.start(true)

            renderer = EncoderRenderer(surface, width, height).apply { start() }
            mic = MicPitch(semitones, formant).apply {
                start { pcm -> audioEncoder?.inputPCMData(Frame(pcm, 0, pcm.size, System.nanoTime() / 1000)) }
            }
        } catch (t: Throwable) {
            onError?.invoke("streamer: ${t.message}")
            stop()
        }
    }

    fun stop() {
        mic?.stop(); mic = null
        renderer?.stop(); renderer = null
        try { videoEncoder?.stop() } catch (_: Throwable) {}
        try { audioEncoder?.stop() } catch (_: Throwable) {}
        try { client?.disconnect() } catch (_: Throwable) {}
        client = null
        audioEncoder = null
        videoEncoder = null
    }

    // ---- EGL renderer: I420 -> YUV->RGB shader -> encoder surface ----
    private class EncoderRenderer(
        private val surface: Surface,
        private val width: Int,
        private val height: Int
    ) {
        private val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        private lateinit var ctx: android.opengl.EGLContext
        private lateinit var win: android.opengl.EGLSurface
        private var texY = 0; private var texU = 0; private var texV = 0
        private var program = 0
        private val vbo = IntArray(1)
        private var running = true
        private lateinit var thread: Thread

        fun start() {
            val ver = IntArray(2)
            EGL14.eglInitialize(display, ver, 0, ver, 1)
            val cfg = choose() ?: return
            ctx = EGL14.eglCreateContext(display, cfg, EGL14.EGL_NO_CONTEXT,
                intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0)
            win = EGL14.eglCreateWindowSurface(display, cfg, surface, intArrayOf(EGL14.EGL_NONE), 0)
            val t = IntArray(3); GLES20.glGenTextures(3, t, 0)
            texY = t[0]; texU = t[1]; texV = t[2]
            listOf(texY, texU, texV).forEach {
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, it)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            }
            compile()
            thread = Thread { loop() }.apply { name = "enc-render"; start() }
        }

        private fun choose(): android.opengl.EGLConfig {
            val cfg = arrayOfNulls<android.opengl.EGLConfig>(1)
            val n = IntArray(1)
            EGL14.eglChooseConfig(display,
                intArrayOf(EGL14.EGL_RENDER_TYPE, EGL14.EGL_WINDOW_BIT,
                    EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8,
                    EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8, EGL14.EGL_NONE),
                0, cfg, 0, 1, n, 0)
            return cfg[0]!!
        }

        private fun compile() {
            val vs = GLES20.glCreateShader(GLES20.GL_VERTEX_SHADER)
            GLES20.glShaderSource(vs, "attribute vec2 p; attribute vec2 uv; varying vec2 v; void main(){ v=uv; gl_Position=vec4(p,0.0,1.0); }")
            GLES20.glCompileShader(vs)
            val fs = GLES20.glCreateShader(GLES20.GL_FRAGMENT_SHADER)
            GLES20.glShaderSource(fs,
                "precision mediump float; varying vec2 v;" +
                "uniform sampler2D yTex; uniform sampler2D uTex; uniform sampler2D vTex;" +
                "void main(){ float y=texture2D(yTex,v).r; float u=texture2D(uTex,v).r-0.5; float vv=texture2D(vTex,v).r-0.5;" +
                "gl_FragColor=vec4(y+1.402*vv, y-0.344*u-0.714*vv, y+1.772*u, 1.0); }")
            GLES20.glCompileShader(fs)
            program = GLES20.glCreateProgram()
            GLES20.glAttachShader(program, vs); GLES20.glAttachShader(program, fs); GLES20.glLinkProgram(program)
            GLES20.glGenBuffers(1, vbo, 0)
            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vbo[0])
            val verts = floatArrayOf(
                -1f, -1f, 0f, 1f, 1f, -1f, 1f, 1f, -1f, 1f, 0f, 0f,
                 1f, -1f, 1f, 1f, 1f, 1f, 1f, 0f, -1f, 1f, 0f, 0f)
            val bb = ByteBuffer.allocateDirect(verts.size * 4).order(ByteOrder.nativeOrder())
            bb.asFloatBuffer().put(verts)
            GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, verts.size * 4, bb, GLES20.GL_STATIC_DRAW)
        }

        private fun loop() {
            val halfW = width / 2
            val halfH = height / 2
            while (running) {
                try {
                    val f = AppAshmem.readLatest() ?: run { Thread.sleep(8); continue }
                    GLES20.glUseProgram(program)
                    upload(texY, f.first, width, height)
                    upload(texU, f.second, halfW, halfH)
                    upload(texV, f.third, halfW, halfH)
                    GLES20.glActiveTexture(GLES20.GL_TEXTURE0); GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texY)
                    GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "yTex"), 0)
                    GLES20.glActiveTexture(GLES20.GL_TEXTURE1); GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texU)
                    GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "uTex"), 1)
                    GLES20.glActiveTexture(GLES20.GL_TEXTURE2); GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texV)
                    GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "vTex"), 2)
                    if (!EGL14.eglMakeCurrent(display, win, win, ctx)) { Thread.sleep(20); continue }
                    GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
                    GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vbo[0])
                    val p = GLES20.glGetAttribLocation(program, "p")
                    val uv = GLES20.glGetAttribLocation(program, "uv")
                    GLES20.glEnableVertexAttribArray(p); GLES20.glVertexAttribPointer(p, 2, GLES20.GL_FLOAT, false, 16, 0)
                    GLES20.glEnableVertexAttribArray(uv); GLES20.glVertexAttribPointer(uv, 2, GLES20.GL_FLOAT, false, 16, 8)
                    GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, 6)
                    EGL14.eglSwapBuffers(display, win)
                } catch (t: Throwable) {
                    Thread.sleep(50) // encoder surface released; ride it out
                }
            }
        }

        private fun upload(tex: Int, data: ByteBuffer, w: Int, h: Int) {
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex)
            GLES20.glPixelStorei(GLES20.GL_UNPACK_ROW_LENGTH, 0)
            GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_LUMINANCE, w, h, 0,
                GLES20.GL_LUMINANCE, GLES20.GL_UNSIGNED_BYTE, data)
        }

        fun stop() {
            running = false
            try { thread.join(1000) } catch (_: Throwable) {}
            try { EGL14.eglDestroySurface(display, win) } catch (_: Throwable) {}
            try { EGL14.eglDestroyContext(display, ctx) } catch (_: Throwable) {}
            try { EGL14.eglTerminate(display) } catch (_: Throwable) {}
        }
    }
}
