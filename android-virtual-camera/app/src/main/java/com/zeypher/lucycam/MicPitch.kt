package com.zeypher.lucycam

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import kotlin.math.cos
import kotlin.math.PI
import kotlin.math.roundToInt

/**
 * Real-time mic pitch/formant shifter (OLA), reads the mic and emits processed
 * PCM16 mono. Feed the output to an encoder or an AudioTrack for monitoring.
 */
class MicPitch(
    semitones: Double,
    formant: Double = 1.0,
    private val sampleRate: Int = 48000
) {
    private val W = 2048
    private val Ha = 512
    private val hann = FloatArray(W) { 0.5f * (1f - cos(2f * PI.toFloat() * it / (W - 1))) }
    private val inBuf = FloatArray(W + Ha)
    private val outBuf = FloatArray(16384)
    private var inCount = 0
    private var anaCount = 0
    private var synCount = 0
    private var outCount = 0

    private var semitones = semitones
    private var formant = formant
    private var ratio = Math.pow(2.0, semitones / 12.0)
    private var Hs = maxOf(1, (Ha * ratio).roundToInt())

    /** Update the effect live without restarting capture. */
    fun set(semitones: Double, formant: Double) {
        this.semitones = semitones
        this.formant = formant
        this.ratio = Math.pow(2.0, semitones / 12.0)
        this.Hs = maxOf(1, (Ha * ratio).roundToInt())
    }

    private var record: AudioRecord? = null
    private var thread: Thread? = null
    private var running = false

    fun start(onPcm: (ByteArray) -> Unit) {
        val min = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION, sampleRate,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, Ha * 4) * 2
        )
        record = rec
        running = true
        val frame = ShortArray(Ha)
        val pending = ByteArray(Ha * 2)
        var pendingN = 0
        thread = Thread {
            rec.startRecording()
            while (running) {
                val n = rec.read(frame, 0, Ha)
                if (n <= 0) continue
                for (i in 0 until n) {
                    val s = frame[i] / 32768f
                    inBuf[inCount % inBuf.size] = s
                    if (outCount < synCount) {
                        val o = (outBuf[outCount % outBuf.size] * 32767f)
                            .toInt().coerceIn(-32768, 32767).toShort()
                        pending[pendingN++] = (o.toInt() and 0xFF).toByte()
                        pending[pendingN++] = ((o.toInt() ushr 8) and 0xFF).toByte()
                    }
                    outCount++
                    inCount++
                    while (inCount - anaCount >= Ha) {
                        grain(anaCount, formant)
                        anaCount += Ha
                    }
                }
                if (pendingN > 0) {
                    val chunk = pending.copyOf(pendingN)
                    pendingN = 0
                    onPcm(chunk)
                }
            }
        }.apply { name = "micpitch"; start() }
    }

    private fun grain(anaBase: Int, formant: Double) {
        val g = FloatArray(W)
        for (nn in 0 until W) {
            val srcF = nn * formant
            val idx = (anaBase + srcF.toInt()) % inBuf.size
            g[nn] = (if (srcF < W) inBuf[idx] else 0f) * hann[nn]
        }
        for (nn in 0 until W) {
            val pos = (synCount + nn) % outBuf.size
            outBuf[pos] += g[nn] * hann[nn]
        }
        synCount += Hs
    }

    fun stop() {
        running = false
        try { thread?.join(1000) } catch (_: Throwable) {}
        try { record?.stop() } catch (_: Throwable) {}
        try { record?.release() } catch (_: Throwable) {}
        record = null
    }
}
