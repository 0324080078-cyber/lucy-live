import { useEffect, useRef, useState } from "react";
import { connectLucy, LucyHandle } from "./lucy";
import { createVoiceChanger, VoiceChanger } from "./voiceChanger";
import { startLive, LiveSession } from "./streamer";

// Empty string => same-origin (used when the backend serves this PWA).
// Set VITE_BACKEND to the backend URL when hosting the PWA separately.
const BACKEND = import.meta.env.VITE_BACKEND ?? "";

const PRESETS: Record<string, { semitones: number; formant: number }> = {
  Normal: { semitones: 0, formant: 1.0 },
  Chipmunk: { semitones: 12, formant: 1.4 },
  Demon: { semitones: -7, formant: 0.7 },
  Robot: { semitones: 3, formant: 0.85 },
  Deep: { semitones: -5, formant: 0.8 },
  Helium: { semitones: 9, formant: 1.3 },
};

export function App() {
  const camRef = useRef<HTMLVideoElement>(null);
  const outRef = useRef<HTMLVideoElement>(null);
  const rawStream = useRef<MediaStream | null>(null);
  const lucyVideo = useRef<MediaStreamTrack | null>(null);
  const voice = useRef<VoiceChanger | null>(null);
  const lucy = useRef<LucyHandle | null>(null);
  const live = useRef<LiveSession | null>(null);
  const combined = useRef<MediaStream | null>(null);

  const [status, setStatus] = useState("idle");
  const [preset, setPreset] = useState("Normal");
  const [refImg, setRefImg] = useState<File | null>(null);
  const [liveOn, setLiveOn] = useState(false);
  const [ready, setReady] = useState(false);

  useEffect(() => () => {
    rawStream.current?.getTracks().forEach((t) => t.stop());
    lucy.current?.disconnect();
    voice.current?.stop();
    live.current?.stop();
  }, []);

  async function token(): Promise<string> {
    const r = await fetch(`${BACKEND}/api/token`, { method: "POST" });
    const j = await r.json();
    return j.apiKey;
  }

  async function start() {
    try {
      setStatus("capturing camera");
      const stream = await navigator.mediaDevices.getUserMedia({
        video: { facingMode: "user", width: { ideal: 720 }, height: { ideal: 1280 }, frameRate: { ideal: 30 } },
        audio: { echoCancellation: true, noiseSuppression: true },
      });
      rawStream.current = stream;
      if (camRef.current) camRef.current.srcObject = stream;

      setStatus("starting voice changer");
      const p = PRESETS[preset];
      voice.current = await createVoiceChanger(stream, p);

      setStatus("connecting Lucy 2.5");
      const key = await token();
      lucy.current = await connectLucy({
        apiKey: key,
        inputStream: stream,
        referenceImage: refImg || undefined,
        initialPrompt: refImg
          ? "Substitute the character in the video with the person in the reference image."
          : "Change the background to a neon-lit cyberpunk city street at night.",
        onRemoteStream: (s) => {
          lucyVideo.current = s.getVideoTracks()[0];
          rebuildCombined();
        },
      });

      setStatus("live — move and talk");
    } catch (e) {
      setStatus("error: " + (e as Error).message);
    }
  }

  function rebuildCombined() {
    if (!lucyVideo.current || !voice.current) return;
    const c = new MediaStream();
    c.addTrack(lucyVideo.current);
    c.addTrack(voice.current.track);
    combined.current = c;
    setReady(true);
    if (outRef.current) outRef.current.srcObject = c;
  }

  function applyPreset(name: string) {
    setPreset(name);
    const p = PRESETS[name];
    voice.current?.set(p.semitones, p.formant);
  }

  async function goLive() {
    if (!combined.current) return;
    setStatus("going live");
    live.current = await startLive(combined.current, BACKEND);
    setLiveOn(true);
    setStatus("ON AIR");
  }

  function stopLive() {
    live.current?.stop();
    live.current = null;
    setLiveOn(false);
    setStatus("live — move and talk");
  }

  return (
    <div className="app">
      <h1>Lucy Live</h1>
      <p className="status">{status}</p>

      <div className="stage">
        <video ref={camRef} muted playsInline autoPlay className="cam" />
        <video ref={outRef} muted playsInline autoPlay className="out" />
      </div>

      <label className="row">
        Avatar image (full-body front shot)
        <input
          type="file"
          accept="image/*"
          onChange={(e) => setRefImg(e.target.files?.[0] ?? null)}
        />
      </label>

      <div className="presets">
        {Object.keys(PRESETS).map((n) => (
          <button
            key={n}
            className={n === preset ? "on" : ""}
            onClick={() => applyPreset(n)}
          >
            {n}
          </button>
        ))}
      </div>

      <div className="actions">
        <button onClick={start} disabled={status !== "idle" && !status.startsWith("error")}>
          Connect Lucy
        </button>
        {!liveOn ? (
          <button className="live" onClick={goLive} disabled={!ready}>
            Go Live
          </button>
        ) : (
          <button className="live on" onClick={stopLive}>
            Stop
          </button>
        )}
      </div>
    </div>
  );
}
