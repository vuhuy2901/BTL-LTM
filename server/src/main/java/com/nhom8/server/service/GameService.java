package com.nhom8.server.service;


import org.springframework.stereotype.Service;

import com.nhom8.server.game.Room;
import com.nhom8.server.game.RoomManager;
import com.nhom8.server.model.Friendship;
import com.nhom8.server.model.User;
import com.nhom8.server.model.TopicEntry;
import com.nhom8.server.util.TopicPool;
import com.nhom8.common.message.Envelope;
import com.nhom8.common.message.MessageType;
import com.nhom8.server.repository.FriendshipRepository;
import com.nhom8.server.repository.MatchRepository;
import com.nhom8.server.repository.MatchResultRepository;
import com.nhom8.server.repository.PaintingRepository;
import com.nhom8.server.repository.UserRepository;
import com.nhom8.server.ws.ClientSession;

import java.util.*;
import java.util.concurrent.*;

/**
 * Lớp điều khiển trung tâm (Controller/Service) phía Server của trò chơi "Vẽ Hình Đoán Ý".
 * Thay thế ServerControl trong phiên bản cũ, nay được Spring quản lý (@Service).
 *
 * Thay đổi so với phiên bản cũ:
 * - ClientHandler → ClientSession
 * - UserDAO, MatchDAO... → UserRepository, MatchRepository... (Spring Data JPA, @Autowired)
 * - new RoomManager(this) → vẫn giữ pattern cũ vì RoomManager cần tham chiếu ngược
 *
 * Logic nghiệp vụ giữ nguyên 100%.
 */
@Service
public class GameService {

    @org.springframework.beans.factory.annotation.Value("${game.reconnect-grace-ms:30000}")
    private long reconnectGraceMillis = 30_000;
    @org.springframework.beans.factory.annotation.Value("${game.scoring.minimum-exposure-ms:10000}")
    private long minimumGuessExposureMillis = 10_000;

    public long getMinimumGuessExposureMillis() { return minimumGuessExposureMillis; }
    private java.util.function.LongSupplier clock = System::currentTimeMillis;
    private final Map<String, String> recoveryTokens = new ConcurrentHashMap<>();

    /** Bảng ánh xạ username → ClientSession cho tất cả người chơi đang trực tuyến */
    private final ConcurrentHashMap<String, ClientSession> onlineClients = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Set<String>> roomInvites = new ConcurrentHashMap<>();
    private static final long ROOM_INVITE_COOLDOWN_MS = 15_000;
    private record InvitePair(String sender, String recipient) { }
    private final Map<InvitePair, Long> roomInviteRetryAt = new HashMap<>();
    private final ConcurrentHashMap<String, PendingReconnect> pendingReconnects = new ConcurrentHashMap<>();
    private final ScheduledExecutorService reconnectScheduler = Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread = new Thread(task, "player-reconnect-expiry");
        thread.setDaemon(true);
        return thread;
    });

    /** Danh sách tất cả ClientSession đang kết nối (kể cả chưa đăng nhập) */
    private final List<ClientSession> allClients = Collections.synchronizedList(new ArrayList<>());

    /** Quản lý phòng chơi */
    private final RoomManager roomManager;

    // === Spring Data JPA Repositories (thay DAO) ===
    private final UserRepository userRepository;
    private final MatchRepository matchRepository;
    private final MatchResultRepository matchResultRepository;
    private final PaintingRepository paintingRepository;
    private final FriendshipRepository friendshipRepository;
    private final org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder roomPasswordEncoder =
            new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder();

    private static final class PendingReconnect {
        private final String roomId;
        private final ClientSession disconnectedSession;
        private final long deadline;
        private volatile ScheduledFuture<?> expiryTask;

        private PendingReconnect(String roomId, ClientSession disconnectedSession, long deadline) {
            this.roomId = roomId;
            this.disconnectedSession = disconnectedSession;
            this.deadline = deadline;
        }
    }

    @jakarta.annotation.PreDestroy
    public void stopReconnectScheduler() {
        reconnectScheduler.shutdownNow();
    }

    @org.springframework.beans.factory.annotation.Autowired
    private TopicCatalog topicCatalog;

    @jakarta.annotation.PostConstruct
    public void loadCustomTopics() {
        reconnectScheduler.scheduleAtFixedRate(this::expireDisconnectedSessions, 1, 1, TimeUnit.SECONDS);
    }

    @org.springframework.beans.factory.annotation.Autowired
    private com.nhom8.server.repository.ChatMessageRepository chatMessageRepository;

    @org.springframework.beans.factory.annotation.Autowired
    @org.springframework.context.annotation.Lazy
    private FriendService friendService;

    /**
     * Constructor injection — Spring tự động inject các Repository.
     */
    public GameService(UserRepository userRepository,
                       MatchRepository matchRepository,
                       MatchResultRepository matchResultRepository,
                       PaintingRepository paintingRepository,
                       FriendshipRepository friendshipRepository) {
        this.userRepository = userRepository;
        this.matchRepository = matchRepository;
        this.matchResultRepository = matchResultRepository;
        this.paintingRepository = paintingRepository;
        this.friendshipRepository = friendshipRepository;
        this.roomManager = new RoomManager(this);
    }

    /**
     * Thêm ClientSession mới vào danh sách kết nối.
     */
    public void addClient(ClientSession handler) {
        allClients.add(handler);
    }

    public int getOnlineCount() {
        return onlineClients.size();
    }

    public int getRoomCount() {
        return roomManager.getRoomCount();
    }

    // ========================================================================
    // XỬ LÝ XÁC THỰC TÀI KHOẢN
    // ========================================================================

    @org.springframework.beans.factory.annotation.Autowired
    private AuthService authService;

    public synchronized void handleAuth(ClientSession handler, Envelope request) {
        String username = request.get("username");
        String password = request.get("password");
        if (username == null || password == null) return;

        try {
            com.nhom8.common.message.payload.LoginResultPayload payload = authService.login(username, password);
            username = payload.getPlayer().getUsername();
            if (handler.getUsername() != null && !handler.getUsername().equals(username)) {
                handler.sendMessage(Envelope.error(MessageType.LOGIN_RESULT, "Vui lòng đăng xuất trước khi đổi tài khoản."));
                return;
            }
            boolean resumeRequested = request.getBoolean("resume", false);
            if (resumeRequested && !Objects.equals(recoveryTokens.get(username), request.getString("resumeToken"))) {
                handler.sendMessage(Envelope.error(MessageType.LOGIN_RESULT, "Phiên khôi phục không hợp lệ.")
                        .putBoolean("recoveryRejected", true));
                return;
            }
            if (resumeRequested && request.getString("resumeToken") == null) {
                handler.sendMessage(Envelope.error(MessageType.LOGIN_RESULT, "Thiếu mã khôi phục phiên."));
                return;
            }
            ClientSession oldSession = onlineClients.get(username);
            if (oldSession != null && oldSession != handler) {
                if (oldSession.isConnected()) {
                    if (!resumeRequested) oldSession.sendMessage(Envelope.error(MessageType.SESSION_REPLACED, "Đăng nhập ở nơi khác."));
                    handleDisconnect(oldSession);
                    oldSession.disconnect();
                } else {
                    handleDisconnect(oldSession);
                }
            }

            handler.setUsername(username);
            handler.setDisplayName(payload.getPlayer().getDisplayName());
            onlineClients.put(username, handler);
            System.out.println("[SERVER] User " + username + " đã kết nối WebSocket.");

            PendingReconnect reconnect = pendingReconnects.get(username);
            Room restoredRoom = null;
            if (reconnect != null) {
                if (clock.getAsLong() >= reconnect.deadline) {
                    expireReconnectWindow(username, reconnect);
                    reconnect = null;
                }
            }
            if (reconnect != null && pendingReconnects.remove(username, reconnect)) {
                if (reconnect.expiryTask != null) reconnect.expiryTask.cancel(false);
                restoredRoom = roomManager.getRoom(reconnect.roomId);
                if (restoredRoom == null || !restoredRoom.reconnectPlayer(handler)) {
                    restoredRoom = null;
                }
            }
            handler.setConnectionState(com.nhom8.common.dto.ConnectionState.CONNECTED);
            if (restoredRoom != null) restoredRoom.getGameSession().onConnectionChanged(username, true);
            String recoveryToken = resumeRequested ? recoveryTokens.get(username) : UUID.randomUUID().toString();
            recoveryTokens.put(username, recoveryToken);

            Envelope response = new Envelope(MessageType.LOGIN_RESULT);
            response.setSuccess(true);
            response.setContent("Đăng nhập thành công");
            response.put("player", payload.getPlayer());
            response.put("resumeToken", recoveryToken);
            response.put("graceMillis", reconnectGraceMillis);
            response.put("serverTime", System.currentTimeMillis());
            if (resumeRequested && restoredRoom == null) {
                response.putBoolean("recoveryRejected", true);
                String previousRoomId = request.getString("resumeRoomId");
                Room previousRoom = previousRoomId == null ? null : roomManager.getRoom(previousRoomId);
                response.put("previousRoomId", previousRoomId);
                response.putBoolean("canRejoin", previousRoom != null && previousRoom.getStatus() == Room.RoomStatus.WAITING && !previousRoom.isFull());
            }
            User loggedInUser = userRepository.findByUsername(username);
            response.put("role", loggedInUser != null ? loggedInUser.getRole() : "USER");
            if (restoredRoom != null) {
                response.put("reconnected", true);
                response.put("roomId", restoredRoom.getRoomId());
                response.put("roomName", restoredRoom.getRoomName());
            }
            handler.sendMessage(response);

            sendChatInbox(handler);

            broadcastPlayerList();
            sendRoomList(handler);
            sendFriendListToUser(username);
            broadcastFriendListToFriends(username);
            if (restoredRoom != null) {
                broadcastRoomUpdate(restoredRoom);
                broadcastRoomList();
                restoredRoom.getGameSession().sendReconnectState(handler);
                restoredRoom.broadcast(new Envelope(MessageType.PLAYER_RECONNECTED).put("username", username));
                Envelope reconnected = new Envelope(MessageType.ROOM_CHAT);
                reconnected.put("senderDisplayName", "Hệ thống");
                reconnected.put("content", payload.getPlayer().getDisplayName() + " đã kết nối lại.");
                restoredRoom.broadcast(reconnected);
            }
        } catch (Exception e) {
            handler.sendMessage(Envelope.error(MessageType.LOGIN_RESULT, e.getMessage()));
        }
    }

    public void handleRegister(ClientSession handler, Envelope request) {
        String username = request.get("username");
        String password = request.get("password");
        String displayName = request.get("displayName");
        try {
            authService.register(username, password, displayName);
            handler.sendMessage(Envelope.success(MessageType.REGISTER, "Đăng ký thành công!"));
            broadcastAdminStats();
            broadcastAdminUsers();
        } catch (Exception e) {
            handler.sendMessage(Envelope.error(MessageType.REGISTER, e.getMessage()));
        }
    }

    // ========================================================================
    // XỬ LÝ PHÒNG CHƠI
    // ========================================================================

    public void handleCreateRoom(ClientSession handler, Envelope request) {
        if (!handler.isAuthenticated() || handler.getUsername() == null) {
            handler.sendMessage(Envelope.error(MessageType.ROOM_RESPONSE, "Bạn cần đăng nhập trước khi tạo phòng."));
            return;
        }

        if (handler.getCurrentRoomId() != null) {
            handler.sendMessage(Envelope.error(MessageType.ROOM_RESPONSE, "Bạn đang ở trong một phòng khác."));
            return;
        }

        String roomName = request.getString("roomName");
        if (roomName == null || roomName.isBlank()) roomName = "Phòng " + handler.getUsername();
        else roomName = roomName.trim();
        String roomPassword = request.getString("roomPassword");
        if (roomPassword != null) roomPassword = roomPassword.trim();
        if (roomPassword != null && !roomPassword.isEmpty()
                && (roomPassword.length() < 4 || roomPassword.length() > 32)) {
            handler.sendMessage(Envelope.error(MessageType.ROOM_RESPONSE,
                    "Mật khẩu phòng nếu đặt phải từ 4 đến 32 ký tự."));
            return;
        }
        String passwordHash = roomPassword == null || roomPassword.isEmpty() ? null : roomPasswordEncoder.encode(roomPassword);
        Room room = roomManager.createRoom(handler, roomName, passwordHash);

        if (room != null) {
            handler.setCurrentRoomId(room.getRoomId());
            Envelope response = Envelope.success(MessageType.ROOM_RESPONSE, "Tạo phòng thành công!");
            response.put("roomId", room.getRoomId());
            response.put("roomName", room.getRoomName());
            response.put("hostUsername", room.getHostUsername());
            response.put("players", room.getPlayerListData());
            response.put("playerCount", room.getPlayerCount());
            response.put("status", room.getStatus().name());
            response.put("maxRounds", room.getMaxRounds());
            response.put("drawTime", room.getDrawTime());
            response.put("language", room.getLanguage());
            response.put("hasPassword", room.hasPassword());
            handler.sendMessage(response);

            broadcastRoomUpdate(room);
            broadcastPlayerList();
            broadcastRoomList();
        } else {
            handler.sendMessage(Envelope.error(MessageType.ROOM_RESPONSE,
                    "Không thể tạo phòng. Vui lòng thử lại."));
        }
    }

    public synchronized void handleJoinRoom(ClientSession handler, Envelope request) {
        if (!handler.isAuthenticated() || handler.getUsername() == null) {
            handler.sendMessage(Envelope.error(MessageType.ROOM_RESPONSE, "Bạn cần đăng nhập trước khi vào phòng."));
            return;
        }

        if (handler.getCurrentRoomId() != null) {
            handler.sendMessage(Envelope.error(MessageType.ROOM_RESPONSE, "Bạn đang ở trong một phòng khác."));
            return;
        }

        String roomId = request.getString("roomId");
        Room room = roomManager.getRoom(roomId);

        if (room == null) {
            handler.sendMessage(Envelope.error(MessageType.ROOM_RESPONSE, "Phòng không tồn tại."));
            return;
        }

        boolean invited = roomInvites.getOrDefault(roomId, Collections.emptySet()).contains(handler.getUsername());
        if (!invited && room.hasPassword() && !room.matchesPassword(request.get("roomPassword"), roomPasswordEncoder)) {
            Envelope passwordRequired = Envelope.error(MessageType.ROOM_PASSWORD_REQUIRED,
                    "Phòng này có mật khẩu hoặc mật khẩu chưa đúng.");
            passwordRequired.put("roomId", room.getRoomId());
            passwordRequired.put("roomName", room.getRoomName());
            handler.sendMessage(passwordRequired);
            return;
        }

        if (room.isFull()) {
            handler.sendMessage(Envelope.error(MessageType.ROOM_RESPONSE,
                    "Phòng đã đầy (tối đa " + Room.MAX_PLAYERS + " người)."));
            return;
        }

        if (room.isPlaying()) {
            handler.sendMessage(Envelope.error(MessageType.ROOM_RESPONSE,
                    "Phòng đang trong trận đấu, không thể tham gia."));
            return;
        }

        room.addPlayer(handler);
        room.getGameSession().cancelStartCountdown();
        if (invited) {
            Set<String> invitees = roomInvites.get(roomId);
            if (invitees != null) invitees.remove(handler.getUsername());
        }
        handler.setCurrentRoomId(roomId);

        Envelope response = Envelope.success(MessageType.ROOM_RESPONSE, "Tham gia phòng thành công!");
        response.put("roomId", room.getRoomId());
        response.put("roomName", room.getRoomName());
        response.put("hostUsername", room.getHostUsername());
        response.put("players", room.getPlayerListData());
        response.put("playerCount", room.getPlayerCount());
        response.put("status", room.getStatus().name());
        response.put("maxRounds", room.getMaxRounds());
        response.put("drawTime", room.getDrawTime());
        response.put("language", room.getLanguage());
        response.put("hasPassword", room.hasPassword());
        handler.sendMessage(response);

        broadcastRoomUpdate(room);
        broadcastPlayerList();
        broadcastRoomList();
    }

    public synchronized void handleLeaveRoom(ClientSession handler, Envelope request) {
        String roomId = handler.getCurrentRoomId();
        if (roomId == null) return;

        Room room = roomManager.getRoom(roomId);
        if (room == null) return;

        room.removePlayer(handler);
        room.getGameSession().cancelStartCountdown();
        handler.setCurrentRoomId(null);
        handler.setConnectionState(com.nhom8.common.dto.ConnectionState.CONNECTED);
        PendingReconnect pending = pendingReconnects.remove(handler.getUsername());
        if (pending != null && pending.expiryTask != null) pending.expiryTask.cancel(false);

        if (room.isPlaying() && room.getPlayerCount() < Room.MIN_ACTIVE_PLAYERS) {
            dissolveRoom(room);
        } else if (room.getPlayerCount() == 0) {
            roomManager.removeRoom(roomId);
        } else {
            broadcastRoomUpdate(room);
            if (!room.isPlaying() && room.allPlayersReady()) room.getGameSession().beginStartCountdown();
        }

        broadcastPlayerList();
        broadcastRoomList();
    }

    public void handleReady(ClientSession handler, Envelope request) {
        String roomId = handler.getCurrentRoomId();
        if (roomId == null) return;

        Room room = roomManager.getRoom(roomId);
        if (room == null) return;
        if (room.isPlaying()) return;

        boolean isReady = request.getBoolean("isReady", true);

        room.setPlayerReady(handler.getUsername(), isReady);
        if (!isReady) room.getGameSession().cancelStartCountdown();
        broadcastRoomUpdate(room);

        if (room.getPlayerCount() >= Room.MIN_PLAYERS && room.allPlayersReady()) {
            room.getGameSession().beginStartCountdown();
        }
    }

    public void handleRoomUpdateSettings(ClientSession handler, Envelope request) {
        String roomId = handler.getCurrentRoomId();
        if (roomId == null) return;
        Room room = roomManager.getRoom(roomId);
        if (room == null || !room.getHostUsername().equalsIgnoreCase(handler.getUsername())) return;

        if (request.getData() != null && request.getData().has("maxRounds")) {
            int requestedRounds = request.getInt("maxRounds", room.getMaxRounds());
            room.setMaxRounds(Math.max(1, Math.min(12, requestedRounds)));
        }
        if (request.getData() != null && request.getData().has("drawTime")) {
            room.setDrawTime(request.getInt("drawTime", room.getDrawTime()));
        }
        if (request.getData() != null && request.getData().has("language")) {
            room.setLanguage(request.getString("language"));
        }
        Envelope updateSettings = new Envelope(MessageType.ROOM_UPDATE_SETTINGS);
        updateSettings.put("maxRounds", room.getMaxRounds());
        updateSettings.put("drawTime", room.getDrawTime());
        updateSettings.put("language", room.getLanguage());
        updateSettings.put("hasPassword", room.hasPassword());
        room.broadcast(updateSettings);
    }

    public void handleRoomKickPlayer(ClientSession handler, Envelope request) {
        String roomId = handler.getCurrentRoomId();
        if (roomId == null) return;
        Room room = roomManager.getRoom(roomId);
        if (room == null || !room.getHostUsername().equalsIgnoreCase(handler.getUsername())) return;

        String targetUsername = request.get("targetUsername");
        ClientSession targetSession = onlineClients.get(targetUsername);
        if (targetSession != null && room.getPlayerUsernames().contains(targetUsername)) {
            // Remove player from room
            room.removePlayer(targetSession);
            room.getGameSession().cancelStartCountdown();
            targetSession.setCurrentRoomId(null);
            targetSession.setConnectionState(com.nhom8.common.dto.ConnectionState.CONNECTED);

            // Send notification to the kicked player
            Envelope kickedMsg = new Envelope(MessageType.ROOM_DISSOLVED);
            kickedMsg.setContent("Bạn đã bị chủ phòng mời ra khỏi phòng.");
            targetSession.sendMessage(kickedMsg);

            // Update room
            broadcastRoomUpdate(room);
            if (!room.isPlaying() && room.allPlayersReady()) room.getGameSession().beginStartCountdown();
            broadcastPlayerList();
            broadcastRoomList();
        }
    }

    public void handleRoomChatSend(ClientSession handler, Envelope request) {
        String roomId = handler.getCurrentRoomId();
        if (roomId == null) return;
        Room room = roomManager.getRoom(roomId);
        if (room == null) return;

        String content = request.get("content");
        if (content == null || content.trim().isEmpty()) return;

        if (room.isPlaying() && room.getGameSession().containsSecretAnswer(content)) {
            Envelope hiddenNotice = new Envelope(MessageType.ROOM_CHAT_RECEIVE);
            hiddenNotice.put("senderDisplayName", "Hệ thống");
            hiddenNotice.put("content", "Tin nhắn đã bị ẩn vì có chứa từ khóa của bức tranh.");
            handler.sendMessage(hiddenNotice);
            return;
        }

        Envelope chatMsg = new Envelope(MessageType.ROOM_CHAT_RECEIVE);
        chatMsg.put("senderUsername", handler.getUsername());
        chatMsg.put("senderDisplayName", handler.getDisplayName());
        chatMsg.put("content", content);
        chatMsg.put("timestamp", new java.util.Date().toString());
        
        room.broadcast(chatMsg);
    }

    // ========================================================================
    // XỬ LÝ LUỒNG TRÒ CHƠI
    // ========================================================================

    public void handleTopicSelect(ClientSession handler, Envelope request) {
        String roomId = handler.getCurrentRoomId();
        if (roomId == null) return;
        Room room = roomManager.getRoom(roomId);
        if (room == null) return;
        String selectedTopic = request.get("topic");
        room.getGameSession().onTopicSelected(handler.getUsername(), selectedTopic);
    }

    public void handleDrawData(ClientSession handler, Envelope request) {
        String roomId = handler.getCurrentRoomId();
        if (roomId == null) return;
        Room room = roomManager.getRoom(roomId);
        if (room == null || !room.isPlaying()) return;
        
        room.getGameSession().onDrawingDataReceived(handler.getUsername(), request);
    }

    public void handleDrawSubmit(ClientSession handler, Envelope request) {
        String roomId = handler.getCurrentRoomId();
        if (roomId == null) return;
        Room room = roomManager.getRoom(roomId);
        if (room == null) return;
        room.getGameSession().onDrawingSubmitted(handler.getUsername(), request);
    }

    public void handleGuessSubmit(ClientSession handler, Envelope request) {
        String roomId = handler.getCurrentRoomId();
        if (roomId == null) return;
        Room room = roomManager.getRoom(roomId);
        if (room == null) return;
        room.getGameSession().onGuessSubmitted(handler.getUsername(), request);
    }


    // ========================================================================
    // XỬ LÝ NHẮN TIN RIÊNG
    // ========================================================================

    public void handleDirectMessageSend(ClientSession handler, Envelope request) {
        String targetUsername = request.get("targetUsername");
        String content = request.get("content");
        String myUsername = handler.getUsername();

        if (myUsername == null || targetUsername == null || content == null || content.trim().isEmpty()) {
            return;
        }

        User me = userRepository.findByUsername(myUsername);
        if (me == null) me = userRepository.findByUsernameIgnoreCase(myUsername);
        User target = userRepository.findByUsername(targetUsername);
        if (target == null) target = userRepository.findByUsernameIgnoreCase(targetUsername);
        if (me == null || target == null) return;

        // Lưu tin nhắn vào DB
        com.nhom8.server.model.ChatMessage chat = new com.nhom8.server.model.ChatMessage(me, target, content);
        chatMessageRepository.save(chat);

        // Chuẩn bị payload gửi đi
        Envelope msg = new Envelope(MessageType.DIRECT_MESSAGE_RECEIVE);
        msg.put("senderUsername", me.getUsername());
        msg.put("senderDisplayName", me.getDisplayName());
        msg.put("senderAvatar", me.getAvatar());
        msg.put("content", content);
        msg.put("timestamp", chat.getCreatedAt().toString());

        // Nếu người nhận đang online, gửi luôn
        ClientSession targetSession = onlineClients.get(target.getUsername());
        if (targetSession == null) {
            targetSession = onlineClients.get(targetUsername);
        }
        if (targetSession != null) {
            targetSession.sendMessage(msg);
            sendChatInbox(targetSession);
        }

        // Gửi thông báo thành công về cho người gửi (để họ add vào giao diện)
        Envelope ack = new Envelope(MessageType.DIRECT_MESSAGE_RECEIVE);
        ack.put("senderUsername", me.getUsername());
        ack.put("senderDisplayName", me.getDisplayName());
        ack.put("senderAvatar", me.getAvatar());
        ack.put("content", content);
        ack.put("timestamp", chat.getCreatedAt().toString());
        ack.put("isSelf", true);
        ack.put("targetUsername", target.getUsername());
        handler.sendMessage(ack);
        sendChatInbox(handler);
    }

    @org.springframework.transaction.annotation.Transactional
    public void handleChatHistoryRequest(ClientSession handler, Envelope request) {
        String targetUsername = request.get("targetUsername");
        String myUsername = handler.getUsername();

        if (myUsername == null || targetUsername == null) return;

        try {
            User me = userRepository.findByUsername(myUsername);
            if (me == null) me = userRepository.findByUsernameIgnoreCase(myUsername);
            User target = userRepository.findByUsername(targetUsername);
            if (target == null) target = userRepository.findByUsernameIgnoreCase(targetUsername);
            if (me == null || target == null) return;

            chatMessageRepository.markConversationRead(me, target);

            java.util.List<com.nhom8.server.model.ChatMessage> history = chatMessageRepository.findChatHistory(me, target);
            java.util.List<java.util.Map<String, Object>> responseList = new java.util.ArrayList<>();

            for (com.nhom8.server.model.ChatMessage c : history) {
                java.util.Map<String, Object> map = new java.util.HashMap<>();
                map.put("senderUsername", c.getSender().getUsername());
                map.put("senderDisplayName", c.getSender().getDisplayName());
                map.put("content", c.getContent());
                map.put("timestamp", c.getCreatedAt().toString());
                map.put("isSelf", c.getSender().getUsername().equals(myUsername));
                responseList.add(map);
            }

            Envelope response = new Envelope(MessageType.CHAT_HISTORY_RESPONSE);
            response.put("targetUsername", target.getUsername());
            response.put("history", responseList);
            handler.sendMessage(response);
            sendChatInbox(handler);
        } catch (Exception e) {
            System.err.println("[GameService] Lỗi lấy lịch sử chat: " + e.getMessage());
            Envelope response = new Envelope(MessageType.CHAT_HISTORY_RESPONSE);
            response.put("targetUsername", targetUsername);
            response.put("history", new java.util.ArrayList<>());
            handler.sendMessage(response);
        }
    }

    @org.springframework.transaction.annotation.Transactional(readOnly = true)
    public void handleChatInboxRequest(ClientSession handler) {
        sendChatInbox(handler);
    }

    @org.springframework.transaction.annotation.Transactional
    public void handleChatMarkRead(ClientSession handler, Envelope request) {
        String username = handler.getUsername();
        String partnerUsername = request.get("targetUsername");
        if (username == null || partnerUsername == null) return;
        User me = userRepository.findByUsername(username);
        if (me == null) me = userRepository.findByUsernameIgnoreCase(username);
        User partner = userRepository.findByUsername(partnerUsername);
        if (partner == null) partner = userRepository.findByUsernameIgnoreCase(partnerUsername);
        if (me == null || partner == null) return;
        chatMessageRepository.markConversationRead(me, partner);
        sendChatInbox(handler);
    }

    private void sendChatInbox(ClientSession handler) {
        String username = handler.getUsername();
        if (username == null || chatMessageRepository == null) return;
        User me = userRepository.findByUsername(username);
        if (me == null) me = userRepository.findByUsernameIgnoreCase(username);
        if (me == null) return;

        List<Map<String, Object>> conversations = new ArrayList<>();
        for (User partner : chatMessageRepository.findChattedUsers(me)) {
            List<com.nhom8.server.model.ChatMessage> history = chatMessageRepository.findChatHistory(me, partner);
            if (history.isEmpty()) continue;
            com.nhom8.server.model.ChatMessage latest = history.get(history.size() - 1);
            Map<String, Object> item = new HashMap<>();
            item.put("partnerUsername", partner.getUsername());
            item.put("partnerDisplayName", partner.getDisplayName());
            item.put("partnerAvatar", partner.getAvatar());
            item.put("lastMessage", latest.getContent());
            item.put("lastMessageIsSelf", me.equals(latest.getSender()));
            item.put("timestamp", latest.getCreatedAt() == null ? "" : latest.getCreatedAt().toString());
            item.put("unreadCount", chatMessageRepository.countByReceiverAndSenderAndReadStatusFalse(me, partner));
            conversations.add(item);
        }
        conversations.sort((a, b) -> String.valueOf(b.get("timestamp")).compareTo(String.valueOf(a.get("timestamp"))));
        Envelope response = new Envelope(MessageType.CHAT_INBOX_RESPONSE);
        response.put("conversations", conversations);
        response.put("unreadTotal", conversations.stream()
                .mapToLong(item -> ((Number) item.get("unreadCount")).longValue()).sum());
        handler.sendMessage(response);
    }

    // ========================================================================
    // XỬ LÝ ADMIN
    // ========================================================================

    private boolean isAdmin(ClientSession handler) {
        String username = handler.getUsername();
        if (username == null) return false;
        User user = userRepository.findByUsername(username);
        return user != null && "ADMIN".equals(user.getRole());
    }

    public void handleAdminGetUsers(ClientSession handler, Envelope request) {
        if (!requireAdmin(handler, MessageType.ADMIN_USERS_RESPONSE)) return;
        List<User> allUsers = userRepository.findAllByOrderByRankingScoreDesc();
        List<Map<String, Object>> usersData = new ArrayList<>();
        for (User u : allUsers) {
            Map<String, Object> map = new HashMap<>();
            map.put("userId", u.getUserId());
            map.put("username", u.getUsername());
            map.put("rankingScore", u.getRankingScore());
            map.put("role", u.getRole());
            usersData.add(map);
        }
        Envelope response = Envelope.success(MessageType.ADMIN_USERS_RESPONSE, "Success");
        response.put("users", usersData);
        handler.sendMessage(response);
    }

    public void handleAdminUpdateScore(ClientSession handler, Envelope request) {
        if (!requireAdmin(handler, MessageType.ADMIN_UPDATE_SCORE)) return;
        String targetUser = request.get("targetUser");
        int newScore = request.getInt("newScore", -1);
        if (targetUser == null || newScore < 0) {
            handler.sendMessage(Envelope.error(MessageType.ADMIN_UPDATE_SCORE, "Dữ liệu điểm không hợp lệ."));
            return;
        }

        User user = userRepository.findByUsername(targetUser);
        if (user != null) {
            user.setRankingScore(newScore);
            userRepository.save(user);
            handler.sendMessage(Envelope.success(MessageType.ADMIN_UPDATE_SCORE,
                    "Cập nhật điểm thành công cho " + targetUser));
            broadcastPlayerList();
        }
    }

    public void handleAdminDissolveRoom(ClientSession handler, Envelope request) {
        if (!requireAdmin(handler, MessageType.ADMIN_DISSOLVE_ROOM)) return;
        String roomId = request.get("roomId");
        Room room = roomManager.getRoom(roomId);
        if (room != null) {
            dissolveRoom(room, "Phòng đã được quản trị viên giải tán.");
            handler.sendMessage(Envelope.success(MessageType.ADMIN_DISSOLVE_ROOM,
                    "Đã giải tán phòng " + roomId));
        }
    }

    private boolean requireAdmin(ClientSession handler, MessageType responseType) {
        if (isAdmin(handler)) return true;
        handler.sendMessage(Envelope.error(responseType, "Chỉ quản trị viên được thực hiện thao tác này."));
        return false;
    }

    public void handleAdminGetRooms(ClientSession handler) {
        if (!requireAdmin(handler, MessageType.ADMIN_GET_ROOMS)) return;
        handler.sendMessage(Envelope.success(MessageType.ADMIN_GET_ROOMS, "Danh sách phòng")
                .put("rooms", roomManager.getRoomListData()));
    }

    public void handleAdminStats(ClientSession handler) {
        if (!requireAdmin(handler, MessageType.ADMIN_STATS)) return;
        handler.sendMessage(createAdminStats());
    }

    private Envelope createAdminStats() {
        return Envelope.success(MessageType.ADMIN_STATS, "Thống kê hệ thống")
                .put("totalUsers", userRepository.count())
                .put("onlineUsers", onlineClients.size())
                .put("activeRooms", roomManager.getRoomCount())
                .put("totalMatches", matchRepository.count())
                .put("totalPaintings", paintingRepository.count())
                .put("customTopics", topicCatalog.countCustom());
    }

    public void broadcastAdminStats() {
        for (ClientSession session : onlineClients.values()) {
            if (isAdmin(session)) session.sendMessage(createAdminStats());
        }
    }

    private void broadcastAdminUsers() {
        List<User> allUsers = userRepository.findAllByOrderByRankingScoreDesc();
        List<Map<String, Object>> usersData = new ArrayList<>();
        for (User user : allUsers) {
            Map<String, Object> map = new HashMap<>();
            map.put("userId", user.getUserId());
            map.put("username", user.getUsername());
            map.put("rankingScore", user.getRankingScore());
            map.put("role", user.getRole());
            usersData.add(map);
        }
        Envelope response = Envelope.success(MessageType.ADMIN_USERS_RESPONSE, "Success");
        response.put("users", usersData);
        for (ClientSession session : onlineClients.values()) {
            if (isAdmin(session)) session.sendMessage(response);
        }
    }

    private void broadcastAdminRooms() {
        Envelope response = Envelope.success(MessageType.ADMIN_GET_ROOMS, "Danh sách phòng")
                .put("rooms", roomManager.getRoomListData());
        for (ClientSession session : onlineClients.values()) {
            if (isAdmin(session)) session.sendMessage(response);
        }
    }

    private void broadcastAdminTopics() {
        Envelope response = Envelope.success(MessageType.ADMIN_TOPICS, "Danh sách từ khóa")
                .put("topics", TopicPool.getCustomTopics().stream().sorted().toList())
                .put("builtInTopics", List.of());
        for (ClientSession session : onlineClients.values()) {
            if (isAdmin(session)) session.sendMessage(response);
        }
    }

    public void handleAdminTopics(ClientSession handler) {
        if (!requireAdmin(handler, MessageType.ADMIN_TOPICS)) return;
        handler.sendMessage(Envelope.success(MessageType.ADMIN_TOPICS, "Danh sách từ khóa")
                .put("topics", TopicPool.getCustomTopics().stream().sorted().toList())
                .put("builtInTopics", List.of()));
    }

    public void handleAdminTopicAdd(ClientSession handler, Envelope request) {
        if (!requireAdmin(handler, MessageType.ADMIN_TOPIC_ADD)) return;
        String word = request.getString("word");
        word = word == null ? "" : word.trim().replaceAll("\\s+", " ");
        if (word.length() < 2 || word.length() > 100 || activeTopicExists(word, null)) {
            handler.sendMessage(Envelope.error(MessageType.ADMIN_TOPIC_ADD, "Từ khóa phải dài 2–100 ký tự và chưa tồn tại."));
            return;
        }
        topicCatalog.save(new TopicEntry(word, true));
        handler.sendMessage(Envelope.success(MessageType.ADMIN_TOPIC_ADD, "Đã thêm từ khóa: " + word));
        broadcastAdminTopics();
        broadcastAdminStats();
    }

    public void handleAdminTopicDelete(ClientSession handler, Envelope request) {
        if (!requireAdmin(handler, MessageType.ADMIN_TOPIC_DELETE)) return;
        String word = request.getString("word");
        TopicEntry topic = findActiveTopic(word);
        if (topic == null) {
            handler.sendMessage(Envelope.error(MessageType.ADMIN_TOPIC_DELETE, "Không tìm thấy từ khóa."));
            return;
        }
        if (TopicPool.getBuiltInTopics().size() + TopicPool.getCustomTopics().size() <= 1) {
            handler.sendMessage(Envelope.error(MessageType.ADMIN_TOPIC_DELETE, "Cần giữ ít nhất một từ khóa để bắt đầu trận."));
            return;
        }
        // Keep the row for historical paintings that still reference this keyword.
        topic.setActive(false);
        topicCatalog.save(topic);
        handler.sendMessage(Envelope.success(MessageType.ADMIN_TOPIC_DELETE, "Đã xóa từ khóa: " + topic.getWord()));
        broadcastAdminTopics();
        broadcastAdminStats();
    }

    public void handleAdminTopicUpdate(ClientSession handler, Envelope request) {
        if (!requireAdmin(handler, MessageType.ADMIN_TOPIC_UPDATE)) return;
        String currentWord = request.getString("word");
        String rawReplacement = request.getString("replacement");
        String replacement = rawReplacement == null ? "" : rawReplacement.trim().replaceAll("\\s+", " ");
        if (replacement.length() < 2 || replacement.length() > 100) {
            handler.sendMessage(Envelope.error(MessageType.ADMIN_TOPIC_UPDATE, "Từ khóa phải dài 2–100 ký tự."));
            return;
        }
        TopicEntry topic = findActiveTopic(currentWord);
        if (topic == null) {
            handler.sendMessage(Envelope.error(MessageType.ADMIN_TOPIC_UPDATE, "Không tìm thấy từ khóa."));
            return;
        }
        if (activeTopicExists(replacement, topic.getId())) {
            handler.sendMessage(Envelope.error(MessageType.ADMIN_TOPIC_UPDATE, "Từ khóa này đã tồn tại."));
            return;
        }

        topic.setWord(replacement);
        topicCatalog.save(topic);
        handler.sendMessage(Envelope.success(MessageType.ADMIN_TOPIC_UPDATE, "Đã sửa từ khóa thành: " + replacement));
        broadcastAdminTopics();
    }

    private TopicEntry findActiveTopic(String word) {
        if (word == null) return null;
        return topicCatalog.all().stream()
                .filter(t -> t.isActive() && t.getWord().equalsIgnoreCase(word)).findFirst().orElse(null);
    }

    private boolean activeTopicExists(String word, Long exceptId) {
        return topicCatalog.all().stream().anyMatch(t -> t.isActive()
                && !java.util.Objects.equals(t.getId(), exceptId)
                && t.getWord().equalsIgnoreCase(word));
    }

    // ========================================================================
    // XỬ LÝ HỆ THỐNG BẠN BÈ
    // ========================================================================

    public String getUserRoomId(String username) {
        ClientSession session = onlineClients.get(username);
        if (session == null || session.getCurrentRoomId() == null) return null;
        return roomManager.getRoom(session.getCurrentRoomId()) == null ? null : session.getCurrentRoomId();
    }

    public boolean isUserOnline(String username) {
        return onlineClients.containsKey(username);
    }

    public void notifyFriendRequestViaWS(User receiver, User sender) {
        ClientSession receiverSession = onlineClients.get(receiver.getUsername());
        if (receiverSession != null) {
            Envelope notify = new Envelope(MessageType.FRIEND_REQUEST_NOTIFY);
            notify.setSuccess(true);
            notify.put("senderUsername", sender.getUsername());
            notify.put("senderDisplayName", sender.getDisplayName());
            notify.put("senderAvatar", sender.getAvatar());
            notify.put("senderScore", sender.getRankingScore());
            notify.setContent(sender.getDisplayName() + " muốn kết bạn với bạn!");
            receiverSession.sendMessage(notify);
        }
    }

    public void notifyFriendAcceptViaWS(User receiver, User accepter) {
        ClientSession receiverSession = onlineClients.get(receiver.getUsername());
        if (receiverSession != null) {
            Envelope notify = new Envelope(MessageType.FRIEND_UPDATE);
            notify.setSuccess(true);
            notify.put("action", "ACCEPTED");
            notify.put("username", accepter.getUsername());
            notify.put("displayName", accepter.getDisplayName());
            notify.put("avatar", accepter.getAvatar());
            notify.setContent(accepter.getDisplayName() + " đã chấp nhận lời mời kết bạn!");
            receiverSession.sendMessage(notify);
        }
    }

    public void notifyFriendRemoveViaWS(User receiver, User remover) {
        ClientSession receiverSession = onlineClients.get(receiver.getUsername());
        if (receiverSession != null) {
            Envelope notify = new Envelope(MessageType.FRIEND_UPDATE);
            notify.setSuccess(true);
            notify.put("action", "REMOVED");
            notify.put("username", remover.getUsername());
            notify.setContent("Danh sách bạn bè đã thay đổi.");
            receiverSession.sendMessage(notify);
        }
    }

    public void notifyFriendRequestCancelled(User receiver, User sender) {
        ClientSession session = onlineClients.get(receiver.getUsername());
        if (session != null) {
            Envelope update = new Envelope(MessageType.FRIEND_UPDATE);
            update.setSuccess(true);
            update.put("action", "REQUEST_CANCELLED");
            update.put("username", sender.getUsername());
            session.sendMessage(update);
        }
    }

    /**
     * Gửi danh sách bạn bè mới nhất tới người dùng cụ thể nếu họ đang online.
     */
    public void sendFriendListToUser(String username) {
        if (username == null) return;
        ClientSession session = onlineClients.get(username);
        if (session != null && session.isOpen()) {
            try {
                List<Map<String, Object>> friends = friendService.getFriendList(username);
                Envelope response = Envelope.success(MessageType.FRIEND_LIST, "Danh sách bạn bè.");
                response.put("friends", friends);
                session.sendMessage(response);
            } catch (Exception e) {
                System.err.println("[SERVER] Lỗi gửi friend list tới " + username + ": " + e.getMessage());
            }
        }
    }

    /**
     * Broadcast danh sách bạn bè cập nhật tới tất cả bạn bè đang online của user.
     * Kích hoạt ngay khi user đăng nhập, đăng xuất hoặc cập nhật hồ sơ.
     */
    public void broadcastFriendListToFriends(String username) {
        if (username == null) return;
        try {
            User user = userRepository.findByUsername(username);
            if (user == null) user = userRepository.findByUsernameIgnoreCase(username);
            if (user == null) return;

            List<Friendship> friendships = friendshipRepository.findAllAcceptedFriends(user.getUserId());
            if (friendships != null) {
                for (Friendship f : friendships) {
                    User friendUser = (f.getSender().getUserId() != null && f.getSender().getUserId().equals(user.getUserId()))
                            ? f.getReceiver() : f.getSender();
                    if (friendUser != null && friendUser.getUsername() != null) {
                        sendFriendListToUser(friendUser.getUsername());
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("[SERVER] Lỗi broadcast friend status cho " + username + ": " + e.getMessage());
        }
    }

    // ========================================================================
    // XỬ LÝ MỜI VÀO PHÒNG
    // ========================================================================

    /**
     * Xử lý yêu cầu mời bạn bè vào phòng chơi.
     * Chỉ có thể mời người là bạn bè và đang online.
     */
    public synchronized void handleRoomInviteSend(ClientSession handler, Envelope request) {
        String targetUsername = request.get("targetUsername");
        String myUsername = handler.getUsername();
        String roomId = handler.getCurrentRoomId();

        if (myUsername == null || targetUsername == null || roomId == null) {
            roomInviteFeedback(handler, targetUsername, false, "Bạn chưa ở trong phòng chơi nào.", 0);
            return;
        }

        User me = userRepository.findByUsername(myUsername);
        User target = userRepository.findByUsername(targetUsername);
        if (me == null || target == null) {
            roomInviteFeedback(handler, targetUsername, false, "Không tìm thấy người chơi.", 0);
            return;
        }

        long now = clock.getAsLong();
        roomInviteRetryAt.entrySet().removeIf(entry -> entry.getValue() <= now);
        InvitePair pair = new InvitePair(me.getUsername(), target.getUsername());
        long retryAfter = roomInviteRetryAt.getOrDefault(pair, 0L) - now;
        if (retryAfter > 0) {
            roomInviteFeedback(handler, targetUsername, false,
                    "Vui lòng đợi " + ((retryAfter + 999) / 1000) + " giây trước khi mời lại người này.", retryAfter);
            return;
        }

        // Kiểm tra là bạn bè
        Friendship friendship = friendshipRepository.findByUsers(me, target);
        if (friendship == null || friendship.getStatus() != Friendship.FriendshipStatus.ACCEPTED) {
            roomInviteFeedback(handler, targetUsername, false, "Bạn chỉ có thể mời bạn bè vào phòng.", 0);
            return;
        }

        ClientSession targetSession = onlineClients.get(targetUsername);
        if (targetSession == null) {
            roomInviteFeedback(handler, targetUsername, false, targetUsername + " đang offline.", 0);
            return;
        }

        if (targetSession.getCurrentRoomId() != null) {
            roomInviteFeedback(handler, targetUsername, false,
                    targetUsername + (roomId.equals(targetSession.getCurrentRoomId())
                            ? " đã ở trong phòng này." : " đang ở trong phòng khác."), 0);
            sendPlayerList(handler);
            return;
        }

        Room room = roomManager.getRoom(roomId);
        if (room == null || !roomId.equals(handler.getCurrentRoomId())) {
            roomInviteFeedback(handler, targetUsername, false, "Bạn cần ở trong phòng muốn mời người chơi.", 0);
            return;
        }
        if (room.isPlaying() || room.isFull()) {
            roomInviteFeedback(handler, targetUsername, false, "Phòng đang chơi hoặc đã đủ người, không thể mời thêm.", 0);
            return;
        }

        roomInviteRetryAt.put(pair, now + ROOM_INVITE_COOLDOWN_MS);
        roomInvites.computeIfAbsent(roomId, ignored -> ConcurrentHashMap.newKeySet()).add(targetUsername);
        Envelope invite = new Envelope(MessageType.ROOM_INVITE_NOTIFY);
        invite.put("roomId", roomId);
        invite.put("roomName", room.getRoomName());
        invite.put("inviterUsername", myUsername);
        invite.put("inviterDisplayName", me.getDisplayName());
        invite.put("inviterAvatar", me.getAvatar());
        invite.put("hostUsername", room.getHostUsername());
        ClientSession host = room.getPlayer(room.getHostUsername());
        invite.put("hostDisplayName", host == null ? room.getHostUsername() : host.getDisplayName());
        invite.put("currentPlayers", room.getPlayerCount());
        invite.put("maxPlayers", Room.MAX_PLAYERS);
        invite.put("maxRounds", room.getMaxRounds());
        invite.put("drawTime", room.getDrawTime());
        invite.put("language", room.getLanguage());
        invite.put("hasPassword", room.hasPassword());
        invite.put("status", room.getStatus().name());
        invite.setContent(myUsername + " mời bạn vào phòng " + room.getRoomName() + "!");
        targetSession.sendMessage(invite);

        roomInviteFeedback(handler, targetUsername, true,
                "Đã gửi lời mời vào phòng cho " + targetUsername + ".", ROOM_INVITE_COOLDOWN_MS);
    }

    private void roomInviteFeedback(ClientSession handler, String target, boolean success, String message, long retryAfter) {
        Envelope response = success ? Envelope.success(MessageType.FRIEND_UPDATE, message)
                : Envelope.error(MessageType.FRIEND_UPDATE, message);
        handler.sendMessage(response.put("action", "ROOM_INVITE").put("targetUsername", target)
                .put("retryAfterMillis", retryAfter));
    }

    /**
     * Xử lý phản hồi lời mời vào phòng (chấp nhận hoặc từ chối).
     */
    public void handleRoomInviteRespond(ClientSession handler, Envelope request) {
        boolean accept = request.getBoolean("accept", false);
        String roomId = request.getString("roomId");
        if (roomId == null || handler.getUsername() == null) return;
        Set<String> invitees = roomInvites.get(roomId);
        if (invitees == null || !invitees.contains(handler.getUsername())) {
            if (accept) handler.sendMessage(Envelope.error(MessageType.ROOM_RESPONSE,
                    "Lời mời đã hết hiệu lực. Hãy nhờ người trong phòng mời lại."));
            return;
        }
        if (!accept) { invitees.remove(handler.getUsername()); return; }
        Envelope joinRequest = new Envelope(MessageType.JOIN_ROOM);
        joinRequest.putString("roomId", roomId);
        handleJoinRoom(handler, joinRequest);
    }

    // ========================================================================
    // XỬ LÝ NGẮT KẾT NỐI
    // ========================================================================

    public synchronized void handleDisconnect(ClientSession handler) {
        String username = handler.getUsername();
        if (username != null) {
            boolean wentOffline = onlineClients.remove(username, handler);
            if (wentOffline) broadcastFriendListToFriends(username);

            String roomId = handler.getCurrentRoomId();
            if (wentOffline && roomId != null) {
                Room room = roomManager.getRoom(roomId);
                if (room != null) {
                    room.getGameSession().cancelStartCountdown();
                    long deadline = clock.getAsLong() + reconnectGraceMillis;
                    handler.setConnectionState(com.nhom8.common.dto.ConnectionState.RECONNECTING);
                    handler.setReconnectDeadline(deadline);
                    room.getGameSession().onConnectionChanged(username, false);
                    PendingReconnect pending = new PendingReconnect(roomId, handler, deadline);
                    PendingReconnect previous = pendingReconnects.put(username, pending);
                    if (previous != null && previous.expiryTask != null) previous.expiryTask.cancel(false);
                    pending.expiryTask = reconnectScheduler.schedule(
                            () -> expireReconnectWindow(username, pending),
                            reconnectGraceMillis, TimeUnit.MILLISECONDS);
                    Envelope disconnectMsg = new Envelope(MessageType.PLAYER_DISCONNECTED);
                    disconnectMsg.put("username", username);
                    disconnectMsg.put("graceSeconds", reconnectGraceMillis / 1000);
                    disconnectMsg.put("reconnectDeadline", deadline);
                    disconnectMsg.put("serverTime", System.currentTimeMillis());
                    room.broadcast(disconnectMsg);
                    Envelope notice = new Envelope(MessageType.ROOM_CHAT);
                    notice.put("senderDisplayName", "Hệ thống");
                    notice.put("content", username + " mất kết nối. Trận vẫn tiếp tục; người chơi có "
                            + reconnectGraceMillis / 1000 + " giây để vào lại.");
                    room.broadcast(notice);
                    broadcastRoomUpdate(room);
                }
            }
        }

        allClients.remove(handler);
        broadcastPlayerList();
        broadcastRoomList();
    }

    private synchronized void expireReconnectWindow(String username, PendingReconnect pending) {
        if (clock.getAsLong() < pending.deadline) return;
        if (!pendingReconnects.remove(username, pending)) return;
        Room room = roomManager.getRoom(pending.roomId);
        if (room == null) return;
        ClientSession disconnected = room.getPlayer(username);
        if (disconnected != pending.disconnectedSession || disconnected.isConnected()) return;

        room.removePlayer(disconnected);
        disconnected.setCurrentRoomId(null);
        Envelope expired = new Envelope(MessageType.ROOM_CHAT);
        expired.put("senderDisplayName", "Hệ thống");
        expired.put("content", username + " đã hết thời gian kết nối lại và đã rời phòng.");
        room.broadcast(expired);

        if (room.isPlaying() && room.getPlayerCount() < Room.MIN_ACTIVE_PLAYERS) {
            dissolveRoom(room);
        } else if (room.getPlayerCount() == 0) {
            roomManager.removeRoom(room.getRoomId());
        } else {
            broadcastRoomUpdate(room);
        }
        broadcastPlayerList();
        broadcastRoomList();
    }

    /** Chặn thông điệp trễ từ kết nối đã bị thay thế và tuần tự hóa vòng đời phiên. */
    public synchronized void dispatchCurrent(ClientSession handler, Envelope message, Runnable action) {
        switch (message.getType()) {
            case LOGIN, LOGIN_REQUEST, REGISTER, REGISTER_REQUEST -> action.run();
            default -> {
                if (handler.isConnected() && handler.getUsername() != null
                        && onlineClients.get(handler.getUsername()) == handler) action.run();
            }
        }
    }

    public void handleDrawingDraft(ClientSession handler, Envelope request) {
        Room room = handler.getCurrentRoomId() == null ? null : roomManager.getRoom(handler.getCurrentRoomId());
        if (room != null) room.getGameSession().onDraftSaved(handler.getUsername(), request);
    }

    public synchronized void expireDisconnectedSessions() {
        pendingReconnects.forEach(this::expireReconnectWindow);
    }

    // ========================================================================
    // BROADCAST
    // ========================================================================

    public List<Map<String, Object>> buildPlayerListData() {
        List<Map<String, Object>> playerList = new ArrayList<>();
        for (Map.Entry<String, ClientSession> entry : onlineClients.entrySet()) {
            ClientSession session = entry.getValue();
            Map<String, Object> playerInfo = new HashMap<>();
            playerInfo.put("username", entry.getKey());
            playerInfo.put("displayName", session.getDisplayName() != null ? session.getDisplayName() : entry.getKey());

            String roomId = session.getCurrentRoomId();
            String status = "Đang rảnh";
            if (roomId != null) {
                Room room = roomManager.getRoom(roomId);
                if (room != null) {
                    playerInfo.put("roomId", room.getRoomId());
                    playerInfo.put("roomName", room.getRoomName());
                    boolean canJoin = (room.getStatus() == Room.RoomStatus.WAITING && room.getPlayerCount() < Room.MAX_PLAYERS);
                    playerInfo.put("canJoin", canJoin);
                    if (room.getStatus() == Room.RoomStatus.WAITING) {
                        status = "Đang trong phòng chờ";
                    } else if (room.getStatus() == Room.RoomStatus.PLAYING) {
                        status = "Đang chơi";
                    }
                }
            }
            playerInfo.put("status", status);

            User user = userRepository.findByUsername(entry.getKey());
            playerInfo.put("rankingScore", user != null ? user.getRankingScore() : 0);
            playerInfo.put("avatar", user != null ? user.getAvatar() : "1");

            playerList.add(playerInfo);
        }
        return playerList;
    }

    public void sendPlayerList(ClientSession handler) {
        Envelope msg = new Envelope(MessageType.PLAYER_LIST);
        msg.put("players", buildPlayerListData());
        handler.sendMessage(msg);
    }

    public void broadcastPlayerList() {
        Envelope msg = new Envelope(MessageType.PLAYER_LIST);
        msg.put("players", buildPlayerListData());

        for (ClientSession handler : onlineClients.values()) {
            handler.sendMessage(msg);
        }
        broadcastAdminStats();
        broadcastAdminUsers();
    }

    public void sendRoomList(ClientSession handler) {
        Envelope msg = new Envelope(MessageType.ROOM_LIST);
        msg.put("rooms", roomManager.getRoomListData());
        handler.sendMessage(msg);
    }

    public void broadcastRoomList() {
        Envelope msg = new Envelope(MessageType.ROOM_LIST);
        msg.put("rooms", roomManager.getRoomListData());

        for (ClientSession handler : onlineClients.values()) {
            handler.sendMessage(msg);
        }
        broadcastAdminStats();
        broadcastAdminRooms();
    }

    public void broadcastRoomUpdate(Room room) {
        Envelope msg = new Envelope(MessageType.ROOM_UPDATE);
        msg.put("roomId", room.getRoomId());
        msg.put("roomName", room.getRoomName());
        msg.put("hostUsername", room.getHostUsername());
        var roomPlayers = room.getPlayerListData();
        for (var player : roomPlayers) {
            User user = authService.getProfile((String) player.get("username"));
            player.put("avatar", user == null ? "1" : user.getAvatar());
        }
        msg.put("players", roomPlayers);
        msg.put("playerCount", room.getPlayerCount());
        msg.put("minPlayers", Room.MIN_PLAYERS);
        msg.put("maxPlayers", Room.MAX_PLAYERS);
        msg.put("serverTime", System.currentTimeMillis());
        msg.put("status", room.getStatus().name());
        msg.put("startCountdownDeadlineMillis", room.getGameSession().getStartCountdownDeadlineMillis());
        msg.put("maxRounds", room.getMaxRounds());
        msg.put("drawTime", room.getDrawTime());
        msg.put("language", room.getLanguage());
        msg.put("hasPassword", room.hasPassword());

        room.broadcast(msg);
    }

    private void dissolveRoom(Room room) {
        dissolveRoom(room, "Phòng đã bị giải tán do không đủ người chơi (tối thiểu "
                + Room.MIN_ACTIVE_PLAYERS + " người trong trận đang chơi).");
    }

    private void dissolveRoom(Room room, String reason) {
        Envelope dissolveMsg = new Envelope(MessageType.ROOM_DISSOLVED);
        dissolveMsg.setContent(reason);
        room.broadcast(dissolveMsg);

        for (ClientSession handler : room.getPlayers()) {
            handler.setCurrentRoomId(null);
        }

        room.getGameSession().cancelGame();
        roomManager.removeRoom(room.getRoomId());

        broadcastPlayerList();
        broadcastRoomList();
    }

    // ========================================================================
    // XỬ LÝ HỆ THỐNG BẠN BÈ (Delegate to FriendService)
    // ========================================================================

    public void handleFriendRequest(ClientSession handler, Envelope request) {
        String targetUsername = request.get("targetUsername");
        if (handler.getUsername() == null || targetUsername == null) return;
        try {
            friendService.sendFriendRequest(handler.getUsername(), targetUsername);
            handler.sendMessage(Envelope.success(MessageType.FRIEND_REQUEST,
                    "Đã gửi lời mời kết bạn tới " + targetUsername + "."));
        } catch (Exception e) {
            handler.sendMessage(Envelope.error(MessageType.FRIEND_REQUEST, e.getMessage()));
        }
    }

    public void handleFriendResponse(ClientSession handler, Envelope request) {
        String senderUsername = request.get("senderUsername");
        boolean accept = request.getBoolean("accept", false);
        if (handler.getUsername() == null || senderUsername == null) return;
        try {
            friendService.respondFriendRequest(handler.getUsername(), senderUsername, accept);
            handler.sendMessage(Envelope.success(MessageType.FRIEND_RESPONSE,
                    accept ? "Đã chấp nhận lời mời kết bạn." : "Đã từ chối lời mời kết bạn."));
        } catch (Exception e) {
            handler.sendMessage(Envelope.error(MessageType.FRIEND_RESPONSE, e.getMessage()));
        }
    }

    public void handleFriendList(ClientSession handler, Envelope request) {
        if (handler.getUsername() == null) return;
        try {
            List<Map<String, Object>> friends = friendService.getFriendList(handler.getUsername());
            Envelope response = Envelope.success(MessageType.FRIEND_LIST, "Danh sách bạn bè.");
            response.put("friends", friends);
            handler.sendMessage(response);
        } catch (Exception e) {
            handler.sendMessage(Envelope.error(MessageType.FRIEND_LIST, e.getMessage()));
        }
    }

    public void handleFriendRemove(ClientSession handler, Envelope request) {
        String targetUsername = request.get("targetUsername");
        if (handler.getUsername() == null || targetUsername == null) return;
        try {
            friendService.removeFriend(handler.getUsername(), targetUsername);
            handler.sendMessage(Envelope.success(MessageType.FRIEND_REMOVE,
                    "Đã xóa bạn bè " + targetUsername + "."));
        } catch (Exception e) {
            handler.sendMessage(Envelope.error(MessageType.FRIEND_REMOVE, e.getMessage()));
        }
    }

    public void handleFriendCancelRequest(ClientSession handler, Envelope request) {
        String targetUsername = request.get("targetUsername");
        if (handler.getUsername() == null || targetUsername == null) return;
        try {
            friendService.cancelFriendRequest(handler.getUsername(), targetUsername);
            handler.sendMessage(Envelope.success(MessageType.FRIEND_CANCEL_REQUEST, "Đã hủy lời mời kết bạn."));
        } catch (Exception e) {
            handler.sendMessage(Envelope.error(MessageType.FRIEND_CANCEL_REQUEST, e.getMessage()));
        }
    }

    public void handleFriendPendingList(ClientSession handler, Envelope request) {
        if (handler.getUsername() == null) return;
        try {
            List<Map<String, Object>> pending = friendService.getPendingRequests(handler.getUsername());
            Envelope response = Envelope.success(MessageType.FRIEND_PENDING_LIST, "Danh sách lời mời đang chờ.");
            response.put("pendingRequests", pending);
            handler.sendMessage(response);
        } catch (Exception e) {
            handler.sendMessage(Envelope.error(MessageType.FRIEND_PENDING_LIST, e.getMessage()));
        }
    }

    // ========================================================================
    // TÌM KIẾM NGƯỜI CHƠI
    // ========================================================================

    public void handleSearchPlayer(ClientSession handler, Envelope request) {
        String keyword = request.get("keyword");
        if (keyword == null || keyword.trim().isEmpty() || handler.getUsername() == null) return;
        List<Map<String, Object>> playerList = friendService.searchUsers(keyword.trim(), handler.getUsername());

        Envelope response = Envelope.success(MessageType.SEARCH_PLAYER, "Kết quả tìm kiếm.");
        response.put("results", playerList);
        handler.sendMessage(response);
    }

    // ========================================================================
    // BẢNG XẾP HẠNG
    // ========================================================================

    public void handleLeaderboard(ClientSession handler, Envelope request) {
        List<User> topUsers = userRepository.findTop50ByOrderByRankingScoreDesc();
        List<Integer> userIds = topUsers.stream().map(User::getUserId).filter(Objects::nonNull).toList();
        Map<Integer, int[]> pointBreakdowns = new HashMap<>();
        if (!userIds.isEmpty()) {
            for (Object[] row : matchResultRepository.sumPointBreakdownByUserIds(userIds)) {
                int userId = ((Number) row[0]).intValue();
                int pointsDrawn = row[1] == null ? 0 : ((Number) row[1]).intValue();
                int pointsGuess = row[2] == null ? 0 : ((Number) row[2]).intValue();
                pointBreakdowns.put(userId, new int[]{pointsDrawn, pointsGuess});
            }
        }
        List<Map<String, Object>> leaderboard = new ArrayList<>();
        int rank = 1;
        for (User u : topUsers) {
            int[] breakdown = pointBreakdowns.getOrDefault(u.getUserId(), new int[]{0, 0});
            Map<String, Object> entry = new HashMap<>();
            entry.put("rank", rank++);
            entry.put("username", u.getUsername());
            entry.put("displayName", u.getDisplayName());
            entry.put("avatar", u.getAvatar());
            entry.put("rankingScore", u.getRankingScore());
            entry.put("pointsDrawn", breakdown[0]);
            entry.put("pointsGuess", breakdown[1]);
            entry.put("online", isUserOnline(u.getUsername()));
            leaderboard.add(entry);
        }
        Envelope response = Envelope.success(MessageType.LEADERBOARD, "Bảng xếp hạng.");
        response.put("leaderboard", leaderboard);
        handler.sendMessage(response);
    }

    public void handleProfileRequest(ClientSession handler, Envelope envelope) {
        String targetUsername = envelope.getString("username");
        if (targetUsername == null || targetUsername.trim().isEmpty()) {
            targetUsername = handler.getUsername();
        }
        if (targetUsername == null || targetUsername.trim().isEmpty()) {
            handler.sendMessage(Envelope.error(MessageType.PROFILE_RESPONSE, "Chưa đăng nhập."));
            return;
        }

        User user = authService.getProfile(targetUsername);
        if (user == null) {
            handler.sendMessage(Envelope.error(MessageType.PROFILE_RESPONSE, "Không tìm thấy người chơi: " + targetUsername));
            return;
        }

        List<com.nhom8.server.model.MatchResult> results = matchResultRepository.findByUserWithMatchOrderByStartedAtDesc(user);
        int totalMatches = results.size();
        int totalPoints = results.stream().mapToInt(com.nhom8.server.model.MatchResult::getTotalPoints).sum();
        int winCount = 0;
        for (com.nhom8.server.model.MatchResult mr : results) {
            if (mr.getMatch() != null) {
                List<com.nhom8.server.model.MatchResult> allResults = matchResultRepository.findByMatch_MatchId(mr.getMatch().getMatchId());
                int maxScore = allResults.stream().mapToInt(com.nhom8.server.model.MatchResult::getTotalPoints).max().orElse(0);
                if (mr.getTotalPoints() >= maxScore && mr.getTotalPoints() > 0) {
                    winCount++;
                }
            }
        }

        Envelope response = Envelope.success(MessageType.PROFILE_RESPONSE, "Hồ sơ cá nhân.");
        response.put("username", user.getUsername());
        response.put("displayName", user.getDisplayName());
        response.put("avatar", user.getAvatar() != null ? user.getAvatar() : "1");
        response.put("rankingScore", user.getRankingScore() != null ? user.getRankingScore() : 0);
        response.put("createdAt", user.getCreatedAt() != null ? user.getCreatedAt().toString() : "");
        response.put("totalMatches", totalMatches);
        response.put("totalPoints", totalPoints);
        response.put("winCount", winCount);
        response.putBoolean("isSelf", targetUsername.equalsIgnoreCase(handler.getUsername()));
        handler.sendMessage(response);
    }

    public void handleUpdateProfile(ClientSession handler, Envelope envelope) {
        if (handler.getUsername() == null || handler.getUsername().isEmpty()) {
            handler.sendMessage(Envelope.error(MessageType.UPDATE_PROFILE_RESPONSE, "Bạn chưa đăng nhập."));
            return;
        }

        String newDisplayName = envelope.getString("newDisplayName");
        String newAvatar = envelope.getString("newAvatar");
        String oldPassword = envelope.getString("oldPassword");
        String newPassword = envelope.getString("newPassword");

        try {
            User updatedUser = authService.updateProfile(handler.getUsername(), newDisplayName, newAvatar, oldPassword, newPassword);
            handler.setDisplayName(updatedUser.getDisplayName());

            Envelope response = Envelope.success(MessageType.UPDATE_PROFILE_RESPONSE, "Cập nhật thông tin cá nhân thành công!");
            response.put("username", updatedUser.getUsername());
            response.put("displayName", updatedUser.getDisplayName());
            response.put("avatar", updatedUser.getAvatar());
            response.putBoolean("avatarUpdated", newAvatar != null && !newAvatar.isBlank());
            response.put("rankingScore", updatedUser.getRankingScore());
            handler.sendMessage(response);

            // Gửi lại danh sách người chơi toàn hệ thống và cập nhật bạn bè
            broadcastPlayerList();
            broadcastFriendListToFriends(handler.getUsername());
            if (handler.getCurrentRoomId() != null) {
                Room room = roomManager.getRoom(handler.getCurrentRoomId());
                if (room != null) broadcastRoomUpdate(room);
            }
        } catch (IllegalArgumentException e) {
            Envelope error = Envelope.error(MessageType.UPDATE_PROFILE_RESPONSE, e.getMessage());
            error.putBoolean("avatarUpdated", newAvatar != null && !newAvatar.isBlank());
            handler.sendMessage(error);
        } catch (Exception e) {
            Envelope error = Envelope.error(MessageType.UPDATE_PROFILE_RESPONSE, "Lỗi cập nhật: " + e.getMessage());
            error.putBoolean("avatarUpdated", newAvatar != null && !newAvatar.isBlank());
            handler.sendMessage(error);
        }
    }

    public void handleMatchHistory(ClientSession handler, Envelope envelope) {
        String targetUsername = envelope.getString("username");
        if (targetUsername == null || targetUsername.trim().isEmpty()) {
            targetUsername = handler.getUsername();
        }
        if (targetUsername == null) {
            handler.sendMessage(Envelope.error(MessageType.MATCH_HISTORY_RESPONSE, "Chưa đăng nhập."));
            return;
        }

        User user = authService.getProfile(targetUsername);
        if (user == null) {
            handler.sendMessage(Envelope.error(MessageType.MATCH_HISTORY_RESPONSE, "Không tìm thấy người chơi."));
            return;
        }

        List<com.nhom8.server.model.MatchResult> results = matchResultRepository.findByUserWithMatchOrderByStartedAtDesc(user);
        List<Map<String, Object>> historyList = new ArrayList<>();
        java.time.format.DateTimeFormatter fmt = java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");
        java.util.Set<Integer> includedMatchIds = new java.util.HashSet<>();

        for (com.nhom8.server.model.MatchResult mr : results) {
            if (mr.getMatch() == null || !includedMatchIds.add(mr.getMatch().getMatchId())) continue;
            Map<String, Object> item = new HashMap<>();
            item.put("resultId", mr.getResultId());
            com.nhom8.server.model.Match match = mr.getMatch();
            item.put("matchId", match.getMatchId());
            item.put("roomId", match.getRoomId());
            item.put("startedAt", match.getStartedAt() != null ? match.getStartedAt().format(fmt) : "");
            item.put("endedAt", match.getEndedAt() != null ? match.getEndedAt().format(fmt) : "");
            item.put("startedAtEpoch", match.getStartedAt() == null ? 0L
                    : match.getStartedAt().atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli());
            List<com.nhom8.server.model.MatchResult> allResults = matchResultRepository.findByMatch_MatchId(match.getMatchId());
            allResults.sort((a, b) -> Integer.compare(b.getTotalPoints(), a.getTotalPoints()));
            int rank = 1;
            for (int i = 0; i < allResults.size(); i++) {
                if (allResults.get(i).getResultId().equals(mr.getResultId())) { rank = i + 1; break; }
            }
            item.put("rank", rank);
            item.put("totalPlayers", allResults.size());
            item.put("hasResult", true);
            item.put("pointsDrawn", mr.getPointsDrawn() != null ? mr.getPointsDrawn() : 0);
            item.put("pointsGuess", mr.getPointsGuess() != null ? mr.getPointsGuess() : 0);
            item.put("pointsAdjustment", mr.getPointsAdjustment());
            item.put("scoringVersion", match.getScoringVersion());
            item.put("totalPoints", mr.getTotalPoints());
            historyList.add(item);
        }

        // Older/incomplete matches may have saved drawings but no final MatchResult rows.
        for (com.nhom8.server.model.Painting painting : paintingRepository.findByPainterUsernameWithMatch(user.getUsername())) {
            com.nhom8.server.model.Match match = painting.getMatch();
            if (match == null || !includedMatchIds.add(match.getMatchId())) continue;
            Map<String, Object> item = new HashMap<>();
            item.put("matchId", match.getMatchId());
            item.put("roomId", match.getRoomId());
            item.put("startedAt", match.getStartedAt() != null ? match.getStartedAt().format(fmt) : "");
            item.put("endedAt", match.getEndedAt() != null ? match.getEndedAt().format(fmt) : "");
            item.put("startedAtEpoch", match.getStartedAt() == null ? 0L
                    : match.getStartedAt().atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli());
            item.put("rank", 0);
            item.put("totalPlayers", 0);
            item.put("hasResult", false);
            item.put("pointsDrawn", 0);
            item.put("pointsGuess", 0);
            item.put("pointsAdjustment", 0);
            item.put("scoringVersion", match.getScoringVersion());
            item.put("totalPoints", 0);
            historyList.add(item);
        }
        historyList.sort((a, b) -> Long.compare((Long) b.get("startedAtEpoch"), (Long) a.get("startedAtEpoch")));
        if (historyList.size() > 30) historyList = new ArrayList<>(historyList.subList(0, 30));

        Envelope response = Envelope.success(MessageType.MATCH_HISTORY_RESPONSE, "Lịch sử trận đấu.");
        response.put("history", historyList);
        response.put("username", targetUsername);
        handler.sendMessage(response);
    }

    /** Only a participant may inspect a match or its drawings. */
    private boolean canReplay(ClientSession handler, int matchId) {
        if (handler.getUsername() == null) return false;
        return matchResultRepository.existsByMatch_MatchIdAndUser_Username(matchId, handler.getUsername())
                || paintingRepository.existsByMatch_MatchIdAndPainter_Username(matchId, handler.getUsername());
    }

    public void handleMatchDetail(ClientSession handler, Envelope request) {
        int matchId = request.getInt("matchId", 0);
        if (matchId <= 0 || !canReplay(handler, matchId)) {
            handler.sendMessage(Envelope.error(MessageType.MATCH_DETAIL_RESPONSE, "Không có quyền xem trận này."));
            return;
        }
        List<Map<String, Object>> drawings = new ArrayList<>();
        for (com.nhom8.server.model.Painting painting :
                paintingRepository.findForMatch(matchId)) {
            Map<String, Object> item = new HashMap<>();
            item.put("paintingId", painting.getPaintingId());
            item.put("roundNumber", painting.getRoundNumber());
            item.put("turnNumber", painting.getTurnNumber());
            item.put("painter", painting.getPainter().getUsername());
            item.put("topic", painting.getTopic());
            drawings.add(item);
        }
        handler.sendMessage(Envelope.success(MessageType.MATCH_DETAIL_RESPONSE, "Chi tiết trận đấu")
                .put("matchId", matchId).put("drawings", drawings));
    }

    public void handlePaintingReplay(ClientSession handler, Envelope request) {
        int paintingId = request.getInt("paintingId", 0);
        com.nhom8.server.model.Painting painting = paintingRepository.findForReplay(paintingId).orElse(null);
        if (painting == null || painting.getMatch() == null
                || !canReplay(handler, painting.getMatch().getMatchId())) {
            handler.sendMessage(Envelope.error(MessageType.PAINTING_REPLAY_RESPONSE, "Không có quyền xem tranh này."));
            return;
        }
        handler.sendMessage(Envelope.success(MessageType.PAINTING_REPLAY_RESPONSE, "Xem lại tranh")
                .put("paintingId", paintingId)
                .put("strokeData", painting.getStrokeData() == null ? "[]" : painting.getStrokeData())
                .put("resultData", painting.getResultData()));
    }

    // === Getters ===

    public ConcurrentHashMap<String, ClientSession> getOnlineClients() { return onlineClients; }
    public ClientSession getClientSession(String username) { return onlineClients.get(username); }
    public RoomManager getRoomManager() { return roomManager; }
    public UserRepository getUserRepository() { return userRepository; }
    public MatchRepository getMatchRepository() { return matchRepository; }
    public MatchResultRepository getMatchResultRepository() { return matchResultRepository; }
    public PaintingRepository getPaintingRepository() { return paintingRepository; }
    public TopicCatalog getTopicCatalog() { return topicCatalog; }
    public FriendshipRepository getFriendshipRepository() { return friendshipRepository; }
}
