package com.nhom8.client.ui;

import com.nhom8.client.MainApplication;
import com.nhom8.client.ws.GameWebSocketClient;
import com.nhom8.common.message.Envelope;
import com.nhom8.common.message.MessageType;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;

import java.lang.reflect.Field;

/** Kiểm tra màn 3–2–1 trên client khi server gửi trạng thái phòng. */
public class RoomCountdownUiCheck extends Application {
    private static Throwable failure;

    @Override public void start(Stage stage) {
        try {
            MainApplication app = new MainApplication();
            set(app, "primaryStage", stage);
            set(app, "currentUsername", "admin");
            set(app, "webSocketClient", new GameWebSocketClient("ws://localhost:8080/ws/game", event -> {}) {
                @Override public void send(Envelope event) { }
                @Override public boolean isConnected() { return true; }
            });
            RoomScreen screen = new RoomScreen(app, "123456", "Phòng thử");
            stage.setScene(screen.createScene());
            stage.show();

            long now = System.currentTimeMillis();
            screen.handleMessage(new Envelope(MessageType.ROOM_UPDATE)
                    .put("roomId", "123456").put("serverTime", now)
                    .put("startCountdownDeadlineMillis", now + 3_000));
            Platform.runLater(() -> {
                try {
                    StackPane overlay = (StackPane) field(screen, "startCountdownOverlay");
                    require(overlay.isVisible(), "Phải hiện lớp đếm ngược khi đủ sẵn sàng");
                    require("3".equals(((javafx.scene.control.Label) field(screen, "startCountdownNumber")).getText()),
                            "Đếm ngược phải bắt đầu từ 3");
                    screen.handleMessage(new Envelope(MessageType.ROOM_UPDATE)
                            .put("roomId", "123456").put("serverTime", now)
                            .put("startCountdownDeadlineMillis", 0));
                    Platform.runLater(() -> {
                        try {
                            require(!overlay.isVisible(), "Hủy sẵn sàng phải đóng lớp đếm ngược");
                            System.out.println("PASS: room countdown appears and cancels on server updates.");
                        } catch (Throwable error) { failure = error; error.printStackTrace(); }
                        finally { Platform.exit(); }
                    });
                } catch (Throwable error) { failure = error; error.printStackTrace(); Platform.exit(); }
            });
        } catch (Throwable error) { failure = error; error.printStackTrace(); Platform.exit(); }
    }

    private static Object field(Object target, String name) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }

    private static void set(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    public static void main(String[] args) {
        launch(args);
        if (failure != null) System.exit(1);
    }

    public static final class Runner {
        public static void main(String[] args) { RoomCountdownUiCheck.main(args); }
    }
}
