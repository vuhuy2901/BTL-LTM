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
import javafx.scene.input.MouseEvent;
import javafx.geometry.Point2D;
import javafx.animation.PauseTransition;
import javafx.util.Duration;

import java.util.Optional;

public class LobbyScreen {
    private final MainApplication app;
    private final ObservableList<RoomEntry> roomList;
    private final ObservableList<PlayerEntry> playerList;
    private final ObservableList<FriendEntry> friendList;
    private final ObservableList<PendingRequestEntry> pendingList;
    private final ObservableList<SearchPlayerEntry> searchResults = FXCollections.observableArrayList();
    private final ObservableList<LeaderboardEntry> leaderboardEntries = FXCollections.observableArrayList();

    private ListView<RoomEntry> roomListView;
    private ListView<PlayerEntry> playerListView;
    private ListView<FriendEntry> friendListView;
    private ListView<PendingRequestEntry> pendingListView;
    private Label leaderboardStatusLabel;

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
    private Dialog<Void> friendSearchDialog;
    private TextField friendSearchField;
    private Label friendSearchHint;
    private String lastFriendSearch = "";
    private final PauseTransition friendSearchDebounce = new PauseTransition(Duration.millis(280));

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
        public String avatar;
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
        public String roomId;

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

    public static class SearchPlayerEntry {
        public String username;
        public String displayName;
        public String avatar;
        public int rankingScore;
        public int pointsDrawn;
        public int pointsGuess;
        public boolean online;
        public String friendStatus = "NONE";
        public boolean requestFromMe;
        public String getEffectiveName() { return displayName == null || displayName.isBlank() ? username : displayName; }
    }

    public static class LeaderboardEntry {
        public int rank;
        public String username;
        public String displayName;
        public String avatar;
        public int rankingScore;
        public int pointsDrawn;
        public int pointsGuess;
        public boolean online;

        public String getEffectiveName() {
            return displayName == null || displayName.isBlank() ? username : displayName;
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

        Label brandLabel = new Label("VẼ HÌNH ĐOÁN Ý — SẢNH CHỜ");
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

        Button soundBtn = new Button(BackgroundMusic.isMuted() ? "🔇 Nhạc: Tắt" : "🔊 Nhạc: Bật");
        soundBtn.getStyleClass().add("pencil-button-sm");
        soundBtn.setTooltip(new Tooltip("Bật / Tắt âm thanh nền"));
        soundBtn.setOnAction(e -> {
            boolean isMuted = BackgroundMusic.toggleMute();
            soundBtn.setText(isMuted ? "🔇 Nhạc: Tắt" : "🔊 Nhạc: Bật");
        });
        BackgroundMusic.addMuteListener(muted -> Platform.runLater(() ->
                soundBtn.setText(muted ? "🔇 Nhạc: Tắt" : "🔊 Nhạc: Bật")
        ));

        Button logoutBtn = new Button("Đăng xuất");
        logoutBtn.getStyleClass().add("pencil-button-sm");
        logoutBtn.setOnAction(e -> app.returnToLogin(null));

        topBar.getChildren().addAll(brandLabel, spacer, welcomeLabel, profileBtn, soundBtn);
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
        tabPendingBtn = new Button("🔔 Lời mời (0)");

        tabFriendsBtn.setMaxWidth(Double.MAX_VALUE);
        tabPendingBtn.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(tabFriendsBtn, Priority.ALWAYS);
        HBox.setHgrow(tabPendingBtn, Priority.ALWAYS);

        tabHeader.getChildren().addAll(tabFriendsBtn, tabPendingBtn);

        // Friends and invitations are the only social views outside a room.
        socialContentStack = new StackPane();
        VBox.setVgrow(socialContentStack, Priority.ALWAYS);

        // TAB 1: FRIENDS VIEW
        VBox friendsTabBox = createFriendsTabBox();

        // TAB 2: PENDING REQUESTS VIEW
        VBox pendingTabBox = createPendingTabBox();

        socialContentStack.getChildren().addAll(friendsTabBox, pendingTabBox);

        // Tab switching logic
        Runnable selectFriendsTab = () -> {
            styleActiveTab(tabFriendsBtn);
            styleInactiveTab(tabPendingBtn);
            friendsTabBox.setVisible(true);
            pendingTabBox.setVisible(false);
        };

        Runnable selectPendingTab = () -> {
            styleInactiveTab(tabFriendsBtn);
            styleActiveTab(tabPendingBtn);
            friendsTabBox.setVisible(false);
            pendingTabBox.setVisible(true);
        };

        tabFriendsBtn.setOnAction(e -> selectFriendsTab.run());
        tabPendingBtn.setOnAction(e -> selectPendingTab.run());

        // Default to Friends tab
        selectFriendsTab.run();

        socialCard.getChildren().addAll(tabHeader, socialContentStack);

        mainContainer.getChildren().addAll(roomsCard, socialCard);

        Tab lobbyTab = new Tab("🎨 Sảnh chờ", mainContainer);
        lobbyTab.setClosable(false);
        Tab leaderboardTab = new Tab("🏆 Xếp hạng", createLeaderboardView());
        leaderboardTab.setClosable(false);
        TabPane mainTabs = new TabPane(lobbyTab, leaderboardTab);
        mainTabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        mainTabs.getSelectionModel().selectedItemProperty().addListener((observable, oldTab, selectedTab) -> {
            if (selectedTab == leaderboardTab) requestLeaderboard();
        });
        root.setCenter(mainTabs);

        StackPane lobbyRoot = new StackPane(root);
        AnchorPane floatingLayer = new AnchorPane();
        floatingLayer.setPickOnBounds(false);
        Button inboxButton = new Button("💬");
        inboxButton.getStyleClass().add("floating-inbox-button");
        inboxButton.setTooltip(new Tooltip("Kéo để di chuyển · Bấm để mở hộp thư"));
        inboxButton.setCursor(javafx.scene.Cursor.MOVE);
        inboxUnreadBadge = new Label();
        inboxUnreadBadge.getStyleClass().add("inbox-unread-badge");
        inboxUnreadBadge.setVisible(false);
        inboxUnreadBadge.setMouseTransparent(true);
        StackPane inboxFab = new StackPane(inboxButton, inboxUnreadBadge);
        inboxFab.setMinSize(58, 58);
        inboxFab.setPrefSize(58, 58);
        inboxFab.setMaxSize(58, 58);
        StackPane.setAlignment(inboxUnreadBadge, Pos.TOP_RIGHT);
        AnchorPane.setRightAnchor(inboxFab, 26.0);
        AnchorPane.setBottomAnchor(inboxFab, 22.0);
        floatingLayer.getChildren().add(inboxFab);
        lobbyRoot.getChildren().add(floatingLayer);

        // Drag in the full overlay bounds and keep the icon plus badge together.
        double[] dragStart = new double[2];
        double[] grabOffset = new double[2];
        boolean[] dragged = new boolean[1];
        boolean[] detachedFromCorner = new boolean[1];
        Runnable keepInboxButtonInBounds = () -> {
            if (!detachedFromCorner[0]) return;
            double maxX = Math.max(0, floatingLayer.getWidth() - inboxFab.getWidth());
            double maxY = Math.max(0, floatingLayer.getHeight() - inboxFab.getHeight());
            inboxFab.relocate(clamp(inboxFab.getLayoutX(), 0, maxX),
                    clamp(inboxFab.getLayoutY(), 0, maxY));
        };
        inboxFab.addEventFilter(MouseEvent.MOUSE_PRESSED, e -> {
            Point2D pointer = floatingLayer.sceneToLocal(e.getSceneX(), e.getSceneY());
            dragStart[0] = pointer.getX();
            dragStart[1] = pointer.getY();
            grabOffset[0] = pointer.getX() - inboxFab.getLayoutX();
            grabOffset[1] = pointer.getY() - inboxFab.getLayoutY();
            dragged[0] = false;
        });
        inboxFab.addEventFilter(MouseEvent.MOUSE_DRAGGED, e -> {
            Point2D pointer = floatingLayer.sceneToLocal(e.getSceneX(), e.getSceneY());
            double dx = pointer.getX() - dragStart[0];
            double dy = pointer.getY() - dragStart[1];
            if (!dragged[0] && Math.hypot(dx, dy) < 5) return;
            dragged[0] = true;
            if (!detachedFromCorner[0]) {
                AnchorPane.setRightAnchor(inboxFab, null);
                AnchorPane.setBottomAnchor(inboxFab, null);
                detachedFromCorner[0] = true;
            }
            double maxX = Math.max(0, floatingLayer.getWidth() - inboxFab.getWidth());
            double maxY = Math.max(0, floatingLayer.getHeight() - inboxFab.getHeight());
            inboxFab.relocate(clamp(pointer.getX() - grabOffset[0], 0, maxX),
                    clamp(pointer.getY() - grabOffset[1], 0, maxY));
            e.consume();
        });
        floatingLayer.widthProperty().addListener((obs, oldValue, newValue) -> keepInboxButtonInBounds.run());
        floatingLayer.heightProperty().addListener((obs, oldValue, newValue) -> keepInboxButtonInBounds.run());
        inboxButton.setOnAction(e -> {
            if (dragged[0]) {
                dragged[0] = false;
                return;
            }
            openInbox();
        });

        scene = new Scene(lobbyRoot, 1100, 640);
        MainApplication.applyTheme(scene);
        return scene;
    }

    private VBox createLeaderboardView() {
        VBox content = new VBox(14);
        content.setPadding(new Insets(24));
        content.setAlignment(Pos.TOP_CENTER);
        content.getStyleClass().add("speech-box-white");

        HBox header = new HBox(12);
        header.setAlignment(Pos.CENTER_LEFT);
        Label title = new Label("🏆 BẢNG XẾP HẠNG NGƯỜI CHƠI");
        title.setStyle("-fx-font-family: 'Amatic SC'; -fx-font-size: 32px; -fx-font-weight: bold; -fx-text-fill: -fx-ink;");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        Button refreshButton = new Button("↻ Cập nhật");
        refreshButton.getStyleClass().add("pencil-button-sm");
        refreshButton.setOnAction(event -> requestLeaderboard());
        header.getChildren().addAll(title, spacer, refreshButton);

        leaderboardStatusLabel = new Label("Đang tải bảng xếp hạng...");
        leaderboardStatusLabel.setStyle("-fx-font-family: 'Patrick Hand'; -fx-font-size: 15px; -fx-text-fill: #64748B;");

        TableView<LeaderboardEntry> table = new TableView<>(leaderboardEntries);
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
        table.setPlaceholder(new Label("Chưa có dữ liệu xếp hạng."));
        VBox.setVgrow(table, Priority.ALWAYS);

        TableColumn<LeaderboardEntry, String> rankColumn = new TableColumn<>("Hạng");
        rankColumn.setCellValueFactory(cell -> new javafx.beans.property.SimpleStringProperty(
                cell.getValue().rank <= 3 ? switch (cell.getValue().rank) {
                    case 1 -> "🥇 1";
                    case 2 -> "🥈 2";
                    default -> "🥉 3";
                } : String.valueOf(cell.getValue().rank)));
        rankColumn.setMaxWidth(100);

        TableColumn<LeaderboardEntry, String> nameColumn = new TableColumn<>("Người chơi");
        nameColumn.setCellValueFactory(cell -> new javafx.beans.property.SimpleStringProperty(
                (cell.getValue().online ? "🟢 " : "⚪ ") + cell.getValue().getEffectiveName()
                        + "  (@" + (cell.getValue().username == null ? "" : cell.getValue().username) + ")"));
        nameColumn.setCellFactory(column -> new TableCell<>() {
            @Override protected void updateItem(String name, boolean empty) {
                super.updateItem(name, empty);
                setText(empty ? null : name);
                LeaderboardEntry entry = getTableRow() == null ? null : getTableRow().getItem();
                setGraphic(empty || entry == null ? null : AvatarView.create(entry.avatar, 30));
            }
        });

        TableColumn<LeaderboardEntry, String> drawnColumn = new TableColumn<>("Điểm vẽ");
        drawnColumn.setCellValueFactory(cell -> new javafx.beans.property.SimpleStringProperty(
                String.format("%,d", cell.getValue().pointsDrawn)));

        TableColumn<LeaderboardEntry, String> guessColumn = new TableColumn<>("Điểm đoán");
        guessColumn.setCellValueFactory(cell -> new javafx.beans.property.SimpleStringProperty(
                String.format("%,d", cell.getValue().pointsGuess)));

        TableColumn<LeaderboardEntry, String> scoreColumn = new TableColumn<>("Điểm");
        scoreColumn.setCellValueFactory(cell -> new javafx.beans.property.SimpleStringProperty(
                String.format("%,d điểm", cell.getValue().rankingScore)));
        scoreColumn.setMaxWidth(170);

        table.getColumns().addAll(rankColumn, nameColumn, drawnColumn, guessColumn, scoreColumn);
        content.getChildren().addAll(header, leaderboardStatusLabel, table);
        return content;
    }

    private void requestLeaderboard() {
        if (app.getWsClient() == null || !app.getWsClient().isConnected()) {
            if (leaderboardStatusLabel != null) leaderboardStatusLabel.setText("Chưa kết nối máy chủ.");
            return;
        }
        if (leaderboardStatusLabel != null) leaderboardStatusLabel.setText("Đang tải bảng xếp hạng...");
        app.getWsClient().send(new Envelope(MessageType.LEADERBOARD_REQUEST));
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
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

        friendActionBar.getChildren().addAll(friendCountLabel, sp, addFriendBtn);

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
                    avatarPane.getChildren().add(AvatarView.create(item.avatar, 34));

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
                        statusBadge.setText(item.roomId == null ? "🟢 TRỰC TUYẾN" : "🚪 ĐANG TRONG PHÒNG");
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

        onlineActionBar.getChildren().addAll(playerCountLabel, sp);

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

                    row.getChildren().addAll(AvatarView.create(item.avatar, 34), statusDot, infoBox, spacer, actions);
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

        pendingActionBar.getChildren().addAll(pTitle, sp);

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

                    row.getChildren().addAll(AvatarView.create(item.avatar, 34), infoBox, spacer, acceptBtn, rejectBtn);
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
        if (inboxDialog == null) inboxDialog = new InboxDialog(app);
        inboxDialog.show();
        app.requestChatInbox();
    }

    public void openConversation(String username, String displayName) {
        if (inboxDialog == null) inboxDialog = new InboxDialog(app);
        inboxDialog.openConversation(username, displayName);
        app.requestChatInbox();
    }

    public boolean isConversationOpen(String username) {
        return inboxDialog != null && inboxDialog.isShowing()
                && username != null && username.equalsIgnoreCase(inboxDialog.getActiveUsername());
    }

    public void handleDirectMessage(Envelope envelope) {
        if (inboxDialog != null) inboxDialog.handleDirectMessage(envelope);
    }

    public void handleChatHistoryResponse(Envelope envelope) {
        if (inboxDialog != null) inboxDialog.handleHistoryResponse(envelope);
    }

    public void updateInbox(Envelope envelope) {
        int unread = Math.max(0, envelope.getInt("unreadTotal", 0));
        if (inboxUnreadBadge != null) {
            inboxUnreadBadge.setText(unread > 99 ? "99+" : Integer.toString(unread));
            inboxUnreadBadge.setVisible(unread > 0);
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
        if (friendSearchDialog != null && friendSearchDialog.isShowing()) {
            friendSearchField.requestFocus();
            return;
        }
        friendSearchDialog = new Dialog<>();
        friendSearchDialog.setTitle("Tìm người chơi");
        friendSearchDialog.initOwner(app.getPrimaryStage());
        friendSearchDialog.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
        friendSearchField = new TextField();
        friendSearchField.setPromptText("Tìm theo tên hoặc username...");
        friendSearchField.setStyle("-fx-font-size: 16px; -fx-padding: 10px;");
        friendSearchHint = new Label("Nhập tên để tìm người chơi và gửi lời mời kết bạn.");
        friendSearchHint.setStyle("-fx-text-fill: #64748B; -fx-font-size: 13px;");
        ListView<SearchPlayerEntry> results = new ListView<>(searchResults);
        results.setPrefSize(500, 340);
        results.setPlaceholder(new Label("Chưa có kết quả. Hãy thử tìm tên hoặc username."));
        results.setCellFactory(view -> new ListCell<>() {
            @Override protected void updateItem(SearchPlayerEntry player, boolean empty) {
                super.updateItem(player, empty);
                if (empty || player == null) { setGraphic(null); return; }
                HBox row = new HBox(12); row.setAlignment(Pos.CENTER_LEFT); row.setPadding(new Insets(8));
                StackPane avatar = AvatarView.create(player.avatar, 36);
                VBox details = new VBox(2);
                Label name = new Label(player.getEffectiveName());
                name.setStyle("-fx-font-size: 16px; -fx-font-weight: bold;");
                Label meta = new Label("@" + player.username + " · " + (player.online ? "Đang online" : "Ngoại tuyến") + " · " + player.rankingScore + " điểm");
                meta.setStyle("-fx-text-fill: #64748B; -fx-font-size: 12px;");
                details.getChildren().addAll(name, meta); HBox.setHgrow(details, Priority.ALWAYS);
                Button action = new Button();
                if ("ACCEPTED".equals(player.friendStatus)) { action.setText("✓ Bạn bè"); action.setDisable(true); }
                else if ("PENDING".equals(player.friendStatus) && player.requestFromMe) { action.setText("Hủy lời mời"); action.setOnAction(e -> cancelFriendRequest(player.username)); }
                else if ("PENDING".equals(player.friendStatus)) { action.setText("Chấp nhận"); action.setOnAction(e -> respondFriendRequest(player.username, true)); }
                else { action.setText("＋ Kết bạn"); action.getStyleClass().add("pencil-button-sm"); action.setOnAction(e -> sendFriendRequest(player.username)); }
                row.getChildren().addAll(avatar, details, action); setGraphic(row);
            }
        });
        VBox content = new VBox(10, friendSearchField, friendSearchHint, results);
        content.setPadding(new Insets(12)); content.setPrefWidth(540);
        friendSearchDialog.getDialogPane().setContent(content);
        friendSearchField.textProperty().addListener((obs, oldValue, value) -> {
            friendSearchDebounce.stop();
            String query = value == null ? "" : value.trim();
            searchResults.clear();
            if (query.isEmpty()) { friendSearchHint.setText("Nhập tên để tìm người chơi và gửi lời mời kết bạn."); return; }
            friendSearchHint.setText("Đang tìm người chơi…");
            friendSearchDebounce.setOnFinished(e -> searchPlayers(query));
            friendSearchDebounce.playFromStart();
        });
        friendSearchDialog.setOnHidden(e -> { friendSearchDebounce.stop(); friendSearchDialog = null; });
        friendSearchDialog.show();
        friendSearchField.requestFocus();
    }

    private void searchPlayers(String query) {
        if (app.getWsClient() == null || !app.getWsClient().isConnected()) return;
        lastFriendSearch = query;
        Envelope request = new Envelope(MessageType.SEARCH_PLAYER);
        request.put("keyword", query);
        app.getWsClient().send(request);
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
        if (friendSearchHint != null) friendSearchHint.setText("Đang gửi lời mời tới @" + targetUsername.trim() + "…");
    }

    public void respondFriendRequest(String senderUsername, boolean accept) {
        Envelope env = new Envelope(MessageType.FRIEND_RESPONSE);
        env.put("senderUsername", senderUsername);
        env.putBoolean("accept", accept);
        app.getWsClient().send(env);
    }

    private void cancelFriendRequest(String username) {
        Envelope request = new Envelope(MessageType.FRIEND_CANCEL_REQUEST);
        request.put("targetUsername", username);
        app.getWsClient().send(request);
        if (friendSearchHint != null) friendSearchHint.setText("Đang hủy lời mời tới @" + username + "…");
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
                    if (password != null && !password.isBlank()) env.put("roomPassword", password);
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

            if (env.getType() == MessageType.LEADERBOARD) {
                leaderboardEntries.clear();
                JsonNode rows = env.getData() == null ? null : env.getData().get("leaderboard");
                if (rows != null && rows.isArray()) {
                    rows.forEach(node -> {
                        LeaderboardEntry entry = new LeaderboardEntry();
                        entry.rank = node.path("rank").asInt(leaderboardEntries.size() + 1);
                        entry.username = node.path("username").asText("");
                        entry.displayName = node.path("displayName").asText(entry.username);
                        entry.avatar = node.path("avatar").asText("1");
                        entry.rankingScore = node.path("rankingScore").asInt(0);
                        entry.pointsDrawn = node.path("pointsDrawn").asInt(0);
                        entry.pointsGuess = node.path("pointsGuess").asInt(0);
                        entry.online = node.path("online").asBoolean(false);
                        leaderboardEntries.add(entry);
                    });
                }
                if (leaderboardStatusLabel != null) {
                    leaderboardStatusLabel.setText(leaderboardEntries.isEmpty()
                            ? "Chưa có người chơi trong bảng xếp hạng."
                            : "Top " + leaderboardEntries.size() + " người chơi · 🟢 đang online · ⚪ ngoại tuyến");
                }
            } else if (env.getType() == MessageType.ROOM_LIST) {
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
                        entry.maxPlayers = r.has("maxPlayers") ? r.get("maxPlayers").asInt() : 8;
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
                        p.avatar = u.path("avatar").asText("1");
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
                for (FriendEntry friend : friendList) {
                    PlayerEntry player = playerList.stream().filter(p -> p.username.equals(friend.username)).findFirst().orElse(null);
                    friend.isOnline = player != null;
                    friend.roomId = player == null ? null : player.roomId;
                }
                if (friendListView != null) friendListView.refresh();
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
                        fe.roomId = f.path("roomId").asText(null);
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
            } else if (env.getType() == MessageType.SEARCH_PLAYER || env.getType() == MessageType.SEARCH_USER_RESPONSE) {
                if (friendSearchDialog == null || !friendSearchDialog.isShowing()) return;
                searchResults.clear();
                JsonNode results = env.getData() == null ? null : env.getData().get("results");
                if (results != null) results.forEach(node -> {
                    SearchPlayerEntry entry = new SearchPlayerEntry();
                    entry.username = node.path("username").asText("");
                    entry.displayName = node.path("displayName").asText(entry.username);
                    entry.rankingScore = node.path("rankingScore").asInt(0);
                    entry.online = node.path("isOnline").asBoolean(node.path("online").asBoolean(false));
                    entry.friendStatus = node.path("friendStatus").asText("NONE");
                    entry.avatar = node.path("avatar").asText("1");
                    entry.requestFromMe = node.path("isSender").asBoolean(false);
                    if (!entry.username.isBlank()) searchResults.add(entry);
                });
                if (friendSearchHint != null) friendSearchHint.setText(searchResults.isEmpty()
                        ? "Không tìm thấy người chơi phù hợp." : searchResults.size() + " người chơi · chọn Kết bạn để gửi lời mời.");
            } else if (env.getType() == MessageType.FRIEND_REQUEST) {
                if (env.isSuccess()) {
                    if (friendSearchHint != null) friendSearchHint.setText("Đã gửi lời mời. Bạn sẽ thấy trạng thái cập nhật ngay.");
                    requestPendingRequests();
                    requestFriends();
                    if (!lastFriendSearch.isBlank()) searchPlayers(lastFriendSearch);
                } else {
                    if (friendSearchHint != null) friendSearchHint.setText(env.getContent() != null ? env.getContent() : "Không thể gửi lời mời kết bạn.");
                }
            } else if (env.getType() == MessageType.FRIEND_RESPONSE) {
                requestFriends();
                requestPendingRequests();
                if (!lastFriendSearch.isBlank()) searchPlayers(lastFriendSearch);
            } else if (env.getType() == MessageType.FRIEND_CANCEL_REQUEST) {
                if (friendSearchHint != null) friendSearchHint.setText(env.getContent() == null ? "Đã cập nhật lời mời." : env.getContent());
                requestPendingRequests();
                if (!lastFriendSearch.isBlank()) searchPlayers(lastFriendSearch);
            } else if (env.getType() == MessageType.FRIEND_REMOVE) {
                requestFriends();
            } else if (env.getType() == MessageType.FRIEND_REQUEST_NOTIFY) {
                requestPendingRequests();
                if (tabPendingBtn != null) tabPendingBtn.setText("🔔 Lời mời · mới");
            } else if (env.getType() == MessageType.FRIEND_UPDATE) {
                requestFriends();
                requestPendingRequests();
                if (!lastFriendSearch.isBlank()) searchPlayers(lastFriendSearch);
            } else if (env.getType() == MessageType.ROOM_INVITE_NOTIFY) {
                app.showRoomInvitation(env);
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
