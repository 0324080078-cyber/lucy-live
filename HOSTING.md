# Hosting & Running Lucy Live

This covers getting Lucy Live running on real devices. Nothing is hosted for
you — you supply a host and a Decart API key. There are three moving parts:

1. **Backend** (`backend/`) — mints Decart session tokens (`/api/token`) and relays
   browser captures to RTMP (`/stream`). The Android app only needs `/api/token`.
2. **PWA** (`app/`) — browser UI for the avatar + voice changer + Go Live. Optional
   if you only use the Android app.
3. **Android app** (`android-virtual-camera/`) — the Lucy avatar + virtual camera.

## Prerequisites
- A **Decart API key** (set as `DECART_API_KEY` on the backend).
- A host for the backend: [Fly.io](https://fly.io), [Render](https://render.com),
  any Docker VPS, or just your LAN machine during dev.
- `ffmpeg` on the backend host **only** if you use the in-browser "Go Live" relay
  (the Dockerfile installs it). The Android app's own RTMP "Go Live" does not use it.
- For the **virtual-camera** feature (avatar as a system camera): a rooted phone
  with LSPosed + the Magisk module. The avatar/voice/RTMP features work on a normal
  device without root.

## 1. Deploy the backend
The repo root has a combined `Dockerfile` that builds the PWA **and** the backend
into one image, and serves both from the same origin (so the PWA needs no
`VITE_BACKEND`). Deploy configs at the repo root (`fly.toml`, `render.yaml`) use it.

**Published image (from CI)**
The `publish-backend` workflow pushes the image to GitHub Container Registry on
every push to `main`/`master`:
```
ghcr.io/<your-github-user>/<your-repo>/lucy-live-backend:latest
```
Run it anywhere Docker runs:
```
docker run -p 8080:8080 \
  -e DECART_API_KEY=sk-... -e RTMP_URL=rtmp://live.twitch.tv/app/KEY \
  ghcr.io/<your-github-user>/<your-repo>/lucy-live-backend:latest
```
Fly.io / Render can also pull this image directly instead of building.

**Fly.io** (builds from the root `Dockerfile`)
```
fly launch --no-deploy
fly secrets set DECART_API_KEY=sk-... RTMP_URL=rtmp://live.twitch.tv/app/KEY
fly deploy
# backend URL = https://lucy-live.fly.dev  (serves the PWA too)
```

**Render** (root `render.yaml`)
- New → Web Service → connect repo → Runtime: Docker.
- Add secret env vars `DECART_API_KEY` and `RTMP_URL` in the dashboard.

**API-only image** (no baked-in PWA)
`backend/Dockerfile` + `backend/fly.toml` / `backend/render.yaml` build a smaller
backend-only image; host the PWA separately (step 2) in that case.

Set `RTMP_URL` only if you want the browser "Go Live" relay; the Android app streams
directly and ignores it.

## 2. (Optional) Deploy the PWA separately
If you deployed the **combined** image (step 1), the PWA is already served at the
same host — skip this. Only do this if you run the API-only backend image.

The PWA must know the backend URL at **build time**:

```
cd app
VITE_BACKEND=https://lucy-live.fly.dev npm install && npm run build
# upload app/dist to Netlify / Vercel / GitHub Pages / any static host
```
(Leave `VITE_BACKEND` unset only when the backend serves the PWA itself.)

## 3. Run the Android app on a device
1. Build the APK: push the repo to GitHub and let the CI workflow produce the
   `lucy-live-android` artifact, **or** open `android-virtual-camera/` in Android
   Studio and run/assemble. Also build the Magisk module artifact if using the
   virtual camera.
2. Install the APK (`adb install app-debug.apk`) on the phone.
3. Open the app, enter your **Backend URL** (saved automatically), pick an avatar
   image, tap **Connect Lucy**.
4. Tap **Go Live (RTMP)** with your ingest URL to stream the avatar.
5. Virtual camera (root only): flash the Magisk module + LSPosed, then any
   camera-using app opening `lucy_vcam` gets the avatar.

## Env vars (backend)
| Var | Required | Purpose |
|-----|----------|---------|
| `DECART_API_KEY` | yes | Decart session token minting |
| `RTMP_URL` | no | Browser "Go Live" relay target |
| `PORT` | no | Listen port (default 8080) |
| `PWA_DIR` | no | Serve a built PWA from this dir |
