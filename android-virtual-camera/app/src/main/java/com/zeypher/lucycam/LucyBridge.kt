package com.zeypher.lucycam

import ai.decart.sdk.DecartClient
import ai.decart.sdk.DecartClientConfig
import ai.decart.sdk.RealtimeModels
import ai.decart.sdk.realtime.ConnectOptions
import ai.decart.sdk.realtime.FacingMode
import ai.decart.sdk.realtime.InitialPrompt
import ai.decart.sdk.realtime.RealTimeClient
import ai.decart.sdk.realtime.RealtimeMediaStream
import android.content.Context
import kotlinx.coroutines.*

/**
 * Wraps the Decart Android SDK (ai.decart.sdk) for Lucy 2.5 realtime.
 * Local camera -> Lucy -> remote avatar VideoTrack -> FramePump (Ashmem).
 * Method/package names verified against com.github.DecartAI:decart-android:0.2.0.
 */
class LucyBridge(private val context: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var client: DecartClient? = null
    private var realtime: RealTimeClient? = null
    private var localStream: RealtimeMediaStream? = null
    private var remoteStream: RealtimeMediaStream? = null

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

        // The SDK owns camera capture + WebRTC plumbing; build the local stream
        // at the model resolution (1280x720 for LUCY_2_5).
        val local = realtime.createLocalVideoStream(
            model = RealtimeModels.LUCY_2_5,
            facing = FacingMode.FRONT
        )
        this.localStream = local

        // Reference image for character substitution (data URL or http URL).
        val image = referenceImageB64?.let {
            if (it.startsWith("data:") || it.startsWith("http")) it
            else "data:image/jpeg;base64,$it"
        }

        realtime.connect(
            options = ConnectOptions(
                model = RealtimeModels.LUCY_2_5,
                initialPrompt = InitialPrompt(text = initialPrompt, enhance = true),
                initialImage = image,
                facing = FacingMode.FRONT,
                onRemoteStream = { stream ->
                    remoteStream = stream
                    stream.videoTrack?.addSink(FramePumpHolder.pump)
                    onStatus("avatar live")
                }
            ),
            localStream = local
        )
    }

    /** Swap the prompt live (suspends until server ack). */
    fun setPrompt(text: String, enhance: Boolean = true) {
        scope.launch { runCatching { realtime?.setPrompt(text, enhance) } }
    }

    fun disconnect() {
        runCatching { remoteStream?.videoTrack?.removeSink(FramePumpHolder.pump) }
        runCatching { realtime?.disconnect() }
        runCatching { localStream?.dispose() }
        runCatching { client?.release() }
        remoteStream = null
        localStream = null
        realtime = null
        client = null
    }
}
