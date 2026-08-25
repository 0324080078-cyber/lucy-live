// Decart Lucy 2.5 realtime wrapper.
// Sends the camera MediaStream; Lucy returns an edited video stream where the
// character from `referenceImage` is swapped in and tracks body movement + lip sync.

import { createDecartClient, models } from "@decartai/sdk";

export interface LucyHandle {
  realtime: any;
  disconnect(): void;
}

export async function connectLucy(opts: {
  apiKey: string;
  inputStream: MediaStream;
  referenceImage?: File | Blob; // full-body front shot of the avatar
  initialPrompt?: string;
  onRemoteStream: (s: MediaStream) => void;
}): Promise<LucyHandle> {
  const model = models.realtime("lucy-2.5");
  const client = createDecartClient({ apiKey: opts.apiKey });

  const realtime = await client.realtime.connect(opts.inputStream, {
    model,
    mirror: "auto",
    onRemoteStream: opts.onRemoteStream,
    initialState: {
      prompt: {
        text: opts.initialPrompt ?? "Substitute the character in the video with the person in the reference image.",
        enhance: true,
      },
    },
  });

  if (opts.referenceImage) {
    await realtime.set({
      prompt: "Substitute the character in the video with the person in the reference image.",
      image: opts.referenceImage,
      enhance: true,
    });
  }

  return {
    realtime,
    disconnect() {
      try { (realtime as any).disconnect?.(); } catch {}
      try { (client as any).disconnect?.(); } catch {}
    },
  };
}
