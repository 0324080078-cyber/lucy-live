# Lucy Live — Android Virtual Camera Bridge (rooted)

Makes the Lucy 2.5 avatar show up as a **real, selectable camera** (`lucy_vcam`) in other
apps (TikTok, Instagram, WhatsApp, Zoom, Meet, etc.) on **rooted Android** with LSPosed.

## Install order (do these in this exact sequence)

1. **Reboot to recovery / Magisk** and flash `lucy-vcam-magisk.zip` (this zip). Reboot.
   - This just ships the module metadata + sepolicy hook point; the real injection is the LSPosed module below.
2. **Install the LSPosed module APK**: it's inside this zip at `fakecam/lucy-fakecam.apk`.
   Copy it off, install it, then open **LSPosed Manager → Modules**, tick **Lucy FakeCamera**, and tick **System Framework** (it hooks `android`). Reboot.
3. **Install the Lucy Live app** (`app-debug.apk` from the CI `lucy-live-android` artifact, or build `app/`). Grant **Camera + Microphone** when prompted.
4. **Connect Lucy**: open the app, set your Decart backend/token, pick an avatar image, tap **Connect Lucy**. Keep it running.
5. **Use the camera**: in TikTok / WhatsApp / Zoom / Meet / Instagram, open the camera picker and choose **lucy_vcam** (shown as "LucyVCam"). You should see the avatar, not the lens.

> If the camera is black: confirm the Lucy Live app is *connected* (step 4) and that the LSPosed module is ticked + rebooted (step 2). The module binds to the app's Ashmem service, so the app must be running first.

## How it works

```
Lucy 2.5 (Decart Android SDK)
   │  remote VideoTrack (avatar, lip-synced, body-moving)
   ▼
FramePump (VideoSink)  →  I420 planes packed straight into Ashmem (no color conversion)
   ▼
Ashmem ring buffer "LUCYVCAM" (I420)   ← exposed to system via AIDL service (IVcamBuffer)
   ▼
LSPosed module (FakeCamera)     ← runs in system_server
   • hooks CameraManager.getCameraIdList → adds "lucy_vcam"
   • hooks openCamera("lucy_vcam")  → opens front cam, tags instance
   • createCaptureSession → client surfaces get an EGL renderer fed by Ashmem
   • VcamRenderer uploads Y/U/V as GL_LUMINANCE textures, YUV→RGB in the shader (GPU)
   ▼
Any app that opens "lucy_vcam" sees the avatar instead of the lens.
```

The app does **zero** color math now — it writes WebRTC's native I420 planes straight to
shared memory. The YUV→RGB conversion runs on the GPU in the module's fragment shader.

## Parts

- `app/` — the Lucy Live companion app (Kotlin). Runs Lucy, pumps frames to Ashmem.
- `magisk/fakecam/` — the LSPosed module that injects the virtual camera (multi-module:
  `app/` = the module, `xposedapi/` = vendored Xposed API stub, `compileOnly`, not packaged).
- `magisk/` — Magisk module packaging (`module.prop`, `customize.sh`, `service.sh`).

## Build

The whole thing builds in CI — push to GitHub and run **Actions → Build Lucy Live**
(see `.github/workflows/build.yml`). It produces three artifacts:

- `app-debug.apk` — the Lucy Live companion app.
- `fakecam-debug.apk` (under `lucy-live-android`) — the LSPosed module.
- `lucy-vcam-magisk.zip` — flashable Magisk module (with the module APK inside).

### Local build (no wrapper)

This repo intentionally ships **no Gradle wrapper** — use a Gradle 8.9 + Android SDK
(cmdline-tools, `platforms;android-34`, `build-tools;34.0.0`) install:

```bash
# Companion app (needs the Decart Android SDK + rtmp lib from JitPack → network)
gradle -p app assembleDebug

# LSPosed module (multi-module: app + vendored xposedapi stub)
gradle -p magisk/fakecam assembleDebug

# Magisk zip
cd magisk && ./pack.sh
```

> The companion app pulls `com.github.DecartAI:decart-android:0.2.0` and
> `com.github.pedroSG94:rtmp-rtsp-stream-client-java:2.5.0` from JitPack, so the first
> build needs network access.

## Runtime

1. Flash the Magisk module, install + activate the LSPosed module (reboot).
2. Install the Lucy Live app, grant Camera + Mic.
3. Open it, point `VITE_BACKEND`/token at your Decart backend, pick avatar image, **Connect Lucy**.
4. In TikTok/WhatsApp/Zoom, pick the camera named **LucyVCam** (or "lucy_vcam").

## Features in this app

- **Virtual camera** (`lucy_vcam`): Lucy avatar as a system-selectable camera for any app
  (TikTok/WhatsApp/Zoom/Meet/Instagram). Root + LSPosed.
- **Real-time voice changer** (`MicPitch.kt`): mic → OLA pitch/formant shift → monitor + PCM tap.
- **Live RTMP streaming** (`Streamer.kt`): avatar video (I420→EGL→H.264) + voice-changed audio
  (AAC) pushed straight to an RTMP ingest, via `rtmp-rtsp-stream-client-java`.

## Caveats (read before shipping)

- The virtual-camera injection hooks every `openCamera`/`createCaptureSession` overload and
  diverts the client's output Surfaces to an EGL renderer fed by the Ashmem I420 buffer; the
  real front camera only drives a throwaway surface to keep the session valid. This covers the
  Camera2 preview/capture path used by virtually all video-call and social apps. A few apps that
  talk to the camera HAL directly with private formats would instead need the **HAL shim** —
  see `magisk/HAL_SHIM.md`.
- **External SDK shapes are the only unverified part.** The companion app talks to two libraries
  whose exact class/method names could not be checked offline and must be confirmed against the
  version you install (the JS SDK and backend were verified and match):
  - Decart Android SDK (`com.github.DecartAI:decart-android:0.2.0`) — used in
    `app/src/main/java/com/zeypher/lucycam/LucyBridge.kt`
    (`RealtimeModels.LUCY_2_5`, `DecartClient`, `DecartClientConfig`, `ConnectOptions`,
    `InitialPrompt`, `realtime.initialize/connect/setImage`). If your SDK version differs,
    adjust those symbols there; the rest of the app does not depend on them.
  - `rtmp-rtsp-stream-client-java:2.5.0` — used in `Streamer.kt`
    (`VideoEncoder`/`AudioEncoder`/`RtmpSender`, `prepareVideo`/`inputSurface`/`prepareAudio`/
    `setVideoEncoder`/`setAudioEncoder`/`connect`/`startStream`/`inputPCMData`/`stopStream`).
    Pin the matching release and fix names in `Streamer.kt` if needed.
- Resolution is fixed at the Lucy output (720×1280 portrait). Change in `AshmemBuffer` (app +
  module must match).
- The voice-changer monitor plays to the speaker; use headphones to avoid feedback. To inject
  the changed voice into a *call's* audio you'd also need a virtual-mic HAL shim (same family as
  the camera shim). The RTMP path includes the changed voice.
