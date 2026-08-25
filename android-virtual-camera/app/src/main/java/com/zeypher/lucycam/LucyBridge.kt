package com.zeypher.lucycam

import ai.decart.sdk.DecartClient
import ai.decart.sdk.DecartClientConfig
import ai.decart.sdk.InitialPrompt
import ai.decart.sdk.RealtimeModels
import ai.decart.sdk.realtime.ConnectOptions
import ai.decart.sdk.realtime.RealTimeClient
import android.content.Context
import kotlinx.coroutines.*

/**
 * Wraps the Decart Android SDK (ai.decart.sdk) for the Lucy realtime avatar.
 * Lucy avatar VideoTrack (remote) -> FramePump (Ashmem) -> virtual camera.
 * API verified against com.github.DecartAI:decart-android:0.2.0.
 */
class LucyBridge(private val context: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var client: DecartClient? = null
    private var realtime: RealTimeClient? = null
    private var sinkAttached = false

    suspend fun connect(
        apiKey: String,
        referenceImageB64: String?,
        initialPrompt: String,
        onStatus: (String) -> Unit
    ) {
        val client = DecartClient(context, DecartClientConfig(apiKey = apiKey))
        this.client = client
        val realtime = client.realtime
        this.realtime = realtime

        // Reference image for character substitution (data URL or http URL),
        // or a raw base64 blob we wrap ourselves.
        val image = referenceImageB64?.let {
            if (it.startsWith("data:") || it.startsWith("http")) it
            else "data:image/jpeg;base64,$it"
        }

        realtime.connect(
            localVideoTrack = null,
            localAudioTrack = null,
            options = ConnectOptions(
                model = RealtimeModels.LUCY_2_RT,
                initialPrompt = InitialPrompt(text = initialPrompt, enhance = true),
                initialImage = image,
                onRemoteVideoTrack = { track ->
                    track.addSink(FramePumpHolder.pump)
                    sinkAttached = true
                    onStatus("avatar live")
                }
            )
        )
    }

    /** Swap the prompt live. */
    fun setPrompt(text: String, enhance: Boolean = true) {
        scope.launch { runCatching { realtime?.setPrompt(text, enhance) } }
    }

    fun disconnect() {
        if (sinkAttached) {
            runCatching { realtime?.disconnect() }
            sinkAttached = false
        }
        runCatching { realtime?.disconnect() }
        runCatching { client?.release() }
        realtime = null
        client = null
    }
}
