package com.nhom8.client.ui;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.Clip;
import javax.sound.sampled.FloatControl;
import java.io.ByteArrayInputStream;
import java.util.Random;

/**
 * Hiệu ứng âm thanh tiếng nét bút vẽ sột soạt trên giấy (và tiếng gôm tẩy).
 * Âm thanh được tự động tổng hợp PCM dạng hạt ma sát (granular friction),
 * không phụ thuộc tệp tài nguyên bên ngoài.
 */
public final class PencilSound {
    private static final float SAMPLE_RATE = 22_050f;
    private static final int VARIATIONS = 3;
    private static Clip[] pencilClips;
    private static Clip eraserClip;
    private static boolean initialized;
    private static int nextClipIndex = 0;
    private static long lastDrawTime = 0;

    private PencilSound() { }

    /**
     * Phát âm thanh sột soạt khi vẽ hoặc tẩy trên canvas.
     * Tự động điều tiết tần suất (throttle) khoảng 75ms/lần để nét vẽ mượt mà tự nhiên.
     */
    public static void playDraw(boolean isEraser) {
        long now = System.currentTimeMillis();
        if (now - lastDrawTime < 75) {
            return;
        }
        lastDrawTime = now;

        java.util.concurrent.CompletableFuture.runAsync(() -> {
            try {
                if (!initialized) initialize();
                Clip clip;
                if (isEraser) {
                    clip = eraserClip;
                } else if (pencilClips != null && pencilClips.length > 0) {
                    clip = pencilClips[nextClipIndex % pencilClips.length];
                    nextClipIndex++;
                } else {
                    clip = null;
                }

                if (clip != null) {
                    if (clip.isRunning()) clip.stop();
                    clip.setFramePosition(0);
                    clip.start();
                }
            } catch (Throwable ignored) {
                // Đảm bảo không bao giờ ảnh hưởng đến thao tác vẽ tranh
            }
        });
    }

    private static synchronized void initialize() {
        if (initialized) return;
        initialized = true;

        try {
            pencilClips = new Clip[VARIATIONS];
            Random rnd = new Random(42);

            // Sinh 3 biến thể tiếng bút chì cọ xát trên giấy ráp (~65ms)
            for (int v = 0; v < VARIATIONS; v++) {
                int frames = (int) (SAMPLE_RATE * 0.065);
                byte[] pcm = new byte[frames * 2];
                double filter = 0.0;
                double centerFreq = 2200.0 + (v * 350.0);

                for (int i = 0; i < frames; i++) {
                    double progress = (double) i / frames;
                    // Envelope: Attack nhanh, Decay tự nhiên
                    double env = progress < 0.15 ? (progress / 0.15) : Math.pow(1.0 - progress, 1.8);
                    double white = rnd.nextDouble() * 2.0 - 1.0;
                    // Bộ lọc High-pass lọc bớt tần số thấp, giữ lại tiếng ráp của ngòi bút
                    filter = 0.65 * filter + 0.35 * white;
                    double highPass = white - filter;
                    // Thêm một chút dao động cộng hưởng giấy
                    double tone = Math.sin(2.0 * Math.PI * centerFreq * i / SAMPLE_RATE) * 0.12;
                    double sample = (highPass * 0.88 + tone) * env * 0.55;
                    short val = (short) (Math.max(-1.0, Math.min(1.0, sample)) * Short.MAX_VALUE);
                    pcm[i * 2] = (byte) (val & 0xff);
                    pcm[i * 2 + 1] = (byte) ((val >>> 8) & 0xff);
                }

                pencilClips[v] = createClip(pcm, frames);
            }

            // Sinh 1 tiếng gôm tẩy cao su cọ xát bề mặt giấy (~90ms, trầm và êm hơn)
            int eraserFrames = (int) (SAMPLE_RATE * 0.090);
            byte[] eraserPcm = new byte[eraserFrames * 2];
            double eraserFilter = 0.0;
            for (int i = 0; i < eraserFrames; i++) {
                double progress = (double) i / eraserFrames;
                double env = progress < 0.2 ? (progress / 0.2) : Math.pow(1.0 - progress, 2.2);
                double white = rnd.nextDouble() * 2.0 - 1.0;
                eraserFilter = 0.85 * eraserFilter + 0.15 * white;
                double sample = eraserFilter * env * 0.50;
                short val = (short) (Math.max(-1.0, Math.min(1.0, sample)) * Short.MAX_VALUE);
                eraserPcm[i * 2] = (byte) (val & 0xff);
                eraserPcm[i * 2 + 1] = (byte) ((val >>> 8) & 0xff);
            }
            eraserClip = createClip(eraserPcm, eraserFrames);

        } catch (Throwable ignored) {
        }
    }

    private static Clip createClip(byte[] pcm, int frames) throws Exception {
        AudioFormat format = new AudioFormat(SAMPLE_RATE, 16, 1, true, false);
        try (AudioInputStream input = new AudioInputStream(
                new ByteArrayInputStream(pcm), format, frames)) {
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
