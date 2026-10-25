package com.nhom8.common.message;

/**
 * Liệt kê toàn bộ các loại thông điệp (message type) trong giao thức
 * truyền thông giữa Client (JavaFX) và Server thông qua WebSocket JSON.
 *
 * Module: common (dùng chung cho cả server và client).
 */
public enum MessageType {

    // === Xác thực tài khoản ===
    LOGIN,
    LOGIN_REQUEST,
    LOGIN_RESULT,
    LOGIN_RESPONSE,
    REGISTER,
    REGISTER_REQUEST,
    REGISTER_RESPONSE,
    LOGOUT,

    // === Thông tin cá nhân ===
    UPDATE_PROFILE,
    UPDATE_PROFILE_REQUEST,
    UPDATE_PROFILE_RESPONSE,
    PROFILE_RESULT,

    // === Sảnh chờ (Lobby) ===
    ONLINE_LIST,
    PLAYER_LIST,
    STATUS_UPDATE,
    PLAYER_DISCONNECTED,
    PLAYER_RECONNECTED,
    SESSION_REPLACED,
    GAME_STATE_SNAPSHOT,
    DRAWING_DRAFT_SAVE,
    DRAWING_DRAFT_ACK,
    DRAWING_SUBMITTED,
    DRAWING_SKIPPED,

    // === Quản lý phòng chơi ===
    ROOM_CREATE,
    CREATE_ROOM,
    ROOM_LIST,
    ROOM_JOIN,
    JOIN_ROOM,
    ROOM_LEAVE,
    LEAVE_ROOM,
    ROOM_UPDATE,
    ROOM_RESPONSE,
    ROOM_PASSWORD_REQUIRED,
    READY,
    HOST_CHANGED,
    ROOM_DISBANDED,
    ROOM_DISSOLVED,
    ROOM_CHAT,
    ROOM_CHAT_SEND,
    ROOM_CHAT_RECEIVE,
    ROOM_KICK,
    ROOM_KICK_PLAYER,
    ROOM_SETTINGS,
    ROOM_UPDATE_SETTINGS,

    // === Mời vào phòng ===
    ROOM_INVITE_SEND,
    ROOM_INVITE_NOTIFY,
    ROOM_INVITE_RESPOND,

    // === Duyệt người lạ vào phòng ===
    ROOM_JOIN_REQUEST,
    ROOM_JOIN_APPROVAL,
    ROOM_JOIN_PENDING,

    // === Luồng trò chơi ===
    MATCH_START,
    GAME_START,
    TOPIC_OPTIONS,
    TOPIC_SELECT,
    TOPIC_CONFIRMED,
    DRAW_PHASE_START,
    DRAW_START,
    DRAWING_RESUME,
    STROKE_BATCH,
    DRAW_SUBMIT,
    GUESS_PHASE_START,
    GUESS_START,
    PAINTING_DISPLAY,
    GUESS_SUBMIT,
    GUESS_RESULT,
    GUESS_LOG,
    GUESS_PHASE_END,
    MATCH_SUMMARY,
    GAME_RESULT,

    // === Đồng hồ đếm ngược ===
    TIMER_UPDATE,

    // === Tính điểm và tổng kết ===
    ROUND_RESULT,

    // === Bảng xếp hạng ===
    LEADERBOARD,
    LEADERBOARD_REQUEST,
    LEADERBOARD_RESPONSE,
    GUESS_RANKING_UPDATE,

    // === Hệ thống bạn bè ===
    FRIEND_REQUEST,
    FRIEND_REQUEST_SEND,
    FRIEND_RESPONSE,
    FRIEND_REQUEST_RESPOND,
    FRIEND_LIST,
    FRIEND_LIST_REQUEST,
    FRIEND_LIST_RESPONSE,
    FRIEND_REMOVE,
    FRIEND_CANCEL_REQUEST,
    FRIEND_UPDATE,
    FRIEND_PENDING_LIST,
    FRIEND_REQUEST_NOTIFY,

    // === Tìm kiếm người chơi ===
    SEARCH_PLAYER,
    SEARCH_USER_REQUEST,
    SEARCH_USER_RESPONSE,

    // === Nhắn tin riêng (Chat 1-1) ===
    CHAT_PRIVATE,
    DIRECT_MESSAGE_SEND,
    DIRECT_MESSAGE_RECEIVE,
    CHAT_HISTORY,
    CHAT_HISTORY_REQUEST,
    CHAT_HISTORY_RESPONSE,
    CHAT_INBOX_REQUEST,
    CHAT_INBOX_RESPONSE,
    CHAT_MARK_READ,

    // === Lịch sử trận đấu ===
    MATCH_HISTORY,
    MATCH_HISTORY_REQUEST,
    MATCH_HISTORY_RESPONSE,
    MATCH_DETAIL_REQUEST,
    MATCH_DETAIL_RESPONSE,
    PAINTING_REPLAY_REQUEST,
    PAINTING_REPLAY_RESPONSE,

    // === Hồ sơ cá nhân ===
    PROFILE,
    PROFILE_REQUEST,
    PROFILE_RESPONSE,

    // === Admin ===
    ADMIN_GET_USERS,
    ADMIN_USERS_RESPONSE,
    ADMIN_UPDATE_SCORE,
    ADMIN_DISSOLVE_ROOM,
    ADMIN_GET_ROOMS,
    ADMIN_STATS,
    ADMIN_TOPICS,
    ADMIN_TOPIC_ADD,
    ADMIN_TOPIC_DELETE,
    ADMIN_TOPIC_UPDATE,

    // === Lỗi chung ===
    ERROR
}
