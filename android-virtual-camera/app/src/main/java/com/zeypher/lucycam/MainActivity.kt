package com.zeypher.lucycam

import android.app.Activity
import android.content.Intent
import android.graphics.BitmapFactory
import android.media.AudioTrack
import android.net.Uri
import android.os.Bundle
import android.util.Base64
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import kotlinx.coroutines.*
import java.io.ByteArrayOutputStream

class MainActivity : Activity() {

    // Address of the Lucy Live backend (token + RTMP relay). Configurable in the UI.
    private lateinit var backendInput: EditText
    private val prefs by lazy { getSharedPreferences("lucy", MODE_PRIVATE) }
    private var avatarB64: String? = null
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private lateinit var status: TextView
    private lateinit var connectBtn: Button
    private lateinit var disconnectBtn: Button

    private var mic: MicPitch? = null
    private var monitor: AudioTrack? = null
    private var streamer: Streamer? = null
    private var bridge: LucyBridge? = null
    private var voiceSemi = 0.0
    private var voiceFormant = 1.0
    private var connecting = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pick = Button(this).apply {
            text = "Pick avatar image"
            setOnClickListener { startActivityForResult(Intent(Intent.ACTION_GET_CONTENT).apply { type = "image/*" }, 1) }
        }
        connectBtn = Button(this).apply { text = "Connect Lucy"; setOnClickListener { onConnect() } }
        disconnectBtn = Button(this).apply { text = "Disconnect"; setOnClickListener { onDisconnect() } }
        disconnectBtn.isEnabled = false

        backendInput = EditText(this).apply {
            hint = "Backend URL (https://host)"
            setText(prefs.getString("backend", getString(R.string.backend_url)))
        }

        val voice = Button(this).apply { text = "Voice: Off"; setOnClickListener { toggleVoice(this) } }
        val presets = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            for (p in listOf(
                Triple("Normal", 0.0, 1.0),
                Triple("Chipmunk", 12.0, 1.4),
                Triple("Demon", -7.0, 0.7),
                Triple("Robot", 3.0, 0.85),
                Triple("Deep", -5.0, 0.8)
            )) {
                addView(Button(this@MainActivity).apply {
                    text = p.first
                    setOnClickListener {
                        voiceSemi = p.second
                        voiceFormant = p.third
                        mic?.set(p.second, p.third)
                        status.text = "voice preset: ${p.first}"
                    }
                })
            }
        }

        val rtmpUrl = EditText(this).apply { hint = "rtmp://live.twitch.tv/app/KEY" }
        val live = Button(this).apply {
            text = "Go Live (RTMP)"
            setOnClickListener {
                if (streamer == null) {
                    streamer = Streamer(rtmpUrl.text.toString().ifBlank { "rtmp://127.0.0.1/live/test" })
                    streamer!!.onError = { status.text = it }
                    try {
                        streamer!!.start(voiceSemi, voiceFormant)
                        text = "Stop Live"
                        status.text = "streaming"
                    } catch (e: Throwable) {
                        status.text = "live error: ${e.message}"
                        streamer = null
                    }
                } else {
                    streamer!!.stop(); streamer = null; text = "Go Live (RTMP)"
                }
            }
        }

        status = TextView(this).apply { text = "idle" }
        val col = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
            addView(pick); addView(connectBtn); addView(disconnectBtn); addView(backendInput); addView(voice); addView(presets)
            addView(rtmpUrl); addView(live); addView(status)
        }
        setContentView(col)

        FramePumpHolder.pump.init()
    }

    private fun toggleVoice(btn: Button) {
        if (mic == null) {
            monitor = createMonitorTrack()
            mic = MicPitch(voiceSemi, voiceFormant).apply {
                start { pcm -> monitor?.write(pcm, 0, pcm.size) }
            }
            btn.text = "Voice: On"
        } else {
            mic?.stop(); mic = null
            monitor?.release(); monitor = null
            btn.text = "Voice: Off"
        }
    }

    private fun createMonitorTrack(): AudioTrack {
        val fmt = android.media.AudioFormat.Builder()
            .setSampleRate(48000)
            .setChannelMask(android.media.AudioFormat.CHANNEL_OUT_MONO)
            .setEncoding(android.media.AudioFormat.ENCODING_PCM_16BIT)
            .build()
        return AudioTrack.Builder()
            .setAudioAttributes(
                android.media.AudioAttributes.Builder()
                    .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(fmt)
            .build()
            .apply { play() }
    }

    private fun onDisconnect() {
        bridge?.disconnect()
        bridge = null
        connecting = false
        connectBtn.isEnabled = true
        disconnectBtn.isEnabled = false
        status.text = "disconnected"
    }

    private fun onConnect() {
        if (connecting) return
        connecting = true
        connectBtn.isEnabled = false
        disconnectBtn.isEnabled = true
        status.text = "fetching token"
        prefs.edit().putString("backend", backendInput.text.toString().trim()).apply()
        scope.launch {
            try {
                val key = fetchToken()
                status.text = "connecting Lucy"
                bridge = LucyBridge(this@MainActivity)
                bridge!!.connect(
                    apiKey = key,
                    referenceImageB64 = avatarB64,
                    initialPrompt = avatarB64?.let {
                        "Substitute the character in the video with the person in the reference image."
                    } ?: "Change the background to a neon-lit cyberpunk city street at night.",
                    onStatus = { status.text = it }
                )
                connecting = false
                connectBtn.isEnabled = true
            } catch (e: Throwable) {
                connecting = false
                connectBtn.isEnabled = true
                disconnectBtn.isEnabled = false
                status.text = "error: ${e.message}"
            }
        }
    }

    private suspend fun fetchToken(): String = withContext(Dispatchers.IO) {
        val base = backendInput.text.toString().trim().trimEnd('/')
        val url = java.net.URL("$base/api/token")
        val con = url.openConnection() as java.net.HttpURLConnection
        con.requestMethod = "POST"
        con.inputStream.bufferedReader().readText().let {
            org.json.JSONObject(it).getString("apiKey")
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 1 && resultCode == Activity.RESULT_OK) {
            data?.data?.let { uri -> avatarB64 = uriToBase64(uri) }
            status.text = "avatar image set"
        }
    }

    private fun uriToBase64(uri: Uri): String {
        val inp = contentResolver.openInputStream(uri)!!
        val bmp = BitmapFactory.decodeStream(inp)
        val out = ByteArrayOutputStream()
        bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, out)
        return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }

    override fun onDestroy() {
        super.onDestroy()
        bridge?.disconnect()
        bridge = null
        scope.cancel()
        streamer?.stop()
        mic?.stop()
        monitor?.release()
        FramePumpHolder.pump.release()
    }
}
