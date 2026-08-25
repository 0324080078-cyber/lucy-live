package com.zeypher.fakecam

import android.opengl.EGL14
import android.opengl.GLES20
import android.opengl.GLES30
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Renders the Ashmem I420 frames into the client app's output Surfaces via EGL.
 * YUV->RGB happens in the fragment shader (GPU), so the app side does zero color
 * conversion. Each client Surface is an EGL window surface; we push a textured
 * fullscreen quad every frame.
 */
class VcamRenderer(surfaces: List<Surface>) {

    private val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
    private val ctx: android.opengl.EGLContext
    private val winSurfaces: List<android.opengl.EGLSurface>
    private val texY: Int
    private val texU: Int
    private val texV: Int
    private var running = true
    private val thread: Thread

    init {
        val versions = IntArray(2)
        EGL14.eglInitialize(display, versions, 0, versions, 1)
        val config = chooseConfig()
        ctx = EGL14.eglCreateContext(
            display, config, EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0
        )
        winSurfaces = surfaces.map {
            EGL14.eglCreateWindowSurface(display, config, it, intArrayOf(EGL14.EGL_NONE), 0)
        }
        val texs = IntArray(3)
        GLES20.glGenTextures(3, texs, 0)
        texY = texs[0]; texU = texs[1]; texV = texs[2]
        listOf(texY, texU, texV).forEach {
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, it)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        }
        compileProgram()
        thread = Thread { loop() }.apply { name = "vcam-render"; start() }
    }

    private fun chooseConfig(): android.opengl.EGLConfig {
        val cfg = arrayOfNulls<android.opengl.EGLConfig>(1)
        val num = IntArray(1)
        EGL14.eglChooseConfig(
            display,
            intArrayOf(
                EGL14.EGL_RENDER_TYPE, EGL14.EGL_WINDOW_BIT,
                EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_NONE
            ), 0, cfg, 0, 1, num, 0
        )
        return cfg[0]!!
    }

    private var program = 0
    private val vbo = IntArray(1)

    private fun compileProgram() {
        val vs = GLES20.glCreateShader(GLES20.GL_VERTEX_SHADER)
        GLES20.glShaderSource(vs, "attribute vec2 p; attribute vec2 uv; varying vec2 v; void main(){ v=uv; gl_Position=vec4(p,0.0,1.0); }")
        GLES20.glCompileShader(vs)
        val fs = GLES20.glCreateShader(GLES20.GL_FRAGMENT_SHADER)
        GLES20.glShaderSource(fs,
            "precision mediump float; varying vec2 v;" +
            "uniform sampler2D yTex; uniform sampler2D uTex; uniform sampler2D vTex;" +
            "void main(){" +
            "  float y = texture2D(yTex, v).r;" +
            "  float u = texture2D(uTex, v).r - 0.5;" +
            "  float vv = texture2D(vTex, v).r - 0.5;" +
            "  float r = y + 1.402 * vv;" +
            "  float g = y - 0.344 * u - 0.714 * vv;" +
            "  float b = y + 1.772 * u;" +
            "  gl_FragColor = vec4(r, g, b, 1.0);" +
            "}")
        GLES20.glCompileShader(fs)
        program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, vs)
        GLES20.glAttachShader(program, fs)
        GLES20.glLinkProgram(program)

        GLES20.glGenBuffers(1, vbo, 0)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vbo[0])
        val verts = floatArrayOf(
            -1f, -1f, 0f, 1f,
             1f, -1f, 1f, 1f,
            -1f,  1f, 0f, 0f,
             1f, -1f, 1f, 1f,
             1f,  1f, 1f, 0f,
            -1f,  1f, 0f, 0f
        )
        val bb = ByteBuffer.allocateDirect(verts.size * 4).order(ByteOrder.nativeOrder())
        bb.asFloatBuffer().put(verts)
        GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, verts.size * 4, bb, GLES20.GL_STATIC_DRAW)
    }

    private fun loop() {
        val w = AshmemReader.width
        val h = AshmemReader.height
        while (running) {
            try {
                val frame = AshmemReader.lockFrame()
                if (frame == null) { Thread.sleep(8); continue }
                GLES20.glUseProgram(program)

                upload(texY, frame.first, w, h)
                upload(texU, frame.second, w / 2, h / 2)
                upload(texV, frame.third, w / 2, h / 2)

                GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texY)
                GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "yTex"), 0)
                GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texU)
                GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "uTex"), 1)
                GLES20.glActiveTexture(GLES20.GL_TEXTURE2)
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texV)
                GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "vTex"), 2)

                for (ws in winSurfaces) {
                    if (!EGL14.eglMakeCurrent(display, ws, ws, ctx)) continue
                    GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
                    GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vbo[0])
                    val pLoc = GLES20.glGetAttribLocation(program, "p")
                    val uvLoc = GLES20.glGetAttribLocation(program, "uv")
                    GLES20.glEnableVertexAttribArray(pLoc)
                    GLES20.glVertexAttribPointer(pLoc, 2, GLES20.GL_FLOAT, false, 16, 0)
                    GLES20.glEnableVertexAttribArray(uvLoc)
                    GLES20.glVertexAttribPointer(uvLoc, 2, GLES20.GL_FLOAT, false, 16, 8)
                    GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, 6)
                    EGL14.eglSwapBuffers(display, ws)
                }
            } catch (t: Throwable) {
                // Surface died (app closed the session, etc.) — survive and retry.
                Thread.sleep(50)
            }
        }
    }

    private fun upload(tex: Int, data: ByteBuffer, w: Int, h: Int) {
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex)
        GLES20.glPixelStorei(GLES30.GL_UNPACK_ROW_LENGTH, 0)
        GLES20.glTexImage2D(
            GLES20.GL_TEXTURE_2D, 0, GLES20.GL_LUMINANCE, w, h, 0,
            GLES20.GL_LUMINANCE, GLES20.GL_UNSIGNED_BYTE, data
        )
    }

    fun stop() {
        running = false
        try { thread.join(1000) } catch (_: Throwable) {}
        for (ws in winSurfaces) EGL14.eglDestroySurface(display, ws)
        EGL14.eglDestroyContext(display, ctx)
        EGL14.eglTerminate(display)
    }
}
