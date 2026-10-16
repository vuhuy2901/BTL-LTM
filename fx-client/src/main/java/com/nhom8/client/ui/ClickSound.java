package com.nhom8.client.ui;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.Clip;
import javax.sound.sampled.FloatControl;
import java.io.ByteArrayInputStream;

/** Âm thanh xác nhận thao tác ngắn, nhỏ và không cần tệp tài nguyên ngoài. */
public final class ClickSound {
    private static final float SAMPLE_RATE = 22_050f;
    private static Clip clip;
    private static boolean initialized;

    private ClickSound() { }

    public static void play() {
        java.util.concurrent.CompletableFuture.runAsync(() -> {
            try {
                if (!initialized) initialize();
                if (clip != null) {
                    if (clip.isRunning()) clip.stop();
                    clip.setFramePosition(0);
                    clip.start();
                }
            } catch (Throwable ignored) {
                // Thiết bị không có đầu ra âm thanh thì thao tác giao diện vẫn hoạt động bình thường.
            }
        });
    }

    private static synchronized void initialize() throws Exception {
        if (initialized) return;
        initialized = true;

        int frames = (int) (SAMPLE_RATE * 0.035);
        byte[] pcm = new byte[frames * 2];
        for (int i = 0; i < frames; i++) {
            double progress = (double) i / frames;
            double envelope = Math.pow(1.0 - progress, 3.2);
            double frequency = 1_250.0 - 420.0 * progress;
            double sample = Math.sin(2.0 * Math.PI * frequency * i / SAMPLE_RATE)
                    * envelope * 0.50;
            short value = (short) (sample * Short.MAX_VALUE);
            pcm[i * 2] = (byte) (value & 0xff);
            pcm[i * 2 + 1] = (byte) ((value >>> 8) & 0xff);
        }

        AudioFormat format = new AudioFormat(SAMPLE_RATE, 16, 1, true, false);
        try (AudioInputStream input = new AudioInputStream(
                new ByteArrayInputStream(pcm), format, frames)) {
            clip = AudioSystem.getClip();
            clip.open(input);
            if (clip.isControlSupported(FloatControl.Type.MASTER_GAIN)) {
                FloatControl gain = (FloatControl) clip.getControl(FloatControl.Type.MASTER_GAIN);
                gain.setValue(Math.min(0.0f, gain.getMaximum()));
            }
        }
    }
}
