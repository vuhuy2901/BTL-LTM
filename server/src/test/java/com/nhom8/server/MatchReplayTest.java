package com.nhom8.server;

import com.nhom8.common.message.Envelope;
import com.nhom8.common.message.MessageType;
import com.nhom8.server.model.Match;
import com.nhom8.server.model.MatchResult;
import com.nhom8.server.model.Painting;
import com.nhom8.server.model.User;
import com.nhom8.server.repository.MatchRepository;
import com.nhom8.server.repository.MatchResultRepository;
import com.nhom8.server.repository.PaintingRepository;
import com.nhom8.server.repository.UserRepository;
import com.nhom8.server.service.GameService;
import com.nhom8.server.ws.ClientSession;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class MatchReplayTest {
    @Autowired GameService service;
    @Autowired UserRepository users;
    @Autowired MatchRepository matches;
    @Autowired MatchResultRepository results;
    @Autowired PaintingRepository paintings;

    @Test void participantCanSeeEachRoundAndOutsiderCannot() {
        User player = users.save(new User("replayPlayer", "hash", "Replay Player"));
        Match match = matches.save(new Match("history-room"));
        results.save(new MatchResult(match, player));
        Painting first = new Painting(match, player, "con mèo");
        first.setRoundNumber(1);
        first.setTurnNumber(2);
        first.setStrokeData("[]");
        paintings.save(first);
        Painting old = new Painting(match, player, "cái ô");
        old.setStrokeData("[]");
        paintings.save(old);
        Match incomplete = matches.save(new Match("history-old-room"));
        paintings.save(new Painting(incomplete, player, "con chuồn chuồn"));

        ClientSession participant = mock(ClientSession.class);
        when(participant.getUsername()).thenReturn(player.getUsername());
        service.handleMatchDetail(participant, new Envelope(MessageType.MATCH_DETAIL_REQUEST)
                .put("matchId", match.getMatchId()));
        var response = org.mockito.ArgumentCaptor.forClass(Envelope.class);
        verify(participant).sendMessage(response.capture());
        assertTrue(response.getValue().isSuccess());
        assertEquals(2, response.getValue().getData().path("drawings").size());
        assertTrue(response.getValue().getData().path("drawings").toString().contains("con mèo"));

        ClientSession outsider = mock(ClientSession.class);
        when(outsider.getUsername()).thenReturn("otherPlayer");
        service.handleMatchDetail(outsider, new Envelope(MessageType.MATCH_DETAIL_REQUEST)
                .put("matchId", match.getMatchId()));
        service.handlePaintingReplay(outsider, new Envelope(MessageType.PAINTING_REPLAY_REQUEST)
                .put("paintingId", first.getPaintingId()));
        verify(outsider, times(2)).sendMessage(response.capture());
        assertFalse(response.getAllValues().get(1).isSuccess());
        assertFalse(response.getAllValues().get(2).isSuccess());

        service.handlePaintingReplay(participant, new Envelope(MessageType.PAINTING_REPLAY_REQUEST)
                .put("paintingId", first.getPaintingId()));
        verify(participant, times(2)).sendMessage(response.capture());
        assertEquals("[]", response.getAllValues().get(4).getString("strokeData"));

        clearInvocations(participant);
        service.handleMatchHistory(participant, new Envelope(MessageType.MATCH_HISTORY_REQUEST));
        verify(participant).sendMessage(response.capture());
        var history = response.getValue().getData().path("history");
        assertEquals(2, history.size());
        assertTrue(history.findValues("hasResult").stream().anyMatch(v -> v.asBoolean()));
        assertTrue(history.findValues("hasResult").stream().anyMatch(v -> !v.asBoolean()));
    }
}
