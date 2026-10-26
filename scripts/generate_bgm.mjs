import fs from 'fs';
import path from 'path';

// Parameters for high quality, warm, playful acoustic BGM
const SAMPLE_RATE = 22050;
const BPM = 114;
const BEAT_DURATION = 60 / BPM; // ~0.5263s
const BAR_DURATION = BEAT_DURATION * 4; // ~2.105s
const TOTAL_BARS = 8;
const TOTAL_DURATION = BAR_DURATION * TOTAL_BARS; // ~16.84s
const TOTAL_SAMPLES = Math.round(TOTAL_DURATION * SAMPLE_RATE);

const buffer = new Float32Array(TOTAL_SAMPLES);

// Note frequencies (in Hz)
const NOTES = {
    'C2': 65.41, 'D2': 73.42, 'E2': 82.41, 'F2': 87.31, 'G2': 98.00, 'A2': 110.00, 'B2': 123.47,
    'C3': 130.81, 'D3': 146.83, 'E3': 164.81, 'F3': 174.61, 'G3': 196.00, 'A3': 220.00, 'B3': 246.94,
    'C4': 261.63, 'D4': 293.66, 'E4': 329.63, 'F4': 349.23, 'G4': 392.00, 'A4': 440.00, 'B4': 493.88,
    'C5': 523.25, 'D5': 587.33, 'E5': 659.25, 'F5': 698.46, 'G5': 783.99, 'A5': 880.00, 'B5': 987.77,
    'C6': 1046.50
};

// Helper: add an instrument note with wrapped looping (reverb/tail wraps around to beginning)
function addNote(freq, startTime, duration, volume, type = 'marimba') {
    const numSamples = Math.round(duration * SAMPLE_RATE);
    const startSample = Math.round(startTime * SAMPLE_RATE);

    for (let i = 0; i < numSamples; i++) {
        const t = i / SAMPLE_RATE;
        let env = 0;
        let val = 0;

        if (type === 'marimba') {
            // Warm marimba/wood pluck: fast attack, exponential decay + warm woody overtone
            env = Math.exp(-t * 5.5) * (1 - Math.exp(-t * 120));
            val = Math.sin(2 * Math.PI * freq * t) * 0.7
                + Math.sin(2 * Math.PI * freq * 2.0 * t) * 0.25 * Math.exp(-t * 10)
                + Math.sin(2 * Math.PI * freq * 3.01 * t) * 0.12 * Math.exp(-t * 18);
        } else if (type === 'bass') {
            // Warm acoustic plucked bass: rich 1st & 2nd harmonic, rounded filter
            env = Math.exp(-t * 3.2) * (1 - Math.exp(-t * 80));
            val = Math.sin(2 * Math.PI * freq * t) * 0.8
                + Math.sin(2 * Math.PI * freq * 2.0 * t) * 0.25 * Math.exp(-t * 5)
                + Math.sin(2 * Math.PI * freq * 0.5 * t) * 0.15;
        } else if (type === 'bell') {
            // Sweet music-box / kalimba bell lead
            env = Math.exp(-t * 3.8) * (1 - Math.exp(-t * 150));
            val = Math.sin(2 * Math.PI * freq * t) * 0.65
                + Math.sin(2 * Math.PI * freq * 2.76 * t) * 0.22 * Math.exp(-t * 6)
                + Math.sin(2 * Math.PI * freq * 5.4 * t) * 0.08 * Math.exp(-t * 12);
        } else if (type === 'tap') {
            // Gentle acoustic wooden percussion tap
            env = Math.exp(-t * 35);
            val = (Math.sin(2 * Math.PI * 320 * t) + (Math.random() * 2 - 1) * 0.4);
        }

        const sampleIndex = (startSample + i) % TOTAL_SAMPLES;
        buffer[sampleIndex] += val * env * volume;
    }
}

// 8-bar chord progression:
// Bar 0: Cmaj7   (C3, G3, B3, E4)
// Bar 1: Am7     (A2, G3, C4, E4)
// Bar 2: Dm7     (D3, F3, A3, C4)
// Bar 3: G7      (G2, F3, B3, D4)
// Bar 4: Em7     (E3, G3, B3, D4)
// Bar 5: Am7     (A2, E3, A3, C4)
// Bar 6: Dm7     (D3, F3, A3, D4)
// Bar 7: G7sus-G (G2, F3, C4, D4 -> G2, F3, B3, D4)

const chords = [
    { bass: 'C3', notes: ['C4', 'E4', 'G4', 'B4'] },
    { bass: 'A2', notes: ['C4', 'E4', 'G4', 'A4'] },
    { bass: 'D3', notes: ['C4', 'D4', 'F4', 'A4'] },
    { bass: 'G2', notes: ['B3', 'D4', 'F4', 'G4'] },
    { bass: 'E3', notes: ['B3', 'D4', 'E4', 'G4'] },
    { bass: 'A2', notes: ['A3', 'C4', 'E4', 'G4'] },
    { bass: 'D3', notes: ['A3', 'C4', 'D4', 'F4'] },
    { bass: 'G2', notes: ['B3', 'D4', 'F4', 'A4'] },
];

for (let bar = 0; bar < TOTAL_BARS; bar++) {
    const barTime = bar * BAR_DURATION;
    const chord = chords[bar];

    // Bassline (bouncy roots on beat 0, syncopated 5th/octave on beat 2.5)
    addNote(NOTES[chord.bass], barTime + 0 * BEAT_DURATION, 1.2, 0.48, 'bass');
    addNote(NOTES[chord.bass] * 1.5, barTime + 2.5 * BEAT_DURATION, 0.8, 0.35, 'bass');
    addNote(NOTES[chord.bass], barTime + 3.5 * BEAT_DURATION, 0.6, 0.32, 'bass');

    // Marimba chord arpeggios (syncopated, light, bouncy)
    const [n1, n2, n3, n4] = chord.notes;
    addNote(NOTES[n1], barTime + 0.5 * BEAT_DURATION, 0.7, 0.22, 'marimba');
    addNote(NOTES[n2], barTime + 1.0 * BEAT_DURATION, 0.7, 0.22, 'marimba');
    addNote(NOTES[n3], barTime + 1.5 * BEAT_DURATION, 0.7, 0.24, 'marimba');
    addNote(NOTES[n4], barTime + 2.0 * BEAT_DURATION, 0.7, 0.26, 'marimba');
    addNote(NOTES[n2], barTime + 2.75 * BEAT_DURATION, 0.6, 0.20, 'marimba');
    addNote(NOTES[n3], barTime + 3.25 * BEAT_DURATION, 0.6, 0.22, 'marimba');

    // Subtle rhythmic wooden taps on beats 2 and 4
    addNote(240, barTime + 1.0 * BEAT_DURATION, 0.08, 0.12, 'tap');
    addNote(240, barTime + 3.0 * BEAT_DURATION, 0.08, 0.12, 'tap');
}

// Playful Melody (Kalimba / Bell) - Doodle theme
// Bar 0: E5 - G5 - A5
// Bar 1: G5 - E5 - D5 - C5
// Bar 2: D5 - E5 - F5 - A5
// Bar 3: G5 - D5 - B4
// Bar 4: B4 - D5 - E5 - G5
// Bar 5: A5 - C6 - B5 - A5
// Bar 6: F5 - A5 - G5 - F5
// Bar 7: D5 - E5 - D5 (resolves into Bar 0)
const melody = [
    // Bar 0
    { note: 'E5', time: 0.0, dur: 0.8, vol: 0.35 },
    { note: 'G5', time: 1.0, dur: 0.8, vol: 0.35 },
    { note: 'A5', time: 2.0, dur: 1.4, vol: 0.40 },

    // Bar 1
    { note: 'G5', time: 4.0, dur: 0.6, vol: 0.35 },
    { note: 'E5', time: 4.75, dur: 0.6, vol: 0.32 },
    { note: 'D5', time: 5.5, dur: 0.6, vol: 0.30 },
    { note: 'C5', time: 6.25, dur: 1.2, vol: 0.38 },

    // Bar 2
    { note: 'D5', time: 8.0, dur: 0.6, vol: 0.32 },
    { note: 'E5', time: 8.75, dur: 0.6, vol: 0.32 },
    { note: 'F5', time: 9.5, dur: 0.8, vol: 0.35 },
    { note: 'A5', time: 10.5, dur: 1.2, vol: 0.40 },

    // Bar 3
    { note: 'G5', time: 12.0, dur: 1.2, vol: 0.38 },
    { note: 'D5', time: 13.5, dur: 0.8, vol: 0.32 },
    { note: 'B4', time: 14.5, dur: 1.6, vol: 0.35 },

    // Bar 4
    { note: 'B4', time: 16.0, dur: 0.6, vol: 0.30 },
    { note: 'D5', time: 16.75, dur: 0.6, vol: 0.32 },
    { note: 'E5', time: 17.5, dur: 0.8, vol: 0.35 },
    { note: 'G5', time: 18.5, dur: 1.4, vol: 0.40 },

    // Bar 5
    { note: 'A5', time: 20.0, dur: 0.6, vol: 0.38 },
    { note: 'C6', time: 20.75, dur: 0.8, vol: 0.42 },
    { note: 'B5', time: 21.75, dur: 0.6, vol: 0.36 },
    { note: 'A5', time: 22.5, dur: 1.2, vol: 0.38 },

    // Bar 6
    { note: 'F5', time: 24.0, dur: 0.8, vol: 0.35 },
    { note: 'A5', time: 25.0, dur: 0.8, vol: 0.38 },
    { note: 'G5', time: 26.0, dur: 0.6, vol: 0.35 },
    { note: 'F5', time: 26.75, dur: 1.0, vol: 0.32 },

    // Bar 7
    { note: 'D5', time: 28.0, dur: 0.8, vol: 0.34 },
    { note: 'E5', time: 29.0, dur: 0.8, vol: 0.36 },
    { note: 'D5', time: 30.0, dur: 1.8, vol: 0.35 },
];

for (const m of melody) {
    // Map beat time to bar time
    const t = (m.time / 4) * BAR_DURATION;
    addNote(NOTES[m.note], t, m.dur * BEAT_DURATION, m.vol, 'bell');
}

// Soft peak normalization to prevent clipping
let maxPeak = 0;
for (let i = 0; i < TOTAL_SAMPLES; i++) {
    const abs = Math.abs(buffer[i]);
    if (abs > maxPeak) maxPeak = abs;
}

const targetPeak = 0.78;
const scale = maxPeak > 0 ? targetPeak / maxPeak : 1.0;

// Convert to 16-bit PCM WAV
const pcmBytes = new Int16Array(TOTAL_SAMPLES);
for (let i = 0; i < TOTAL_SAMPLES; i++) {
    const s = Math.max(-1.0, Math.min(1.0, buffer[i] * scale));
    pcmBytes[i] = Math.round(s * 32767);
}

// Build WAV header
const dataSize = TOTAL_SAMPLES * 2;
const fileSize = 36 + dataSize;
const header = Buffer.alloc(44);

header.write('RIFF', 0);
header.writeUInt32LE(fileSize, 4);
header.write('WAVE', 8);
header.write('fmt ', 12);
header.writeUInt32LE(16, 16); // subchunk1 size
header.writeUInt16LE(1, 20);  // PCM format
header.writeUInt16LE(1, 22);  // mono
header.writeUInt32LE(SAMPLE_RATE, 24);
header.writeUInt32LE(SAMPLE_RATE * 2, 28); // byte rate (sampleRate * numChannels * bitsPerSample/8)
header.writeUInt16LE(2, 32);  // block align
header.writeUInt16LE(16, 34); // bits per sample
header.write('data', 36);
header.writeUInt32LE(dataSize, 40);

const pcmBuffer = Buffer.from(pcmBytes.buffer);
const outputBuffer = Buffer.concat([header, pcmBuffer]);

const targetPath = path.resolve('fx-client/src/main/resources/sounds/bgm_lobby.wav');
fs.writeFileSync(targetPath, outputBuffer);

console.log(`Generated: ${targetPath}`);
console.log(`Samples: ${TOTAL_SAMPLES}, Duration: ${TOTAL_DURATION.toFixed(2)}s, Size: ${(outputBuffer.length / 1024).toFixed(1)} KB`);
