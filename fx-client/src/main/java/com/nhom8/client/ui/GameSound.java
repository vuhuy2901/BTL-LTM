package com.nhom8.client.ui;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.Clip;
import javax.sound.sampled.FloatControl;
import java.io.ByteArrayInputStream;
import java.net.URL;
import java.util.concurrent.CompletableFuture;

/** Short game cues from bundled CC0 assets, with synthesized fallbacks. */
public final class GameSound {
    private static final float SAMPLE_RATE = 22_050f;
    private static volatile boolean initialized;
    private static Clip tick;
    private static Clip urgentTick;
    private static Clip correct;
    private static Clip correctForRoom;
    private static Clip phaseStart;
    private static Clip timeout;
    private static Clip drawingSubmitted;
    private static Clip paintingRevealed;
    private static Clip wrongGuess;
    private static Clip startTick;
    private static Clip startGo;
    private static volatile boolean enabled = true;

    private GameSound() { }

    public static void playCountdownTick(int secondsLeft) {
        play(secondsLeft <= 3 ? 1 : 0);
    }

    public static void playCorrect() { play(2); }
    public static void playRoomCorrect() { play(3); }
    public static void playPhaseStart() { play(4); }
    public static void playTimeout() { play(5); }
    public static void playDrawingSubmitted() { play(6); }
    public static void playPaintingRevealed() { play(7); }
    public static void playWrongGuess() { play(8); }
    public static void playStartTick() { play(9); }
    public static void playStartGo() { play(10); }
    public static boolean isEnabled() { return enabled; }
    public static void setEnabled(boolean value) { enabled = value; }

    private static void play(int sound) {
        if (!enabled) return;
        CompletableFuture.runAsync(() -> {
            try {
                if (!enabled) return;
                initialize();
                Clip clip = switch (sound) {
                    case 1 -> urgentTick;
                    case 2 -> correct;
                    case 3 -> correctForRoom;
                    case 4 -> phaseStart;
                    case 5 -> timeout;
                    case 6 -> drawingSubmitted;
                    case 7 -> paintingRevealed;
                    case 8 -> wrongGuess;
                    case 9 -> startTick;
                    case 10 -> startGo;
                    default -> tick;
                };
                if (clip != null) {
                    if (clip.isRunning()) clip.stop();
                    clip.setFramePosition(0);
                    clip.start();
                }
            } catch (Throwable ignored) {
                // Audio output is optional; game actions continue if audio is unavailable.
            }
        });
    }

    private static synchronized void initialize() throws Exception {
        if (initialized) return;
        tick = tone(new double[]{1_050}, new int[]{58}, 0.24);
        urgentTick = tone(new double[]{1_420}, new int[]{76}, 0.34);
        correct = resourceClip("/sounds/confirmation_004.wav");
        if (correct == null) correct = tone(new double[]{660, 880, 1_100, 1_320}, new int[]{105, 105, 120, 230}, 0.42);
        correctForRoom = tone(new double[]{740, 990}, new int[]{85, 140}, 0.24);
        phaseStart = tone(new double[]{520, 780}, new int[]{95, 145}, 0.27);
        timeout = tone(new double[]{660, 440, 300}, new int[]{130, 150, 220}, 0.30);
        drawingSubmitted = resourceClip("/sounds/confirmation_003.wav");
        if (drawingSubmitted == null) drawingSubmitted = tone(new double[]{620, 900}, new int[]{85, 150}, 0.25);
        paintingRevealed = resourceClip("/sounds/open_004.wav");
        if (paintingRevealed == null) paintingRevealed = tone(new double[]{520, 760}, new int[]{85, 140}, 0.24);
        wrongGuess = resourceClip("/sounds/error_006.wav");
        if (wrongGuess == null) wrongGuess = tone(new double[]{400, 300}, new int[]{95, 145}, 0.22);
        startTick = resourceClip("/sounds/tick_001.wav");
        if (startTick == null) startTick = tone(new double[]{900}, new int[]{70}, 0.28);
        startGo = resourceClip("/sounds/bong_001.wav");
        if (startGo == null) startGo = tone(new double[]{740, 1100}, new int[]{100, 180}, 0.35);
        initialized = true;
    }

    private static Clip resourceClip(String path) {
        URL resource = GameSound.class.getResource(path);
        if (resource == null) return null;
        try (AudioInputStream input = AudioSystem.getAudioInputStream(resource)) {
            Clip clip = AudioSystem.getClip();
            clip.open(input);
            return clip;
        } catch (Exception error) {
            return null;
        }
    }

    private static Clip tone(double[] frequencies, int[] durationsMs, double volume) throws Exception {
        int totalFrames = 0;
        for (int duration : durationsMs) totalFrames += (int) (SAMPLE_RATE * duration / 1_000.0);
        totalFrames += Math.max(0, frequencies.length - 1) * (int) (SAMPLE_RATE * 0.018);
        byte[] pcm = new byte[totalFrames * 2];
        int frame = 0;
        for (int note = 0; note < frequencies.length; note++) {
            int frames = (int) (SAMPLE_RATE * durationsMs[note] / 1_000.0);
            for (int i = 0; i < frames; i++, frame++) {
                double progress = (double) i / frames;
                double attack = Math.min(1.0, i / (SAMPLE_RATE * 0.008));
                double decay = Math.pow(1.0 - progress, 1.6);
                double envelope = attack * decay;
                double fundamental = Math.sin(2.0 * Math.PI * frequencies[note] * i / SAMPLE_RATE);
                double overtone = Math.sin(4.0 * Math.PI * frequencies[note] * i / SAMPLE_RATE) * 0.18;
                short value = (short) ((fundamental + overtone) * envelope * volume * Short.MAX_VALUE);
                pcm[frame * 2] = (byte) (value & 0xff);
                pcm[frame * 2 + 1] = (byte) ((value >>> 8) & 0xff);
            }
            if (note < frequencies.length - 1) frame += (int) (SAMPLE_RATE * 0.018);
        }

        AudioFormat format = new AudioFormat(SAMPLE_RATE, 16, 1, true, false);
        try (AudioInputStream input = new AudioInputStream(
                new ByteArrayInputStream(pcm), format, totalFrames)) {
            Clip clip = AudioSystem.getClip();
            clip.open(input);
            if (clip.isControlSupported(FloatControl.Type.MASTER_GAIN)) {
                FloatControl gain = (FloatControl) clip.getControl(FloatControl.Type.MASTER_GAIN);
                gain.setValue(Math.min(0.0f, gain.getMaximum()));
            }
            return clip;
        }
    }
}
