package com.nhom8.client.ui;

import com.nhom8.client.MainApplication;
import com.nhom8.client.ws.GameWebSocketClient;
import com.nhom8.common.message.*;
import javafx.application.Platform;
import javafx.scene.control.*;
import javafx.scene.layout.HBox;
import javafx.stage.Stage;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.CountDownLatch;

/** Kiểm tra nút mời khi bạn bè vào phòng rồi rời phòng. */
public class RoomInvitePresenceUiCheck {
    private static Throwable failure;
    public static void main(String[] args) throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        Platform.startup(() -> Platform.runLater(() -> {
            try {
                MainApplication app = new MainApplication();
                Stage stage = new Stage();
                set(app, "primaryStage", stage);
                set(app, "currentUsername", "admin");
                java.util.concurrent.atomic.AtomicInteger invites = new java.util.concurrent.atomic.AtomicInteger();
                set(app, "webSocketClient", new GameWebSocketClient("ws://localhost:8080/ws/game", e -> {}) {
                    @Override public void send(Envelope e) { if (e.getType() == MessageType.ROOM_INVITE_SEND) invites.incrementAndGet(); }
                    @Override public boolean isConnected() { return true; }
                });
                RoomScreen screen = new RoomScreen(app, "room", "Thử trạng thái");
                stage.setScene(screen.createScene());
                invoke(screen, "onFriendListUpdate", new Envelope(MessageType.FRIEND_LIST)
                        .put("friends", List.of(Map.of("username", "friend", "isOnline", true))));
                require(!button(screen).isDisabled(), "Bạn đang rảnh phải mời được");
                button(screen).fire();
                require(button(screen).isDisabled(), "Gửi một lần phải khóa nút mời ngay");
                button(screen).fire();
                require(invites.get() == 1, "Bấm lặp không được gửi thêm yêu cầu");
                invoke(screen, "onFriendListUpdate", new Envelope(MessageType.FRIEND_LIST)
                        .put("friends", List.of(Map.of("username", "friend", "isOnline", true))));
                require(button(screen).isDisabled() && button(screen).getText().contains("s)"),
                        "Cập nhật danh sách phải giữ đếm ngược thời gian chờ");
                Method cooldown = RoomScreen.class.getDeclaredMethod("setInviteCooldown", String.class, long.class);
                cooldown.setAccessible(true);
                cooldown.invoke(screen, "friend", 0L);
                invoke(screen, "onRoomUpdate", new Envelope(MessageType.ROOM_UPDATE)
                        .put("players", List.of(Map.of("username", "friend"))));
                require(button(screen).isDisabled(), "Vào cùng phòng phải khóa nút mời ngay");
                invoke(screen, "updateFriendRoomPresence", new Envelope(MessageType.PLAYER_LIST)
                        .put("players", List.of(Map.of("username", "friend", "roomId", "room"))));
                require(button(screen).getText().contains("Đã vào phòng"), "Phải hiện trạng thái đã vào phòng");
                invoke(screen, "onRoomUpdate", new Envelope(MessageType.ROOM_UPDATE).put("players", List.of()));
                invoke(screen, "updateFriendRoomPresence", new Envelope(MessageType.PLAYER_LIST)
                        .put("players", List.of(Map.of("username", "friend"))));
                require(!button(screen).isDisabled(), "Rời phòng về sảnh phải mời lại được");
                System.out.println("PASS: invite cooldown survives refresh; room presence disables and restores invites.");
            } catch (Throwable error) { failure = error; error.printStackTrace(); }
            finally { done.countDown(); Platform.exit(); }
        }));
        done.await();
        if (failure != null) System.exit(1);
    }
    @SuppressWarnings("unchecked")
    private static Button button(RoomScreen screen) throws Exception {
        ListView<LobbyScreen.FriendEntry> view = (ListView<LobbyScreen.FriendEntry>) field(screen, "friendListView");
        ListCell<LobbyScreen.FriendEntry> cell = view.getCellFactory().call(view);
        Method update = cell.getClass().getDeclaredMethod("updateItem", LobbyScreen.FriendEntry.class, boolean.class);
        update.setAccessible(true);
        update.invoke(cell, view.getItems().get(0), false);
        return (Button) ((HBox) cell.getGraphic()).getChildren().stream().filter(Button.class::isInstance).findFirst().orElseThrow();
    }
    private static Object field(Object target, String name) throws Exception {
        Field f = target.getClass().getDeclaredField(name); f.setAccessible(true); return f.get(target);
    }
    private static void set(Object target, String name, Object value) throws Exception {
        Field f = target.getClass().getDeclaredField(name); f.setAccessible(true); f.set(target, value);
    }
    private static void invoke(Object target, String name, Envelope value) throws Exception {
        Method method = target.getClass().getDeclaredMethod(name, Envelope.class); method.setAccessible(true); method.invoke(target, value);
    }
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
