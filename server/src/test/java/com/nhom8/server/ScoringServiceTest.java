package com.nhom8.server;

import com.nhom8.server.service.ScoringService;
import com.nhom8.server.game.GuessingWindow;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class ScoringServiceTest {
    @ParameterizedTest @CsvSource({"1,2,100", "2,2,50", "1,3,100", "2,3,67", "3,3,33", "1,7,100", "2,7,86", "3,7,71", "4,7,57", "5,7,43", "6,7,29", "7,7,14"})
    void guessPointsFollowOrderAndRoomSize(int rank, int slots, int points) {
        assertEquals(points, ScoringService.calculateGuessScore(rank, slots));
    }

    @Test void guessWindowStillRejectsLateAndInvalidAnswers() {
        assertTrue(ScoringService.isWithinGuessWindow(0, 29999, 30000));
        assertFalse(ScoringService.isWithinGuessWindow(0, 30000, 30000));
        assertFalse(ScoringService.isWithinGuessWindow(0, 30001, 30000));
        assertFalse(ScoringService.isWithinGuessWindow(1, 0, 30000));
        assertFalse(ScoringService.isWithinGuessWindow(0, 0, 0));
        assertEquals(0, ScoringService.calculateGuessScore(0, 2));
        assertEquals(0, ScoringService.calculateGuessScore(3, 2));
        assertEquals(0, ScoringService.calculateGuessScore(1, 0));
    }

    @Test void drawingPointsFollowCorrectShareOfEligibleGuessers() {
        assertEquals(100, ScoringService.calculateDrawingScore(4, 4));
        assertEquals(75, ScoringService.calculateDrawingScore(4, 3));
        assertEquals(50, ScoringService.calculateDrawingScore(4, 2));
        assertEquals(25, ScoringService.calculateDrawingScore(4, 1));
        assertEquals(67, ScoringService.calculateDrawingScore(3, 2));
        assertEquals(-20, ScoringService.calculateDrawingScore(4, 0));
        assertEquals(0, ScoringService.calculateDrawingScore(0, 0));
        assertEquals(100, ScoringService.calculateDrawingScore(7, 7));
        assertThrows(IllegalArgumentException.class, () -> ScoringService.calculateDrawingScore(2, 3));
    }

    @Test void cumulativeExposureExcludesAuthorAndPreservesCorrectDisconnectedGuesser() {
        GuessingWindow window = new GuessingWindow(0, 30000, 10000, Map.of("a",true,"b",true,"c",false));
        window.connectionChanged("a", false, 4000);
        window.connectionChanged("a", true, 20000);
        assertEquals(9999, window.connectedMillis("a", 25999));
        assertFalse(window.eligibleGuessers(25999).contains("a"));
        assertTrue(window.eligibleGuessers(26000).contains("a"));
        assertEquals(100, window.awardCorrect("b", 0, 1));
        window.permanentlyLeft("b", 1000);
        assertTrue(window.eligibleGuessers(30000).contains("b"));
        assertFalse(window.eligibleGuessers(30000).contains("c"));
        assertFalse(window.eligibleGuessers(30000).contains("artist"));
        assertEquals(0, window.awardCorrect("b", 2000, 1));
    }

    @Test void candidatesWithoutOpportunityDoNotCauseEarlyFinishOrFailurePenalty() {
        GuessingWindow window = new GuessingWindow(0, 30000, 10000, Map.of("a",true,"b",false));
        assertEquals(100, window.awardCorrect("a", 2000, 1));
        assertFalse(window.everyoneCorrect(2000));
        window.permanentlyLeft("b", 3000);
        assertTrue(window.everyoneCorrect(3000));
        assertEquals(100, ScoringService.calculateDrawingScore(window.eligibleGuessers(3000).size(), window.correctScores().size()));
    }

    @Test void disconnectedOrUnknownPlayersCannotAwardPoints() {
        GuessingWindow window = new GuessingWindow(0, 30000, 10000, Map.of("a",false));
        assertEquals(0, window.awardCorrect("a", 100, 1));
        assertEquals(0, window.awardCorrect("forged", 100, 1));
        window.connectionChanged("a", true, 5000);
        assertEquals(100, window.awardCorrect("a", 5000, 1));
        assertEquals(0, window.awardCorrect("a", 6000, 1));
        assertEquals(1, window.correctScores().size());
    }
}
