package com.nhom8.server.game;

import com.nhom8.server.service.ScoringService;
import java.util.*;

/** Theo dõi cơ hội đoán và điểm đúng đầu tiên của từng người cho một bức tranh. */
public final class GuessingWindow {
    private static final class Exposure {
        Long connectedSince;
        long accumulated;
        Integer score;
        boolean left;
    }
    private final Map<String, Exposure> players = new LinkedHashMap<>();
    private final long startedAt, durationMs, minimumExposureMs;

    public GuessingWindow(long startedAt, long durationMs, long minimumExposureMs, Map<String, Boolean> candidates) {
        this.startedAt = startedAt; this.durationMs = durationMs;
        this.minimumExposureMs = Math.max(0, minimumExposureMs);
        candidates.forEach((username, connected) -> {
            Exposure exposure = new Exposure();
            if (connected) exposure.connectedSince = startedAt;
            players.put(username, exposure);
        });
    }

    public synchronized void connectionChanged(String username, boolean connected, long now) {
        Exposure exposure = players.get(username);
        if (exposure == null || exposure.left) return;
        long bounded = Math.max(startedAt, Math.min(startedAt + durationMs, now));
        if (!connected && exposure.connectedSince != null) {
            exposure.accumulated += Math.max(0, bounded - exposure.connectedSince);
            exposure.connectedSince = null;
        } else if (connected && exposure.connectedSince == null) exposure.connectedSince = bounded;
    }

    public synchronized void permanentlyLeft(String username, long now) {
        connectionChanged(username, false, now);
        Exposure exposure = players.get(username);
        if (exposure != null) exposure.left = true;
    }

    public synchronized int awardCorrect(String username, long acceptedAt, int correctRank) {
        Exposure exposure = players.get(username);
        if (exposure == null || exposure.left || exposure.connectedSince == null || exposure.score != null) return 0;
        if (!ScoringService.isWithinGuessWindow(startedAt, acceptedAt, durationMs)) return 0;
        int score = ScoringService.calculateGuessScore(correctRank, players.size());
        if (score > 0) exposure.score = score;
        return score;
    }

    public synchronized long connectedMillis(String username, long now) {
        Exposure exposure = players.get(username);
        if (exposure == null) return 0;
        long bounded = Math.max(startedAt, Math.min(startedAt + durationMs, now));
        return exposure.accumulated + (exposure.connectedSince == null ? 0 : Math.max(0, bounded - exposure.connectedSince));
    }

    public synchronized Set<String> eligibleGuessers(long now) {
        Set<String> eligible = new LinkedHashSet<>();
        players.forEach((username, exposure) -> {
            if (exposure.score != null || connectedMillis(username, now) >= minimumExposureMs) eligible.add(username);
        });
        return eligible;
    }

    public synchronized List<Integer> correctScores() {
        return players.values().stream().filter(p -> p.score != null).map(p -> p.score).toList();
    }

    public synchronized boolean everyoneCorrect(long now) {
        boolean any = false;
        for (var entry : players.entrySet()) {
            Exposure exposure = entry.getValue();
            // Chờ cả người đang được giữ chỗ: họ vẫn có thể quay lại và đoán đúng.
            if (!exposure.left || exposure.score != null || connectedMillis(entry.getKey(), now) >= minimumExposureMs) {
                any = true;
                if (exposure.score == null) return false;
            }
        }
        return any;
    }
}
