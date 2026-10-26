package com.nhom8.client.ui;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.Clip;
import javax.sound.sampled.FloatControl;
import java.io.ByteArrayInputStream;
import java.net.URL;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Quản lý nhạc nền (Background Music - BGM) cho Sảnh chờ (Lobby) và Phòng chờ (Room).
 * Khi vào trận đấu (Game), nhạc nền tự động tắt để người chơi tập trung vẽ và đoán.
 * Cung cấp nút bật/tắt (Mute/Unmute) trên thanh tiêu đề của sảnh và phòng chờ.
 */
public final class BackgroundMusic {

    private static final float SAMPLE_RATE = 22_050f;
    private static volatile boolean muted = false;
    private static volatile boolean shouldPlay = false;
    private static Clip musicClip;
    private static volatile boolean initialized = false;
    private static final List<Consumer<Boolean>> muteListeners = new CopyOnWriteArrayList<>();

    private BackgroundMusic() { }

    /**
     * Bắt đầu phát nhạc nền lặp vô tận (cho Sảnh chờ và Phòng chờ).
     */
    public static synchronized void play() {
        shouldPlay = true;
        if (muted) return;

        CompletableFuture.runAsync(() -> {
            try {
                if (!initialized) initialize();
                if (!shouldPlay || muted) return;

                synchronized (BackgroundMusic.class) {
                    if (!shouldPlay || muted) return;
                    if (musicClip != null) {
                        if (!musicClip.isRunning()) {
                            musicClip.setLoopPoints(0, -1);
                            musicClip.loop(Clip.LOOP_CONTINUOUSLY);
                        }
                    }
                }
            } catch (Throwable ignored) {
            }
        });
    }

    /**
     * Tắt/dừng nhạc nền (dùng khi vào trận đấu hoặc rời ứng dụng).
     */
    public static synchronized void stop() {
        shouldPlay = false;
        if (musicClip != null && musicClip.isRunning()) {
            try {
                musicClip.stop();
                musicClip.setFramePosition(0);
            } catch (Throwable ignored) {
            }
        }
    }

    /**
     * Chuyển đổi trạng thái bật/tắt nhạc nền.
     */
    public static synchronized boolean toggleMute() {
        setMuted(!muted);
        return muted;
    }

    /**
     * Đặt trực tiếp trạng thái tắt tiếng.
     */
    public static synchronized void setMuted(boolean value) {
        muted = value;
        if (muted) {
            if (musicClip != null && musicClip.isRunning()) {
                try {
                    musicClip.stop();
                } catch (Throwable ignored) {
                }
            }
        } else {
            if (shouldPlay) {
                play();
            }
        }
        notifyListeners();
    }

    public static boolean isMuted() {
        return muted;
    }

    public static void addMuteListener(Consumer<Boolean> listener) {
        if (listener != null) {
            muteListeners.add(listener);
            listener.accept(muted);
        }
    }

    public static void removeMuteListener(Consumer<Boolean> listener) {
        muteListeners.remove(listener);
    }

    private static void notifyListeners() {
        for (Consumer<Boolean> listener : muteListeners) {
            try {
                listener.accept(muted);
            } catch (Throwable ignored) {
            }
        }
    }

    private static synchronized void initialize() {
        if (initialized) return;
        initialized = true;

        try {
            musicClip = loadClip("/sounds/bgm_lobby.wav", -12.0f);
            if (musicClip == null) {
                musicClip = generateFallbackClip();
            }
        } catch (Throwable ignored) {
        }
    }

    private static Clip loadClip(String resourcePath, float gainDb) {
        try {
            URL url = BackgroundMusic.class.getResource(resourcePath);
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

    private static Clip generateFallbackClip() {
        try {
            int frames = (int) (SAMPLE_RATE * 8.0);
            byte[] pcm = new byte[frames * 2];
            for (int i = 0; i < frames; i++) {
                double t = i / SAMPLE_RATE;
                double sample = Math.sin(2.0 * Math.PI * 440.0 * t) * 0.05
                        * Math.exp(-((t % 2.0) * 2.0));
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
