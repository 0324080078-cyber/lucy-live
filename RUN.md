# RUN.md — Lucy Live (deploy & run)

Lucy Live turns the **Decart Lucy** realtime avatar (character swap + lip sync + body
movement) into a **virtual camera** on a rooted Android phone, with a real-time voice
changer, RTMP live streaming, and a browser/phone PWA. This guide covers building the
artifacts and running the whole stack end-to-end.

```
 Decart Lucy (cloud)                 Phone (rooted + LSPosed)
 ┌──────────────┐   webrtc/HTTPS    ┌────────────────────────────┐
 │ Lucy backend │◀─────────────────▶│ Lucy app (com.zeypher.     │
 │ (GHCR image) │  /api/token + RTP │   lucycam)                 │
 │              │                    │  • LucyBridge (Decart SDK) │
 │ RTMP relay ──┼──▶ rtmp://...     │  • FramePump → AppAshmem   │
 │ (ffmpeg)     │                    │  • VcamService (AIDL)      │
 └──────────────┘                    └────────────┬───────────────┘
                                                  │ AIDL getBuffer()
                                                  ▼
                                         FakeCamera module
                                         (com.zeypher.fakecam, in
                                          system_server) renders
                                          lucy_vcam → any camera app
```

---

## 1. Build the artifacts (CI)

Local Android builds are not supported (CI-only toolchain). Push to `main` and let
GitHub Actions build everything:

```bash
git add -A
git commit -m "…"
git push origin main
```

Three jobs run in `.github/workflows/build.yml`:

| Job               | Output (artifact)                         | Notes                                  |
|-------------------|-------------------------------------------|----------------------------------------|
| `android`         | `lucy-live-android`                       | Lucy app APK + FakeCamera (LSPosed) APK + `lucy-vcam-magisk.zip` |
| `pwa`             | `app/dist`                                | Web PWA (`tsc && vite build`)          |
| `publish-backend` | `ghcr.io/0324080078-cyber/lucy-live-backend:latest` | Docker image (PWA + backend) |

Download the `lucy-live-android` artifact and unzip:

```
android-virtual-camera/app/build/outputs/apk/debug/app-debug.apk        → Lucy app
android-virtual-camera/magisk/fakecam/app/build/outputs/apk/debug/app-debug.apk → FakeCamera module
android-virtual-camera/magisk/lucy-vcam-magisk.zip                     → Magisk module (optional)
```

> If the **`pwa`** job fails, it is almost always `tsc` type errors in `app/src`.
> Re-run `npm install && npm run build` locally to see them, fix, and push.
> The build script is `tsc && vite build` (noEmit type-check + bundle).

---

## 2. Deploy the backend (the Decart bridge + relay)

The backend:
- serves `/api/token` (returns a Decart API key to the Lucy app — the key lives only on the server),
- serves the PWA from `PWA_DIR` when present,
- relays browser WebM → `ffmpeg` → RTMP at `/stream`.

### 2a. Run the published image (recommended)

```bash
docker pull ghcr.io/0324080078-cyber/lucy-live-backend:latest

docker run -d --name lucy-live \
  -p 8080:8080 \
  -e DECART_API_KEY="<your Decart API key>" \
  -e RTMP_URL="rtmp://live.twitch.tv/app/<stream_key>" \
  -e PWA_DIR=/app/pwa \
  ghcr.io/0324080078-cyber/lucy-live-backend:latest
```

Then open `http://<host>:8080/` for the PWA, and point the Android app's
**Backend URL** field at `http://<host>:8080`.

> `RTMP_URL` is optional; without it `/stream` refuses to relay but the app still works.

### 2b. Run locally (dev)

```bash
cd backend
npm install
export DECART_API_KEY=...   # required
export RTMP_URL=...         # optional
node server.js
```

The PWA is served only if `PWA_DIR` points at a built `app/dist` (default
`../../app/dist`). For PWA dev use `cd app && npm run dev`.

### 2c. One-shot hosting (Fly / Render)

`backend/fly.toml` and `backend/render.yaml` are included. Set the
`DECART_API_KEY` / `RTMP_URL` secrets in the platform dashboard, then deploy.

---

## 3. Set up the phone (rooted + LSPosed)

Requirements: Android 8+ (minSdk 26), **Magisk** + **LSPosed** installed,
camera apps that enumerate virtual cameras (most stock apps do).

1. Install the **Lucy app** APK:
   `adb install app-debug.apk` (package `com.zeypher.lucycam`).
2. Install the **FakeCamera (LSPosed) module** APK:
   `adb install fakecam-app-debug.apk` (package `com.zeypher.fakecam`).
3. Open **LSPosed Manager** → Modules → enable **FakeCamera**.
4. In the module's scope, make sure **System Framework** (and any camera app you
   want to redirect) is ticked.
5. Reboot.
6. (Optional) For non-LSPosed setups, flash `lucy-vcam-magisk.zip` via Magisk.

> The FakeCamera module hooks `CameraManager` in `android` (system_server) and
> exposes a camera whose id/text contains `lucy_vcam`. Open any camera app
> (e.g. the stock camera, WhatsApp, Zoom, OBS) and select the **Lucy** camera.

---

## 4. Run end-to-end

1. **Start the backend** (step 2) so `/api/token` is reachable.
2. **Open the Lucy app** on the phone.
   - Enter the **Backend URL** (e.g. `http://192.168.x.x:8080`).
   - Pick an avatar / reference image (character swap).
   - Press **Connect Lucy**. This starts a Decart Lucy session; incoming video
     frames are written into an ashmem ring buffer (`AppAshmem` → `FramePump`).
3. **Open a camera app** → choose the **Lucy / lucy_vcam** camera. The avatar
   video now appears as a live camera feed.
4. **Voice changer**: the app captures mic → `MicPitch` (pitch shift) →
   `AudioTrack` (monitor) and can feed the RTMP/stream pipeline.
5. **Go live**: from the PWA (or any client) open a WebSocket to
   `ws://<host>:8080/stream` and send MediaRecorder WebM chunks; the backend
   pipes them through `ffmpeg` to `RTMP_URL`.

---

## 5. Troubleshooting

**No `lucy_vcam` camera appears**
- Confirm LSPosed module is enabled + scoped to System Framework, and the phone rebooted.
- Open the Lucy app **first** (it must be running so `AppAshmem` exists and
  `VcamService` is bindable). `VcamService` is created on first `bindService`.
- Check logcat: `adb logcat | grep -iE "FakeCamera|VcamBuffer|VcamService|LucyCam"`.

**AIDL `getBuffer()` returns no fd / module shows black**
- The module binds to `com.zeypher.lucycam` action `com.zeypher.lucycam.VCAM_BUFFER`.
  Ensure the Lucy app package is exactly `com.zeypher.lucycam`.
- `AppAshmem.fd()` uses a hidden `@hide` `SharedMemory.getFileDescriptor()` via
  reflection + `VMRuntime.setHiddenApiExemptions`. This only works on a rooted /
  LSPosed device (hidden-API restriction bypassed). On a non-rooted device it fails.

**Frames are stale / tearing**
- `AppAshmem` uses a 2-slot ring (header at offset 0, slot0 at 1024, slot1 at
  `1024+PLANE`, `PLANE=1280*720*3/2`). The module reads the slot written in
  `OFF_SEQ` only after `OFF_FRAME` increments. If you change dimensions, update
  `WIDTH/HEIGHT` in both `AppAshmem`/`FramePump` (app) and `AshmemReader`
  (module) together.

**Decart connect fails**
- Verify `DECART_API_KEY` on the backend and that `/api/token` returns
  `{apiKey: "..."}`. The app uses the real Decart Android SDK
  (`com.github.DecartAI:decart-android:0.2.0`); model is `RealtimeModels.LUCY_2_RT`.

**PWA build fails in CI (`pwa` job)**
- Run `cd app && npm install && npm run build` locally; fix `tsc` errors; push.

**RTMP never connects**
- Set `RTMP_URL` on the backend container. Without it, `/stream` closes with
  "RTMP_URL not configured".
