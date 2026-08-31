import express from "express";
import { createServer } from "http";
import { WebSocketServer } from "ws";
import { createDecartClient } from "@decartai/sdk";
import { spawn } from "child_process";
import path from "path";
import { existsSync } from "fs";
import { fileURLToPath } from "url";

const __dirname = path.dirname(fileURLToPath(import.meta.url));

const PORT = process.env.PORT || 8080;
const DECART_API_KEY = process.env.DECART_API_KEY;
const RTMP_URL = process.env.RTMP_URL; // e.g. rtmp://live.twitch.tv/app/KEY

if (!DECART_API_KEY) {
  console.error("[fatal] DECART_API_KEY not set");
  process.exit(1);
}
if (!RTMP_URL) {
  console.warn("[warn] RTMP_URL not set — /stream will refuse to relay");
}

const app = express();
app.use(express.json());

// Allow the mobile PWA (served from another LAN origin) to fetch its token.
app.use((req, res, next) => {
  res.setHeader("Access-Control-Allow-Origin", "*");
  res.setHeader("Access-Control-Allow-Headers", "Content-Type");
  res.setHeader("Access-Control-Allow-Methods", "GET,POST,OPTIONS");
  if (req.method === "OPTIONS") return res.sendStatus(204);
  next();
});

// --- Ephemeral Decart token (never ship the real key to the client) ---
app.post("/api/token", async (_req, res) => {
  try {
    const client = createDecartClient({ apiKey: DECART_API_KEY });
    const token = await client.tokens.create();
    res.json({ apiKey: token.apiKey });
  } catch (err) {
    console.error("[token] error", err);
    res.status(500).json({ error: String(err) });
  }
});

// --- Live relay: browser MediaRecorder webm chunks -> ffmpeg -> RTMP ---
const server = createServer(app);
const wss = new WebSocketServer({ server, path: "/stream" });

wss.on("connection", (ws) => {
  console.log("[stream] client connected");
  if (!RTMP_URL) {
    ws.close(1011, "RTMP_URL not configured on server");
    return;
  }

  const ffmpeg = spawn("ffmpeg", [
    "-fflags", "+nobuffer",
    "-i", "pipe:0",
    "-c:v", "libx264",
    "-preset", "veryfast",
    "-pix_fmt", "yuv420p",
    "-g", "60",
    "-c:a", "aac",
    "-b:a", "128k",
    "-ar", "44100",
    "-f", "flv",
    RTMP_URL,
  ], { stdio: ["pipe", "pipe", "pipe"] });

  ffmpeg.stderr.on("data", (d) => process.stderr.write(`[ffmpeg] ${d}`));
  ffmpeg.on("exit", (code) => console.log(`[ffmpeg] exited ${code}`));

  ws.on("message", (chunk) => {
    if (ffmpeg.stdin.writable) ffmpeg.stdin.write(chunk);
  });
  ws.on("close", () => {
    console.log("[stream] client disconnected");
    try { ffmpeg.stdin.end(); } catch {}
    setTimeout(() => ffmpeg.kill("SIGKILL"), 2000);
  });
  ws.on("error", (e) => console.error("[ws] error", e));
});

// Optionally serve a prebuilt PWA from PWA_DIR (see HOSTING.md). The Android
// app only needs /api/token, so this is skipped when the dir is absent.
const PWA_DIR = path.resolve(process.env.PWA_DIR || path.join(__dirname, "../../app/dist"));
if (existsSync(PWA_DIR)) {
  console.log(`[static] serving PWA from ${PWA_DIR}`);
  app.use(express.static(PWA_DIR));
  app.get("*", (_req, res) => res.sendFile(path.join(PWA_DIR, "index.html")));
}

server.listen(PORT, () => {
  console.log(`Lucy Live backend on :${PORT}`);
});
