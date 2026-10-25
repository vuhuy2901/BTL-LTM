package com.nhom8.server;

import com.nhom8.common.message.*;
import com.nhom8.server.game.GameSession;
import com.nhom8.server.model.MatchResult;
import org.junit.jupiter.api.*;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** Tái sử dụng bộ dựng server giả, chạy qua GameSession và vòng đời phiên thực. */
class TimeScoringGameTest {
    SessionRecoveryTest fixture;
    GameSession game;
    AtomicLong clock;
    SessionRecoveryTest.Client artist, a, b;
    String answer, drawingId;

    @BeforeEach void setup() {
        setupGame(3);
    }
    private void setupGame(int playerCount) {
        fixture = new SessionRecoveryTest(); fixture.setup(); game = fixture.room.getGameSession();
        for (int i = 4; i <= playerCount; i++) {
            var client = fixture.login("p" + i,null,null); fixture.room.addPlayer(client);
            client.setCurrentRoomId(fixture.room.getRoomId()); fixture.clients.add(client);
        }
        clock = new AtomicLong(100000);
        ReflectionTestUtils.setField(game, "scoringClock", (java.util.function.LongSupplier) clock::get);
        fixture.drawing(); ReflectionTestUtils.invokeMethod(game, "finishDrawingPhase");
        String painter = fixture.clients.get(0).last(MessageType.GUESS_START).getString("painterUsername");
        artist = fixture.clients.stream().filter(c -> c.getUsername().equals(painter)).findFirst().orElseThrow();
        var guessers = fixture.clients.stream().filter(c -> c != artist).toList(); a = guessers.get(0); b = guessers.get(1);
        answer = (String) ((Map<?,?>) ReflectionTestUtils.getField(game, "selectedTopics")).get(painter);
        drawingId = a.last(MessageType.GUESS_START).getString("drawingId");
    }
    @AfterEach void cleanup() { fixture.cleanup(); }
    void at(long ms) { clock.set(100000 + ms); }
    void guess(SessionRecoveryTest.Client client, String text) {
        Envelope request = new Envelope(MessageType.GUESS_SUBMIT).put("drawingId", drawingId).put("elapsedMs", 0);
        request.setContent(text); fixture.service.handleGuessSubmit(client, request);
    }
    MatchResult result(String username) { return ((Map<String,MatchResult>) ReflectionTestUtils.getField(game,"matchResults")).get(username); }
    Envelope finish() { ReflectionTestUtils.invokeMethod(game, "finishCurrentRound"); return artist.last(MessageType.ROUND_RESULT); }

    @Test void guessPointsFollowOrderEvenWhenFirstCorrectIsLate() {
        at(20000); guess(a, answer); assertEquals(100, a.last(MessageType.GUESS_RESULT).getInt("points",0));
        at(21000); guess(b, answer); assertEquals(50, b.last(MessageType.GUESS_RESULT).getInt("points",0));
        assertEquals(100, artist.last(MessageType.ROUND_RESULT).getInt("painterPoints",0));
    }
    @Test void wrongThenCorrectAndDuplicateMessagesAwardOnlyOnce() {
        at(5000); guess(a,"wrong_answer_9283"); assertEquals(0,a.last(MessageType.GUESS_RESULT).getInt("points",-1));
        at(15000); guess(a,answer); guess(a,answer);
        assertEquals(100,result(a.getUsername()).getPointsGuess());
        guess(b,answer); assertEquals(50,result(b.getUsername()).getPointsGuess());
        assertEquals(100,artist.last(MessageType.ROUND_RESULT).getInt("painterPoints",0));
    }
    @Test void rejectsDeadlineAuthorWrongDrawingAndOfflineAnswers() {
        guess(artist,answer); assertEquals(0,result(artist.getUsername()).getPointsGuess());
        Envelope forged = new Envelope(MessageType.GUESS_SUBMIT).put("drawingId","old-drawing"); forged.setContent(answer);
        fixture.service.handleGuessSubmit(a, forged); assertEquals(0,result(a.getUsername()).getPointsGuess());
        fixture.lose(a); at(1000); guess(a,answer); assertEquals(0,result(a.getUsername()).getPointsGuess());
        at(30000); guess(b,answer); assertEquals(0,result(b.getUsername()).getPointsGuess());
    }
    @Test void shortDisconnectedExposureDoesNotLowerDrawingScore() {
        at(9000); fixture.lose(a); at(15000); guess(b,answer); at(30000);
        Envelope round = finish(); assertEquals(1,round.getInt("totalGuessers",0)); assertEquals(100,round.getInt("painterPoints",0));
    }
    @Test void zeroExposureMeansNoArtistPenalty() {
        fixture.lose(a); fixture.lose(b); at(30000); Envelope round = finish();
        assertEquals(0,round.getInt("totalGuessers",-1)); assertEquals(0,round.getInt("painterPoints",-1));
    }
    @Test void noCorrectWithExposurePenalizesOnlyOnceAndPersistsNegativePoints() {
        at(30000); finish(); finish();
        assertEquals(-20,result(artist.getUsername()).getPointsDrawn());
        game.endGame(); game.endGame();
        verify(fixture.users,times(1)).addRankingScore(fixture.records.get(artist.getUsername()).getUserId(),-20);
        verify(fixture.matches, times(2)).save(any()); // Một lần tạo, một lần kết thúc.
    }
    @Test void connectedTimeAccumulatesAcrossRecoveryAndUsesOriginalDeadline() {
        long deadline = (long) ReflectionTestUtils.getField(game,"phaseDeadlineMillis");
        at(4000); fixture.lose(a); at(20000);
        var recovered = fixture.login(a.getUsername(),a.token(),fixture.room.getRoomId());
        assertEquals(deadline,recovered.last(MessageType.GAME_STATE_SNAPSHOT).getData().path("phaseDeadline").asLong());
        at(25000); guess(b,answer); at(30000);
        Envelope round = finish(); assertEquals(2,round.getInt("totalGuessers",0)); assertEquals(50,round.getInt("painterPoints",0));
    }
    @Test void correctLeaverRemainsSuccessfulAndRewardsAreNotCountedTwice() {
        at(5000); guess(a,answer); fixture.service.handleLeaveRoom(a,new Envelope(MessageType.ROOM_LEAVE));
        at(15000); guess(b,answer);
        Envelope round = artist.last(MessageType.ROUND_RESULT);
        assertEquals(2,round.getInt("totalGuessers",0)); assertEquals(2,round.getInt("correctCount",0)); assertEquals(100,round.getInt("painterPoints",0));
        assertEquals(120,result(artist.getUsername()).getTotalPoints());
        assertEquals(70,result(b.getUsername()).getTotalPoints());
        game.endGame();
        verify(fixture.users,times(1)).addRankingScore(fixture.records.get(artist.getUsername()).getUserId(),100);
        verify(fixture.users,times(1)).addRankingScore(fixture.records.get(b.getUsername()).getUserId(),50);
        assertEquals(4,artist.last(MessageType.GAME_RESULT).getInt("scoringVersion",0));
    }
    @Test void simultaneousCorrectAnswersReceiveDifferentOrderScores() throws Exception {
        at(15000); ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> first = executor.submit(() -> guess(a,answer)); Future<?> second = executor.submit(() -> guess(b,answer));
            first.get(5,TimeUnit.SECONDS); second.get(5,TimeUnit.SECONDS);
            assertEquals(Set.of(100, 50), Set.of(result(a.getUsername()).getPointsGuess(), result(b.getUsername()).getPointsGuess()));
        } finally { executor.shutdownNow(); }
    }

    @Test void threeOfFourCorrectGivesArtistSeventyFivePoints() {
        fixture.cleanup(); setupGame(5);
        var third = fixture.clients.stream().filter(c -> c != artist && c != a && c != b).findFirst().orElseThrow();
        at(0); guess(a,answer); at(15000); guess(b,answer); at(29999); guess(third,answer); at(30000);
        Envelope round = finish(); assertEquals(4,round.getInt("totalGuessers",0));
        assertEquals(3,round.getInt("correctCount",0)); assertEquals(75,round.getInt("painterPoints",0));
        assertEquals(225,round.getInt("sumOfCorrectGuessScores",0));
    }

    @Test void alreadySubmittedDrawingRetainsScoringAfterArtistDisconnect() {
        fixture.lose(artist); at(15000); guess(a,answer); guess(b,answer);
        assertEquals(100,result(artist.getUsername()).getPointsDrawn());
        assertEquals(100,result(a.getUsername()).getPointsGuess());
    }
}
