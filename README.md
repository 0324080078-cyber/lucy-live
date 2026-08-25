# Lucy Live — realtime avatar + voice changer, streaming to mobile

Turn your phone camera into a live, moving avatar driven by **Lucy 2.5** (Decart AI),
with **real-time lip sync**, **body movement tracking**, and a **real-time voice changer**,
then push the combined feed to any RTMP platform (Twitch / YouTube / TikTok / your own nginx-rtmp).

Runs as a **mobile PWA** — installable on iOS Safari and Android Chrome, no App Store needed.

## Pipeline

```
[phone camera + mic]
      │  video track ─────────────┐
      │  audio track ──► voiceChanger (Web Audio pitch/formant shift)
      │                          │
      ├─► Decart realtime (lucy-2.5)   ← character swap = body + lip sync
      │        returns edited video (avatar moving, lips synced)
      │
      └─► combined = lucyVideo + voiceChangedAudio
                 │
                 ├─► on-screen preview
                 └─► MediaRecorder ─► WebSocket ─► backend ffmpeg ─► RTMP
```

Lucy 2.5's `Motion accuracy` capability already tracks facial expressions, lip sync and
body movement, so feeding it your camera + a reference avatar image gives a live avatar
that moves and talks with you. The voice changer runs locally on the mic before the feed
is combined and pushed.

## Requirements

- Node 18+
- A Decart API key → https://platform.decart.ai/api-keys  (set `DECART_API_KEY`)
- `ffmpeg` on the backend machine (for RTMP muxing)
- An RTMP ingest URL + key for your platform

## Quick start

### 1. Backend (token + relay)

```bash
cd backend
npm install
export DECART_API_KEY="your-key"
export RTMP_URL="rtmp://live.twitch.tv/app/YOUR_STREAM_KEY"
npm start
# token endpoint : http://localhost:8080/api/token
# relay websocket: ws://localhost:8080/stream
```

### 2. Mobile app (PWA)

```bash
cd app
npm install
export VITE_BACKEND="http://<your-lan-ip>:8080"
npm run dev      # open on phone: http://<lan-ip>:5173
# or build + serve as PWA:
npm run build && npm run preview
```

On the phone: allow camera + mic, pick an avatar reference image (full-body front shot),
hit **Connect Lucy**, pick a voice preset, then **Go Live**.

## Voice presets

| Preset   | Semitones | Formant |
|----------|-----------|---------|
| Normal   | 0         | 1.0     |
| Chipmunk | +12       | 1.4     |
| Demon    | -7        | 0.7     |
| Robot    | +3        | 0.85    |
| Deep     | -5        | 0.8     |
| Helium   | +9        | 1.3     |

## Files

```
lucy-live/
  backend/
    server.js        # express: /api/token + /stream (WS -> ffmpeg -> RTMP)
    package.json
  app/
    src/
      lucy.ts            # Decart realtime wrapper (character swap + lip sync)
      voiceChanger.ts    # Web Audio graph + worklet
      pitchShiftWorklet.js
      streamer.ts        # MediaRecorder -> WS relay
      App.tsx            # mobile UI
    index.html
    vite.config.ts
    package.json
```

## Notes

- Lucy needs network; inference runs on Decart's side (no local GPU).
- For true native iOS/Android builds, wrap this PWA in Capacitor — all modules
  (Decart JS SDK, Web Audio, MediaRecorder, WebSocket) work inside a WebView.
- Latency is interactive (~40ms on Lucy's side); the RTMP relay adds a few hundred ms.
