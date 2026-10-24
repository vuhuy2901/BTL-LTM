package com.nhom8.client.ui;

import com.nhom8.client.MainApplication;
import com.nhom8.common.dto.StrokeDTO;
import com.nhom8.common.message.Envelope;
import com.nhom8.common.message.MessageType;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.layout.Pane;
import javafx.scene.shape.Path;
import javafx.stage.Stage;

import java.lang.reflect.Field;
import java.util.List;

/** Kiểm tra chủ phòng/admin thấy các nét tranh được chiếu qua nhiều lượt. */
public class PaintingDisplayUiCheck extends Application {
    private static Throwable failure;

    @Override public void start(Stage stage) {
        try {
            MainApplication app = new MainApplication();
            Field username = MainApplication.class.getDeclaredField("currentUsername");
            username.setAccessible(true);
            username.set(app, "admin");
            GameScreen screen = new GameScreen(app, "room-test");
            stage.setScene(screen.createScene());
            stage.show();
            StrokeDTO first = new StrokeDTO("BRUSH", "#000000", 4,
                    List.of(20.0, 100.0), List.of(30.0, 110.0));
            screen.handleMessage(new Envelope(MessageType.GUESS_START).put("drawingId", "one")
                    .put("painterUsername", "player1").put("paintingIndex", 1).put("totalPaintings", 2));
            screen.handleMessage(new Envelope(MessageType.PAINTING_DISPLAY).put("drawingId", "one")
                    .put("painterUsername", "player1").put("hint", "_ _ _").put("drawData", List.of(first)));
            Platform.runLater(() -> {
                try {
                    Field layerField = GameScreen.class.getDeclaredField("paintingLayer");
                    layerField.setAccessible(true);
                    Pane layer = (Pane) layerField.get(screen);
                    require(layer.isVisible() && layer.getChildren().size() == 1,
                            "Admin phải thấy nét tranh của player1");
                    require(layer.getChildren().get(0) instanceof Path,
                            "Tranh phải được chiếu bằng nét vector");
                    screen.handleMessage(new Envelope(MessageType.GUESS_START).put("drawingId", "two")
                            .put("painterUsername", "player2").put("paintingIndex", 2).put("totalPaintings", 2));
                    screen.handleMessage(new Envelope(MessageType.PAINTING_DISPLAY).put("drawingId", "two")
                            .put("painterUsername", "player2").put("drawData", List.of(first, first)));
                    Platform.runLater(() -> {
                        try {
                            require(layer.getChildren().size() == 2,
                                    "Lượt tiếp theo phải thay tranh cũ bằng tranh mới");
                            System.out.println("PASS: admin receives and displays paintings across turns.");
                        } catch (Throwable ex) { failure = ex; ex.printStackTrace(); }
                        finally { Platform.exit(); }
                    });
                } catch (Throwable ex) { failure = ex; ex.printStackTrace(); Platform.exit(); }
            });
        } catch (Throwable ex) { failure = ex; ex.printStackTrace(); Platform.exit(); }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    public static void main(String[] args) {
        launch(args);
        if (failure != null) System.exit(1);
    }

    public static final class Runner {
        public static void main(String[] args) { PaintingDisplayUiCheck.main(args); }
    }
}
