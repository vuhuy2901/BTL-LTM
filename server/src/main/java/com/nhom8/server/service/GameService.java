package com.nhom8.server.service;


import org.springframework.stereotype.Service;

import com.nhom8.server.game.Room;
import com.nhom8.server.game.RoomManager;
import com.nhom8.server.model.Friendship;
import com.nhom8.server.model.User;
import com.nhom8.server.model.CustomTopic;
import com.nhom8.server.repository.CustomTopicRepository;
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
import java.util.concurrent.ConcurrentHashMap;

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

    /** Bảng ánh xạ username → ClientSession cho tất cả người chơi đang trực tuyến */
    private final ConcurrentHashMap<String, ClientSession> onlineClients = new ConcurrentHashMap<>();

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

    @org.springframework.beans.factory.annotation.Autowired
    private CustomTopicRepository customTopicRepository;

    @jakarta.annotation.PostConstruct
    public void loadCustomTopics() {
        refreshCustomTopics();
    }

    private void refreshCustomTopics() {
        TopicPool.setCustomTopics(customTopicRepository.findAll().stream().map(CustomTopic::getWord).toList());
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

    public void handleAuth(ClientSession handler, Envelope request) {
        String username = request.get("username");
        String password = request.get("password");
        if (username == null || password == null) return;

        try {
            com.nhom8.common.message.payload.LoginResultPayload payload = authService.login(username, password);
            username = payload.getPlayer().getUsername();
            if (onlineClients.containsKey(username)) {
                // Đóng connection cũ
                ClientSession oldSession = onlineClients.get(username);
                oldSession.sendMessage(Envelope.error(MessageType.LOGIN_RESULT, "Đăng nhập ở nơi khác."));
                try {
                    oldSession.getSession().close(org.springframework.web.socket.CloseStatus.NORMAL);
                } catch (java.io.IOException e) {
                    e.printStackTrace();
                }
            }

            handler.setUsername(username);
            handler.setDisplayName(payload.getPlayer().getDisplayName());
            onlineClients.put(username, handler);
            System.out.println("[SERVER] User " + username + " đã kết nối WebSocket.");

            Envelope response = new Envelope(MessageType.LOGIN_RESULT);
            response.setSuccess(true);
            response.setContent("Đăng nhập thành công");
            response.put("player", payload.getPlayer());
            User loggedInUser = userRepository.findByUsername(username);
            response.put("role", loggedInUser != null ? loggedInUser.getRole() : "USER");
            handler.sendMessage(response);

            sendChatInbox(handler);

            broadcastPlayerList();
            sendRoomList(handler);
            sendFriendListToUser(username);
            broadcastFriendListToFriends(username);
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

        String roomName = request.getOrDefault("roomName", "Phòng " + handler.getUsername());
        String roomPassword = request.get("roomPassword");
        if (roomPassword != null) roomPassword = roomPassword.trim();
        if (roomPassword == null || roomPassword.length() < 4 || roomPassword.length() > 32) {
            handler.sendMessage(Envelope.error(MessageType.ROOM_RESPONSE,
                    "Mật khẩu phòng bắt buộc và phải từ 4 đến 32 ký tự."));
            return;
        }
        String passwordHash = roomPasswordEncoder.encode(roomPassword);
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

    public void handleJoinRoom(ClientSession handler, Envelope request) {
        if (!handler.isAuthenticated() || handler.getUsername() == null) {
            handler.sendMessage(Envelope.error(MessageType.ROOM_RESPONSE, "Bạn cần đăng nhập trước khi vào phòng."));
            return;
        }

        if (handler.getCurrentRoomId() != null) {
            handler.sendMessage(Envelope.error(MessageType.ROOM_RESPONSE, "Bạn đang ở trong một phòng khác."));
            return;
        }

        String roomId = request.get("roomId");
        Room room = roomManager.getRoom(roomId);

        if (room == null) {
            handler.sendMessage(Envelope.error(MessageType.ROOM_RESPONSE, "Phòng không tồn tại."));
            return;
        }

        if (room.hasPassword() && !room.matchesPassword(request.get("roomPassword"), roomPasswordEncoder)) {
            Envelope passwordRequired = Envelope.error(MessageType.ROOM_PASSWORD_REQUIRED,
                    "Phòng này có mật khẩu hoặc mật khẩu chưa đúng.");
            passwordRequired.put("roomId", room.getRoomId());
            passwordRequired.put("roomName", room.getRoomName());
            handler.sendMessage(passwordRequired);
            return;
        }

        if (room.isFull()) {
            handler.sendMessage(Envelope.error(MessageType.ROOM_RESPONSE,
                    "Phòng đã đầy (tối đa 6 người)."));
            return;
        }

        if (room.isPlaying()) {
            handler.sendMessage(Envelope.error(MessageType.ROOM_RESPONSE,
                    "Phòng đang trong trận đấu, không thể tham gia."));
            return;
        }

        room.addPlayer(handler);
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

    public void handleLeaveRoom(ClientSession handler, Envelope request) {
        String roomId = handler.getCurrentRoomId();
        if (roomId == null) return;

        Room room = roomManager.getRoom(roomId);
        if (room == null) return;

        room.removePlayer(handler);
        handler.setCurrentRoomId(null);

        if (room.isPlaying() && room.getPlayerCount() < Room.MIN_PLAYERS) {
            dissolveRoom(room);
        } else if (room.getPlayerCount() == 0) {
            roomManager.removeRoom(roomId);
        } else {
            broadcastRoomUpdate(room);
        }

        broadcastPlayerList();
        broadcastRoomList();
    }

    public void handleReady(ClientSession handler, Envelope request) {
        String roomId = handler.getCurrentRoomId();
        if (roomId == null) return;

        Room room = roomManager.getRoom(roomId);
        if (room == null) return;

        boolean isReady = request.getBoolean("isReady", true);

        room.setPlayerReady(handler.getUsername(), isReady);
        broadcastRoomUpdate(room);

        if (room.getPlayerCount() >= Room.MIN_PLAYERS && room.allPlayersReady()) {
            room.getGameSession().startGame();
        }
    }

    public void handleRoomUpdateSettings(ClientSession handler, Envelope request) {
        String roomId = handler.getCurrentRoomId();
        if (roomId == null) return;
        Room room = roomManager.getRoom(roomId);
        if (room == null || !room.getHostUsername().equalsIgnoreCase(handler.getUsername())) return;

        if (request.getData() != null && request.getData().has("maxRounds")) {
            room.setMaxRounds(request.getInt("maxRounds", room.getMaxRounds()));
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
            targetSession.setCurrentRoomId(null);

            // Send notification to the kicked player
            Envelope kickedMsg = new Envelope(MessageType.ROOM_DISSOLVED);
            kickedMsg.setContent("Bạn đã bị chủ phòng mời ra khỏi phòng.");
            targetSession.sendMessage(kickedMsg);

            // Update room
            broadcastRoomUpdate(room);
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
        String guess = request.getContent();
        room.getGameSession().onGuessSubmitted(handler.getUsername(), guess);
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
            if (history != null && history.size() > 100) {
                history = history.subList(history.size() - 100, history.size());
            }
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
        handler.sendMessage(Envelope.success(MessageType.ADMIN_STATS, "Thống kê hệ thống")
                .put("totalUsers", userRepository.count())
                .put("onlineUsers", onlineClients.size())
                .put("activeRooms", roomManager.getRoomCount())
                .put("totalMatches", matchRepository.count())
                .put("totalPaintings", paintingRepository.count())
                .put("customTopics", customTopicRepository.count()));
    }

    public void handleAdminTopics(ClientSession handler) {
        if (!requireAdmin(handler, MessageType.ADMIN_TOPICS)) return;
        handler.sendMessage(Envelope.success(MessageType.ADMIN_TOPICS, "Từ khóa tự thêm")
                .put("topics", customTopicRepository.findAll().stream().map(CustomTopic::getWord).sorted().toList()));
    }

    public void handleAdminTopicAdd(ClientSession handler, Envelope request) {
        if (!requireAdmin(handler, MessageType.ADMIN_TOPIC_ADD)) return;
        String word = request.getString("word");
        word = word == null ? "" : word.trim().replaceAll("\\s+", " ");
        if (word.length() < 2 || word.length() > 100 || customTopicRepository.existsByWordIgnoreCase(word)) {
            handler.sendMessage(Envelope.error(MessageType.ADMIN_TOPIC_ADD, "Từ khóa phải dài 2–100 ký tự và chưa tồn tại."));
            return;
        }
        customTopicRepository.save(new CustomTopic(word));
        refreshCustomTopics();
        handler.sendMessage(Envelope.success(MessageType.ADMIN_TOPIC_ADD, "Đã thêm từ khóa: " + word));
        handleAdminTopics(handler);
        handleAdminStats(handler);
    }

    public void handleAdminTopicDelete(ClientSession handler, Envelope request) {
        if (!requireAdmin(handler, MessageType.ADMIN_TOPIC_DELETE)) return;
        String word = request.getString("word");
        CustomTopic topic = customTopicRepository.findAll().stream()
                .filter(t -> t.getWord().equalsIgnoreCase(word)).findFirst().orElse(null);
        if (topic == null) {
            handler.sendMessage(Envelope.error(MessageType.ADMIN_TOPIC_DELETE, "Không tìm thấy từ khóa."));
            return;
        }
        customTopicRepository.delete(topic);
        refreshCustomTopics();
        handler.sendMessage(Envelope.success(MessageType.ADMIN_TOPIC_DELETE, "Đã xóa từ khóa: " + topic.getWord()));
        handleAdminTopics(handler);
        handleAdminStats(handler);
    }

    // ========================================================================
    // XỬ LÝ HỆ THỐNG BẠN BÈ
    // ========================================================================

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
    public void handleRoomInviteSend(ClientSession handler, Envelope request) {
        String targetUsername = request.get("targetUsername");
        String myUsername = handler.getUsername();
        String roomId = handler.getCurrentRoomId();

        if (myUsername == null || targetUsername == null || roomId == null) {
            handler.sendMessage(Envelope.error(MessageType.FRIEND_UPDATE,
                    "Bạn chưa ở trong phòng chơi nào."));
            return;
        }

        User me = userRepository.findByUsername(myUsername);
        User target = userRepository.findByUsername(targetUsername);
        if (me == null || target == null) return;

        // Kiểm tra là bạn bè
        Friendship friendship = friendshipRepository.findByUsers(me, target);
        if (friendship == null || friendship.getStatus() != Friendship.FriendshipStatus.ACCEPTED) {
            handler.sendMessage(Envelope.error(MessageType.FRIEND_UPDATE,
                    "Bạn chỉ có thể mời bạn bè vào phòng."));
            return;
        }

        ClientSession targetSession = onlineClients.get(targetUsername);
        if (targetSession == null) {
            handler.sendMessage(Envelope.error(MessageType.FRIEND_UPDATE,
                    targetUsername + " đang offline."));
            return;
        }

        if (targetSession.getCurrentRoomId() != null) {
            handler.sendMessage(Envelope.error(MessageType.FRIEND_UPDATE,
                    targetUsername + " đang ở trong phòng khác."));
            return;
        }

        Room room = roomManager.getRoom(roomId);
        if (room == null) return;

        // Lời mời không bỏ qua mật khẩu phòng; server vẫn xác thực khi người nhận tham gia.
        Envelope invite = new Envelope(MessageType.ROOM_INVITE_NOTIFY);
        invite.put("roomId", roomId);
        invite.put("roomName", room.getRoomName());
        invite.put("inviterUsername", myUsername);
        invite.put("inviterAvatar", me.getAvatar());
        invite.setContent(myUsername + " mời bạn vào phòng " + room.getRoomName() + "!");
        targetSession.sendMessage(invite);

        handler.sendMessage(Envelope.success(MessageType.FRIEND_UPDATE,
                "Đã gửi lời mời vào phòng cho " + targetUsername + "."));
    }

    /**
     * Xử lý phản hồi lời mời vào phòng (chấp nhận hoặc từ chối).
     */
    public void handleRoomInviteRespond(ClientSession handler, Envelope request) {
        boolean accept = Boolean.TRUE.equals(request.get("accept"));
        String roomId = request.get("roomId");

        if (!accept || roomId == null) return;

        // Chấp nhận → tự động join room
        Envelope joinRequest = new Envelope(MessageType.JOIN_ROOM);
        joinRequest.put("roomId", roomId);
        handleJoinRoom(handler, joinRequest);
    }

    // ========================================================================
    // XỬ LÝ NGẮT KẾT NỐI
    // ========================================================================

    public void handleDisconnect(ClientSession handler) {
        String username = handler.getUsername();
        if (username != null) {
            boolean wentOffline = onlineClients.remove(username, handler);
            if (wentOffline) broadcastFriendListToFriends(username);

            String roomId = handler.getCurrentRoomId();
            if (roomId != null) {
                Room room = roomManager.getRoom(roomId);
                if (room != null) {
                    room.removePlayer(handler);

                    Envelope disconnectMsg = new Envelope(MessageType.PLAYER_DISCONNECTED);
                    disconnectMsg.put("username", username);
                    room.broadcast(disconnectMsg);

                    if (room.isPlaying() && room.getPlayerCount() < Room.MIN_PLAYERS) {
                        dissolveRoom(room);
                    } else if (room.getPlayerCount() == 0) {
                        roomManager.removeRoom(roomId);
                    } else {
                        broadcastRoomUpdate(room);
                    }
                }
            }
        }

        allClients.remove(handler);
        broadcastPlayerList();
        broadcastRoomList();
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
    }

    public void broadcastRoomUpdate(Room room) {
        Envelope msg = new Envelope(MessageType.ROOM_UPDATE);
        msg.put("roomId", room.getRoomId());
        msg.put("roomName", room.getRoomName());
        msg.put("hostUsername", room.getHostUsername());
        msg.put("players", room.getPlayerListData());
        msg.put("playerCount", room.getPlayerCount());
        msg.put("status", room.getStatus().name());
        msg.put("maxRounds", room.getMaxRounds());
        msg.put("drawTime", room.getDrawTime());
        msg.put("language", room.getLanguage());
        msg.put("hasPassword", room.hasPassword());

        room.broadcast(msg);
    }

    private void dissolveRoom(Room room) {
        dissolveRoom(room, "Phòng đã bị giải tán do không đủ người chơi (tối thiểu "
                + Room.MIN_PLAYERS + " người).");
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
        if (keyword == null || keyword.trim().isEmpty()) return;

        List<User> results = userRepository.findByUsernameContainingIgnoreCase(keyword.trim());
        List<User> displayResults = userRepository.findByDisplayNameContainingIgnoreCase(keyword.trim());

        // Hợp nhất kết quả, loại trùng
        Set<String> seen = new HashSet<>();
        List<Map<String, Object>> playerList = new ArrayList<>();
        for (User u : results) {
            if (seen.add(u.getUsername())) {
                Map<String, Object> info = new HashMap<>();
                info.put("username", u.getUsername());
                info.put("displayName", u.getDisplayName());
                info.put("avatar", u.getAvatar());
                info.put("rankingScore", u.getRankingScore());
                info.put("online", isUserOnline(u.getUsername()));
                playerList.add(info);
            }
        }
        for (User u : displayResults) {
            if (seen.add(u.getUsername())) {
                Map<String, Object> info = new HashMap<>();
                info.put("username", u.getUsername());
                info.put("displayName", u.getDisplayName());
                info.put("avatar", u.getAvatar());
                info.put("rankingScore", u.getRankingScore());
                info.put("online", isUserOnline(u.getUsername()));
                playerList.add(info);
            }
        }

        Envelope response = Envelope.success(MessageType.SEARCH_PLAYER, "Kết quả tìm kiếm.");
        response.put("results", playerList);
        handler.sendMessage(response);
    }

    // ========================================================================
    // BẢNG XẾP HẠNG
    // ========================================================================

    public void handleLeaderboard(ClientSession handler, Envelope request) {
        List<User> topUsers = userRepository.findTop50ByOrderByRankingScoreDesc();
        List<Map<String, Object>> leaderboard = new ArrayList<>();
        int rank = 1;
        for (User u : topUsers) {
            Map<String, Object> entry = new HashMap<>();
            entry.put("rank", rank++);
            entry.put("username", u.getUsername());
            entry.put("displayName", u.getDisplayName());
            entry.put("avatar", u.getAvatar());
            entry.put("rankingScore", u.getRankingScore());
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
            response.put("rankingScore", updatedUser.getRankingScore());
            handler.sendMessage(response);

            // Gửi lại danh sách người chơi toàn hệ thống và cập nhật bạn bè
            broadcastPlayerList();
            broadcastFriendListToFriends(handler.getUsername());
        } catch (IllegalArgumentException e) {
            handler.sendMessage(Envelope.error(MessageType.UPDATE_PROFILE_RESPONSE, e.getMessage()));
        } catch (Exception e) {
            handler.sendMessage(Envelope.error(MessageType.UPDATE_PROFILE_RESPONSE, "Lỗi cập nhật: " + e.getMessage()));
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

        for (com.nhom8.server.model.MatchResult mr : results) {
            if (historyList.size() >= 30) break; // Giới hạn 30 trận gần nhất
            Map<String, Object> item = new HashMap<>();
            item.put("resultId", mr.getResultId());
            if (mr.getMatch() != null) {
                item.put("matchId", mr.getMatch().getMatchId());
                item.put("roomId", mr.getMatch().getRoomId());
                item.put("startedAt", mr.getMatch().getStartedAt() != null ? mr.getMatch().getStartedAt().format(fmt) : "");
                item.put("endedAt", mr.getMatch().getEndedAt() != null ? mr.getMatch().getEndedAt().format(fmt) : "");

                List<com.nhom8.server.model.MatchResult> allResults = matchResultRepository.findByMatch_MatchId(mr.getMatch().getMatchId());
                allResults.sort((a, b) -> Integer.compare(b.getTotalPoints(), a.getTotalPoints()));
                int rank = 1;
                for (int i = 0; i < allResults.size(); i++) {
                    if (allResults.get(i).getResultId().equals(mr.getResultId())) {
                        rank = i + 1;
                        break;
                    }
                }
                item.put("rank", rank);
                item.put("totalPlayers", allResults.size());
            } else {
                item.put("matchId", 0);
                item.put("roomId", "—");
                item.put("startedAt", "");
                item.put("endedAt", "");
                item.put("rank", 1);
                item.put("totalPlayers", 1);
            }
            item.put("pointsDrawn", mr.getPointsDrawn() != null ? mr.getPointsDrawn() : 0);
            item.put("pointsGuess", mr.getPointsGuess() != null ? mr.getPointsGuess() : 0);
            item.put("totalPoints", mr.getTotalPoints());
            historyList.add(item);
        }

        Envelope response = Envelope.success(MessageType.MATCH_HISTORY_RESPONSE, "Lịch sử trận đấu.");
        response.put("history", historyList);
        response.put("username", targetUsername);
        handler.sendMessage(response);
    }

    // === Getters ===

    public ConcurrentHashMap<String, ClientSession> getOnlineClients() { return onlineClients; }
    public ClientSession getClientSession(String username) { return onlineClients.get(username); }
    public RoomManager getRoomManager() { return roomManager; }
    public UserRepository getUserRepository() { return userRepository; }
    public MatchRepository getMatchRepository() { return matchRepository; }
    public MatchResultRepository getMatchResultRepository() { return matchResultRepository; }
    public PaintingRepository getPaintingRepository() { return paintingRepository; }
    public FriendshipRepository getFriendshipRepository() { return friendshipRepository; }
}
