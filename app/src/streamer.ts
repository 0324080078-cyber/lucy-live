// Live relay: MediaRecorder on the combined stream -> WebSocket chunks ->
// backend ffmpeg -> RTMP. Returns controls to start/stop the broadcast.

export interface LiveSession {
  stop(): void;
}

export async function startLive(
  combined: MediaStream,
  backend: string
): Promise<LiveSession> {
  const ws = new WebSocket(`${backend.replace(/^http/, "ws")}/stream`);
  await new Promise<void>((res, rej) => {
    ws.onopen = () => res();
    ws.onerror = () => rej(new Error("relay ws failed"));
  });

  const mime = pickMime();
  const rec = new MediaRecorder(combined, {
    mimeType: mime,
    videoBitsPerSecond: 2_500_000,
    audioBitsPerSecond: 128_000,
  });
  rec.ondataavailable = (e) => {
    if (e.data.size > 0 && ws.readyState === WebSocket.OPEN) ws.send(e.data);
  };
  rec.start(200); // chunk every 200ms

  return {
    stop() {
      try { rec.stop(); } catch {}
      try { ws.close(); } catch {}
    },
  };
}

function pickMime(): string {
  const candidates = [
    "video/webm;codecs=vp8,opus",
    "video/webm;codecs=vp9,opus",
    "video/webm",
  ];
  for (const c of candidates) {
    if (typeof MediaRecorder !== "undefined" && MediaRecorder.isTypeSupported(c)) return c;
  }
  return "";
}
