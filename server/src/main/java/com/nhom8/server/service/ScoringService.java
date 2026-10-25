package com.nhom8.server.service;

/** Quy tắc điểm cho trận mới; Server quyết định thứ tự đáp án đúng. */
public final class ScoringService {
    public static final int VERSION = 4;
    public static final long GUESS_DURATION_MS = 30_000;
    public static final long DEFAULT_EXPOSURE_MS = 10_000;

    private ScoringService() {}

    public static boolean isWithinGuessWindow(long startedAt, long acceptedAt, long durationMs) {
        return durationMs > 0 && acceptedAt >= startedAt && acceptedAt - startedAt < durationMs;
    }

    /** Chia đều 100 điểm cho các vị trí đoán theo số người có quyền đoán lúc mở tranh. */
    public static int calculateGuessScore(int correctRank, int guesserSlots) {
        if (guesserSlots < 1 || correctRank < 1 || correctRank > guesserSlots) return 0;
        return (int) Math.round(100.0 * (guesserSlots - correctRank + 1) / guesserSlots);
    }

    public static int calculateDrawingScore(int eligibleCount, int correctCount) {
        if (eligibleCount <= 0) return 0;
        if (correctCount < 0 || correctCount > eligibleCount) throw new IllegalArgumentException("Số người đúng không hợp lệ.");
        if (correctCount == 0) return -20;
        return (int) Math.round(100.0 * correctCount / eligibleCount);
    }
}
