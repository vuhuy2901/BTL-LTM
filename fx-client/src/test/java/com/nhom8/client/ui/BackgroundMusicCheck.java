package com.nhom8.client.ui;

import java.util.concurrent.atomic.AtomicBoolean;

public class BackgroundMusicCheck {
    public static void main(String[] args) {
        System.out.println("Testing BackgroundMusic...");

        BackgroundMusic.setMuted(false);
        if (BackgroundMusic.isMuted()) throw new AssertionError("Should not be muted initially");

        boolean toggled = BackgroundMusic.toggleMute();
        if (!toggled || !BackgroundMusic.isMuted()) throw new AssertionError("Toggle mute failed");

        toggled = BackgroundMusic.toggleMute();
        if (toggled || BackgroundMusic.isMuted()) throw new AssertionError("Toggle unmute failed");

        AtomicBoolean listenerCalled = new AtomicBoolean(false);
        BackgroundMusic.addMuteListener(listenerCalled::set);
        BackgroundMusic.setMuted(true);
        if (!listenerCalled.get()) throw new AssertionError("Mute listener not triggered");

        BackgroundMusic.play();
        BackgroundMusic.stop();

        System.out.println("BackgroundMusic tests PASSED!");
    }
}
