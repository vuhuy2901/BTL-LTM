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

public class RoomScreen {
    private final MainApplication app;
    private final String roomId;
    private String roomName;

    private boolean isCurrentUserHost = false;
    private boolean isCurrentUserReady = false;

    private Label titleLabel;
    private Label codeBadge;
    private Label playerCountLabel;
    private VBox playersListVBox;
    private Button readyBtn;

    private ComboBox<Integer> drawTimeCombo;
    private ComboBox<Integer> roundsCombo;
    private Button saveSettingsBtn;

    private ObservableList<String> chatMessages;
    private ListView<String> chatListView;
    private TextField chatInput;

    private Scene scene;

    public RoomScreen(MainApplication app, String roomId, String roomName) {
        this.app = app;
        this.roomId = roomId;
        this.roomName = (roomName != null && !roomName.isEmpty()) ? roomName : "Phòng " + roomId;
        this.chatMessages = FXCollections.observableArrayList();
    }

    public Scene createScene() {
        if (scene != null) return scene;

        BorderPane root = new BorderPane();
        root.setStyle("-fx-background-color: #F5B800;");

        // Top Navigation Bar
        HBox topBar = new HBox(15);
        topBar.setAlignment(Pos.CENTER_LEFT);
        topBar.setPadding(new Insets(15, 25, 15, 25));
        topBar.setStyle("-fx-background-color: rgba(0, 0, 0, 0.1);");

        Button leaveBtn = new Button("⬅ Rời phòng");
        leaveBtn.setStyle("-fx-background-color: white; -fx-text-fill: #D8000C; -fx-font-weight: bold; -fx-font-size: 13px; -fx-cursor: hand; -fx-background-radius: 6;");
        leaveBtn.setOnAction(e -> leaveRoom());

        titleLabel = new Label("Phòng: " + roomName);
        titleLabel.setStyle("-fx-font-size: 22px; -fx-font-weight: bold; -fx-text-fill: white;");

        codeBadge = new Label("🔑 Mã phòng: " + roomId);
        codeBadge.setStyle("-fx-background-color: white; -fx-text-fill: #F5B800; -fx-font-weight: bold; -fx-padding: 6 14; -fx-background-radius: 15; -fx-font-size: 14px;");

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        playerCountLabel = new Label("1/6 Người chơi");
        playerCountLabel.setStyle("-fx-font-size: 15px; -fx-font-weight: bold; -fx-text-fill: white;");

        topBar.getChildren().addAll(leaveBtn, titleLabel, codeBadge, spacer, playerCountLabel);
        root.setTop(topBar);

        // Center Split: Left = Players & Settings, Right = Chat
        HBox contentBox = new HBox(20);
        contentBox.setPadding(new Insets(20));
        contentBox.setAlignment(Pos.CENTER);

        // LEFT CARD: Players + Settings
        VBox leftCard = new VBox(15);
        leftCard.setPrefWidth(600);
        leftCard.setPadding(new Insets(20));
        leftCard.setStyle("-fx-background-color: white; -fx-background-radius: 12; -fx-effect: dropshadow(three-pass-box, rgba(0,0,0,0.15), 10, 0, 0, 3);");

        Label playersHeader = new Label("Danh sách người chơi");
        playersHeader.setStyle("-fx-font-size: 18px; -fx-font-weight: bold; -fx-text-fill: #333333;");

        playersListVBox = new VBox(10);
        ScrollPane playerScroll = new ScrollPane(playersListVBox);
        playerScroll.setFitToWidth(true);
        playerScroll.setPrefHeight(220);
        playerScroll.setStyle("-fx-background-color: transparent; -fx-background: transparent;");

        // Ready controls
        readyBtn = new Button("Sẵn sàng (✔)");
        readyBtn.setStyle("-fx-background-color: #52c41a; -fx-text-fill: white; -fx-font-weight: bold; -fx-font-size: 15px; -fx-padding: 10 25; -fx-background-radius: 8; -fx-cursor: hand;");
        readyBtn.setOnAction(e -> toggleReady());

        Label readyHint = new Label("Cần tối thiểu 2 người chơi cùng sẵn sàng để bắt đầu");
        readyHint.setStyle("-fx-font-size: 12px; -fx-text-fill: #888888;");

        HBox readyBox = new HBox(15, readyBtn, readyHint);
        readyBox.setAlignment(Pos.CENTER_LEFT);

        // Settings section
        Separator sep = new Separator();

        Label settingsHeader = new Label("⚙ Cài đặt phòng");
        settingsHeader.setStyle("-fx-font-size: 16px; -fx-font-weight: bold; -fx-text-fill: #333333;");

        HBox settingsControls = new HBox(15);
        settingsControls.setAlignment(Pos.CENTER_LEFT);

        Label drawTimeLbl = new Label("Thời gian vẽ:");
        drawTimeLbl.setStyle("-fx-font-weight: bold; -fx-text-fill: #555555;");
        drawTimeCombo = new ComboBox<>();
        drawTimeCombo.getItems().addAll(30, 45, 60, 90, 120);
        drawTimeCombo.setValue(60);

        Label roundsLbl = new Label("Số vòng:");
        roundsLbl.setStyle("-fx-font-weight: bold; -fx-text-fill: #555555;");
        roundsCombo = new ComboBox<>();
        roundsCombo.getItems().addAll(2, 3, 4, 5);
        roundsCombo.setValue(3);

        saveSettingsBtn = new Button("Lưu cài đặt");
        saveSettingsBtn.setStyle("-fx-background-color: #F5B800; -fx-text-fill: white; -fx-font-weight: bold; -fx-background-radius: 6; -fx-cursor: hand;");
        saveSettingsBtn.setOnAction(e -> updateRoomSettings());

        settingsControls.getChildren().addAll(drawTimeLbl, drawTimeCombo, roundsLbl, roundsCombo, saveSettingsBtn);

        leftCard.getChildren().addAll(playersHeader, playerScroll, readyBox, sep, settingsHeader, settingsControls);

        // RIGHT CARD: In-room Chat
        VBox rightCard = new VBox(15);
        rightCard.setPrefWidth(380);
        rightCard.setPadding(new Insets(20));
        rightCard.setStyle("-fx-background-color: white; -fx-background-radius: 12; -fx-effect: dropshadow(three-pass-box, rgba(0,0,0,0.15), 10, 0, 0, 3);");

        Label chatHeader = new Label("💬 Trò chuyện phòng");
        chatHeader.setStyle("-fx-font-size: 18px; -fx-font-weight: bold; -fx-text-fill: #333333;");

        chatListView = new ListView<>(chatMessages);
        chatListView.setPrefHeight(340);
        chatListView.setStyle("-fx-background-radius: 6;");

        HBox chatInputBox = new HBox(10);
        chatInput = new TextField();
        chatInput.setPromptText("Nhập tin nhắn...");
        HBox.setHgrow(chatInput, Priority.ALWAYS);
        chatInput.setOnAction(e -> sendChatMessage());

        Button sendBtn = new Button("Gửi");
        sendBtn.setStyle("-fx-background-color: #F5B800; -fx-text-fill: white; -fx-font-weight: bold; -fx-background-radius: 6; -fx-cursor: hand;");
        sendBtn.setOnAction(e -> sendChatMessage());

        chatInputBox.getChildren().addAll(chatInput, sendBtn);

        rightCard.getChildren().addAll(chatHeader, chatListView, chatInputBox);

        contentBox.getChildren().addAll(leftCard, rightCard);
        root.setCenter(contentBox);

        scene = new Scene(root, 1050, 620);
        return scene;
    }

    public Scene getScene() {
        return scene;
    }

    private void toggleReady() {
        boolean nextReady = !isCurrentUserReady;
        Envelope env = new Envelope(MessageType.READY);
        env.putBoolean("isReady", nextReady);
        app.getWsClient().send(env);
    }

    private void leaveRoom() {
        Envelope env = new Envelope(MessageType.ROOM_LEAVE);
        app.getWsClient().send(env);
        app.showLobbyScreen();
    }

    private void updateRoomSettings() {
        if (!isCurrentUserHost) return;
        Envelope env = new Envelope(MessageType.ROOM_SETTINGS);
        if (drawTimeCombo.getValue() != null) {
            env.putInt("drawTime", drawTimeCombo.getValue());
        }
        if (roundsCombo.getValue() != null) {
            env.putInt("maxRounds", roundsCombo.getValue());
        }
        env.putString("language", "vi");
        app.getWsClient().send(env);
    }

    private void sendChatMessage() {
        String msg = chatInput.getText();
        if (msg != null && !msg.trim().isEmpty()) {
            Envelope env = new Envelope(MessageType.ROOM_CHAT);
            env.put("content", msg.trim());
            app.getWsClient().send(env);
            chatInput.clear();
        }
    }

    private void kickPlayer(String targetUsername) {
        if (!isCurrentUserHost) return;
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION, "Bạn có chắc muốn kích " + targetUsername + " khỏi phòng?", ButtonType.YES, ButtonType.NO);
        confirm.showAndWait().ifPresent(response -> {
            if (response == ButtonType.YES) {
                Envelope env = new Envelope(MessageType.ROOM_KICK);
                env.put("targetUsername", targetUsername);
                app.getWsClient().send(env);
            }
        });
    }

    public void handleMessage(Envelope env) {
        Platform.runLater(() -> {
            switch (env.getType()) {
                case ROOM_UPDATE:
                    onRoomUpdate(env);
                    break;
                case ROOM_UPDATE_SETTINGS:
                    int drawTime = env.getInt("drawTime", 60);
                    int maxRounds = env.getInt("maxRounds", 3);
                    drawTimeCombo.setValue(drawTime);
                    roundsCombo.setValue(maxRounds);
                    break;
                case ROOM_CHAT_RECEIVE:
                case ROOM_CHAT:
                    String sender = env.getString("senderDisplayName");
                    if (sender == null) sender = env.getString("senderUsername");
                    if (sender == null) sender = env.getSender() != null ? env.getSender() : "Ẩn danh";
                    String text = env.getString("content");
                    if (text == null) text = env.getContent();
                    chatMessages.add(sender + ": " + text);
                    if (chatListView != null && !chatMessages.isEmpty()) {
                        chatListView.scrollTo(chatMessages.size() - 1);
                    }
                    break;
                case GAME_START:
                case MATCH_START:
                    app.showGameScreen(roomId);
                    break;
                case ROOM_DISSOLVED:
                    Alert alert = new Alert(Alert.AlertType.INFORMATION, env.getContent() != null ? env.getContent() : "Phòng đã bị giải tán.");
                    alert.show();
                    app.showLobbyScreen();
                    break;
                default:
                    break;
            }
        });
    }

    private void onRoomUpdate(Envelope env) {
        if (env.getData() == null) return;

        if (env.getData().has("roomName")) {
            roomName = env.getData().get("roomName").asText();
            if (titleLabel != null) titleLabel.setText("Phòng: " + roomName);
        }

        int count = env.getInt("playerCount", 1);
        if (playerCountLabel != null) {
            playerCountLabel.setText(count + "/6 Người chơi");
        }

        if (env.getData().has("players")) {
            playersListVBox.getChildren().clear();
            JsonNode playersNode = env.getData().get("players");

            String myUser = app.getCurrentUsername();

            playersNode.forEach(pNode -> {
                String uName = pNode.has("username") ? pNode.get("username").asText() : "";
                String dName = pNode.has("displayName") ? pNode.get("displayName").asText() : uName;
                boolean ready = pNode.has("isReady") && pNode.get("isReady").asBoolean();
                boolean host = pNode.has("isHost") && pNode.get("isHost").asBoolean();

                if (uName.equals(myUser)) {
                    isCurrentUserHost = host;
                    isCurrentUserReady = ready;
                    updateMyControls();
                }

                HBox card = new HBox(12);
                card.setAlignment(Pos.CENTER_LEFT);
                card.setPadding(new Insets(10, 15, 10, 15));
                card.setStyle("-fx-background-color: #F8F9FA; -fx-background-radius: 8; -fx-border-color: #E9ECEF; -fx-border-radius: 8;");

                Label nameLbl = new Label((host ? "👑 " : "👤 ") + dName + " (@" + uName + ")");
                nameLbl.setStyle("-fx-font-weight: bold; -fx-font-size: 14px; -fx-text-fill: #333333;");
                HBox.setHgrow(nameLbl, Priority.ALWAYS);

                Label statusBadge = new Label();
                if (host) {
                    statusBadge.setText(ready ? "👑 SẴN SÀNG" : "👑 CHỦ PHÒNG");
                    statusBadge.setStyle("-fx-background-color: #FFF3CD; -fx-text-fill: #856404; -fx-font-weight: bold; -fx-padding: 4 10; -fx-background-radius: 12;");
                } else if (ready) {
                    statusBadge.setText("✔ SẴN SÀNG");
                    statusBadge.setStyle("-fx-background-color: #D4EDDA; -fx-text-fill: #155724; -fx-font-weight: bold; -fx-padding: 4 10; -fx-background-radius: 12;");
                } else {
                    statusBadge.setText("⏳ CHỜ ĐỢI");
                    statusBadge.setStyle("-fx-background-color: #E2E3E5; -fx-text-fill: #383D41; -fx-font-weight: bold; -fx-padding: 4 10; -fx-background-radius: 12;");
                }

                card.getChildren().addAll(nameLbl, statusBadge);

                // Nút kích người chơi (chỉ chủ phòng thấy, và không thể tự kích mình)
                if (isCurrentUserHost && !uName.equals(myUser)) {
                    Button kickBtn = new Button("Kích");
                    kickBtn.setStyle("-fx-background-color: #ff4d4f; -fx-text-fill: white; -fx-font-size: 11px; -fx-font-weight: bold; -fx-padding: 3 8; -fx-background-radius: 4; -fx-cursor: hand;");
                    kickBtn.setOnAction(e -> kickPlayer(uName));
                    card.getChildren().add(kickBtn);
                }

                playersListVBox.getChildren().add(card);
            });
        }
    }

    private void updateMyControls() {
        if (readyBtn != null) {
            if (isCurrentUserReady) {
                readyBtn.setText("Hủy sẵn sàng (✖)");
                readyBtn.setStyle("-fx-background-color: #ff4d4f; -fx-text-fill: white; -fx-font-weight: bold; -fx-font-size: 15px; -fx-padding: 10 25; -fx-background-radius: 8; -fx-cursor: hand;");
            } else {
                readyBtn.setText("Sẵn sàng (✔)");
                readyBtn.setStyle("-fx-background-color: #52c41a; -fx-text-fill: white; -fx-font-weight: bold; -fx-font-size: 15px; -fx-padding: 10 25; -fx-background-radius: 8; -fx-cursor: hand;");
            }
        }

        if (saveSettingsBtn != null) {
            saveSettingsBtn.setDisable(!isCurrentUserHost);
            drawTimeCombo.setDisable(!isCurrentUserHost);
            roundsCombo.setDisable(!isCurrentUserHost);
        }
    }
}
