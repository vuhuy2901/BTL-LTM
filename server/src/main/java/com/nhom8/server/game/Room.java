package com.nhom8.server.game;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import com.nhom8.common.message.Envelope;
import com.nhom8.server.service.GameService;
import com.nhom8.server.ws.ClientSession;

/**
 * Lớp đại diện cho một phòng chơi trong trò chơi "Vẽ Hình Đoán Ý".
 * Quản lý danh sách người chơi, trạng thái sẵn sàng, và phiên chơi (GameSession).
 *
 * Thay đổi so với phiên bản cũ:
 * - ClientHandler → ClientSession
 * - ServerControl → GameService
 */
public class Room {

    public enum RoomStatus {
        WAITING, PLAYING, FINISHED
    }

    public static final int MIN_PLAYERS = 3;
    public static final int MAX_PLAYERS = 8;
    public static final int MIN_ACTIVE_PLAYERS = MIN_PLAYERS;

    private String roomId;
    private String roomName;
    private String hostUsername;
    private final String passwordHash;
    private RoomStatus status;
    private ConcurrentHashMap<String, ClientSession> players;
    private ConcurrentHashMap<String, Boolean> readyStatus;
    private GameSession gameSession;

    // Room Settings
    private int maxRounds = 4;
    private int drawTime = 60;
    private String language = "vi";

    public Room(String roomId, String roomName, ClientSession host, GameService gameService) {
        this(roomId, roomName, host, gameService, null);
    }

    public Room(String roomId, String roomName, ClientSession host, GameService gameService, String passwordHash) {
        this.roomId = roomId;
        this.roomName = roomName;
        this.hostUsername = host.getUsername();
        this.passwordHash = passwordHash;
        this.status = RoomStatus.WAITING;
        this.players = new ConcurrentHashMap<>();
        this.readyStatus = new ConcurrentHashMap<>();
        this.gameSession = new GameSession(this);
        this.gameSession.setGameService(gameService);

        addPlayer(host);
    }

    public synchronized void addPlayer(ClientSession handler) {
        if (handler == null || handler.getUsername() == null) return;
        if (players.size() < MAX_PLAYERS) {
            players.put(handler.getUsername(), handler);
            readyStatus.put(handler.getUsername(), false);
        }
    }

    /** Replaces a disconnected session while preserving the player's room and ready state. */
    public synchronized boolean reconnectPlayer(ClientSession handler) {
        if (handler == null || handler.getUsername() == null
                || !players.containsKey(handler.getUsername())) return false;
        players.put(handler.getUsername(), handler);
        handler.setCurrentRoomId(roomId);
        return true;
    }

    public synchronized void removePlayer(ClientSession handler) {
        if (handler == null || handler.getUsername() == null) return;
        if (!players.remove(handler.getUsername(), handler)) return;
        handler.setConnectionState(com.nhom8.common.dto.ConnectionState.LEFT);
        readyStatus.remove(handler.getUsername());
        if (handler.getUsername().equalsIgnoreCase(hostUsername) && !players.isEmpty()) {
            hostUsername = players.keys().nextElement();
        }
        if (isPlaying() && gameSession != null) {
            gameSession.onPlayerLeft(handler.getUsername());
        } else if (gameSession != null) gameSession.forgetFinishedRecovery(handler.getUsername());
    }

    public void setPlayerReady(String username, boolean ready) {
        readyStatus.put(username, ready);
    }

    public boolean allPlayersReady() {
        if (players.size() < MIN_PLAYERS) return false;
        if (players.values().stream().anyMatch(p -> !p.isConnected())) return false;
        for (Boolean ready : readyStatus.values()) {
            if (!ready) return false;
        }
        return true;
    }

    public void broadcast(Envelope message) {
        for (ClientSession handler : players.values()) {
            handler.sendMessage(message);
        }
    }

    public void broadcastExcept(Envelope message, String exceptUsername) {
        for (Map.Entry<String, ClientSession> entry : players.entrySet()) {
            if (!entry.getKey().equals(exceptUsername)) {
                entry.getValue().sendMessage(message);
            }
        }
    }

    public ArrayList<HashMap<String, Object>> getPlayerListData() {
        ArrayList<HashMap<String, Object>> list = new ArrayList<>();
        for (Map.Entry<String, ClientSession> entry : players.entrySet()) {
            HashMap<String, Object> info = new HashMap<>();
            info.put("username", entry.getKey());
            info.put("displayName", entry.getValue().getDisplayName());
            info.put("isReady", readyStatus.getOrDefault(entry.getKey(), false));
            info.put("isHost", entry.getKey().equalsIgnoreCase(hostUsername));
            info.put("connected", entry.getValue().isConnected());
            info.put("connectionState", entry.getValue().getConnectionState().name());
            info.put("reconnectDeadline", entry.getValue().getReconnectDeadline());
            list.add(info);
        }
        return list;
    }

    public String getRoomId() { return roomId; }
    public String getRoomName() { return roomName; }
    public String getHostUsername() { return hostUsername; }
    public boolean hasPassword() { return passwordHash != null && !passwordHash.isBlank(); }
    public boolean matchesPassword(String password, org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder encoder) {
        return !hasPassword() || (password != null && encoder.matches(password, passwordHash));
    }
    public RoomStatus getStatus() { return status; }
    public void setStatus(RoomStatus status) { this.status = status; }
    public int getPlayerCount() { return players.size(); }
    public boolean isFull() { return players.size() >= MAX_PLAYERS; }
    public boolean isPlaying() { return status == RoomStatus.PLAYING; }
    public Collection<ClientSession> getPlayers() { return players.values(); }
    public ClientSession getPlayer(String username) { return players.get(username); }
    public Set<String> getPlayerUsernames() { return players.keySet(); }
    public GameSession getGameSession() { return gameSession; }

    public int getMaxRounds() { return maxRounds; }
    public void setMaxRounds(int maxRounds) { this.maxRounds = maxRounds; }
    public int getDrawTime() { return drawTime; }
    public void setDrawTime(int drawTime) { this.drawTime = drawTime; }
    public String getLanguage() { return language; }
    public void setLanguage(String language) { this.language = language; }

    public void resetReadyStatus() {
        for (String username : readyStatus.keySet()) {
            readyStatus.put(username, false);
        }
    }
}
