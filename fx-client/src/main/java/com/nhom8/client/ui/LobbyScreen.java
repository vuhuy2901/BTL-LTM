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
    private final ObservableList<PlayerEntry> playerList;
    private final ObservableList<FriendEntry> friendList;
    private final ObservableList<PendingRequestEntry> pendingList;

    private ListView<RoomEntry> roomListView;
    private ListView<PlayerEntry> playerListView;
    private ListView<FriendEntry> friendListView;
    private ListView<PendingRequestEntry> pendingListView;

    private Label welcomeLabel;
    private Label roomCountLabel;
    private Label playerCountLabel;
    private Label friendCountLabel;

    private Button tabFriendsBtn;
    private Button tabOnlineBtn;
    private Button tabPendingBtn;
    private StackPane socialContentStack;
    private Label inboxUnreadBadge;
    private InboxDialog inboxDialog;

    private Scene scene;

    public static class RoomEntry {
        public String id;
        public String name;
        public String host;
        public String hostDisplayName;
        public int playerCount;
        public int maxPlayers;
        public String status;
        public boolean hasPassword;

        @Override
        public String toString() {
            String stateStr = "WAITING".equals(status) ? "🟢 Đang chờ" : "🔴 Đang chơi";
            String hostName = (hostDisplayName != null && !hostDisplayName.isEmpty()) ? hostDisplayName : host;
            return String.format("%s🎨 %s  [#%s]\n     👑 Chủ phòng: %s   |   👥 %d/%d người   |   %s",
                    hasPassword ? "🔐 " : "",
                    name, id, hostName, playerCount, maxPlayers, stateStr);
        }
    }

    public static class PlayerEntry {
        public String username;
        public String displayName;
        public String status;
        public int rankingScore;
        public String roomId;
        public String roomName;
        public boolean canJoin;

        public String getEffectiveName() {
            return (displayName != null && !displayName.isEmpty()) ? displayName : username;
        }
    }

    public static class FriendEntry {
        public String username;
        public String displayName;
        public String avatar;
        public int rankingScore;
        public boolean isOnline;

        public String getEffectiveName() {
            return (displayName != null && !displayName.isEmpty()) ? displayName : username;
        }
    }

    public static class PendingRequestEntry {
        public String username;
        public String displayName;
        public String avatar;
        public int rankingScore;

        public String getEffectiveName() {
            return (displayName != null && !displayName.isEmpty()) ? displayName : username;
        }
    }

    public LobbyScreen(MainApplication app) {
        this.app = app;
        this.roomList = FXCollections.observableArrayList();
        this.playerList = FXCollections.observableArrayList();
        this.friendList = FXCollections.observableArrayList();
        this.pendingList = FXCollections.observableArrayList();
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

        Label brandLabel = new Label("SCRIBBLE IT! — SẢNH CHỜ");
        brandLabel.setStyle("-fx-font-family: 'Amatic SC'; -fx-font-size: 38px; -fx-font-weight: bold; -fx-text-fill: -fx-ink;");

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        welcomeLabel = new Label("👤 " + app.getCurrentDisplayName());
        welcomeLabel.getStyleClass().add("name-tag");
        welcomeLabel.setStyle("-fx-cursor: hand;");
        welcomeLabel.setTooltip(new Tooltip("Bấm để xem và chỉnh sửa trang cá nhân"));
        welcomeLabel.setOnMouseClicked(e -> app.openProfileDialog());

        Button profileBtn = new Button("👤 Hồ sơ");
        profileBtn.getStyleClass().add("pencil-button-sm");
        profileBtn.setTooltip(new Tooltip("Xem & Chỉnh sửa hồ sơ cá nhân / Lịch sử đấu"));
        profileBtn.setOnAction(e -> app.openProfileDialog());

        Button refreshAllBtn = new Button("🔄 Làm mới");
        refreshAllBtn.getStyleClass().add("pencil-button-sm");
        refreshAllBtn.setOnAction(e -> refreshAllData());

        Button logoutBtn = new Button("Đăng xuất");
        logoutBtn.getStyleClass().add("pencil-button-sm");
        logoutBtn.setOnAction(e -> {
            if (app.getWsClient() != null) {
                app.getWsClient().close();
            }
            app.start(app.getPrimaryStage());
        });

        topBar.getChildren().addAll(brandLabel, spacer, welcomeLabel, profileBtn, refreshAllBtn);
        if (app.isAdmin()) {
            Button adminBtn = new Button("Quản trị");
            adminBtn.getStyleClass().add("pencil-button-sm");
            adminBtn.setOnAction(e -> app.showAdminScreen());
            topBar.getChildren().add(adminBtn);
        }
        topBar.getChildren().add(logoutBtn);
        root.setTop(topBar);

        // Center Split: Left = Rooms, Right = Social & Chat
        HBox mainContainer = new HBox(20);
        mainContainer.setPadding(new Insets(20));
        mainContainer.setAlignment(Pos.CENTER);
        mainContainer.setFillHeight(true);

        // --- LEFT: ROOMS CARD ---
        VBox roomsCard = new VBox(15);
        roomsCard.setMinWidth(520);
        roomsCard.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(roomsCard, Priority.ALWAYS);
        roomsCard.setPadding(new Insets(20));
        roomsCard.getStyleClass().add("speech-box-white");

        HBox roomsHeader = new HBox(15);
        roomsHeader.setAlignment(Pos.CENTER_LEFT);

        Label roomsTitle = new Label("Danh sách phòng đang chờ");
        roomsTitle.setStyle("-fx-font-family: 'Patrick Hand'; -fx-font-size: 22px; -fx-font-weight: bold; -fx-text-fill: -fx-ink;");

        roomCountLabel = new Label("(0 phòng)");
        roomCountLabel.setStyle("-fx-font-family: 'Patrick Hand'; -fx-font-size: 16px; -fx-text-fill: #555555;");

        Region roomSpacer = new Region();
        HBox.setHgrow(roomSpacer, Priority.ALWAYS);

        Button createRoomBtn = new Button("➕ Tạo phòng");
        createRoomBtn.getStyleClass().add("pencil-button-sm");
        createRoomBtn.setOnAction(e -> promptCreateRoom());

        Button joinByCodeBtn = new Button("🚪 Nhập mã");
        joinByCodeBtn.getStyleClass().add("pencil-button-sm");
        joinByCodeBtn.setOnAction(e -> promptJoinByCode());

        roomsHeader.getChildren().addAll(roomsTitle, roomCountLabel, roomSpacer, createRoomBtn, joinByCodeBtn);

        // Room List View
        roomListView = new ListView<>(roomList);
        VBox.setVgrow(roomListView, Priority.ALWAYS);
        roomListView.setMaxHeight(Double.MAX_VALUE);
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

        Button joinSelectedBtn = new Button("VÀO PHÒNG ĐÃ CHỌN ➔");
        joinSelectedBtn.getStyleClass().add("pencil-button");
        joinSelectedBtn.setPrefWidth(280);
        joinSelectedBtn.setOnAction(e -> {
            RoomEntry selected = roomListView.getSelectionModel().getSelectedItem();
            if (selected != null) {
                joinRoom(selected.id);
            } else {
                DoodleDialog.warning(app.getPrimaryStage(), "Chưa chọn phòng", "Vui lòng chọn một phòng trong danh sách!");
            }
        });

        roomsFooter.getChildren().add(joinSelectedBtn);
        roomsCard.getChildren().addAll(roomsHeader, roomListView, roomsFooter);

        // --- RIGHT: SOCIAL & FRIEND CHAT CARD ---
        VBox socialCard = new VBox(12);
        socialCard.setMinWidth(380);
        socialCard.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(socialCard, Priority.ALWAYS);
        socialCard.setPadding(new Insets(16));
        socialCard.getStyleClass().add("speech-box-white");

        // Tab header buttons
        HBox tabHeader = new HBox(8);
        tabHeader.setAlignment(Pos.CENTER);

        tabFriendsBtn = new Button("👥 Bạn bè (0)");
        tabOnlineBtn = new Button("🌐 Online (0)");
        tabPendingBtn = new Button("🔔 Lời mời (0)");

        tabFriendsBtn.setMaxWidth(Double.MAX_VALUE);
        tabOnlineBtn.setMaxWidth(Double.MAX_VALUE);
        tabPendingBtn.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(tabFriendsBtn, Priority.ALWAYS);
        HBox.setHgrow(tabOnlineBtn, Priority.ALWAYS);
        HBox.setHgrow(tabPendingBtn, Priority.ALWAYS);

        tabHeader.getChildren().addAll(tabFriendsBtn, tabOnlineBtn, tabPendingBtn);

        // StackPane containing the 3 tab views
        socialContentStack = new StackPane();
        VBox.setVgrow(socialContentStack, Priority.ALWAYS);

        // TAB 1: FRIENDS VIEW
        VBox friendsTabBox = createFriendsTabBox();

        // TAB 2: ONLINE USERS VIEW
        VBox onlineTabBox = createOnlineTabBox();

        // TAB 3: PENDING REQUESTS VIEW
        VBox pendingTabBox = createPendingTabBox();

        socialContentStack.getChildren().addAll(friendsTabBox, onlineTabBox, pendingTabBox);

        // Tab switching logic
        Runnable selectFriendsTab = () -> {
            styleActiveTab(tabFriendsBtn);
            styleInactiveTab(tabOnlineBtn);
            styleInactiveTab(tabPendingBtn);
            friendsTabBox.setVisible(true);
            onlineTabBox.setVisible(false);
            pendingTabBox.setVisible(false);
            requestFriends();
        };

        Runnable selectOnlineTab = () -> {
            styleInactiveTab(tabFriendsBtn);
            styleActiveTab(tabOnlineBtn);
            styleInactiveTab(tabPendingBtn);
            friendsTabBox.setVisible(false);
            onlineTabBox.setVisible(true);
            pendingTabBox.setVisible(false);
            requestPlayers();
        };

        Runnable selectPendingTab = () -> {
            styleInactiveTab(tabFriendsBtn);
            styleInactiveTab(tabOnlineBtn);
            styleActiveTab(tabPendingBtn);
            friendsTabBox.setVisible(false);
            onlineTabBox.setVisible(false);
            pendingTabBox.setVisible(true);
            requestPendingRequests();
        };

        tabFriendsBtn.setOnAction(e -> selectFriendsTab.run());
        tabOnlineBtn.setOnAction(e -> selectOnlineTab.run());
        tabPendingBtn.setOnAction(e -> selectPendingTab.run());

        // Default to Friends tab
        selectFriendsTab.run();

        socialCard.getChildren().addAll(tabHeader, socialContentStack);

        mainContainer.getChildren().addAll(roomsCard, socialCard);
        root.setCenter(mainContainer);

        StackPane lobbyRoot = new StackPane(root);
        Button inboxButton = new Button("💬");
        inboxButton.getStyleClass().add("floating-inbox-button");
        inboxButton.setTooltip(new Tooltip("Mở hộp thư và xem tin nhắn chưa đọc"));
        inboxButton.setOnAction(e -> openInbox());
        inboxUnreadBadge = new Label();
        inboxUnreadBadge.getStyleClass().add("inbox-unread-badge");
        inboxUnreadBadge.setVisible(false);
        inboxUnreadBadge.setManaged(false);
        StackPane inboxFab = new StackPane(inboxButton, inboxUnreadBadge);
        inboxFab.setMaxSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);
        inboxFab.setPickOnBounds(false);
        StackPane.setAlignment(inboxUnreadBadge, Pos.TOP_RIGHT);
        StackPane.setAlignment(inboxFab, Pos.BOTTOM_RIGHT);
        StackPane.setMargin(inboxFab, new Insets(0, 26, 22, 0));
        lobbyRoot.setPickOnBounds(false);
        lobbyRoot.getChildren().add(inboxFab);

        scene = new Scene(lobbyRoot, 1100, 640);
        MainApplication.applyTheme(scene);
        return scene;
    }

    private void styleActiveTab(Button btn) {
        btn.setStyle("-fx-background-color: -fx-ink; -fx-text-fill: -fx-primary; -fx-font-family: 'Patrick Hand'; -fx-font-size: 15px; -fx-font-weight: bold; -fx-border-color: -fx-ink; -fx-border-width: 2; -fx-cursor: hand; -fx-padding: 4 6;");
    }

    private void styleInactiveTab(Button btn) {
        btn.setStyle("-fx-background-color: white; -fx-text-fill: -fx-ink; -fx-font-family: 'Patrick Hand'; -fx-font-size: 15px; -fx-font-weight: bold; -fx-border-color: -fx-ink; -fx-border-width: 2; -fx-cursor: hand; -fx-padding: 4 6;");
    }

    // --- TAB 1: FRIENDS ---
    private VBox createFriendsTabBox() {
        VBox box = new VBox(10);
        box.setAlignment(Pos.TOP_CENTER);

        HBox friendActionBar = new HBox(8);
        friendActionBar.setAlignment(Pos.CENTER_LEFT);

        friendCountLabel = new Label("Danh sách bạn bè:");
        friendCountLabel.setStyle("-fx-font-family: 'Patrick Hand'; -fx-font-size: 16px; -fx-font-weight: bold; -fx-text-fill: -fx-ink;");
        Region sp = new Region();
        HBox.setHgrow(sp, Priority.ALWAYS);

        Button addFriendBtn = new Button("➕ Thêm bạn");
        addFriendBtn.getStyleClass().add("pencil-button-sm");
        addFriendBtn.setStyle("-fx-padding: 2 10; -fx-font-size: 15px;");
        addFriendBtn.setOnAction(e -> promptAddFriend());

        Button refreshFriendsBtn = new Button("🔄");
        refreshFriendsBtn.getStyleClass().add("pencil-button-sm");
        refreshFriendsBtn.setStyle("-fx-padding: 2 8; -fx-font-size: 15px;");
        refreshFriendsBtn.setOnAction(e -> requestFriends());

        friendActionBar.getChildren().addAll(friendCountLabel, sp, addFriendBtn, refreshFriendsBtn);

        friendListView = new ListView<>(friendList);
        VBox.setVgrow(friendListView, Priority.ALWAYS);
        friendListView.setMaxHeight(Double.MAX_VALUE);
        friendListView.setPlaceholder(new Label("Chưa có bạn bè nào. Bấm '➕ Thêm bạn' để kết nối!"));
        friendListView.setCellFactory(lv -> new ListCell<>() {
            @Override
            protected void updateItem(FriendEntry item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setGraphic(null);
                    setText(null);
                    setContextMenu(null);
                    setStyle("-fx-background-color: transparent;");
                } else {
                    setStyle("-fx-background-color: transparent; -fx-padding: 3 2 3 2;");

                    HBox card = new HBox(10);
                    card.setAlignment(Pos.CENTER_LEFT);
                    card.setPadding(new Insets(8, 12, 8, 12));

                    if (item.isOnline) {
                        card.setStyle(
                            "-fx-background-color: #FFFFFF;" +
                            "-fx-background-radius: 10px;" +
                            "-fx-border-color: #10B981;" +
                            "-fx-border-width: 2px;" +
                            "-fx-border-radius: 10px;" +
                            "-fx-effect: dropshadow(three-pass-box, rgba(16, 185, 129, 0.22), 6, 0, 0, 2);"
                        );
                    } else {
                        card.setStyle(
                            "-fx-background-color: #F8FAFC;" +
                            "-fx-background-radius: 10px;" +
                            "-fx-border-color: #E2E8F0;" +
                            "-fx-border-width: 1.5px;" +
                            "-fx-border-radius: 10px;" +
                            "-fx-opacity: 0.72;"
                        );
                    }

                    // Avatar badge
                    StackPane avatarPane = new StackPane();
                    avatarPane.setPrefSize(38, 38);
                    avatarPane.setMinSize(38, 38);
                    avatarPane.setMaxSize(38, 38);
                    if (item.isOnline) {
                        avatarPane.setStyle("-fx-background-color: #ECFDF5; -fx-background-radius: 19px; -fx-border-color: #10B981; -fx-border-width: 2px; -fx-border-radius: 19px;");
                    } else {
                        avatarPane.setStyle("-fx-background-color: #F1F5F9; -fx-background-radius: 19px; -fx-border-color: #CBD5E1; -fx-border-width: 1.5px; -fx-border-radius: 19px;");
                    }
                    Label avLabel = new Label(item.isOnline ? "🟢" : "👤");
                    avLabel.setStyle("-fx-font-size: 16px;");
                    avatarPane.getChildren().add(avLabel);

                    // Name and info
                    VBox infoBox = new VBox(2);
                    Label nameLbl = new Label(item.getEffectiveName());
                    if (item.isOnline) {
                        nameLbl.setStyle("-fx-font-family: 'Patrick Hand'; -fx-font-size: 17px; -fx-font-weight: bold; -fx-text-fill: #0F172A;");
                    } else {
                        nameLbl.setStyle("-fx-font-family: 'Patrick Hand'; -fx-font-size: 15px; -fx-font-weight: bold; -fx-text-fill: #64748B;");
                    }

                    Label subLbl = new Label("@" + item.username + " • 🏆 " + item.rankingScore + " điểm");
                    subLbl.setStyle("-fx-font-family: 'Patrick Hand'; -fx-font-size: 12px; -fx-text-fill: #64748B;");
                    infoBox.getChildren().addAll(nameLbl, subLbl);

                    Region spacer = new Region();
                    HBox.setHgrow(spacer, Priority.ALWAYS);

                    // Status pill badge
                    Label statusBadge = new Label();
                    if (item.isOnline) {
                        statusBadge.setText("🟢 TRỰC TUYẾN");
                        statusBadge.setStyle(
                            "-fx-font-family: 'Patrick Hand'; -fx-font-size: 12px; -fx-font-weight: bold; " +
                            "-fx-text-fill: #065F46; -fx-background-color: #D1FAE5; " +
                            "-fx-background-radius: 16px; -fx-border-color: #34D399; -fx-border-radius: 16px; " +
                            "-fx-border-width: 1.5px; -fx-padding: 3 10;"
                        );
                    } else {
                        statusBadge.setText("⚫ Ngoại tuyến");
                        statusBadge.setStyle(
                            "-fx-font-family: 'Patrick Hand'; -fx-font-size: 12px; " +
                            "-fx-text-fill: #94A3B8; -fx-background-color: #F1F5F9; " +
                            "-fx-background-radius: 16px; -fx-border-color: #CBD5E1; -fx-border-radius: 16px; " +
                            "-fx-border-width: 1px; -fx-padding: 3 8;"
                        );
                    }

                    Button chatBtn = new Button("💬 Nhắn tin");
                    chatBtn.getStyleClass().add("pencil-button-sm");
                    if (item.isOnline) {
                        chatBtn.setStyle("-fx-padding: 3 10; -fx-font-size: 13px; -fx-font-weight: bold; -fx-cursor: hand;");
                    } else {
                        chatBtn.setStyle("-fx-padding: 3 8; -fx-font-size: 12px; -fx-cursor: hand; -fx-opacity: 0.85;");
                    }
                    chatBtn.setOnAction(e -> app.openPrivateChat(item.username, item.getEffectiveName()));

                    card.getChildren().addAll(avatarPane, infoBox, spacer, statusBadge, chatBtn);
                    setGraphic(card);
                    setText(null);

                    // Context Menu
                    ContextMenu cm = new ContextMenu();
                    MenuItem chatItem = new MenuItem("💬 Nhắn tin riêng");
                    chatItem.setOnAction(e -> app.openPrivateChat(item.username, item.getEffectiveName()));

                    MenuItem removeItem = new MenuItem("❌ Hủy kết bạn");
                    removeItem.setOnAction(e -> confirmRemoveFriend(item.username));

                    cm.getItems().addAll(chatItem, removeItem);
                    setContextMenu(cm);
                }
            }
        });

        // Double click to chat
        friendListView.setOnMouseClicked(click -> {
            if (click.getClickCount() == 2) {
                FriendEntry selected = friendListView.getSelectionModel().getSelectedItem();
                if (selected != null) {
                    app.openPrivateChat(selected.username, selected.getEffectiveName());
                }
            }
        });

        box.getChildren().addAll(friendActionBar, friendListView);
        return box;
    }

    // --- TAB 2: ONLINE USERS ---
    private VBox createOnlineTabBox() {
        VBox box = new VBox(10);
        box.setAlignment(Pos.TOP_CENTER);

        HBox onlineActionBar = new HBox(8);
        onlineActionBar.setAlignment(Pos.CENTER_LEFT);

        playerCountLabel = new Label("Người chơi đang online:");
        playerCountLabel.setStyle("-fx-font-family: 'Patrick Hand'; -fx-font-size: 16px; -fx-font-weight: bold; -fx-text-fill: -fx-ink;");
        Region sp = new Region();
        HBox.setHgrow(sp, Priority.ALWAYS);

        Button refreshPlayersBtn = new Button("🔄");
        refreshPlayersBtn.getStyleClass().add("pencil-button-sm");
        refreshPlayersBtn.setStyle("-fx-padding: 2 8; -fx-font-size: 15px;");
        refreshPlayersBtn.setOnAction(e -> requestPlayers());

        onlineActionBar.getChildren().addAll(playerCountLabel, sp, refreshPlayersBtn);

        playerListView = new ListView<>(playerList);
        VBox.setVgrow(playerListView, Priority.ALWAYS);
        playerListView.setMaxHeight(Double.MAX_VALUE);
        playerListView.setPlaceholder(new Label("Không có người chơi nào khác đang online."));
        playerListView.setCellFactory(lv -> new ListCell<>() {
            @Override
            protected void updateItem(PlayerEntry item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setGraphic(null);
                    setText(null);
                    setContextMenu(null);
                } else {
                    HBox row = new HBox(8);
                    row.setAlignment(Pos.CENTER_LEFT);
                    row.setPadding(new Insets(4, 6, 4, 6));

                    String icon = "🟢";
                    if ("Đang trong phòng chờ".equals(item.status)) icon = "🟡";
                    else if ("Đang chơi".equals(item.status)) icon = "🔴";

                    Label statusDot = new Label(icon);
                    statusDot.setStyle("-fx-font-size: 14px;");

                    VBox infoBox = new VBox(2);
                    Label nameLbl = new Label(item.getEffectiveName() + (item.username.equals(app.getCurrentUsername()) ? " (Bạn)" : ""));
                    nameLbl.setStyle("-fx-font-family: 'Patrick Hand'; -fx-font-size: 16px; -fx-font-weight: bold; -fx-text-fill: -fx-ink;");

                    String roomHint = (item.roomId != null && !item.roomId.isEmpty()) ? (" • Phòng #" + item.roomId) : "";
                    Label subLbl = new Label("@" + item.username + " • " + item.status + roomHint);
                    subLbl.setStyle("-fx-font-family: 'Patrick Hand'; -fx-font-size: 12px; -fx-text-fill: #555555;");
                    infoBox.getChildren().addAll(nameLbl, subLbl);

                    Region spacer = new Region();
                    HBox.setHgrow(spacer, Priority.ALWAYS);

                    HBox actions = new HBox(4);
                    actions.setAlignment(Pos.CENTER_RIGHT);

                    if (!item.username.equals(app.getCurrentUsername())) {
                        Button chatBtn = new Button("💬");
                        chatBtn.setTooltip(new Tooltip("Nhắn tin"));
                        chatBtn.getStyleClass().add("pencil-button-sm");
                        chatBtn.setStyle("-fx-padding: 2 8; -fx-font-size: 14px;");
                        chatBtn.setOnAction(e -> app.openPrivateChat(item.username, item.getEffectiveName()));
                        actions.getChildren().add(chatBtn);

                        if (isFriend(item.username)) {
                            Label friendBadge = new Label("✓ Bạn bè");
                            friendBadge.getStyleClass().add("friend-badge");
                            actions.getChildren().add(friendBadge);
                        } else {
                            Button addBtn = new Button("➕");
                            addBtn.setTooltip(new Tooltip("Gửi kết bạn"));
                            addBtn.getStyleClass().add("pencil-button-sm");
                            addBtn.setStyle("-fx-padding: 2 8; -fx-font-size: 14px;");
                            addBtn.setOnAction(e -> sendFriendRequest(item.username));
                            actions.getChildren().add(addBtn);
                        }
                    }

                    row.getChildren().addAll(statusDot, infoBox, spacer, actions);
                    setGraphic(row);
                    setText(null);

                    // Context menu
                    if (item.canJoin && item.roomId != null) {
                        ContextMenu cm = new ContextMenu();
                        MenuItem joinItem = new MenuItem("🚪 Vào phòng #" + item.roomId);
                        joinItem.setOnAction(e -> joinRoom(item.roomId));
                        cm.getItems().add(joinItem);
                        setContextMenu(cm);
                    }
                }
            }
        });

        box.getChildren().addAll(onlineActionBar, playerListView);
        return box;
    }

    private boolean isFriend(String username) {
        if (username == null) return false;
        return friendList.stream().anyMatch(friend -> username.equalsIgnoreCase(friend.username));
    }

    // --- TAB 3: PENDING REQUESTS ---
    private VBox createPendingTabBox() {
        VBox box = new VBox(10);
        box.setAlignment(Pos.TOP_CENTER);

        HBox pendingActionBar = new HBox(8);
        pendingActionBar.setAlignment(Pos.CENTER_LEFT);

        Label pTitle = new Label("Lời mời kết bạn gửi tới bạn:");
        pTitle.setStyle("-fx-font-family: 'Patrick Hand'; -fx-font-size: 16px; -fx-font-weight: bold; -fx-text-fill: -fx-ink;");
        Region sp = new Region();
        HBox.setHgrow(sp, Priority.ALWAYS);

        Button refreshPendingBtn = new Button("🔄");
        refreshPendingBtn.getStyleClass().add("pencil-button-sm");
        refreshPendingBtn.setStyle("-fx-padding: 2 8; -fx-font-size: 15px;");
        refreshPendingBtn.setOnAction(e -> requestPendingRequests());

        pendingActionBar.getChildren().addAll(pTitle, sp, refreshPendingBtn);

        pendingListView = new ListView<>(pendingList);
        VBox.setVgrow(pendingListView, Priority.ALWAYS);
        pendingListView.setMaxHeight(Double.MAX_VALUE);
        pendingListView.setPlaceholder(new Label("Không có lời mời kết bạn nào đang chờ."));
        pendingListView.setCellFactory(lv -> new ListCell<>() {
            @Override
            protected void updateItem(PendingRequestEntry item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setGraphic(null);
                    setText(null);
                } else {
                    HBox row = new HBox(8);
                    row.setAlignment(Pos.CENTER_LEFT);
                    row.setPadding(new Insets(4, 6, 4, 6));

                    VBox infoBox = new VBox(2);
                    Label nameLbl = new Label(item.getEffectiveName());
                    nameLbl.setStyle("-fx-font-family: 'Patrick Hand'; -fx-font-size: 16px; -fx-font-weight: bold; -fx-text-fill: -fx-ink;");

                    Label subLbl = new Label("@" + item.username + " • " + item.rankingScore + " điểm");
                    subLbl.setStyle("-fx-font-family: 'Patrick Hand'; -fx-font-size: 12px; -fx-text-fill: #555555;");
                    infoBox.getChildren().addAll(nameLbl, subLbl);

                    Region spacer = new Region();
                    HBox.setHgrow(spacer, Priority.ALWAYS);

                    Button acceptBtn = new Button("✔ Đồng ý");
                    acceptBtn.setStyle("-fx-background-color: #52c41a; -fx-text-fill: white; -fx-font-family: 'Patrick Hand'; -fx-font-weight: bold; -fx-padding: 2 8; -fx-cursor: hand;");
                    acceptBtn.setOnAction(e -> respondFriendRequest(item.username, true));

                    Button rejectBtn = new Button("✖ Từ chối");
                    rejectBtn.setStyle("-fx-background-color: #d9d9d9; -fx-text-fill: -fx-ink; -fx-font-family: 'Patrick Hand'; -fx-padding: 2 8; -fx-cursor: hand;");
                    rejectBtn.setOnAction(e -> respondFriendRequest(item.username, false));

                    row.getChildren().addAll(infoBox, spacer, acceptBtn, rejectBtn);
                    setGraphic(row);
                    setText(null);
                }
            }
        });

        box.getChildren().addAll(pendingActionBar, pendingListView);
        return box;
    }

    public Scene getScene() {
        return scene;
    }

    private void openInbox() {
        if (inboxDialog == null) inboxDialog = new InboxDialog(app, app::openPrivateChat);
        inboxDialog.show();
        app.requestChatInbox();
    }

    public void updateInbox(Envelope envelope) {
        int unread = Math.max(0, envelope.getInt("unreadTotal", 0));
        if (inboxUnreadBadge != null) {
            inboxUnreadBadge.setText(unread > 99 ? "99+" : Integer.toString(unread));
            inboxUnreadBadge.setVisible(unread > 0);
            inboxUnreadBadge.setManaged(unread > 0);
        }
        if (inboxDialog != null) inboxDialog.update(envelope);
    }

    public void updateDisplayName(String newName) {
        Platform.runLater(() -> {
            if (welcomeLabel != null && newName != null && !newName.isEmpty()) {
                welcomeLabel.setText("👤 " + newName);
            }
        });
    }

    // --- ACTIONS ---
    private void refreshAllData() {
        if (app.getWsClient() != null && app.getWsClient().isConnected()) {
            app.getWsClient().send(new Envelope(MessageType.ROOM_LIST));
            app.getWsClient().send(new Envelope(MessageType.PLAYER_LIST));
            app.getWsClient().send(new Envelope(MessageType.FRIEND_LIST));
            app.getWsClient().send(new Envelope(MessageType.FRIEND_PENDING_LIST));
        }
    }

    public void requestFriends() {
        if (app.getWsClient() != null && app.getWsClient().isConnected()) {
            app.getWsClient().send(new Envelope(MessageType.FRIEND_LIST));
        }
    }

    public void requestPlayers() {
        if (app.getWsClient() != null && app.getWsClient().isConnected()) {
            app.getWsClient().send(new Envelope(MessageType.PLAYER_LIST));
        }
    }

    public void requestPendingRequests() {
        if (app.getWsClient() != null && app.getWsClient().isConnected()) {
            app.getWsClient().send(new Envelope(MessageType.FRIEND_PENDING_LIST));
        }
    }

    private void promptAddFriend() {
        DoodleDialog.input(app.getPrimaryStage(), "Thêm bạn bè",
                "Nhập tên đăng nhập (username) của người bạn muốn kết bạn:", "", username -> {
                    if (username != null && !username.trim().isEmpty()) {
                        sendFriendRequest(username.trim());
                    }
                });
    }

    public void sendFriendRequest(String targetUsername) {
        if (targetUsername == null || targetUsername.trim().isEmpty()) return;
        if (targetUsername.equalsIgnoreCase(app.getCurrentUsername())) {
            DoodleDialog.warning(app.getPrimaryStage(), "Cảnh báo", "Bạn không thể tự kết bạn với chính mình!");
            return;
        }
        Envelope env = new Envelope(MessageType.FRIEND_REQUEST);
        env.put("targetUsername", targetUsername.trim());
        app.getWsClient().send(env);
    }

    public void respondFriendRequest(String senderUsername, boolean accept) {
        Envelope env = new Envelope(MessageType.FRIEND_RESPONSE);
        env.put("senderUsername", senderUsername);
        env.putBoolean("accept", accept);
        app.getWsClient().send(env);
    }

    private void confirmRemoveFriend(String targetUsername) {
        DoodleDialog.confirm(app.getPrimaryStage(), "Hủy kết bạn",
                "Bạn có chắc muốn hủy kết bạn với @" + targetUsername + "?",
                "HỦY KẾT BẠN", "GIỮ LẠI", confirmed -> {
                    if (confirmed) {
                        Envelope env = new Envelope(MessageType.FRIEND_REMOVE);
                        env.put("targetUsername", targetUsername);
                        app.getWsClient().send(env);
                    }
                });
    }

    private void promptCreateRoom() {
        DoodleDialog.roomSetup(app.getPrimaryStage(), (name, password) -> {
                    if (name == null) return;
                    String trimmed = name.trim();
                    if (trimmed.isEmpty()) {
                        trimmed = "Phòng của " + app.getCurrentDisplayName();
                    }
                    Envelope env = new Envelope(MessageType.ROOM_CREATE);
                    env.put("roomName", trimmed);
                    env.put("roomPassword", password);
                    app.getWsClient().send(env);
                });
    }

    private void promptJoinByCode() {
        DoodleDialog.input(app.getPrimaryStage(), "Vào phòng bằng mã số",
                "Nhập mã phòng gồm 6 chữ số:", "", code -> {
                    if (code != null && !code.trim().isEmpty()) {
                        joinRoom(code.trim());
                    }
                });
    }

    private void joinRoom(String roomId) {
        sendRoomJoin(roomId, null);
    }

    public void joinRoomWithPassword(String roomId, String password) {
        sendRoomJoin(roomId, password);
    }

    private void sendRoomJoin(String roomId, String password) {
        if (roomId == null || roomId.trim().isEmpty()) return;
        Envelope env = new Envelope(MessageType.ROOM_JOIN);
        env.put("roomId", roomId.trim());
        if (password != null) env.put("roomPassword", password);
        app.getWsClient().send(env);
    }

    public void handleMessage(Envelope env) {
        Platform.runLater(() -> {
            if (welcomeLabel != null) {
                welcomeLabel.setText("👋 " + app.getCurrentDisplayName());
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
                        entry.hasPassword = r.has("hasPassword") && r.get("hasPassword").asBoolean();
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
                        PlayerEntry p = new PlayerEntry();
                        p.username = u.has("username") ? u.get("username").asText() : "";
                        p.displayName = u.has("displayName") ? u.get("displayName").asText() : p.username;
                        p.status = u.has("status") ? u.get("status").asText() : "Đang rảnh";
                        p.rankingScore = u.has("rankingScore") ? u.get("rankingScore").asInt() : 0;
                        p.roomId = u.has("roomId") ? u.get("roomId").asText() : null;
                        p.roomName = u.has("roomName") ? u.get("roomName").asText() : null;
                        p.canJoin = u.has("canJoin") && u.get("canJoin").asBoolean();
                        playerList.add(p);
                    });
                }
                if (playerCountLabel != null) {
                    playerCountLabel.setText("Người chơi đang online: (" + playerList.size() + ")");
                }
                if (tabOnlineBtn != null) {
                    tabOnlineBtn.setText("🌐 Online (" + playerList.size() + ")");
                }
            } else if (env.getType() == MessageType.FRIEND_LIST) {
                friendList.clear();
                if (env.getData() != null && env.getData().has("friends")) {
                    JsonNode friendsNode = env.getData().get("friends");
                    friendsNode.forEach(f -> {
                        String uName = f.has("username") ? f.get("username").asText() : "";
                        if (uName.isEmpty() || (app.getCurrentUsername() != null && uName.equalsIgnoreCase(app.getCurrentUsername()))) {
                            return;
                        }
                        FriendEntry fe = new FriendEntry();
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
                if (tabFriendsBtn != null) {
                    tabFriendsBtn.setText("👥 Bạn bè (" + onlineCount + " Online / " + friendList.size() + ")");
                }
                if (friendCountLabel != null) {
                    friendCountLabel.setText("Danh sách bạn bè (" + onlineCount + " đang online / " + friendList.size() + " người):");
                }
                if (playerListView != null) playerListView.refresh();
            } else if (env.getType() == MessageType.FRIEND_PENDING_LIST) {
                pendingList.clear();
                if (env.getData() != null && env.getData().has("pendingRequests")) {
                    JsonNode reqs = env.getData().get("pendingRequests");
                    reqs.forEach(r -> {
                        PendingRequestEntry pe = new PendingRequestEntry();
                        pe.username = r.has("username") ? r.get("username").asText() : "";
                        pe.displayName = r.has("displayName") ? r.get("displayName").asText() : pe.username;
                        pe.avatar = r.has("avatar") ? r.get("avatar").asText() : "1";
                        pe.rankingScore = r.has("rankingScore") ? r.get("rankingScore").asInt() : 0;
                        pendingList.add(pe);
                    });
                }
                if (tabPendingBtn != null) {
                    tabPendingBtn.setText("🔔 Lời mời (" + pendingList.size() + ")");
                }
            } else if (env.getType() == MessageType.FRIEND_REQUEST) {
                if (env.isSuccess()) {
                    DoodleDialog.info(app.getPrimaryStage(), "Kết bạn thành công", env.getContent() != null ? env.getContent() : "Đã gửi lời mời kết bạn.");
                } else {
                    DoodleDialog.error(app.getPrimaryStage(), "Lỗi kết bạn", env.getContent() != null ? env.getContent() : "Không thể gửi lời mời kết bạn.");
                }
            } else if (env.getType() == MessageType.FRIEND_RESPONSE) {
                requestFriends();
                requestPendingRequests();
            } else if (env.getType() == MessageType.FRIEND_REMOVE) {
                requestFriends();
            } else if (env.getType() == MessageType.FRIEND_REQUEST_NOTIFY) {
                String senderName = env.getString("senderDisplayName");
                if (senderName == null) senderName = env.getString("senderUsername");
                DoodleDialog.info(app.getPrimaryStage(), "Lời mời kết bạn",
                        "🔔 " + (senderName != null ? senderName : "Một người chơi") + " đã gửi cho bạn một lời mời kết bạn!");
                requestPendingRequests();
            } else if (env.getType() == MessageType.FRIEND_UPDATE) {
                requestFriends();
            } else if (env.getType() == MessageType.ROOM_INVITE_NOTIFY) {
                String inviter = env.getString("senderDisplayName");
                if (inviter == null) inviter = env.getString("senderUsername");
                String rId = env.getString("roomId");
                DoodleDialog.confirm(app.getPrimaryStage(), "Lời mời chơi game",
                        "💌 Người bạn " + (inviter != null ? inviter : "bạn bè") + " vừa mời bạn vào phòng #" + rId + "!\nBạn có muốn tham gia không?",
                        "THAM GIA NGAY", "ĐỂ SAU", res -> {
                            if (res && rId != null) {
                                joinRoom(rId);
                            }
                        });
            } else if (env.getType() == MessageType.ROOM_RESPONSE) {
                if (env.isSuccess()) {
                    String roomId = env.getString("roomId");
                    String roomName = env.getString("roomName");
                    if (roomName == null || roomName.isEmpty()) roomName = "Phòng " + roomId;
                    app.showRoomScreen(roomId, roomName);
                    if (app.getRoomScreen() != null) {
                        app.getRoomScreen().handleMessage(env);
                    }
                } else {
                    DoodleDialog.error(app.getPrimaryStage(), "Không thể vào phòng",
                            env.getContent() != null ? env.getContent() : "Không thể thực hiện thao tác phòng.");
                }
            }
        });
    }
}
