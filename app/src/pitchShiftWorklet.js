// Overlap-Add pitch + formant shifter. Baseline real-time voice changer.
// Swap for a SoundTouch/WORLD-based engine if you want studio quality.
class PitchShiftProcessor extends AudioWorkletProcessor {
  static get parameterDescriptors() {
    return [
      { name: "semitones", defaultValue: 0, minValue: -24, maxValue: 24, automationRate: "k-rate" },
      { name: "formant", defaultValue: 1, minValue: 0.5, maxValue: 2, automationRate: "k-rate" },
    ];
  }

  constructor() {
    super();
    this.W = 1024;          // window / grain size
    this.Ha = 256;          // analysis hop (4x overlap)
    this.inBuf = new Float32Array(this.W + this.Ha);
    this.outBuf = new Float32Array(16384);
    this.inCount = 0;       // total input samples written
    this.anaCount = 0;      // total analysis samples consumed
    this.synCount = 0;      // total synthesis samples written
    this.outCount = 0;      // total output samples read
    this.hann = new Float32Array(this.W);
    for (let n = 0; n < this.W; n++) {
      this.hann[n] = 0.5 * (1 - Math.cos((2 * Math.PI * n) / (this.W - 1)));
    }
  }

  process(inputs, outputs, params) {
    const input = inputs[0];
    const output = outputs[0];
    if (!input || input.length === 0) return true;

    const semitones = params.semitones[0];
    const formant = params.formant[0];
    const pitchRatio = Math.pow(2, semitones / 12);
    const Hs = Math.max(1, Math.round(this.Ha * pitchRatio));
    const ch = Math.min(input.length, output.length);
    const block = 128;

    for (let i = 0; i < block; i++) {
      for (let c = 0; c < ch; c++) {
        const s = input[c][i] || 0;
        // ingest
        this.inBuf[this.inCount % this.inBuf.length] = s;
        // emit
        if (this.outCount < this.synCount) {
          output[c][i] = this.outBuf[this.outCount % this.outBuf.length];
        } else {
          output[c][i] = 0; // underrun (pitch-down) — brief silence
        }
      }
      this.inCount++;
      this.outCount++;

      while (this.inCount - this.anaCount >= this.Ha) {
        this.synthesisGrain(this.anaCount, pitchRatio, formant, Hs);
        this.anaCount += this.Ha;
      }
    }
    return true;
  }

  synthesisGrain(anaBase, pitchRatio, formant, Hs) {
    const grain = new Float32Array(this.W);
    // analysis: pull windowed grain from input, apply formant resample
    for (let n = 0; n < this.W; n++) {
      const srcF = n * formant;
      const idx = (anaBase + Math.floor(srcF)) % this.inBuf.length;
      const w = this.hann[n];
      grain[n] = (srcF < this.W ? this.inBuf[idx] : 0) * w;
    }
    // synthesis: overlap-add with hann window at syn position
    for (let n = 0; n < this.W; n++) {
      const pos = (this.synCount + n) % this.outBuf.length;
      this.outBuf[pos] += grain[n] * this.hann[n];
    }
    this.synCount += Hs;
  }
}

registerProcessor("pitch-shift-processor", PitchShiftProcessor);
