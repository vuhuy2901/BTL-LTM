package com.nhom8.client.ui;

import com.fasterxml.jackson.databind.JsonNode;
import com.nhom8.client.MainApplication;
import com.nhom8.common.message.Envelope;
import com.nhom8.common.message.MessageType;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.*;

import java.util.Optional;

public class LobbyScreen {
    private final MainApplication app;
    private final ObservableList<RoomEntry> roomList;
    private final ObservableList<String> playerList;

    private ListView<RoomEntry> roomListView;
    private Label welcomeLabel;
    private Label roomCountLabel;
    private Label playerCountLabel;
    private Scene scene;

    public static class RoomEntry {
        public String id;
        public String name;
        public String host;
        public String hostDisplayName;
        public int playerCount;
        public int maxPlayers;
        public String status;

        @Override
        public String toString() {
            String stateStr = "WAITING".equals(status) ? "🟢 Đang chờ" : "🔴 Đang chơi";
            String hostName = (hostDisplayName != null && !hostDisplayName.isEmpty()) ? hostDisplayName : host;
            return String.format("🎨 %s  [#%s]\n     👑 Chủ phòng: %s   |   👥 %d/%d người   |   %s",
                    name, id, hostName, playerCount, maxPlayers, stateStr);
        }
    }

    public LobbyScreen(MainApplication app) {
        this.app = app;
        this.roomList = FXCollections.observableArrayList();
        this.playerList = FXCollections.observableArrayList();
    }

    public Scene createScene() {
        if (scene != null) return scene;

        BorderPane root = new BorderPane();
        root.setStyle("-fx-background-color: #F5B800;");

        // Top Navigation Bar
        HBox topBar = new HBox(20);
        topBar.setAlignment(Pos.CENTER_LEFT);
        topBar.setPadding(new Insets(15, 25, 15, 25));
        topBar.setStyle("-fx-background-color: rgba(0, 0, 0, 0.1);");

        Label brandLabel = new Label("Scribble It! — Sảnh Chờ");
        brandLabel.setStyle("-fx-font-size: 24px; -fx-font-weight: bold; -fx-text-fill: white; -fx-effect: dropshadow(three-pass-box, rgba(0,0,0,0.3), 4, 0, 0, 1);");

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        welcomeLabel = new Label("👋 Xin chào, " + app.getCurrentDisplayName());
        welcomeLabel.setStyle("-fx-font-size: 15px; -fx-font-weight: bold; -fx-text-fill: white;");

        Button logoutBtn = new Button("Đăng xuất");
        logoutBtn.setStyle("-fx-background-color: white; -fx-text-fill: #D8000C; -fx-font-weight: bold; -fx-font-size: 12px; -fx-cursor: hand; -fx-background-radius: 6;");
        logoutBtn.setOnAction(e -> {
            if (app.getWsClient() != null) {
                app.getWsClient().close();
            }
            app.start(app.getPrimaryStage());
        });

        topBar.getChildren().addAll(brandLabel, spacer, welcomeLabel, logoutBtn);
        root.setTop(topBar);

        // Center Split: Left/Center = Rooms, Right = Online Users
        HBox mainContainer = new HBox(20);
        mainContainer.setPadding(new Insets(20));
        mainContainer.setAlignment(Pos.CENTER);

        // --- LEFT / CENTER: ROOMS CARD ---
        VBox roomsCard = new VBox(15);
        roomsCard.setPrefWidth(680);
        roomsCard.setPadding(new Insets(20));
        roomsCard.setStyle("-fx-background-color: white; -fx-background-radius: 12; -fx-effect: dropshadow(three-pass-box, rgba(0,0,0,0.15), 10, 0, 0, 3);");

        HBox roomsHeader = new HBox(15);
        roomsHeader.setAlignment(Pos.CENTER_LEFT);

        Label roomsTitle = new Label("Danh sách phòng đang chờ");
        roomsTitle.setStyle("-fx-font-size: 18px; -fx-font-weight: bold; -fx-text-fill: #333333;");

        roomCountLabel = new Label("(0 phòng)");
        roomCountLabel.setStyle("-fx-font-size: 14px; -fx-text-fill: #888888;");

        Region roomSpacer = new Region();
        HBox.setHgrow(roomSpacer, Priority.ALWAYS);

        Button createRoomBtn = new Button("➕ Tạo phòng mới");
        createRoomBtn.setStyle("-fx-background-color: #F5B800; -fx-text-fill: white; -fx-font-weight: bold; -fx-font-size: 13px; -fx-padding: 8 16; -fx-background-radius: 6; -fx-cursor: hand;");
        createRoomBtn.setOnAction(e -> promptCreateRoom());

        Button joinByCodeBtn = new Button("🚪 Vào bằng mã");
        joinByCodeBtn.setStyle("-fx-background-color: #52c41a; -fx-text-fill: white; -fx-font-weight: bold; -fx-font-size: 13px; -fx-padding: 8 16; -fx-background-radius: 6; -fx-cursor: hand;");
        joinByCodeBtn.setOnAction(e -> promptJoinByCode());

        roomsHeader.getChildren().addAll(roomsTitle, roomCountLabel, roomSpacer, createRoomBtn, joinByCodeBtn);

        // Room List View
        roomListView = new ListView<>(roomList);
        roomListView.setPrefHeight(380);
        roomListView.setStyle("-fx-background-radius: 6; -fx-font-size: 13px;");

        // Double click to join
        roomListView.setOnMouseClicked(click -> {
            if (click.getClickCount() == 2) {
                RoomEntry selected = roomListView.getSelectionModel().getSelectedItem();
                if (selected != null) {
                    joinRoom(selected.id);
                }
            }
        });

        HBox roomsFooter = new HBox(15);
        roomsFooter.setAlignment(Pos.CENTER_RIGHT);

        Button joinSelectedBtn = new Button("Vào phòng đã chọn ➔");
        joinSelectedBtn.setStyle("-fx-background-color: #F5B800; -fx-text-fill: white; -fx-font-weight: bold; -fx-font-size: 14px; -fx-padding: 10 20; -fx-background-radius: 6; -fx-cursor: hand;");
        joinSelectedBtn.setOnAction(e -> {
            RoomEntry selected = roomListView.getSelectionModel().getSelectedItem();
            if (selected != null) {
                joinRoom(selected.id);
            } else {
                Alert alert = new Alert(Alert.AlertType.WARNING, "Vui lòng chọn một phòng trong danh sách!");
                alert.show();
            }
        });

        roomsFooter.getChildren().add(joinSelectedBtn);

        roomsCard.getChildren().addAll(roomsHeader, roomListView, roomsFooter);

        // --- RIGHT: ONLINE PLAYERS CARD ---
        VBox playersCard = new VBox(15);
        playersCard.setPrefWidth(300);
        playersCard.setPadding(new Insets(20));
        playersCard.setStyle("-fx-background-color: white; -fx-background-radius: 12; -fx-effect: dropshadow(three-pass-box, rgba(0,0,0,0.15), 10, 0, 0, 3);");

        HBox playersHeader = new HBox(10);
        playersHeader.setAlignment(Pos.CENTER_LEFT);

        Label playersTitle = new Label("Người chơi online");
        playersTitle.setStyle("-fx-font-size: 18px; -fx-font-weight: bold; -fx-text-fill: #333333;");

        playerCountLabel = new Label("(0)");
        playerCountLabel.setStyle("-fx-font-size: 14px; -fx-text-fill: #888888;");

        playersHeader.getChildren().addAll(playersTitle, playerCountLabel);

        ListView<String> playerListView = new ListView<>(playerList);
        playerListView.setPrefHeight(430);
        playerListView.setStyle("-fx-background-radius: 6; -fx-font-size: 13px;");

        playersCard.getChildren().addAll(playersHeader, playerListView);

        mainContainer.getChildren().addAll(roomsCard, playersCard);
        root.setCenter(mainContainer);

        scene = new Scene(root, 1050, 620);
        return scene;
    }

    public Scene getScene() {
        return scene;
    }

    private void promptCreateRoom() {
        TextInputDialog dialog = new TextInputDialog("Phòng của " + app.getCurrentDisplayName());
        dialog.setTitle("Tạo phòng mới");
        dialog.setHeaderText("Đặt tên cho phòng của bạn:");
        dialog.setContentText("Tên phòng:");

        Optional<String> result = dialog.showAndWait();
        result.ifPresent(name -> {
            String trimmed = name.trim();
            if (trimmed.isEmpty()) {
                trimmed = "Phòng của " + app.getCurrentDisplayName();
            }
            Envelope env = new Envelope(MessageType.ROOM_CREATE);
            env.put("roomName", trimmed);
            app.getWsClient().send(env);
        });
    }

    private void promptJoinByCode() {
        TextInputDialog dialog = new TextInputDialog();
        dialog.setTitle("Vào phòng bằng mã");
        dialog.setHeaderText("Nhập mã phòng (6 chữ số):");
        dialog.setContentText("Mã phòng:");

        Optional<String> result = dialog.showAndWait();
        result.ifPresent(code -> {
            String trimmed = code.trim();
            if (!trimmed.isEmpty()) {
                joinRoom(trimmed);
            }
        });
    }

    private void joinRoom(String roomId) {
        if (roomId == null || roomId.trim().isEmpty()) return;
        Envelope env = new Envelope(MessageType.ROOM_JOIN);
        env.put("roomId", roomId.trim());
        app.getWsClient().send(env);
    }

    public void handleMessage(Envelope env) {
        Platform.runLater(() -> {
            if (welcomeLabel != null) {
                welcomeLabel.setText("👋 Xin chào, " + app.getCurrentDisplayName());
            }

            if (env.getType() == MessageType.ROOM_LIST) {
                roomList.clear();
                if (env.getData() != null && env.getData().has("rooms")) {
                    JsonNode roomsNode = env.getData().get("rooms");
                    roomsNode.forEach(r -> {
                        RoomEntry entry = new RoomEntry();
                        entry.id = r.has("roomId") ? r.get("roomId").asText() : "";
                        entry.name = r.has("roomName") ? r.get("roomName").asText() : ("Phòng " + entry.id);
                        entry.host = r.has("host") ? r.get("host").asText() : "";
                        entry.hostDisplayName = r.has("hostDisplayName") ? r.get("hostDisplayName").asText() : entry.host;
                        entry.playerCount = r.has("playerCount") ? r.get("playerCount").asInt() : 1;
                        entry.maxPlayers = r.has("maxPlayers") ? r.get("maxPlayers").asInt() : 6;
                        entry.status = r.has("status") ? r.get("status").asText() : "WAITING";
                        roomList.add(entry);
                    });
                }
                if (roomCountLabel != null) {
                    roomCountLabel.setText("(" + roomList.size() + " phòng)");
                }
            } else if (env.getType() == MessageType.PLAYER_LIST || env.getType() == MessageType.ONLINE_LIST) {
                playerList.clear();
                JsonNode listNode = null;
                if (env.getData() != null) {
                    if (env.getData().has("players")) listNode = env.getData().get("players");
                    else if (env.getData().has("users")) listNode = env.getData().get("users");
                }
                if (listNode != null) {
                    listNode.forEach(u -> {
                        String username = u.has("username") ? u.get("username").asText() : "";
                        String displayName = u.has("displayName") ? u.get("displayName").asText() : username;
                        String status = u.has("status") ? u.get("status").asText() : "Online";
                        int score = u.has("rankingScore") ? u.get("rankingScore").asInt() : 0;
                        playerList.add(String.format("👤 %s (@%s)\n    %s • %d điểm", displayName, username, status, score));
                    });
                }
                if (playerCountLabel != null) {
                    playerCountLabel.setText("(" + playerList.size() + ")");
                }
            } else if (env.getType() == MessageType.ROOM_RESPONSE) {
                if (env.isSuccess()) {
                    String roomId = env.getString("roomId");
                    String roomName = env.getString("roomName");
                    if (roomName == null || roomName.isEmpty()) roomName = "Phòng " + roomId;
                    app.showRoomScreen(roomId, roomName);
                } else {
                    Alert alert = new Alert(Alert.AlertType.ERROR, env.getContent() != null ? env.getContent() : "Không thể thực hiện thao tác phòng.");
                    alert.show();
                }
            }
        });
    }
}
