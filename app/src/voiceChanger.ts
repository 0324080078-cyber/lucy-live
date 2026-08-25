// Web Audio voice changer: routes a mic MediaStream through the pitch-shift
// AudioWorklet and returns a new audio track (pitch + formant modified).
import workletUrl from "./pitchShiftWorklet.js?url";

export interface VoiceChanger {
  ctx: AudioContext;
  node: AudioWorkletNode;
  track: MediaStreamTrack;
  set(semitones: number, formant: number): void;
  stop(): void;
}

export async function createVoiceChanger(
  source: MediaStream,
  opts: { semitones?: number; formant?: number } = {}
): Promise<VoiceChanger> {
  const ctx = new (window.AudioContext || (window as any).webkitAudioContext)();
  if (ctx.state === "suspended") await ctx.resume();

  const src = ctx.createMediaStreamSource(source);
  await ctx.audioWorklet.addModule(workletUrl);

  const node = new AudioWorkletNode(ctx, "pitch-shift-processor", {
    numberOfInputs: 1,
    numberOfOutputs: 1,
    outputChannelCount: [2],
  });
  node.parameters.get("semitones")!.value = opts.semitones ?? 0;
  node.parameters.get("formant")!.value = opts.formant ?? 1;

  const dest = ctx.createMediaStreamDestination();
  src.connect(node);
  node.connect(dest);

  return {
    ctx,
    node,
    track: dest.stream.getAudioTracks()[0],
    set(semitones: number, formant: number) {
      node.parameters.get("semitones")!.value = semitones;
      node.parameters.get("formant")!.value = formant;
    },
    stop() {
      src.disconnect();
      node.disconnect();
      dest.disconnect();
      ctx.close();
    },
  };
}
