package com.nhom8.server;

import com.nhom8.common.message.Envelope;
import com.nhom8.common.message.MessageType;
import com.nhom8.server.repository.TopicEntryRepository;
import com.nhom8.server.repository.MatchRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class AdminAndConcurrencyTest {
    @LocalServerPort int port;
    @Autowired TopicEntryRepository topics;
    @Autowired MatchRepository matches;

    static class Client extends TextWebSocketHandler implements AutoCloseable {
        WebSocketSession session;
        volatile String drawingId;
        LinkedBlockingQueue<Envelope> inbox = new LinkedBlockingQueue<>();
        void connect(int port) throws Exception {
            new StandardWebSocketClient().execute(this, "ws://localhost:" + port + "/ws/game")
                    .get(5, TimeUnit.SECONDS);
        }
        @Override public void afterConnectionEstablished(WebSocketSession s) { session = s; }
        @Override protected void handleTextMessage(WebSocketSession s, TextMessage m) {
            Envelope received = Envelope.fromJson(m.getPayload());
            if (received.getString("drawingId") != null) drawingId = received.getString("drawingId");
            inbox.add(received);
        }
        void send(MessageType type, String key, Object value) throws Exception {
            Envelope msg = new Envelope(type);
            if (type == MessageType.DRAW_SUBMIT || type == MessageType.STROKE_BATCH) msg.put("drawingId", drawingId);
            if (key != null) msg.put(key, value);
            session.sendMessage(new TextMessage(msg.toJson()));
        }
        Envelope await(MessageType type) throws Exception {
            long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
            while (System.nanoTime() < end) {
                Envelope next = inbox.poll(300, TimeUnit.MILLISECONDS);
                if (next != null && next.getType() == type) return next;
            }
            fail("Không nhận được " + type);
            return null;
        }
        void authenticate(String username) throws Exception {
            session.sendMessage(new TextMessage(new Envelope(MessageType.LOGIN)
                    .put("username", username).put("password", "123456").toJson()));
            assertTrue(await(MessageType.LOGIN_RESULT).isSuccess());
        }
        @Override public void close() throws Exception { if (session != null) session.close(); }
    }

    @Test void adminPermissionsTopicsRoomsAndSingleGameStart() throws Exception {
        try (Client admin = new Client(); Client p1 = new Client(); Client p2 = new Client(); Client p3 = new Client()) {
            admin.connect(port); p1.connect(port); p2.connect(port); p3.connect(port);
            admin.authenticate("admin"); p1.authenticate("player1"); p2.authenticate("player2"); p3.authenticate("player3");

            p1.send(MessageType.ADMIN_STATS, null, null);
            assertFalse(p1.await(MessageType.ADMIN_STATS).isSuccess());
            admin.send(MessageType.ADMIN_STATS, null, null);
            assertTrue(admin.await(MessageType.ADMIN_STATS).getData().path("totalUsers").asInt() >= 3);

            String word = "kiểm thử quản trị";
            admin.send(MessageType.ADMIN_TOPIC_ADD, "word", word);
            assertTrue(admin.await(MessageType.ADMIN_TOPIC_ADD).isSuccess());
            assertTrue(topics.findAll().stream().anyMatch(t -> t.isActive() && t.getWord().equals(word)));
            p1.send(MessageType.ADMIN_TOPIC_DELETE, "word", word);
            assertFalse(p1.await(MessageType.ADMIN_TOPIC_DELETE).isSuccess());
            assertTrue(topics.findAll().stream().anyMatch(t -> t.isActive() && t.getWord().equals(word)));
            admin.send(MessageType.ADMIN_TOPIC_DELETE, "word", word);
            assertTrue(admin.await(MessageType.ADMIN_TOPIC_DELETE).isSuccess());
            assertFalse(topics.findAll().stream().anyMatch(t -> t.isActive() && t.getWord().equals(word)));

            p1.send(MessageType.ROOM_CREATE, "roomName", "Kiểm thử");
            String roomId = p1.await(MessageType.ROOM_RESPONSE).getString("roomId");
            p2.send(MessageType.ROOM_JOIN, "roomId", roomId);
            assertTrue(p2.await(MessageType.ROOM_RESPONSE).isSuccess());
            long before = matches.count();
            p1.send(MessageType.READY, "isReady", true);
            p2.send(MessageType.READY, "isReady", true);
            // Phòng có hai người đã sẵn sàng vẫn phải chờ người thứ ba.
            p2.send(MessageType.ADMIN_STATS, null, null);
            assertFalse(p2.await(MessageType.ADMIN_STATS).isSuccess());
            assertEquals(before, matches.count());
            p3.send(MessageType.ROOM_JOIN, "roomId", roomId);
            assertTrue(p3.await(MessageType.ROOM_RESPONSE).isSuccess());
            p3.send(MessageType.READY, "isReady", true);
            p1.send(MessageType.READY, "isReady", true);
            p2.send(MessageType.READY, "isReady", true);
            Envelope countdown;
            do { countdown = p1.await(MessageType.ROOM_UPDATE); }
            while (countdown.getData().path("startCountdownDeadlineMillis").asLong() == 0);
            assertTrue(countdown.getData().path("startCountdownDeadlineMillis").asLong()
                    > countdown.getData().path("serverTime").asLong());
            assertEquals(before, matches.count());
            p2.send(MessageType.READY, "isReady", false);
            do { countdown = p1.await(MessageType.ROOM_UPDATE); }
            while (countdown.getData().path("startCountdownDeadlineMillis").asLong() > 0);
            Thread.sleep(3200);
            assertEquals(before, matches.count());
            p2.send(MessageType.READY, "isReady", true);
            do { countdown = p1.await(MessageType.ROOM_UPDATE); }
            while (countdown.getData().path("startCountdownDeadlineMillis").asLong() == 0);
            p1.await(MessageType.GAME_START);
            Thread.sleep(400);
            assertEquals(before + 1, matches.count());

            String topic1 = p1.await(MessageType.TOPIC_OPTIONS).getData().path("topics").get(0).asText();
            String topic2 = p2.await(MessageType.TOPIC_OPTIONS).getData().path("topics").get(0).asText();
            String topic3 = p3.await(MessageType.TOPIC_OPTIONS).getData().path("topics").get(0).asText();
            p1.send(MessageType.TOPIC_SELECT, "topic", topic1);
            p2.send(MessageType.TOPIC_SELECT, "topic", topic2);
            p3.send(MessageType.TOPIC_SELECT, "topic", topic3);
            p1.await(MessageType.DRAW_START);
            p2.await(MessageType.DRAW_START);
            p3.await(MessageType.DRAW_START);
            java.util.Map<String, Object> eraser = java.util.Map.of(
                    "tool", "ERASER", "color", "#FFFFFF", "width", 20,
                    "xPoints", java.util.List.of(10, 20), "yPoints", java.util.List.of(10, 20));
            p1.send(MessageType.DRAW_SUBMIT, "drawData", java.util.List.of(eraser));
            p2.send(MessageType.DRAW_SUBMIT, "drawData", java.util.List.of(eraser));
            p3.send(MessageType.DRAW_SUBMIT, "drawData", java.util.List.of(eraser));
            Envelope painting = p1.await(MessageType.PAINTING_DISPLAY);
            assertEquals("ERASER", painting.getData().path("drawData").get(0).path("tool").asText());

            admin.send(MessageType.ADMIN_DISSOLVE_ROOM, "roomId", roomId);
            assertTrue(admin.await(MessageType.ADMIN_DISSOLVE_ROOM).isSuccess());
            assertEquals(MessageType.ROOM_DISSOLVED, p1.await(MessageType.ROOM_DISSOLVED).getType());
        }
    }

    @Test void reconnectRestoresDrawingOverRealWebSocket() throws Exception {
        try (Client p1 = new Client(); Client p2 = new Client(); Client p3 = new Client(); Client recovered = new Client()) {
            p1.connect(port); p2.connect(port); p3.connect(port);
            p1.authenticate("player1"); p2.authenticate("player2"); p3.authenticate("player3");
            // Xin lại kết quả đăng nhập để lấy mã khôi phục của kết nối hiện tại.
            p1.session.sendMessage(new TextMessage(new Envelope(MessageType.LOGIN).put("username", "player1").put("password", "123456").toJson()));
            String token = p1.await(MessageType.LOGIN_RESULT).getString("resumeToken");
            p1.send(MessageType.ROOM_CREATE, "roomName", "Khôi phục bản nháp");
            String roomId = p1.await(MessageType.ROOM_RESPONSE).getString("roomId");
            p2.send(MessageType.ROOM_JOIN, "roomId", roomId); assertTrue(p2.await(MessageType.ROOM_RESPONSE).isSuccess());
            p3.send(MessageType.ROOM_JOIN, "roomId", roomId); assertTrue(p3.await(MessageType.ROOM_RESPONSE).isSuccess());
            p1.send(MessageType.READY, "isReady", true); p2.send(MessageType.READY, "isReady", true); p3.send(MessageType.READY, "isReady", true);
            for (Client player : java.util.List.of(p1, p2, p3)) {
                Envelope options = player.await(MessageType.TOPIC_OPTIONS);
                player.send(MessageType.TOPIC_SELECT, "topic", options.getData().path("topics").get(0).asText());
            }
            Envelope drawing = p1.await(MessageType.DRAW_START);
            long deadline = drawing.getData().path("phaseDeadline").asLong();
            p1.session.sendMessage(new TextMessage(new Envelope(MessageType.DRAWING_DRAFT_SAVE).put("drawingId", p1.drawingId)
                    .put("draftVersion", 1).put("drawData", java.util.List.of(java.util.Map.of("tool", "BRUSH", "color", "#000000", "width", 3,
                            "xPoints", java.util.List.of(1, 2), "yPoints", java.util.List.of(1, 2)))).toJson()));
            p1.await(MessageType.DRAWING_DRAFT_ACK);
            p1.close(); p2.await(MessageType.PLAYER_DISCONNECTED);
            recovered.connect(port);
            recovered.session.sendMessage(new TextMessage(new Envelope(MessageType.LOGIN).put("username", "player1").put("password", "123456")
                    .putBoolean("resume", true).put("resumeToken", token).put("resumeRoomId", roomId).toJson()));
            assertTrue(recovered.await(MessageType.LOGIN_RESULT).getBoolean("reconnected", false));
            Envelope snapshot = recovered.await(MessageType.GAME_STATE_SNAPSHOT);
            assertEquals("DRAWING", snapshot.getString("phase"));
            assertEquals(deadline, snapshot.getData().path("phaseDeadline").asLong());
            assertEquals(1, snapshot.getData().path("events").findValue("drawData").size());
            assertEquals(4, matches.findAll().stream().filter(m -> m.getRoomId().equals(roomId)).findFirst().orElseThrow().getScoringVersion());
            p2.send(MessageType.ROOM_CHAT_SEND, "content", "92839283");
            assertEquals("92839283", p3.await(MessageType.ROOM_CHAT_RECEIVE).getString("content"));
            p2.session.sendMessage(new TextMessage(new Envelope(MessageType.DIRECT_MESSAGE_SEND)
                    .put("targetUsername", "player1").put("content", "Chat sau reconnect").toJson()));
            assertEquals("Chat sau reconnect", recovered.await(MessageType.DIRECT_MESSAGE_RECEIVE).getString("content"));
            recovered.send(MessageType.LEADERBOARD_REQUEST, null, null);
            assertTrue(recovered.await(MessageType.LEADERBOARD).getData().path("leaderboard").isArray());
            recovered.send(MessageType.ROOM_LEAVE, null, null);
            p2.send(MessageType.ROOM_LEAVE, null, null);
            p3.await(MessageType.ROOM_DISSOLVED);
        }
    }
}
