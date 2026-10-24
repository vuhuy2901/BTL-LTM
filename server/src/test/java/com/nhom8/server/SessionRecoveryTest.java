package com.nhom8.server;

import com.nhom8.common.dto.*;
import com.nhom8.common.message.*;
import com.nhom8.common.message.payload.LoginResultPayload;
import com.nhom8.server.game.*;
import com.nhom8.server.model.*;
import com.nhom8.server.repository.*;
import com.nhom8.server.service.*;
import com.nhom8.server.ws.ClientSession;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.socket.WebSocketSession;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SessionRecoveryTest {
    GameService service;
    AuthService auth;
    UserRepository users;
    MatchRepository matches;
    PaintingRepository paintings;
    Room room;
    List<Client> clients = new ArrayList<>();
    Map<String, User> records = new HashMap<>();
    AtomicLong now = new AtomicLong(System.currentTimeMillis());

    static class Client extends ClientSession {
        boolean open = true;
        List<Envelope> messages = new CopyOnWriteArrayList<>();
        Client() { super(mock(WebSocketSession.class)); }
        @Override public boolean isConnected() { return open && getConnectionState() == ConnectionState.CONNECTED; }
        @Override public void disconnect() { open = false; }
        @Override public void sendMessage(Envelope message) { if (isConnected()) messages.add(Envelope.fromJson(message.toJson())); }
        Envelope last(MessageType type) {
            return messages.stream().filter(e -> e.getType() == type).reduce((a,b) -> b).orElseThrow();
        }
        String token() { return last(MessageType.LOGIN_RESULT).getString("resumeToken"); }
    }

    @BeforeEach void setup() {
        users = mock(UserRepository.class); matches = mock(MatchRepository.class); paintings = mock(PaintingRepository.class);
        service = new GameService(users, matches, mock(MatchResultRepository.class), paintings, mock(FriendshipRepository.class));
        auth = mock(AuthService.class);
        ReflectionTestUtils.setField(service, "authService", auth);
        ReflectionTestUtils.setField(service, "friendService", mock(FriendService.class));
        ReflectionTestUtils.setField(service, "clock", (java.util.function.LongSupplier) now::get);
        when(matches.save(any())).thenAnswer(call -> call.getArgument(0));
        when(users.findByUsername(anyString())).thenAnswer(call -> records.get(call.getArgument(0)));
        when(auth.getProfile(anyString())).thenAnswer(call -> records.get(call.getArgument(0)));
        when(auth.login(anyString(), eq("password"))).thenAnswer(call -> {
            String username = call.getArgument(0);
            return new LoginResultPayload(new PlayerDTO(username, username, "1", 0, "ONLINE", false), null);
        });
        for (int i = 1; i <= 9; i++) {
            User user = new User("p" + i, "password", "p" + i); user.setUserId(i); records.put(user.getUsername(), user);
        }
        for (int i = 1; i <= 3; i++) clients.add(login("p" + i, null, null));
        room = service.getRoomManager().createRoom(clients.get(0), "Recovery");
        for (Client client : clients) { if (room.getPlayer(client.getUsername()) == null) room.addPlayer(client); client.setCurrentRoomId(room.getRoomId()); }
    }

    @AfterEach void cleanup() { if (room != null) room.getGameSession().cancelGame(); if (service != null) service.stopReconnectScheduler(); }

    Client login(String username, String token, String roomId) {
        Client next = new Client(); service.addClient(next);
        Envelope request = new Envelope(MessageType.LOGIN).put("username", username).put("password", "password");
        if (token != null) request.putBoolean("resume", true).put("resumeToken", token).put("resumeRoomId", roomId);
        service.handleAuth(next, request);
        return next;
    }
    void lose(Client client) { client.open = false; service.handleDisconnect(client); }

    @Test void roomInvitesRejectRepeatedAndConcurrentSendsUntilFifteenSeconds() throws Exception {
        Client sender = clients.get(0);
        Client recipient = login("p4", null, null);
        Friendship friendship = new Friendship(records.get("p1"), records.get("p4"));
        friendship.setStatus(Friendship.FriendshipStatus.ACCEPTED);
        FriendshipRepository friendships = (FriendshipRepository) ReflectionTestUtils.getField(service, "friendshipRepository");
        when(friendships.findByUsers(any(User.class), any(User.class))).thenReturn(friendship);
        Envelope request = new Envelope(MessageType.ROOM_INVITE_SEND).put("targetUsername", "p4");
        ExecutorService executor = Executors.newFixedThreadPool(4);
        try {
            List<Future<?>> sends = new ArrayList<>();
            for (int i = 0; i < 12; i++) sends.add(executor.submit(() -> service.handleRoomInviteSend(sender, request)));
            for (Future<?> send : sends) send.get(5, TimeUnit.SECONDS);
        } finally { executor.shutdownNow(); }
        assertEquals(1, recipient.messages.stream().filter(e -> e.getType() == MessageType.ROOM_INVITE_NOTIFY).count());
        assertFalse(sender.last(MessageType.FRIEND_UPDATE).isSuccess());
        assertEquals(15_000, sender.last(MessageType.FRIEND_UPDATE).getData().path("retryAfterMillis").asLong());
        now.addAndGet(14_999);
        service.handleRoomInviteSend(sender, request);
        assertEquals(1, sender.last(MessageType.FRIEND_UPDATE).getData().path("retryAfterMillis").asLong());
        assertEquals(1, recipient.messages.stream().filter(e -> e.getType() == MessageType.ROOM_INVITE_NOTIFY).count());
        now.incrementAndGet();
        service.handleRoomInviteSend(sender, request);
        assertTrue(sender.last(MessageType.FRIEND_UPDATE).isSuccess());
        assertEquals(2, recipient.messages.stream().filter(e -> e.getType() == MessageType.ROOM_INVITE_NOTIFY).count());
    }

    void drawing() {
        clients.forEach(c -> room.setPlayerReady(c.getUsername(), true));
        room.getGameSession().startGame();
        for (Client client : clients) {
            String topic = client.last(MessageType.TOPIC_OPTIONS).getData().path("topics").get(0).asText();
            room.getGameSession().onTopicSelected(client.getUsername(), topic);
        }
    }
    Envelope data(Client client, MessageType type) {
        return new Envelope(type).put("drawingId", client.last(MessageType.DRAW_START).getString("drawingId"))
                .put("draftVersion", 1).put("drawData", List.of(new StrokeDTO("BRUSH", "#000000", 3,
                        List.of(1.0, 2.0), List.of(1.0, 2.0))));
    }
    Envelope resume(Client old) { return login(old.getUsername(), old.token(), room.getRoomId()).last(MessageType.GAME_STATE_SNAPSHOT); }
    Map<?,?> states() { return (Map<?,?>) ReflectionTestUtils.getField(room.getGameSession(), "drawingStates"); }

    @ParameterizedTest @ValueSource(longs = {5000, 10000, 29999})
    void reconnectBeforeDeadlineRestoresOriginalMembership(long elapsed) {
        Client old = clients.get(0); lose(old); now.addAndGet(elapsed);
        Client recovered = login("p1", old.token(), room.getRoomId());
        assertTrue(recovered.last(MessageType.LOGIN_RESULT).getBoolean("reconnected", false));
        assertSame(recovered, room.getPlayer("p1")); assertEquals(3, room.getPlayerCount());
        now.addAndGet(40000); service.expireDisconnectedSessions(); assertSame(recovered, room.getPlayer("p1"));
        verify(users, never()).addRankingScore(anyInt(), anyInt());
    }

    @ParameterizedTest @ValueSource(longs = {30000, 30001, 60000})
    void expiredLoginCannotRestoreAndCanManuallyRejoinWaitingRoom(long elapsed) {
        Client old = clients.get(0); lose(old); now.addAndGet(elapsed);
        Client recovered = login("p1", old.token(), room.getRoomId());
        assertFalse(recovered.last(MessageType.LOGIN_RESULT).getBoolean("reconnected", false));
        assertTrue(recovered.last(MessageType.LOGIN_RESULT).getBoolean("recoveryRejected", false));
        assertNull(room.getPlayer("p1")); assertEquals(ConnectionState.LEFT, old.getConnectionState());
        assertNotEquals("p1", room.getHostUsername());
        service.handleJoinRoom(recovered, new Envelope(MessageType.ROOM_JOIN).put("roomId", room.getRoomId()));
        assertSame(recovered, room.getPlayer("p1"));
    }

    @Test void reservedCapacityReleasedAndDisconnectedReadinessIgnored() {
        for (int i = 4; i <= 8; i++) room.addPlayer(login("p" + i, null, null));
        Client old = clients.get(0); lose(old);
        room.getPlayerUsernames().forEach(u -> room.setPlayerReady(u, true));
        assertFalse(room.allPlayersReady()); assertTrue(room.isFull()); assertEquals("p1", room.getHostUsername());
        now.addAndGet(30000); service.expireDisconnectedSessions();
        assertEquals(7, room.getPlayerCount()); assertFalse(room.isFull());
        Client ninth = login("p9", null, null); service.handleJoinRoom(ninth, new Envelope(MessageType.ROOM_JOIN).put("roomId", room.getRoomId()));
        assertSame(ninth, room.getPlayer("p9"));
    }

    @Test void privateDraftAndTimerAreRecoveredWithoutRestart() {
        drawing(); Client old = clients.get(0);
        room.getGameSession().onDraftSaved("p1", data(old, MessageType.DRAWING_DRAFT_SAVE));
        long deadline = (long) ReflectionTestUtils.getField(room.getGameSession(), "phaseDeadlineMillis");
        lose(old); Envelope snapshot = resume(old);
        assertEquals("DRAWING", snapshot.getString("phase"));
        assertEquals(deadline, snapshot.getData().path("phaseDeadline").asLong());
        assertEquals(1, snapshot.getData().path("events").findValue("drawData").size());
        assertFalse(clients.get(1).messages.stream().anyMatch(e -> e.getType() == MessageType.DRAWING_RESUME || e.getType() == MessageType.DRAWING_DRAFT_ACK));
        verify(users, never()).addRankingScore(anyInt(), anyInt());
    }

    @Test void missedDrawingDeadlineSkipsDraftWithoutDrawingPenaltyAndRejectsLateSubmission() {
        drawing(); Client old = clients.get(0);
        room.getGameSession().onDraftSaved("p1", data(old, MessageType.DRAWING_DRAFT_SAVE)); lose(old);
        ReflectionTestUtils.invokeMethod(room.getGameSession(), "finishDrawingPhase");
        assertEquals("SKIPPED", states().get("p1"));
        assertFalse(((List<?>) ReflectionTestUtils.getField(room.getGameSession(), "paintingOrder")).contains("p1"));
        room.getGameSession().onDrawingSubmitted("p1", data(old, MessageType.DRAW_SUBMIT));
        assertEquals("SKIPPED", states().get("p1"));
        assertEquals("GUESSING", resume(old).getString("phase"));
        verify(users, never()).addRankingScore(anyInt(), anyInt());
    }

    @Test void acceptedDrawingSurvivesDisconnectAndDuplicateSubmissionIsIdempotent() {
        drawing(); Client old = clients.get(0); Envelope submitted = data(old, MessageType.DRAW_SUBMIT);
        room.getGameSession().onDrawingSubmitted("p1", submitted);
        room.getGameSession().onDrawingSubmitted("p1", submitted);
        verify(paintings, times(1)).save(any());
        lose(old); ReflectionTestUtils.invokeMethod(room.getGameSession(), "finishDrawingPhase");
        assertTrue(((List<?>) ReflectionTestUtils.getField(room.getGameSession(), "paintingOrder")).contains("p1"));
        assertTrue(Set.of("SUBMITTED", "PUBLISHED").contains(states().get("p1")));
    }

    @Test void expiredPlayerCannotJoinActiveMatchAndPenaltyIsOnlyAppliedOnce() {
        drawing(); Client old = clients.get(0); lose(old); now.addAndGet(30000);
        service.expireDisconnectedSessions(); service.expireDisconnectedSessions(); room.getGameSession().onPlayerLeft("p1");
        verify(users, times(1)).applyLeaveScores(eq(1), argThat(ids -> ids.containsAll(List.of(2, 3)) && ids.size() == 2), eq(20));
        assertTrue(room.isPlaying()); assertEquals(2, room.getPlayerCount());
        Client recovered = login("p1", old.token(), room.getRoomId());
        service.handleJoinRoom(recovered, new Envelope(MessageType.ROOM_JOIN).put("roomId", room.getRoomId()));
        assertFalse(recovered.last(MessageType.ROOM_RESPONSE).isSuccess());
    }

    @Test void staleSocketCannotDisconnectOrSendAfterReplacement() {
        Client old = clients.get(0); Client recovered = login("p1", null, null);
        service.handleDisconnect(old);
        assertSame(recovered, room.getPlayer("p1"));
        boolean[] invoked = {false};
        service.dispatchCurrent(old, new Envelope(MessageType.ROOM_LEAVE), () -> invoked[0] = true);
        assertFalse(invoked[0]);
    }

    @Test void concurrentRecoveryAndExpiryHaveOneAuthoritativeOutcome() throws Exception {
        Client old = clients.get(0); lose(old); now.addAndGet(30000);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Client> login = executor.submit(() -> login("p1", old.token(), room.getRoomId()));
            Future<?> expire = executor.submit(service::expireDisconnectedSessions);
            Client recovered = login.get(5, TimeUnit.SECONDS); expire.get(5, TimeUnit.SECONDS);
            assertNull(room.getPlayer("p1"));
            assertTrue(recovered.last(MessageType.LOGIN_RESULT).getBoolean("recoveryRejected", false));
        } finally { executor.shutdownNow(); }
    }

    @Test void repeatedDisconnectsHaveFreshWindowButAttemptsDoNotExtendIt() {
        Client old = clients.get(0); lose(old); long deadline = old.getReconnectDeadline();
        now.addAndGet(10000); service.handleDisconnect(old); assertEquals(deadline, old.getReconnectDeadline());
        Client recovered = login("p1", old.token(), room.getRoomId()); lose(recovered);
        assertEquals(now.get() + 30000, recovered.getReconnectDeadline());
    }

    @Test void simultaneousExpiryDissolvesMatchBelowTwoAndReleasesMembership() {
        drawing(); lose(clients.get(0)); lose(clients.get(1)); now.addAndGet(30000);
        service.expireDisconnectedSessions();
        assertNull(service.getRoomManager().getRoom(room.getRoomId()));
        assertNull(clients.get(2).getCurrentRoomId());
    }

    @Test void normalResultsDuringGraceAreRecoveredWithoutRetroactivePenalty() {
        drawing(); Client old = clients.get(0); lose(old); room.getGameSession().endGame();
        assertEquals("RESULTS", resume(old).getString("phase"));
        verify(users, never()).addRankingScore(anyInt(), eq(-20));
        verify(users, never()).applyLeaveScores(anyInt(), anyList(), anyInt());
    }

    @Test void explicitLeaveRemovesImmediatelyAndDoesNotCreateGraceWindow() {
        drawing(); Client old = clients.get(0);
        service.handleLeaveRoom(old, new Envelope(MessageType.ROOM_LEAVE));
        assertNull(room.getPlayer("p1")); lose(old); now.addAndGet(40000); service.expireDisconnectedSessions();
        verify(users, times(1)).applyLeaveScores(eq(1), anyList(), eq(20));
    }

    @Test void forgedRecoveryCannotReplaceValidSession() {
        Client forged = login("p1", "forged", room.getRoomId());
        assertFalse(forged.last(MessageType.LOGIN_RESULT).isSuccess()); assertSame(clients.get(0), room.getPlayer("p1"));
    }

    @Test void reconnectDuringGuessingPreservesAlreadyGuessedStatus() {
        drawing(); ReflectionTestUtils.invokeMethod(room.getGameSession(), "finishDrawingPhase");
        String painter = clients.get(0).last(MessageType.GUESS_START).getString("painterUsername");
        Client guesser = clients.stream().filter(c -> !c.getUsername().equals(painter)).findFirst().orElseThrow();
        Map<?,?> topics = (Map<?,?>) ReflectionTestUtils.getField(room.getGameSession(), "selectedTopics");
        room.getGameSession().onGuessSubmitted(guesser.getUsername(), (String) topics.get(painter));
        lose(guesser); Envelope recovered = resume(guesser);
        assertEquals("GUESSING", recovered.getString("phase"));
        assertTrue(recovered.getData().path("events").findValue("alreadyGuessed").asBoolean());
        assertTrue(recovered.getData().path("scores").toString().contains("100"));
    }

    @Test void duplicateAndOutOfOrderDraftVersionsCannotReplaceNewerDraft() {
        drawing(); Client player = clients.get(0);
        room.getGameSession().onDraftSaved("p1", data(player, MessageType.DRAWING_DRAFT_SAVE).put("draftVersion", 5));
        room.getGameSession().onDraftSaved("p1", data(player, MessageType.DRAWING_DRAFT_SAVE).put("draftVersion", 4).put("drawData", List.of()));
        lose(player);
        assertEquals(1, resume(player).getData().path("events").findValue("drawData").size());
    }

    @Test void dissolvedRoomCannotBeRecovered() {
        Client old = clients.get(0); lose(old); service.getRoomManager().removeRoom(room.getRoomId());
        Client recovered = login("p1", old.token(), room.getRoomId());
        assertFalse(recovered.last(MessageType.LOGIN_RESULT).getBoolean("reconnected", false));
        assertTrue(recovered.last(MessageType.LOGIN_RESULT).getBoolean("recoveryRejected", false));
    }

    @Test void keywordSelectionRecoveryKeepsPrivateChoicesAndDeadline() {
        clients.forEach(c -> room.setPlayerReady(c.getUsername(), true)); room.getGameSession().startGame();
        long deadline = (long) ReflectionTestUtils.getField(room.getGameSession(), "phaseDeadlineMillis");
        Client old = clients.get(0); lose(old); Envelope snapshot = resume(old);
        assertEquals("TOPIC_SELECT", snapshot.getString("phase"));
        assertEquals(deadline, snapshot.getData().path("phaseDeadline").asLong());
        assertEquals(old.last(MessageType.TOPIC_OPTIONS).getData().path("topics"), snapshot.getData().path("events").findValue("topics"));
    }

    @Test void recoveryAfterNextPaintingUsesCurrentPainterAndDeadline() {
        drawing(); ReflectionTestUtils.invokeMethod(room.getGameSession(), "finishDrawingPhase");
        Client old = clients.get(0); lose(old);
        ReflectionTestUtils.invokeMethod(room.getGameSession(), "finishCurrentRound");
        ReflectionTestUtils.invokeMethod(room.getGameSession(), "showNextPainting");
        String painter = clients.get(1).last(MessageType.GUESS_START).getString("painterUsername");
        Envelope snapshot = resume(old);
        assertEquals(painter, snapshot.getData().path("events").findValue("painterUsername").asText());
        assertEquals(ReflectionTestUtils.getField(room.getGameSession(), "phaseDeadlineMillis"), snapshot.getData().path("phaseDeadline").asLong());
    }

    @Test void twoConcurrentRecoveriesStillLeaveOnlyOneAuthoritativeSocket() throws Exception {
        Client old = clients.get(0); lose(old);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Client> first = executor.submit(() -> login("p1", old.token(), room.getRoomId()));
            Future<Client> second = executor.submit(() -> login("p1", old.token(), room.getRoomId()));
            Client a = first.get(5, TimeUnit.SECONDS), b = second.get(5, TimeUnit.SECONDS);
            assertTrue(room.getPlayer("p1") == a || room.getPlayer("p1") == b);
            assertNotEquals(a.isConnected(), b.isConnected());
            now.addAndGet(40000); service.expireDisconnectedSessions();
            assertEquals(3, room.getPlayerCount());
        } finally { executor.shutdownNow(); }
    }

    @Test void changingAccountOnAuthenticatedSocketIsRejected() {
        Client current = clients.get(0);
        service.handleAuth(current, new Envelope(MessageType.LOGIN).put("username", "p2").put("password", "password"));
        assertFalse(current.last(MessageType.LOGIN_RESULT).isSuccess());
        assertEquals("p1", current.getUsername()); assertSame(current, room.getPlayer("p1"));
    }

    @Test void kickedPlayerCanContinueUsingLobbyWithoutReauthentication() {
        Client current = clients.get(1);
        service.handleRoomKickPlayer(clients.get(0), new Envelope(MessageType.ROOM_KICK).put("targetUsername", "p2"));
        assertNull(current.getCurrentRoomId()); assertTrue(current.isConnected());
        boolean[] allowed = {false};
        service.dispatchCurrent(current, new Envelope(MessageType.ROOM_LIST), () -> allowed[0] = true);
        assertTrue(allowed[0]);
    }
}
