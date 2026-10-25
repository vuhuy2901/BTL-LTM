package com.nhom8.client.ui;

import com.fasterxml.jackson.databind.JsonNode;
import com.nhom8.client.MainApplication;
import com.nhom8.common.message.Envelope;
import com.nhom8.common.message.MessageType;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.*;

import java.util.HashSet;
import java.util.Set;

/** Màn quản trị dùng cùng giao thức WebSocket với trò chơi. */
public class AdminScreen {
    private final MainApplication app;
    private Scene scene;
    private final ListView<String> users = new ListView<>();
    private final ListView<String> rooms = new ListView<>();
    private final ListView<String> topics = new ListView<>();
    private final Label stats = new Label("Đang tải thống kê...");
    private final Label status = new Label("");

    public AdminScreen(MainApplication app) { this.app = app; }

    private void send(MessageType type) { app.getWsClient().send(new Envelope(type)); }

    public void refresh() {
        send(MessageType.ADMIN_STATS);
        send(MessageType.ADMIN_GET_USERS);
        send(MessageType.ADMIN_GET_ROOMS);
        send(MessageType.ADMIN_TOPICS);
    }

    public Scene createScene() {
        if (scene != null) return scene;
        BorderPane root = new BorderPane();
        root.setStyle("-fx-background-color: #F5B800;");
        HBox header = new HBox(12);
        header.setPadding(new Insets(16));
        Label title = new Label("QUẢN TRỊ HỆ THỐNG");
        title.setStyle("-fx-font-family: 'Amatic SC'; -fx-font-size: 38px; -fx-font-weight: bold;");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        Button back = new Button("Về sảnh");
        back.setOnAction(e -> app.showLobbyScreen());
        header.getChildren().addAll(title, spacer, back);
        root.setTop(header);

        TabPane tabs = new TabPane();
        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        VBox overview = new VBox(12, stats);
        overview.setPadding(new Insets(20));
        tabs.getTabs().add(new Tab("Thống kê", overview));

        VBox usersPane = new VBox(10, new Label("Tài khoản và điểm xếp hạng"), users);
        usersPane.setPadding(new Insets(16));
        VBox.setVgrow(users, Priority.ALWAYS);
        tabs.getTabs().add(new Tab("Người chơi", usersPane));

        TextField roomId = new TextField();
        roomId.setPromptText("Mã phòng cần giải tán");
        Button dissolve = new Button("Giải tán phòng");
        dissolve.setOnAction(e -> {
            if (roomId.getText().isBlank()) return;
            String selectedRoomId = roomId.getText().trim();
            DoodleDialog.confirm(app.getPrimaryStage(), "Giải tán phòng?",
                    "Bạn có chắc muốn giải tán phòng " + selectedRoomId + "?",
                    "GIẢI TÁN", "HỦY", accepted -> {
                        if (accepted) app.getWsClient().send(
                                new Envelope(MessageType.ADMIN_DISSOLVE_ROOM).put("roomId", selectedRoomId));
                    });
        });
        VBox roomPane = new VBox(10, rooms, new HBox(8, roomId, dissolve));
        roomPane.setPadding(new Insets(16));
        VBox.setVgrow(rooms, Priority.ALWAYS);
        tabs.getTabs().add(new Tab("Phòng chơi", roomPane));

        TextField word = new TextField();
        word.setPromptText("Từ khóa mới");
        Button add = new Button("Thêm");
        add.setOnAction(e -> {
            app.getWsClient().send(new Envelope(MessageType.ADMIN_TOPIC_ADD).put("word", word.getText()));
            word.clear();
        });
        Button delete = new Button("Xóa từ được chọn");
        delete.setOnAction(e -> {
            String selected = topics.getSelectionModel().getSelectedItem();
            if (selected != null) app.getWsClient().send(new Envelope(MessageType.ADMIN_TOPIC_DELETE)
                    .put("word", selected));
        });
        Button edit = new Button("Sửa từ được chọn");
        edit.setOnAction(e -> {
            String selected = topics.getSelectionModel().getSelectedItem();
            if (selected == null) return;
            TextInputDialog dialog = new TextInputDialog(selected);
            dialog.initOwner(app.getPrimaryStage());
            dialog.setTitle("Sửa từ khóa");
            dialog.setHeaderText("Nhập từ khóa mới");
            dialog.setContentText("Từ khóa:");
            dialog.showAndWait().ifPresent(replacement -> app.getWsClient().send(
                    new Envelope(MessageType.ADMIN_TOPIC_UPDATE)
                             .put("word", selected).put("replacement", replacement)));
        });
        topics.setPlaceholder(new Label("Chưa có từ khóa."));
        VBox customPane = new VBox(8, new Label("Tất cả từ khóa dùng trong trận"), topics,
                new HBox(8, word, add, edit, delete));
        HBox.setHgrow(word, Priority.ALWAYS);
        VBox.setVgrow(topics, Priority.ALWAYS);
        VBox topicPane = new VBox(10, new Label("Toàn bộ từ khóa trong database do admin quản lý."), customPane);
        topicPane.setPadding(new Insets(16));
        VBox.setVgrow(customPane, Priority.ALWAYS);
        tabs.getTabs().add(new Tab("Từ khóa", topicPane));

        root.setCenter(tabs);
        status.setPadding(new Insets(10));
        root.setBottom(status);
        scene = new Scene(root, 1050, 700);
        MainApplication.applyTheme(scene);
        return scene;
    }

    public void handleMessage(Envelope env) {
        JsonNode data = env.getData();
        if (!env.isSuccess()) {
            status.setText(env.getContent() == null ? "Thao tác thất bại" : env.getContent());
            return;
        }
        switch (env.getType()) {
            case ADMIN_STATS -> stats.setText(String.format(
                    "Tài khoản: %d    Online: %d    Phòng đang mở: %d\nTrận đã tạo: %d    Tranh đã lưu: %d    Từ khóa: %d",
                    data.path("totalUsers").asLong(), data.path("onlineUsers").asLong(),
                    data.path("activeRooms").asLong(), data.path("totalMatches").asLong(),
                    data.path("totalPaintings").asLong(), data.path("customTopics").asLong()));
            case ADMIN_USERS_RESPONSE -> {
                users.getItems().clear();
                data.path("users").forEach(u -> users.getItems().add(
                        u.path("username").asText() + "  |  " + u.path("role").asText() + "  |  " + u.path("rankingScore").asInt() + " điểm"));
            }
            case ADMIN_GET_ROOMS -> {
                rooms.getItems().clear();
                data.path("rooms").forEach(r -> rooms.getItems().add(
                        "#" + r.path("roomId").asText() + "  " + r.path("roomName").asText() + "  |  "
                        + r.path("playerCount").asInt() + "/" + r.path("maxPlayers").asInt()
                        + "  |  " + r.path("status").asText()));
            }
            case ADMIN_TOPICS -> {
                topics.getItems().clear();
                Set<String> seen = new HashSet<>();
                data.path("topics").forEach(t -> {
                    String topic = t.asText();
                    if (seen.add(topic.toLowerCase(java.util.Locale.ROOT))) topics.getItems().add(topic);
                });
                topics.getItems().sort(String.CASE_INSENSITIVE_ORDER);
            }
            case ADMIN_DISSOLVE_ROOM -> status.setText(env.getContent());
            case ADMIN_TOPIC_ADD, ADMIN_TOPIC_DELETE, ADMIN_TOPIC_UPDATE -> status.setText(env.getContent());
            default -> {}
        }
    }

}
