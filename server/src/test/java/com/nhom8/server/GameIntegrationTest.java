package com.nhom8.server;

import com.nhom8.common.message.Envelope;
import com.nhom8.common.message.MessageType;
import com.nhom8.server.util.JsonUtil;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
public class GameIntegrationTest {
    private static final java.util.Set<String> BOT_ANSWERS = java.util.concurrent.ConcurrentHashMap.newKeySet();

    @LocalServerPort
    private int port;

    @Test
    public void testAutoPlayBotSimulation() throws Exception {
        BOT_ANSWERS.clear();
        System.out.println("Starting full integration test: 3 Bots playing automatically");
        int NUM_BOTS = 3;
        List<BotClient> bots = new ArrayList<>();
        CountDownLatch finishLatch = new CountDownLatch(1);

        for (int i = 1; i <= NUM_BOTS; i++) {
            BotClient bot = new BotClient("bot" + i, "password");
            bot.connect("ws://localhost:" + port + "/ws/game");
            bots.add(bot);
        }

        // Wait for connection
        Thread.sleep(1000);

        // 1. Register & Login
        for (BotClient bot : bots) {
            bot.sendRegisterAndLogin();
        }

        // Wait for all logins to complete
        Thread.sleep(1500);

        // 2. Bot 1 creates a room
        bots.get(0).sendRoomCreate();
        Thread.sleep(1000);
        
        // Find roomId from Bot 1's messages
        String roomId = null;
        for (Envelope env : bots.get(0).getReceivedMessages()) {
            if (env.getType() == MessageType.ROOM_UPDATE && env.getData().has("roomId")) {
                roomId = env.getString("roomId");
            }
        }
        
        if (roomId == null) {
            System.err.println("Room creation failed or room ID not found. Using default 'R1000'");
            roomId = "R1000"; // fallback
        }

        System.out.println("Bot 1 created room: " + roomId);

        // 3. Bot 2 and Bot 3 join room
        bots.get(1).sendRoomJoin(roomId);
        bots.get(2).sendRoomJoin(roomId);
        Thread.sleep(1000);

        // 3.5 Set Room draw time to 5 seconds to speed up test
        Envelope settings = new Envelope(MessageType.ROOM_SETTINGS);
        settings.put("drawTime", 5);
        settings.put("maxRounds", 1);
        bots.get(0).send(settings);
        Thread.sleep(500);

        // 4. All bots ready
        for (BotClient bot : bots) {
            bot.sendReady();
        }

        // 5. Chọn một trong các chủ đề thật được cấp cho từng người chơi.
        // Server shows the synchronized 3–2–1 start countdown before topic selection.
        Thread.sleep(4000);
        for (BotClient bot : bots) {
            for (Envelope env : bot.getReceivedMessages()) {
                if (env.getType() == MessageType.TOPIC_OPTIONS && env.getData().path("topics").isArray()
                        && !env.getData().path("topics").isEmpty()) {
                    bot.sendTopicSelect(env.getData().path("topics").get(0).asText());
                    break;
                }
            }
        }

        // 6. Draw phase
        Thread.sleep(2000);
        for (BotClient bot : bots) {
            bot.sendDrawing();
        }

        // 7. Guess phase - wait for drawing phase to finish and guessing phase to start
        Thread.sleep(6000); // Wait for draw timeout (5s total)
        
        // Each painting has the fixed 30-second guessing window.
        boolean gameFinished = false;
        for (int i = 0; i < 105; i++) {
            Thread.sleep(1000);
            for (Envelope env : bots.get(0).getReceivedMessages()) {
                if (env.getType() == MessageType.GAME_RESULT) {
                    gameFinished = true;
                    break;
                }
            }
            if (gameFinished) break;
        }

        System.out.println("Test simulated successfully. Game Finished: " + gameFinished);
        
        for (BotClient bot : bots) {
            bot.disconnect();
        }
        
        assertTrue(gameFinished, "Integration test should reach GAME_RESULT.");
    }

    private static class BotClient extends TextWebSocketHandler {
        private String username;
        private String password;
        private WebSocketSession session;
        private volatile String drawingId;
        private volatile String guessDrawingId;
        private BlockingQueue<Envelope> receivedMessages = new LinkedBlockingQueue<>();
        private List<Envelope> allMessages = new java.util.concurrent.CopyOnWriteArrayList<>();

        public BotClient(String username, String password) {
            this.username = username;
            this.password = password;
        }

        public void connect(String url) throws Exception {
            StandardWebSocketClient client = new StandardWebSocketClient();
            client.execute(this, url).get(5, TimeUnit.SECONDS);
        }

        @Override
        public void afterConnectionEstablished(WebSocketSession session) {
            this.session = session;
        }

        @Override
        protected void handleTextMessage(WebSocketSession session, TextMessage message) {
            Envelope env = JsonUtil.fromJson(message.getPayload(), Envelope.class);
            if (env != null) {
                if (env.getString("drawingId") != null) drawingId = env.getString("drawingId");
                receivedMessages.offer(env);
                allMessages.add(env);
                
                if (env.getType() == MessageType.GUESS_START) {
                    guessDrawingId = env.getString("drawingId");
                    try {
                        for (String answer : BOT_ANSWERS) sendGuess(answer);
                    } catch (Exception e) {}
                }
            }
        }

        public void send(Envelope envelope) throws Exception {
            if (envelope.getType() == MessageType.DRAW_SUBMIT || envelope.getType() == MessageType.STROKE_BATCH) envelope.put("drawingId", drawingId);
            if (session != null && session.isOpen()) {
                session.sendMessage(new TextMessage(JsonUtil.toJson(envelope)));
            }
        }

        public void sendRegisterAndLogin() throws Exception {
            Envelope reg = new Envelope(MessageType.REGISTER);
            reg.put("username", username);
            reg.put("password", password);
            send(reg);
            Thread.sleep(200);

            Envelope login = new Envelope(MessageType.LOGIN);
            login.put("username", username);
            login.put("password", password);
            send(login);
        }

        public void sendRoomCreate() throws Exception {
            send(new Envelope(MessageType.ROOM_CREATE));
        }

        public void sendRoomJoin(String roomId) throws Exception {
            Envelope env = new Envelope(MessageType.ROOM_JOIN);
            env.put("roomId", roomId);
            send(env);
        }

        public void sendReady() throws Exception {
            send(new Envelope(MessageType.READY));
        }

        public void sendTopicSelect(String topic) throws Exception {
            BOT_ANSWERS.add(topic);
            Envelope env = new Envelope(MessageType.TOPIC_SELECT);
            env.put("topic", topic);
            send(env);
        }

        public void sendDrawing() throws Exception {
            Envelope env = new Envelope(MessageType.STROKE_BATCH);
            env.put("drawData", new ArrayList<>());
            send(env);
        }

        public void sendGuess(String guess) throws Exception {
            Envelope env = new Envelope(MessageType.GUESS_SUBMIT);
            env.put("drawingId", guessDrawingId);
            env.setContent(guess);
            send(env);
        }
        
        public void disconnect() throws Exception {
            if (session != null && session.isOpen()) {
                session.close();
            }
        }
        
        public List<Envelope> getReceivedMessages() {
            return new ArrayList<>(allMessages);
        }
    }
}
