package com.nhom8.client.ui;

import com.fasterxml.jackson.databind.JsonNode;
import com.nhom8.client.MainApplication;
import com.nhom8.common.message.Envelope;
import com.nhom8.common.message.MessageType;
import javafx.application.Platform;
import javafx.animation.PauseTransition;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.*;
import javafx.util.Duration;

public class RoomScreen {
    private final java.util.Map<Label, Long> reconnectLabels = new java.util.HashMap<>();
    private javafx.animation.Timeline reconnectClock;
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
    private Label roomAccessLabel;
    private boolean hasRoomPassword;
    private Button saveSettingsBtn;

    private ObservableList<String> chatMessages;
    private ListView<String> chatListView;
    private TextField chatInput;

    private ObservableList<LobbyScreen.FriendEntry> friendList;
    private ListView<LobbyScreen.FriendEntry> friendListView;
    private Label friendsCountLabel;
    private final ObservableList<LobbyScreen.PendingRequestEntry> pendingFriends = FXCollections.observableArrayList();
    private Tab pendingFriendsTab;
    private Tab chatTab;
    private TabPane socialTabs;
    private int unreadRoomMessages;

    private Scene scene;

    public RoomScreen(MainApplication app, String roomId, String roomName) {
        this.app = app;
        this.roomId = roomId;
        this.roomName = (roomName != null && !roomName.isEmpty()) ? roomName : "Phòng " + roomId;
        this.chatMessages = FXCollections.observableArrayList();
        this.friendList = FXCollections.observableArrayList();
    }

    public Scene createScene() {
        if (scene != null) return scene;

        BorderPane root = new BorderPane();
        root.setStyle("-fx-background-color: #F5B800;");

        // --- TOP NAVIGATION BAR ---
        HBox topBar = new HBox(15);
        topBar.setAlignment(Pos.CENTER_LEFT);
        topBar.setPadding(new Insets(12, 20, 12, 20));
        topBar.setStyle("-fx-background-color: rgba(0, 0, 0, 0.12);");

        Button leaveBtn = new Button("⬅ Rời phòng");
        leaveBtn.getStyleClass().add("pencil-button-sm");
        leaveBtn.setOnAction(e -> leaveRoom());

        titleLabel = new Label("Phòng: " + roomName);
        titleLabel.setStyle("-fx-font-family: 'Amatic SC'; -fx-font-size: 34px; -fx-font-weight: bold; -fx-text-fill: -fx-ink;");

        // Bấm mã phòng hoặc bấm nút sao chép đều sao chép mã phòng
        HBox copyBox = new HBox(6);
        copyBox.setAlignment(Pos.CENTER_LEFT);

        codeBadge = new Label("🔑 Mã: #" + roomId);
        codeBadge.getStyleClass().add("copy-badge");
        codeBadge.setTooltip(new Tooltip("Bấm để sao chép mã phòng #" + roomId));
        codeBadge.setOnMouseClicked(e -> copyRoomCode());

        Button copyBtn = new Button("📋 Chép");
        copyBtn.getStyleClass().add("pencil-button-sm");
        copyBtn.setStyle("-fx-padding: 3 8; -fx-font-size: 14px;");
        copyBtn.setOnAction(e -> copyRoomCode());

        copyBox.getChildren().addAll(codeBadge, copyBtn);

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        playerCountLabel = new Label("1/8 Người chơi");
        playerCountLabel.setStyle("-fx-font-family: 'Patrick Hand'; -fx-font-size: 18px; -fx-font-weight: bold; -fx-text-fill: -fx-ink;");

        topBar.getChildren().addAll(leaveBtn, titleLabel, copyBox, spacer, playerCountLabel);
        root.setTop(topBar);

        // Room controls on the left, three social tabs on the right.
        HBox contentBox = new HBox(15);
        contentBox.setPadding(new Insets(15));
        contentBox.setFillHeight(true);
        contentBox.setAlignment(Pos.CENTER);

        // COLUMN 1: PLAYERS & ROOM SETTINGS (Co giãn linh hoạt)
        VBox playersCard = createPlayersAndSettingsCard();
        HBox.setHgrow(playersCard, Priority.ALWAYS);
        playersCard.setMinWidth(340);
        playersCard.setMaxWidth(Double.MAX_VALUE);

        VBox pendingCard = createPendingFriendsCard();
        VBox friendsCard = createFriendsInviteCard();
        VBox chatCard = createInRoomChatCard();
        pendingFriendsTab = new Tab("🔔 Lời mời kết bạn (0)", pendingCard);
        Tab friendsTab = new Tab("👥 Danh sách bạn", friendsCard);
        chatTab = new Tab("💬 Cuộc trò chuyện", chatCard);
        socialTabs = new TabPane(pendingFriendsTab, friendsTab, chatTab);
        socialTabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        socialTabs.getStyleClass().add("room-social-tabs");
        socialTabs.getSelectionModel().select(chatTab);
        socialTabs.getSelectionModel().selectedItemProperty().addListener((obs, oldTab, newTab) -> {
            if (newTab == chatTab) {
                unreadRoomMessages = 0;
                chatTab.setText("💬 Cuộc trò chuyện");
            }
        });
        HBox.setHgrow(socialTabs, Priority.ALWAYS);
        socialTabs.setMinWidth(560);
        socialTabs.setMaxWidth(Double.MAX_VALUE);

        contentBox.getChildren().addAll(playersCard, socialTabs);
        root.setCenter(contentBox);

        scene = new Scene(root, 1180, 680);
        MainApplication.applyTheme(scene);

        // Yêu cầu lấy danh sách bạn bè để hiển thị trong phòng
        requestFriendsList();
        requestPendingFriendsList();

        return scene;
    }

    private VBox createPlayersAndSettingsCard() {
        VBox card = new VBox(12);
        card.setPadding(new Insets(16));
        card.getStyleClass().add("speech-box-white");

        Label playersHeader = new Label("👥 Danh sách người chơi trong phòng");
        playersHeader.setStyle("-fx-font-family: 'Patrick Hand'; -fx-font-size: 20px; -fx-font-weight: bold; -fx-text-fill: -fx-ink;");

        playersListVBox = new VBox(8);
        ScrollPane playerScroll = new ScrollPane(playersListVBox);
        playerScroll.setFitToWidth(true);
        VBox.setVgrow(playerScroll, Priority.ALWAYS);
        playerScroll.setMaxHeight(Double.MAX_VALUE);
        playerScroll.setStyle("-fx-background-color: transparent; -fx-background: transparent;");

        // Ready controls
        readyBtn = new Button("Sẵn sàng (✔)");
        readyBtn.getStyleClass().add("pencil-button");
        readyBtn.setStyle("-fx-font-size: 18px; -fx-padding: 8 20;");
        readyBtn.setOnAction(e -> toggleReady());

        Label readyHint = new Label("Phòng 3–8 người; tất cả phải sẵn sàng để bắt đầu");
        readyHint.setStyle("-fx-font-family: 'Patrick Hand'; -fx-font-size: 14px; -fx-text-fill: #555555;");

        HBox readyBox = new HBox(12, readyBtn, readyHint);
        readyBox.setAlignment(Pos.CENTER_LEFT);

        Separator sep = new Separator();

        // Settings Section
        Label settingsHeader = new Label("⚙ Cài đặt phòng (Dành cho chủ phòng)");
        settingsHeader.setStyle("-fx-font-family: 'Patrick Hand'; -fx-font-size: 18px; -fx-font-weight: bold; -fx-text-fill: -fx-ink;");

        HBox combosBox = new HBox(12);
        combosBox.setAlignment(Pos.CENTER_LEFT);

        Label drawTimeLbl = new Label("Thời gian vẽ:");
        drawTimeLbl.setStyle("-fx-font-family: 'Patrick Hand'; -fx-font-weight: bold; -fx-text-fill: -fx-ink; -fx-font-size: 15px;");
        drawTimeCombo = new ComboBox<>();
        drawTimeCombo.getItems().addAll(30, 45, 60, 90, 120);
        drawTimeCombo.setValue(60);
        drawTimeCombo.setDisable(true);

        Label roundsLbl = new Label("Số vòng:");
        roundsLbl.setStyle("-fx-font-family: 'Patrick Hand'; -fx-font-weight: bold; -fx-text-fill: -fx-ink; -fx-font-size: 15px;");
        roundsCombo = new ComboBox<>();
        roundsCombo.getItems().addAll(1, 4, 8, 12);
        roundsCombo.setValue(4);
        roundsCombo.setDisable(true);

        combosBox.getChildren().addAll(drawTimeLbl, drawTimeCombo, roundsLbl, roundsCombo);

        roomAccessLabel = new Label("🔓 Phòng công khai");
        roomAccessLabel.setStyle("-fx-font-family: 'Patrick Hand'; -fx-font-size: 15px; -fx-text-fill: -fx-ink; -fx-font-weight: bold;");

        saveSettingsBtn = new Button("💾 Lưu cài đặt phòng");
        saveSettingsBtn.getStyleClass().add("pencil-button-sm");
        saveSettingsBtn.setDisable(true);
        saveSettingsBtn.setOnAction(e -> updateRoomSettings());

        HBox settingsFooter = new HBox(10, roomAccessLabel, new Region(), saveSettingsBtn);
        HBox.setHgrow(settingsFooter.getChildren().get(1), Priority.ALWAYS);
        settingsFooter.setAlignment(Pos.CENTER_LEFT);

        card.getChildren().addAll(playersHeader, playerScroll, readyBox, sep, settingsHeader, combosBox, settingsFooter);
        return card;
    }

    private VBox createPendingFriendsCard() {
        VBox card = new VBox(12);
        card.setPadding(new Insets(16));
        card.getStyleClass().add("speech-box-white");

        Label title = new Label("🔔 Lời mời kết bạn");
        title.setStyle("-fx-font-family: 'Patrick Hand'; -fx-font-size: 20px; -fx-font-weight: bold; -fx-text-fill: -fx-ink;");
        Label hint = new Label("Chấp nhận hoặc từ chối ngay trong phòng chờ.");
        hint.setStyle("-fx-font-family: 'Patrick Hand'; -fx-font-size: 13px; -fx-text-fill: #666666;");

        ListView<LobbyScreen.PendingRequestEntry> requests = new ListView<>(pendingFriends);
        requests.setPlaceholder(new Label("Bạn chưa có lời mời kết bạn nào."));
        VBox.setVgrow(requests, Priority.ALWAYS);
        requests.setCellFactory(list -> new ListCell<>() {
            @Override protected void updateItem(LobbyScreen.PendingRequestEntry item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) { setGraphic(null); setText(null); return; }

                Label name = new Label(item.getEffectiveName());
                name.setStyle("-fx-font-family: 'Patrick Hand'; -fx-font-size: 17px; -fx-font-weight: bold;");
                Label details = new Label("@" + item.username + " · " + item.rankingScore + " điểm");
                details.setStyle("-fx-font-family: 'Patrick Hand'; -fx-font-size: 12px; -fx-text-fill: #64748B;");
                VBox person = new VBox(2, name, details);
                HBox.setHgrow(person, Priority.ALWAYS);

                Button accept = new Button("✓ Đồng ý");
                accept.setStyle("-fx-background-color: #10B981; -fx-text-fill: white; -fx-font-weight: bold; -fx-cursor: hand;");
                Button decline = new Button("Từ chối");
                decline.getStyleClass().add("pencil-button-sm");
                accept.setOnAction(e -> {
                    accept.setDisable(true); decline.setDisable(true);
                    respondFriendRequest(item.username, true);
                });
                decline.setOnAction(e -> {
                    accept.setDisable(true); decline.setDisable(true);
                    respondFriendRequest(item.username, false);
                });
                HBox row = new HBox(10, AvatarView.create(item.avatar, 34), person, accept, decline);
                row.setAlignment(Pos.CENTER_LEFT);
                row.setPadding(new Insets(8));
                setGraphic(row);
                setText(null);
            }
        });

        card.getChildren().addAll(title, hint, requests);
        return card;
    }

    private VBox createFriendsInviteCard() {
        VBox card = new VBox(12);
        card.setPadding(new Insets(16));
        card.getStyleClass().add("speech-box-white");

        HBox headerBox = new HBox(8);
        headerBox.setAlignment(Pos.CENTER_LEFT);

        friendsCountLabel = new Label("👥 Bạn bè (Mời chơi)");
        friendsCountLabel.setStyle("-fx-font-family: 'Patrick Hand'; -fx-font-size: 20px; -fx-font-weight: bold; -fx-text-fill: -fx-ink;");

        Region sp = new Region();
        HBox.setHgrow(sp, Priority.ALWAYS);

        headerBox.getChildren().addAll(friendsCountLabel, sp);

        Label subLbl = new Label("Mời bạn bè đang trực tuyến tham gia ngay vào phòng này");
        subLbl.setStyle("-fx-font-family: 'Patrick Hand'; -fx-font-size: 13px; -fx-text-fill: #666666;");

        friendListView = new ListView<>(friendList);
        VBox.setVgrow(friendListView, Priority.ALWAYS);
        friendListView.setMaxHeight(Double.MAX_VALUE);
        friendListView.setPlaceholder(new Label("Không có bạn bè hoặc chưa kết bạn."));
        friendListView.setCellFactory(lv -> new ListCell<>() {
            @Override
            protected void updateItem(LobbyScreen.FriendEntry item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setGraphic(null);
                    setText(null);
                    setStyle("-fx-background-color: transparent;");
                } else {
                    setStyle("-fx-background-color: transparent; -fx-padding: 3 2 3 2;");

                    HBox card = new HBox(8);
                    card.setAlignment(Pos.CENTER_LEFT);
                    card.setPadding(new Insets(6, 10, 6, 10));

                    if (item.isOnline) {
                        card.setStyle(
                            "-fx-background-color: #FFFFFF;" +
                            "-fx-background-radius: 10px;" +
                            "-fx-border-color: #10B981;" +
                            "-fx-border-width: 2px;" +
                            "-fx-border-radius: 10px;" +
                            "-fx-effect: dropshadow(three-pass-box, rgba(16, 185, 129, 0.2), 5, 0, 0, 1);"
                        );
                    } else {
                        card.setStyle(
                            "-fx-background-color: #F8FAFC;" +
                            "-fx-background-radius: 10px;" +
                            "-fx-border-color: #E2E8F0;" +
                            "-fx-border-width: 1.5px;" +
                            "-fx-border-radius: 10px;" +
                            "-fx-opacity: 0.68;"
                        );
                    }

                    // Avatar badge
                    StackPane avatarPane = new StackPane();
                    avatarPane.setPrefSize(34, 34);
                    avatarPane.setMinSize(34, 34);
                    avatarPane.setMaxSize(34, 34);
                    if (item.isOnline) {
                        avatarPane.setStyle("-fx-background-color: #ECFDF5; -fx-background-radius: 17px; -fx-border-color: #10B981; -fx-border-width: 2px; -fx-border-radius: 17px;");
                    } else {
                        avatarPane.setStyle("-fx-background-color: #F1F5F9; -fx-background-radius: 17px; -fx-border-color: #CBD5E1; -fx-border-width: 1.5px; -fx-border-radius: 17px;");
                    }
                    avatarPane.getChildren().add(AvatarView.create(item.avatar, 30));

                    VBox info = new VBox(2);
                    Label name = new Label(item.getEffectiveName());
                    if (item.isOnline) {
                        name.setStyle("-fx-font-family: 'Patrick Hand'; -fx-font-size: 16px; -fx-font-weight: bold; -fx-text-fill: #0F172A;");
                    } else {
                        name.setStyle("-fx-font-family: 'Patrick Hand'; -fx-font-size: 14px; -fx-font-weight: bold; -fx-text-fill: #64748B;");
                    }

                    Label details = new Label("@" + item.username + " • " + item.rankingScore + "đ");
                    details.setStyle("-fx-font-family: 'Patrick Hand'; -fx-font-size: 11px; -fx-text-fill: #64748B;");
                    info.getChildren().addAll(name, details);

                    Region spacer = new Region();
                    HBox.setHgrow(spacer, Priority.ALWAYS);

                    if (item.isOnline) {
                        Label onlineBadge = new Label("Online");
                        onlineBadge.setStyle(
                            "-fx-font-family: 'Patrick Hand'; -fx-font-size: 11px; -fx-font-weight: bold; " +
                            "-fx-text-fill: #065F46; -fx-background-color: #D1FAE5; -fx-background-radius: 12px; " +
                            "-fx-border-color: #34D399; -fx-border-radius: 12px; -fx-padding: 2 6;"
                        );

                        Button inviteBtn = new Button("✉ Mời");
                        inviteBtn.setStyle(
                            "-fx-font-family: 'Patrick Hand'; -fx-font-size: 13px; -fx-font-weight: bold; " +
                            "-fx-background-color: #10B981; -fx-text-fill: white; -fx-background-radius: 6px; " +
                            "-fx-padding: 3 10; -fx-cursor: hand;"
                        );
                        inviteBtn.setOnAction(e -> {
                            sendRoomInvite(item.username);
                            inviteBtn.setText("✔ Đã mời");
                            inviteBtn.setStyle(
                                "-fx-font-family: 'Patrick Hand'; -fx-font-size: 12px; " +
                                "-fx-background-color: #E2E8F0; -fx-text-fill: #64748B; -fx-background-radius: 6px; " +
                                "-fx-padding: 3 8;"
                            );
                            inviteBtn.setDisable(true);
                            PauseTransition cooldown = new PauseTransition(Duration.seconds(4));
                            cooldown.setOnFinished(done -> {
                                inviteBtn.setText("✉ Mời lại");
                                inviteBtn.setDisable(false);
                            });
                            cooldown.play();
                        });
                        card.getChildren().addAll(avatarPane, info, spacer, onlineBadge, inviteBtn);
                    } else {
                        Label offlineLbl = new Label("⚫ Ngoại tuyến");
                        offlineLbl.setStyle(
                            "-fx-font-family: 'Patrick Hand'; -fx-font-size: 11px; -fx-text-fill: #94A3B8; " +
                            "-fx-background-color: #F1F5F9; -fx-background-radius: 12px; -fx-border-color: #CBD5E1; " +
                            "-fx-border-radius: 12px; -fx-padding: 2 6;"
                        );
                        card.getChildren().addAll(avatarPane, info, spacer, offlineLbl);
                    }

                    setGraphic(card);
                    setText(null);
                }
            }
        });

        card.getChildren().addAll(headerBox, subLbl, friendListView);
        return card;
    }

    private VBox createInRoomChatCard() {
        VBox card = new VBox(12);
        card.setPadding(new Insets(16));
        card.getStyleClass().add("speech-box-white");

        VBox titleBox = new VBox(3);
        Label chatHeader = new Label("💬 Chat nội bộ phòng #" + roomId);
        chatHeader.setStyle("-fx-font-family: 'Patrick Hand'; -fx-font-size: 20px; -fx-font-weight: bold; -fx-text-fill: -fx-ink;");

        Label chatSub = new Label("🔒 Chỉ người trong phòng mới thấy nội dung");
        chatSub.setStyle("-fx-font-family: 'Patrick Hand'; -fx-font-size: 13px; -fx-text-fill: #666666;");
        titleBox.getChildren().addAll(chatHeader, chatSub);

        chatListView = new ListView<>(chatMessages);
        chatListView.setCellFactory(list -> new ChatMessageCell());
        VBox.setVgrow(chatListView, Priority.ALWAYS);
        chatListView.setMaxHeight(Double.MAX_VALUE);
        chatListView.setPlaceholder(new Label("Chưa có tin nhắn trong phòng."));

        HBox inputBox = new HBox(8);
        chatInput = new TextField();
        chatInput.setPromptText("Nhập tin nhắn phòng... (Enter để gửi)");
        chatInput.setStyle("-fx-font-family: 'Patrick Hand'; -fx-font-size: 15px;");
        HBox.setHgrow(chatInput, Priority.ALWAYS);
        chatInput.setOnAction(e -> sendChatMessage());

        Button sendBtn = new Button("Gửi ➔");
        sendBtn.getStyleClass().add("pencil-button-sm");
        sendBtn.setOnAction(e -> sendChatMessage());

        inputBox.getChildren().addAll(chatInput, sendBtn);

        card.getChildren().addAll(titleBox, chatListView, inputBox);
        return card;
    }

    public Scene getScene() {
        return scene;
    }

    private void copyRoomCode() {
        try {
            Clipboard clipboard = Clipboard.getSystemClipboard();
            ClipboardContent content = new ClipboardContent();
            content.putString(roomId);
            clipboard.setContent(content);
            DoodleDialog.showToast(app.getPrimaryStage(), "📋 Đã chép mã phòng #" + roomId + " vào bộ nhớ tạm!");
        } catch (Exception e) {
            DoodleDialog.info(app.getPrimaryStage(), "Mã phòng", "Mã phòng của bạn là: " + roomId);
        }
    }

    private void requestFriendsList() {
        if (app.getWsClient() != null && app.getWsClient().isConnected()) {
            app.getWsClient().send(new Envelope(MessageType.FRIEND_LIST));
        }
    }

    private void requestPendingFriendsList() {
        if (app.getWsClient() != null && app.getWsClient().isConnected()) {
            app.getWsClient().send(new Envelope(MessageType.FRIEND_PENDING_LIST));
        }
    }

    private void respondFriendRequest(String senderUsername, boolean accept) {
        if (app.getWsClient() == null || !app.getWsClient().isConnected()) return;
        Envelope response = new Envelope(MessageType.FRIEND_RESPONSE);
        response.putString("senderUsername", senderUsername);
        response.putBoolean("accept", accept);
        app.getWsClient().send(response);
    }

    private void sendRoomInvite(String targetUsername) {
        if (targetUsername == null || targetUsername.trim().isEmpty()) return;
        Envelope env = new Envelope(MessageType.ROOM_INVITE_SEND);
        env.put("roomId", roomId);
        env.put("targetUsername", targetUsername);
        app.getWsClient().send(env);
        DoodleDialog.showToast(app.getPrimaryStage(), "✉ Đã gửi lời mời tham gia tới @" + targetUsername);
    }

    private void toggleReady() {
        boolean nextReady = !isCurrentUserReady;
        Envelope env = new Envelope(MessageType.READY);
        env.putBoolean("isReady", nextReady);
        app.getWsClient().send(env);
        if (nextReady) {
            int currentCount = playersListVBox != null ? playersListVBox.getChildren().size() : 1;
            if (currentCount < 3) {
                DoodleDialog.showToast(app.getPrimaryStage(), "✔ Đã sẵn sàng! Cần tối thiểu 3 người chơi để bắt đầu (hiện có " + currentCount + "/3).");
            } else {
                DoodleDialog.showToast(app.getPrimaryStage(), "✔ Đã sẵn sàng! Đang chờ tất cả thành viên sẵn sàng để bắt đầu trận.");
            }
        } else {
            DoodleDialog.showToast(app.getPrimaryStage(), "✖ Đã hủy trạng thái sẵn sàng.");
        }
    }

    private void leaveRoom() {
        app.confirmLeave(false);
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
        DoodleDialog.showToast(app.getPrimaryStage(), "⚙ Đã lưu cài đặt phòng thành công!");
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
        DoodleDialog.confirm(app.getPrimaryStage(), "Kick người chơi",
                "Bạn có chắc muốn kick @" + targetUsername + " khỏi phòng?",
                "KICK NGAY", "HỦY BỎ", confirmed -> {
                    if (confirmed) {
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
                case ROOM_RESPONSE:
                    onRoomUpdate(env);
                    break;
                case ROOM_UPDATE_SETTINGS:
                    int drawTime = env.getInt("drawTime", 60);
                    int maxRounds = env.getInt("maxRounds", 4);
                    if (drawTimeCombo != null) drawTimeCombo.setValue(drawTime);
                    if (roundsCombo != null) roundsCombo.setValue(maxRounds);
                    updateRoomAccess(env.getBoolean("hasPassword", hasRoomPassword));
                    break;
                case FRIEND_LIST:
                    onFriendListUpdate(env);
                    break;
                case FRIEND_PENDING_LIST:
                    onPendingFriendsUpdate(env);
                    break;
                case FRIEND_REQUEST_NOTIFY:
                    requestPendingFriendsList();
                    break;
                case FRIEND_UPDATE:
                case FRIEND_REMOVE:
                case FRIEND_RESPONSE:
                    requestFriendsList();
                    requestPendingFriendsList();
                    if (!env.isSuccess() && env.getContent() != null) {
                        DoodleDialog.showToast(app.getPrimaryStage(), env.getContent());
                    }
                    break;
                case ROOM_CHAT_RECEIVE:
                case ROOM_CHAT:
                    String sender = env.getString("senderDisplayName");
                    if (sender == null) sender = env.getString("senderUsername");
                    if (sender == null) sender = env.getSender() != null ? env.getSender() : "Ẩn danh";
                    String text = env.getString("content");
                    if (text == null) text = env.getContent();
                    String senderUsername = env.getString("senderUsername");
                    boolean mine = senderUsername != null && senderUsername.equalsIgnoreCase(app.getCurrentUsername());
                    chatMessages.add((mine ? "Bạn" : sender) + ": " + text);
                    if (socialTabs != null && socialTabs.getSelectionModel().getSelectedItem() != chatTab) {
                        unreadRoomMessages++;
                        chatTab.setText("💬 Cuộc trò chuyện (" + unreadRoomMessages + ")");
                    }
                    if (chatListView != null && !chatMessages.isEmpty()) {
                        chatListView.scrollTo(chatMessages.size() - 1);
                    }
                    break;
                case GAME_START:
                case MATCH_START:
                    app.showGameScreen(roomId, env);
                    break;
                case ROOM_DISSOLVED:
                    DoodleDialog.info(app.getPrimaryStage(), "Phòng giải tán",
                            env.getContent() != null ? env.getContent() : "Phòng đã bị giải tán.");
                    app.showLobbyScreen();
                    break;
                default:
                    break;
            }
        });
    }

    private void onPendingFriendsUpdate(Envelope env) {
        if (!env.isSuccess()) {
            if (env.getContent() != null) DoodleDialog.showToast(app.getPrimaryStage(), env.getContent());
            return;
        }
        pendingFriends.clear();
        JsonNode requests = env.getData() == null ? null : env.getData().get("pendingRequests");
        if (requests != null) requests.forEach(node -> {
            LobbyScreen.PendingRequestEntry entry = new LobbyScreen.PendingRequestEntry();
            entry.username = node.path("username").asText("");
            entry.displayName = node.path("displayName").asText(entry.username);
            entry.rankingScore = node.path("rankingScore").asInt(0);
            entry.avatar = node.path("avatar").asText("1");
            if (!entry.username.isBlank()) pendingFriends.add(entry);
        });
        if (pendingFriendsTab != null) {
            pendingFriendsTab.setText("🔔 Lời mời kết bạn (" + pendingFriends.size() + ")");
        }
    }

    private void onFriendListUpdate(Envelope env) {
        friendList.clear();
        if (env.getData() != null && env.getData().has("friends")) {
            JsonNode friendsNode = env.getData().get("friends");
            friendsNode.forEach(f -> {
                String uName = f.has("username") ? f.get("username").asText() : "";
                if (uName.isEmpty() || (app.getCurrentUsername() != null && uName.equalsIgnoreCase(app.getCurrentUsername()))) {
                    return;
                }
                LobbyScreen.FriendEntry fe = new LobbyScreen.FriendEntry();
                fe.username = uName;
                fe.displayName = f.has("displayName") ? f.get("displayName").asText() : fe.username;
                fe.avatar = f.has("avatar") ? f.get("avatar").asText() : "1";
                fe.rankingScore = f.has("rankingScore") ? f.get("rankingScore").asInt() : 0;
                fe.isOnline = f.has("isOnline") && f.get("isOnline").asBoolean();
                friendList.add(fe);
            });
        }
        // ƯU TIÊN SẮP XẾP BẠN BÈ ĐANG ONLINE LÊN ĐẦU
        friendList.sort((a, b) -> {
            if (a.isOnline != b.isOnline) {
                return Boolean.compare(b.isOnline, a.isOnline);
            }
            return a.getEffectiveName().compareToIgnoreCase(b.getEffectiveName());
        });
        long onlineCount = friendList.stream().filter(f -> f.isOnline).count();
        if (friendsCountLabel != null) {
            friendsCountLabel.setText("👥 Bạn bè (" + onlineCount + " Online / " + friendList.size() + ")");
        }
    }

    private void onRoomUpdate(Envelope env) {
        if (env.getData() == null) return;

        if (env.getData().has("roomName")) {
            roomName = env.getData().get("roomName").asText();
            if (titleLabel != null) titleLabel.setText("Phòng: " + roomName);
        }

        if (env.getData().has("drawTime")) {
            int dt = env.getInt("drawTime", 60);
            if (drawTimeCombo != null) drawTimeCombo.setValue(dt);
        }
        if (env.getData().has("maxRounds")) {
            int mr = env.getInt("maxRounds", 4);
            if (roundsCombo != null) roundsCombo.setValue(mr);
        }
        if (env.getData().has("hasPassword")) updateRoomAccess(env.getBoolean("hasPassword", false));

        int count = env.getInt("playerCount", 1);
        if (playerCountLabel != null) {
            playerCountLabel.setText(count + "/" + env.getInt("maxPlayers", 8) + " Người chơi");
        }

        if (env.getData().has("players")) {
            reconnectLabels.clear();
            playersListVBox.getChildren().clear();
            JsonNode playersNode = env.getData().get("players");

            String myUser = app.getCurrentUsername();

            boolean currentIsHost = false;
            boolean currentIsReady = false;
            if (myUser != null) {
                for (JsonNode pNode : playersNode) {
                    String uName = pNode.has("username") ? pNode.get("username").asText() : "";
                    if (uName.equalsIgnoreCase(myUser)) {
                        currentIsHost = pNode.has("isHost") && pNode.get("isHost").asBoolean();
                        currentIsReady = pNode.has("isReady") && pNode.get("isReady").asBoolean();
                        break;
                    }
                }
            }
            this.isCurrentUserHost = currentIsHost;
            this.isCurrentUserReady = currentIsReady;
            updateMyControls();

            playersNode.forEach(pNode -> {
                String uName = pNode.has("username") ? pNode.get("username").asText() : "";
                String dName = pNode.has("displayName") ? pNode.get("displayName").asText() : uName;
                boolean ready = pNode.has("isReady") && pNode.get("isReady").asBoolean();
                boolean host = pNode.has("isHost") && pNode.get("isHost").asBoolean();
                boolean connected = pNode.path("connected").asBoolean(true);

                HBox card = new HBox(12);
                card.setAlignment(Pos.CENTER_LEFT);
                card.setPadding(new Insets(10, 15, 10, 15));
                card.setStyle("-fx-background-color: #F8F9FA; -fx-background-radius: 8; -fx-border-color: #E9ECEF; -fx-border-radius: 8;");

                Label nameLbl = new Label((host ? "👑 " : "👤 ") + dName + " (@" + uName + ")");
                nameLbl.setStyle("-fx-font-weight: bold; -fx-font-size: 14px; -fx-text-fill: #333333;");
                HBox.setHgrow(nameLbl, Priority.ALWAYS);

                Label statusBadge = new Label();
                if (!connected) {
                    long deadline = pNode.path("reconnectDeadline").asLong(0);
                    long localDeadline = System.currentTimeMillis() + deadline - env.getData().path("serverTime").asLong(System.currentTimeMillis());
                    reconnectLabels.put(statusBadge, localDeadline);
                    statusBadge.setText("⚠ Đang kết nối lại... " + Math.max(0, (localDeadline - System.currentTimeMillis() + 999) / 1000) + "s");
                    card.setOpacity(.55);
                    statusBadge.setStyle("-fx-background-color: #FFF3CD; -fx-text-fill: #856404; -fx-font-weight: bold; -fx-padding: 4 10; -fx-background-radius: 12;");
                } else if (host) {
                    statusBadge.setText(ready ? "👑 SẴN SÀNG" : "👑 CHỦ PHÒNG");
                    statusBadge.setStyle("-fx-background-color: #FFF3CD; -fx-text-fill: #856404; -fx-font-weight: bold; -fx-padding: 4 10; -fx-background-radius: 12;");
                } else if (ready) {
                    statusBadge.setText("✔ SẴN SÀNG");
                    statusBadge.setStyle("-fx-background-color: #D4EDDA; -fx-text-fill: #155724; -fx-font-weight: bold; -fx-padding: 4 10; -fx-background-radius: 12;");
                } else {
                    statusBadge.setText("⏳ CHỜ ĐỢI");
                    statusBadge.setStyle("-fx-background-color: #E2E3E5; -fx-text-fill: #383D41; -fx-font-weight: bold; -fx-padding: 4 10; -fx-background-radius: 12;");
                }

                card.getChildren().addAll(AvatarView.create(pNode.path("avatar").asText("1"), 36), nameLbl, statusBadge);

                // Nút kick người chơi (chỉ chủ phòng thấy và không thể tự kick mình)
                if (isCurrentUserHost && myUser != null && !uName.equalsIgnoreCase(myUser)) {
                    Button kickBtn = new Button("Kick");
                    kickBtn.setStyle("-fx-background-color: #ff4d4f; -fx-text-fill: white; -fx-font-size: 11px; -fx-font-weight: bold; -fx-padding: 3 8; -fx-background-radius: 4; -fx-cursor: hand;");
                    kickBtn.setOnAction(e -> kickPlayer(uName));
                    card.getChildren().add(kickBtn);
                }

                playersListVBox.getChildren().add(card);
            });
            if (reconnectClock == null) {
                reconnectClock = new javafx.animation.Timeline(new javafx.animation.KeyFrame(javafx.util.Duration.seconds(1), event -> {
                    if (scene != app.getPrimaryStage().getScene() || reconnectLabels.isEmpty()) { reconnectClock.stop(); return; }
                    reconnectLabels.forEach((label, deadline) -> label.setText("⚠ Đang kết nối lại... "
                            + Math.max(0, (deadline - System.currentTimeMillis() + 999) / 1000) + "s"));
                }));
                reconnectClock.setCycleCount(javafx.animation.Timeline.INDEFINITE);
            }
            if (!reconnectLabels.isEmpty() && scene == app.getPrimaryStage().getScene()) reconnectClock.play();
        }
    }

    private void updateRoomAccess(boolean hasPassword) {
        hasRoomPassword = hasPassword;
        if (roomAccessLabel != null) {
            roomAccessLabel.setText(hasPassword ? "🔐 Có mật khẩu phòng" : "🔓 Phòng công khai");
            roomAccessLabel.setStyle("-fx-font-family: 'Patrick Hand'; -fx-font-size: 15px; -fx-font-weight: bold; -fx-text-fill: "
                    + (hasPassword ? "#237A3B" : "#555555") + ";");
        }
    }

    private void updateMyControls() {
        if (readyBtn != null) {
            if (isCurrentUserReady) {
                readyBtn.setText("Hủy sẵn sàng (✖)");
                readyBtn.setStyle("-fx-background-color: #ff4d4f; -fx-text-fill: white; -fx-font-weight: bold; -fx-font-size: 16px; -fx-padding: 8 20; -fx-background-radius: 8; -fx-cursor: hand;");
            } else {
                readyBtn.setText("Sẵn sàng (✔)");
                readyBtn.setStyle("-fx-background-color: #52c41a; -fx-text-fill: white; -fx-font-weight: bold; -fx-font-size: 16px; -fx-padding: 8 20; -fx-background-radius: 8; -fx-cursor: hand;");
            }
        }

        if (saveSettingsBtn != null) {
            saveSettingsBtn.setDisable(!isCurrentUserHost);
            drawTimeCombo.setDisable(!isCurrentUserHost);
            roundsCombo.setDisable(!isCurrentUserHost);
        }
    }
}
