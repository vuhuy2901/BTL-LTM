package com.nhom8.server.ws;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.nhom8.common.message.Envelope;
import com.nhom8.server.service.GameService;
import com.nhom8.server.util.JsonUtil;

import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Spring WebSocket Handler — điểm tiếp nhận kết nối WebSocket từ JavaFX Client.
 */
@Component
public class GameWebSocketHandler extends TextWebSocketHandler {

    /** Bảng ánh xạ sessionId → ClientSession */
    private final ConcurrentHashMap<String, ClientSession> sessions = new ConcurrentHashMap<>();

    /** Service xử lý logic nghiệp vụ */
    private final GameService gameService;
    @org.springframework.beans.factory.annotation.Value("${game.heartbeat-interval-ms:5000}")
    private long heartbeatInterval = 5000;
    @org.springframework.beans.factory.annotation.Value("${game.heartbeat-timeout-ms:12000}")
    private long heartbeatTimeout = 12000;
    private final java.util.concurrent.ScheduledExecutorService health = java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "server-connection-health"); thread.setDaemon(true); return thread;
    });

    @jakarta.annotation.PostConstruct public void startHealthChecks() {
        health.scheduleAtFixedRate(() -> {
            for (ClientSession client : sessions.values()) {
                if (!client.isConnected()) continue;
                try {
                    if (System.currentTimeMillis() - client.getLastPongMillis() > heartbeatTimeout) {
                        gameService.handleDisconnect(client); client.disconnect();
                    } else client.ping();
                } catch (Exception ex) { gameService.handleDisconnect(client); client.disconnect(); }
            }
        }, heartbeatInterval, heartbeatInterval, java.util.concurrent.TimeUnit.MILLISECONDS);
    }

    @jakarta.annotation.PreDestroy public void stopHealthChecks() { health.shutdownNow(); }

    @Override protected void handlePongMessage(WebSocketSession session, org.springframework.web.socket.PongMessage message) {
        ClientSession client = sessions.get(session.getId());
        if (client != null) client.receivedPong();
    }

    public GameWebSocketHandler(GameService gameService) {
        this.gameService = gameService;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        session.setTextMessageSizeLimit(10 * 1024 * 1024); // 10MB
        session.setBinaryMessageSizeLimit(10 * 1024 * 1024); // 10MB

        String address = session.getRemoteAddress() != null
                ? session.getRemoteAddress().toString() : session.getId();
        System.out.println("[SERVER] Kết nối mới từ: " + address);

        ClientSession clientSession = new ClientSession(session);
        sessions.put(session.getId(), clientSession);
        gameService.addClient(clientSession);
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage textMessage) {
        ClientSession clientSession = sessions.get(session.getId());
        if (clientSession == null) return;

        try {
            Envelope envelope = JsonUtil.fromJson(textMessage.getPayload(), Envelope.class);
            if (envelope != null && envelope.getType() != null) {
                System.out.println("[SERVER] Nhận từ " + clientSession.getDisplayName() + ": " + envelope.getType());
                try {
                    gameService.dispatchCurrent(clientSession, envelope, () -> dispatchMessage(clientSession, envelope));
                } catch (Exception ex) {
                    System.err.println("[SERVER] Lỗi xử lý thông điệp " + envelope.getType()
                            + " từ " + clientSession.getDisplayName() + ": " + ex.getMessage());
                    ex.printStackTrace();
                }
            }
        } catch (Exception e) {
            System.err.println("[SERVER] JSON không hợp lệ từ "
                    + clientSession.getDisplayName() + ": " + e.getMessage());
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        ClientSession clientSession = sessions.remove(session.getId());
        if (clientSession != null) {
            System.out.println("[SERVER] Ngắt kết nối: " + clientSession.getDisplayName()
                    + " (code=" + status.getCode() + ", reason=" + status.getReason() + ")");
            gameService.handleDisconnect(clientSession);
        }
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        System.err.println("[SERVER] Lỗi WebSocket: " + exception.getMessage());
        ClientSession clientSession = sessions.get(session.getId());
        if (clientSession != null) {
            System.err.println("[SERVER] Lỗi từ client: " + clientSession.getDisplayName());
            gameService.handleDisconnect(clientSession);
            clientSession.disconnect();
        }
    }

    private void dispatchMessage(ClientSession handler, Envelope envelope) {
        switch (envelope.getType()) {
            case LOGIN:
            case LOGIN_REQUEST:
                gameService.handleAuth(handler, envelope);
                break;
            case REGISTER:
            case REGISTER_REQUEST:
                gameService.handleRegister(handler, envelope);
                break;
            case PLAYER_LIST:
            case ONLINE_LIST:
                gameService.sendPlayerList(handler);
                break;
            case ROOM_LIST:
                gameService.sendRoomList(handler);
                break;
            case ROOM_CREATE:
            case CREATE_ROOM:
                gameService.handleCreateRoom(handler, envelope);
                break;
            case ROOM_JOIN:
            case JOIN_ROOM:
                gameService.handleJoinRoom(handler, envelope);
                break;
            case ROOM_LEAVE:
            case LEAVE_ROOM:
                gameService.handleLeaveRoom(handler, envelope);
                break;
            case READY:
                gameService.handleReady(handler, envelope);
                break;
            case ROOM_SETTINGS:
            case ROOM_UPDATE_SETTINGS:
                gameService.handleRoomUpdateSettings(handler, envelope);
                break;
            case ROOM_KICK:
            case ROOM_KICK_PLAYER:
                gameService.handleRoomKickPlayer(handler, envelope);
                break;
            case ROOM_CHAT:
            case ROOM_CHAT_SEND:
                gameService.handleRoomChatSend(handler, envelope);
                break;
            case TOPIC_SELECT:
                gameService.handleTopicSelect(handler, envelope);
                break;
            case STROKE_BATCH:
                gameService.handleDrawData(handler, envelope);
                break;
            case DRAWING_DRAFT_SAVE:
                gameService.handleDrawingDraft(handler, envelope);
                break;
            case DRAW_SUBMIT:
                gameService.handleDrawSubmit(handler, envelope);
                break;
            case GUESS_SUBMIT:
                gameService.handleGuessSubmit(handler, envelope);
                break;
            case ROOM_INVITE_SEND:
                gameService.handleRoomInviteSend(handler, envelope);
                break;
            case ROOM_INVITE_RESPOND:
                gameService.handleRoomInviteRespond(handler, envelope);
                break;
            case CHAT_PRIVATE:
            case DIRECT_MESSAGE_SEND:
                gameService.handleDirectMessageSend(handler, envelope);
                break;
            case CHAT_HISTORY:
            case CHAT_HISTORY_REQUEST:
                gameService.handleChatHistoryRequest(handler, envelope);
                break;
            case CHAT_INBOX_REQUEST:
                gameService.handleChatInboxRequest(handler);
                break;
            case CHAT_MARK_READ:
                gameService.handleChatMarkRead(handler, envelope);
                break;
            case FRIEND_REQUEST:
            case FRIEND_REQUEST_SEND:
                gameService.handleFriendRequest(handler, envelope);
                break;
            case FRIEND_RESPONSE:
            case FRIEND_REQUEST_RESPOND:
                gameService.handleFriendResponse(handler, envelope);
                break;
            case FRIEND_LIST:
            case FRIEND_LIST_REQUEST:
                gameService.handleFriendList(handler, envelope);
                break;
            case FRIEND_REMOVE:
                gameService.handleFriendRemove(handler, envelope);
                break;
            case FRIEND_CANCEL_REQUEST:
                gameService.handleFriendCancelRequest(handler, envelope);
                break;
            case FRIEND_PENDING_LIST:
                gameService.handleFriendPendingList(handler, envelope);
                break;
            case SEARCH_PLAYER:
            case SEARCH_USER_REQUEST:
                gameService.handleSearchPlayer(handler, envelope);
                break;
            case LEADERBOARD:
            case LEADERBOARD_REQUEST:
                gameService.handleLeaderboard(handler, envelope);
                break;
            case PROFILE:
            case PROFILE_REQUEST:
                gameService.handleProfileRequest(handler, envelope);
                break;
            case UPDATE_PROFILE:
            case UPDATE_PROFILE_REQUEST:
                gameService.handleUpdateProfile(handler, envelope);
                break;
            case MATCH_HISTORY:
            case MATCH_HISTORY_REQUEST:
                gameService.handleMatchHistory(handler, envelope);
                break;
            case MATCH_DETAIL_REQUEST:
                gameService.handleMatchDetail(handler, envelope);
                break;
            case PAINTING_REPLAY_REQUEST:
                gameService.handlePaintingReplay(handler, envelope);
                break;
            case ADMIN_GET_USERS:
                gameService.handleAdminGetUsers(handler, envelope);
                break;
            case ADMIN_UPDATE_SCORE:
                gameService.handleAdminUpdateScore(handler, envelope);
                break;
            case ADMIN_DISSOLVE_ROOM:
                gameService.handleAdminDissolveRoom(handler, envelope);
                break;
            case ADMIN_GET_ROOMS:
                gameService.handleAdminGetRooms(handler);
                break;
            case ADMIN_STATS:
                gameService.handleAdminStats(handler);
                break;
            case ADMIN_TOPICS:
                gameService.handleAdminTopics(handler);
                break;
            case ADMIN_TOPIC_ADD:
                gameService.handleAdminTopicAdd(handler, envelope);
                break;
            case ADMIN_TOPIC_DELETE:
                gameService.handleAdminTopicDelete(handler, envelope);
                break;
            case ADMIN_TOPIC_UPDATE:
                gameService.handleAdminTopicUpdate(handler, envelope);
                break;
            default:
                System.out.println("[SERVER] Chưa hỗ trợ loại thông điệp: " + envelope.getType());
                break;
        }
    }
}
