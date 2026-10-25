package com.nhom8.client.ui;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.Clip;
import javax.sound.sampled.FloatControl;
import java.io.ByteArrayInputStream;
import java.net.URL;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Quản lý hiệu ứng âm thanh nét bút vẽ (pencil) và gôm tẩy (eraser) trên bảng vẽ.
 * Sử dụng tệp âm thanh chân thực từ tài nguyên mẫu chuẩn (CC0 1.0) kết hợp cơ chế
 * phát mượt mà (seamless loop khi rê chuột, tự ngắt êm ái khi dừng rê hoặc nhả chuột),
 * đảm bảo âm thanh êm dịu, không giật cục, không ồn ào.
 */
public final class PencilSound {
    private static final float SAMPLE_RATE = 22_050f;
    private static volatile boolean initialized;
    private static Clip pencilClip;
    private static Clip eraserClip;
    private static volatile Clip currentPlayingClip;
    private static volatile long lastDrawTime = 0;
    private static volatile long pressTime = 0;
    private static volatile boolean active = false;
    private static ScheduledExecutorService watchdog;

    private PencilSound() { }

    /**
     * Bắt đầu hoặc duy trì âm thanh sột soạt tự nhiên khi người chơi rê chuột vẽ/tẩy trên canvas.
     * Âm lượng được điều chỉnh êm dịu, không lặp giật tiếng.
     */
    public static void playDraw(boolean isEraser) {
        if (!GameSound.isEnabled()) {
            stopDraw();
            return;
        }

        long now = System.currentTimeMillis();
        if (!active) {
            pressTime = now;
        }
        lastDrawTime = now;
        active = true;

        if (!initialized) {
            initialize();
        }

        Clip targetClip = isEraser ? eraserClip : pencilClip;
        if (targetClip == null) return;

        Clip current = currentPlayingClip;
        if (current != targetClip) {
            if (current != null && current.isRunning()) {
                current.stop();
                current.setFramePosition(0);
            }
            currentPlayingClip = targetClip;
            try {
                targetClip.setLoopPoints(0, -1);
                targetClip.loop(Clip.LOOP_CONTINUOUSLY);
            } catch (Throwable ignored) {
            }
        } else if (!targetClip.isRunning()) {
            try {
                targetClip.setLoopPoints(0, -1);
                targetClip.loop(Clip.LOOP_CONTINUOUSLY);
            } catch (Throwable ignored) {
            }
        }
    }

    /**
     * Dừng âm thanh vẽ khi người chơi nhả chuột, chuột rời canvas hoặc kết thúc lượt vẽ.
     */
    public static void stopDraw() {
        active = false;
        long now = System.currentTimeMillis();
        long elapsedSincePress = now - pressTime;

        // Nếu là thao tác chấm điểm cực nhanh (< 80ms), để phát đủ 80ms rồi tự dừng
        if (elapsedSincePress < 80 && pressTime > 0) {
            return;
        }

        stopCurrentClipImmediate();
    }

    private static synchronized void stopCurrentClipImmediate() {
        Clip clip = currentPlayingClip;
        if (clip != null) {
            try {
                if (clip.isRunning()) {
                    clip.stop();
                }
                clip.setFramePosition(0);
            } catch (Throwable ignored) {
            }
            currentPlayingClip = null;
        }
    }

    private static synchronized void initialize() {
        if (initialized) return;
        initialized = true;

        try {
            pencilClip = loadClip("/sounds/pencil_draw.wav", -6.0f);
            if (pencilClip == null) {
                pencilClip = generateSoftPencilClip();
            }

            eraserClip = loadClip("/sounds/eraser_draw.wav", -5.0f);
            if (eraserClip == null) {
                eraserClip = generateSoftEraserClip();
            }

            watchdog = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "PencilSound-Watchdog");
                t.setDaemon(true);
                return t;
            });

            // Tự động dừng âm thanh sau 130ms nếu không còn thao tác rê chuột vẽ
            watchdog.scheduleWithFixedDelay(() -> {
                try {
                    if (active) {
                        long now = System.currentTimeMillis();
                        if (now - lastDrawTime > 130) {
                            active = false;
                            stopCurrentClipImmediate();
                        }
                    } else if (currentPlayingClip != null) {
                        long now = System.currentTimeMillis();
                        if (now - pressTime >= 80) {
                            stopCurrentClipImmediate();
                        }
                    }
                } catch (Throwable ignored) {
                }
            }, 40, 40, TimeUnit.MILLISECONDS);

        } catch (Throwable ignored) {
        }
    }

    private static Clip loadClip(String resourcePath, float gainDb) {
        try {
            URL url = PencilSound.class.getResource(resourcePath);
            if (url == null) return null;
            try (AudioInputStream ais = AudioSystem.getAudioInputStream(url)) {
                Clip clip = AudioSystem.getClip();
                clip.open(ais);
                if (clip.isControlSupported(FloatControl.Type.MASTER_GAIN)) {
                    FloatControl gain = (FloatControl) clip.getControl(FloatControl.Type.MASTER_GAIN);
                    float target = Math.min(gain.getMaximum(), Math.max(gain.getMinimum(), gainDb));
                    gain.setValue(target);
                }
                return clip;
            }
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * Bộ dự phòng sinh âm thanh êm dịu nếu không tìm thấy tệp WAV tài nguyên.
     */
    private static Clip generateSoftPencilClip() {
        try {
            int frames = (int) (SAMPLE_RATE * 0.50);
            byte[] pcm = new byte[frames * 2];
            double filter = 0.0;
            java.util.Random rnd = new java.util.Random(42);
            for (int i = 0; i < frames; i++) {
                double white = rnd.nextDouble() * 2.0 - 1.0;
                filter += 0.15 * (white - filter);
                double pulse = 0.85 + 0.15 * Math.sin(2.0 * Math.PI * 18 * i / SAMPLE_RATE);
                double sample = filter * pulse * 0.08;
                short val = (short) (sample * Short.MAX_VALUE);
                pcm[i * 2] = (byte) (val & 0xff);
                pcm[i * 2 + 1] = (byte) ((val >>> 8) & 0xff);
            }
            AudioFormat format = new AudioFormat(SAMPLE_RATE, 16, 1, true, false);
            try (AudioInputStream input = new AudioInputStream(new ByteArrayInputStream(pcm), format, frames)) {
                Clip clip = AudioSystem.getClip();
                clip.open(input);
                return clip;
            }
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Clip generateSoftEraserClip() {
        try {
            int frames = (int) (SAMPLE_RATE * 0.50);
            byte[] pcm = new byte[frames * 2];
            double filter = 0.0;
            java.util.Random rnd = new java.util.Random(1337);
            for (int i = 0; i < frames; i++) {
                double white = rnd.nextDouble() * 2.0 - 1.0;
                filter += 0.25 * (white - filter);
                double pulse = 0.80 + 0.20 * Math.sin(2.0 * Math.PI * 12 * i / SAMPLE_RATE);
                double sample = filter * pulse * 0.07;
                short val = (short) (sample * Short.MAX_VALUE);
                pcm[i * 2] = (byte) (val & 0xff);
                pcm[i * 2 + 1] = (byte) ((val >>> 8) & 0xff);
            }
            AudioFormat format = new AudioFormat(SAMPLE_RATE, 16, 1, true, false);
            try (AudioInputStream input = new AudioInputStream(new ByteArrayInputStream(pcm), format, frames)) {
                Clip clip = AudioSystem.getClip();
                clip.open(input);
                return clip;
            }
        } catch (Throwable ignored) {
            return null;
        }
    }
}
