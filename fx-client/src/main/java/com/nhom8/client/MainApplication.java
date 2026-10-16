package com.nhom8.client;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.control.ButtonBase;
import javafx.scene.Node;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.scene.text.Font;
import javafx.stage.Stage;

import com.nhom8.client.ui.DoodleDialog;
import com.nhom8.client.ui.AdminScreen;
import com.nhom8.client.ui.GameScreen;
import com.nhom8.client.ui.LobbyScreen;
import com.nhom8.client.ui.PrivateChatWindow;
import com.nhom8.client.ui.ProfileDialog;
import com.nhom8.client.ui.ClickSound;
import com.nhom8.client.ws.GameWebSocketClient;
import com.nhom8.common.message.Envelope;
import com.nhom8.common.message.MessageType;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class MainApplication extends Application {

    private GameWebSocketClient webSocketClient;
    private Stage primaryStage;
    private String currentUsername = "";
    private String currentDisplayName = "";
    private String currentRole = "USER";
    private AdminScreen adminScreen;
    private String lastEnteredUsername = "";
    private String lastRegisteredUsername = "";
    private String lastRegisteredPassword = "";
    private Envelope lastRoomUpdate;

    private final Map<String, PrivateChatWindow> openChatWindows = new ConcurrentHashMap<>();
    private ProfileDialog profileDialog;

    @Override
    public void start(Stage primaryStage) {
        this.primaryStage = primaryStage;
        primaryStage.setTitle("Scribble It! - Nhóm 8");
        primaryStage.setMinWidth(1000);
        primaryStage.setMinHeight(650);
        loadCustomFonts();
        showLoginScreen();
    }

    private void loadCustomFonts() {
        try {
            if (getClass().getResourceAsStream("/fonts/AmaticSC-Bold.ttf") != null) {
                Font.loadFont(getClass().getResourceAsStream("/fonts/AmaticSC-Bold.ttf"), 36);
            }
            if (getClass().getResourceAsStream("/fonts/AmaticSC-Regular.ttf") != null) {
                Font.loadFont(getClass().getResourceAsStream("/fonts/AmaticSC-Regular.ttf"), 36);
            }
            if (getClass().getResourceAsStream("/fonts/PatrickHand-Regular.ttf") != null) {
                Font.loadFont(getClass().getResourceAsStream("/fonts/PatrickHand-Regular.ttf"), 18);
            }
        } catch (Exception e) {
            System.err.println("Không thể nạp font: " + e.getMessage());
        }
    }

    public static void applyTheme(Scene scene) {
        if (scene == null) return;
        if (!Boolean.TRUE.equals(scene.getProperties().get("scribble.clickSoundInstalled"))) {
            scene.addEventFilter(MouseEvent.MOUSE_PRESSED, event -> {
                try {
                    if (event.getButton() != MouseButton.PRIMARY) return;
                    Node node = event.getTarget() instanceof Node target ? target : null;
                    while (node != null && !(node instanceof ButtonBase)) node = node.getParent();
                    if (node instanceof ButtonBase control && !control.isDisabled()) ClickSound.play();
                } catch (Throwable ignored) {}
            });
            scene.getProperties().put("scribble.clickSoundInstalled", true);
        }
        try {
            java.net.URL cssUrl = MainApplication.class.getResource("/css/style.css");
            if (cssUrl != null) {
                String css = cssUrl.toExternalForm();
                if (!scene.getStylesheets().contains(css)) {
                    scene.getStylesheets().add(css);
                }
            }
        } catch (Exception e) {
            System.err.println("Không thể áp dụng CSS: " + e.getMessage());
        }
    }

    private Button loginBtn;
    private Button registerBtn;
    private Label statusLabel;
    private String serverUrl = "ws://localhost:8080/ws/game";
    private String serverAddressInput = System.getProperty("scribble.server", "localhost:8080");
    private String connectedServerUrl;

    private void showLoginScreen() {
        VBox root = new VBox(20);
        root.setAlignment(Pos.CENTER);
        root.setPadding(new Insets(30));

        // Game Brand Title (Áp dụng .title-label theo phong cách Amatic SC)
        Label titleLabel = new Label("SCRIBBLE IT!");
        titleLabel.getStyleClass().add("title-label");

        Label subtitleLabel = new Label("🎨 Trò chơi Vẽ Hình Đoán Ý trực tuyến (Nhóm 8)");
        subtitleLabel.setStyle("-fx-font-family: 'Patrick Hand'; -fx-font-size: 19px; -fx-text-fill: -fx-ink; -fx-font-weight: bold;");

        // Card Container (Áp dụng speech-box-white viền mực vẽ tay)
        VBox card = new VBox(15);
        card.setAlignment(Pos.CENTER);
        card.setMaxWidth(400);
        card.getStyleClass().add("speech-box-white");

        // Tabs Header: [ Đăng nhập ] | [ Đăng ký ]
        HBox tabBox = new HBox(12);
        tabBox.setAlignment(Pos.CENTER);
        Button tabLogin = new Button("Đăng nhập");
        Button tabRegister = new Button("Đăng ký tài khoản");
        tabLogin.setMaxWidth(Double.MAX_VALUE);
        tabRegister.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(tabLogin, Priority.ALWAYS);
        HBox.setHgrow(tabRegister, Priority.ALWAYS);

        // Status Message Label
        statusLabel = new Label("");
        statusLabel.setStyle("-fx-text-fill: #D8000C; -fx-font-weight: bold; -fx-font-size: 15px; -fx-font-family: 'Patrick Hand';");
        statusLabel.setWrapText(true);
        statusLabel.setAlignment(Pos.CENTER);

        Label serverLabel = new Label("Máy chủ (IP hoặc tên miền):");
        TextField serverField = new TextField(serverAddressInput);
        serverField.setPromptText("Ví dụ: 192.168.1.180:8080");
        serverField.setTooltip(new Tooltip("Cùng mạng Wi-Fi: dùng IP của máy chạy server. Có thể nhập ws:// hoặc wss:// đầy đủ."));

        // --- Form Đăng nhập ---
        VBox loginForm = new VBox(10);
        loginForm.setAlignment(Pos.CENTER_LEFT);

        Label loginUserLbl = new Label("Tên đăng nhập:");
        loginUserLbl.setStyle("-fx-font-weight: bold; -fx-text-fill: -fx-ink; -fx-font-size: 16px; -fx-font-family: 'Patrick Hand';");
        TextField loginUserField = new TextField();
        loginUserField.setPromptText("Tên đăng nhập (3-30 ký tự)");

        Label loginPassLbl = new Label("Mật khẩu:");
        loginPassLbl.setStyle("-fx-font-weight: bold; -fx-text-fill: -fx-ink; -fx-font-size: 16px; -fx-font-family: 'Patrick Hand';");
        PasswordField loginPassField = new PasswordField();
        loginPassField.setPromptText("Mật khẩu");

        // Nút Đăng nhập cây bút chì (.pencil-button)
        loginBtn = new Button("ĐĂNG NHẬP ➔");
        loginBtn.getStyleClass().add("pencil-button");
        loginBtn.setMaxWidth(Double.MAX_VALUE);

        Hyperlink gotoRegisterLink = new Hyperlink("Chưa có tài khoản? Bấm để Đăng ký");
        gotoRegisterLink.setStyle("-fx-text-fill: -fx-ink; -fx-font-family: 'Patrick Hand'; -fx-font-size: 16px; -fx-underline: true;");
        gotoRegisterLink.setMaxWidth(Double.MAX_VALUE);
        gotoRegisterLink.setAlignment(Pos.CENTER);

        loginForm.getChildren().addAll(loginUserLbl, loginUserField, loginPassLbl, loginPassField, loginBtn, gotoRegisterLink);

        // --- Form Đăng ký ---
        VBox registerForm = new VBox(10);
        registerForm.setAlignment(Pos.CENTER_LEFT);

        Label regUserLbl = new Label("Tên đăng nhập *:");
        regUserLbl.setStyle("-fx-font-weight: bold; -fx-text-fill: -fx-ink; -fx-font-size: 16px; -fx-font-family: 'Patrick Hand';");
        TextField regUserField = new TextField();
        regUserField.setPromptText("3-30 ký tự (chữ, số, gạch dưới)");

        Label regDisplayLbl = new Label("Tên hiển thị trong game:");
        regDisplayLbl.setStyle("-fx-font-weight: bold; -fx-text-fill: -fx-ink; -fx-font-size: 16px; -fx-font-family: 'Patrick Hand';");
        TextField regDisplayField = new TextField();
        regDisplayField.setPromptText("Ví dụ: Tuấn Pro, Họa Sĩ Nhí...");

        Label regPassLbl = new Label("Mật khẩu *:");
        regPassLbl.setStyle("-fx-font-weight: bold; -fx-text-fill: -fx-ink; -fx-font-size: 16px; -fx-font-family: 'Patrick Hand';");
        PasswordField regPassField = new PasswordField();
        regPassField.setPromptText("Tối thiểu 4 ký tự");

        Label regConfirmPassLbl = new Label("Xác nhận lại mật khẩu *:");
        regConfirmPassLbl.setStyle("-fx-font-weight: bold; -fx-text-fill: -fx-ink; -fx-font-size: 16px; -fx-font-family: 'Patrick Hand';");
        PasswordField regConfirmPassField = new PasswordField();
        regConfirmPassField.setPromptText("Nhập lại mật khẩu ở trên");

        // Nút Đăng ký cây bút chì (.pencil-button)
        registerBtn = new Button("ĐĂNG KÝ NGAY ➔");
        registerBtn.getStyleClass().add("pencil-button");
        registerBtn.setMaxWidth(Double.MAX_VALUE);

        Hyperlink gotoLoginLink = new Hyperlink("Đã có tài khoản? Quay lại Đăng nhập");
        gotoLoginLink.setStyle("-fx-text-fill: -fx-ink; -fx-font-family: 'Patrick Hand'; -fx-font-size: 16px; -fx-underline: true;");
        gotoLoginLink.setMaxWidth(Double.MAX_VALUE);
        gotoLoginLink.setAlignment(Pos.CENTER);

        registerForm.getChildren().addAll(regUserLbl, regUserField, regDisplayLbl, regDisplayField, regPassLbl, regPassField, regConfirmPassLbl, regConfirmPassField, registerBtn, gotoLoginLink);

        // Container cho Form (hoán đổi giữa Login và Register)
        VBox formContainer = new VBox(loginForm);

        Runnable selectLoginTab = () -> {
            tabLogin.setStyle("-fx-background-color: -fx-ink; -fx-text-fill: -fx-primary; -fx-font-family: 'Amatic SC'; -fx-font-size: 24px; -fx-font-weight: bold; -fx-border-color: -fx-ink; -fx-border-width: 2; -fx-cursor: hand; -fx-padding: 2 10;");
            tabRegister.setStyle("-fx-background-color: white; -fx-text-fill: -fx-ink; -fx-font-family: 'Amatic SC'; -fx-font-size: 24px; -fx-font-weight: bold; -fx-border-color: -fx-ink; -fx-border-width: 2; -fx-cursor: hand; -fx-padding: 2 10;");
            formContainer.getChildren().setAll(loginForm);
            statusLabel.setText("");
        };

        Runnable selectRegisterTab = () -> {
            tabRegister.setStyle("-fx-background-color: -fx-ink; -fx-text-fill: -fx-primary; -fx-font-family: 'Amatic SC'; -fx-font-size: 24px; -fx-font-weight: bold; -fx-border-color: -fx-ink; -fx-border-width: 2; -fx-cursor: hand; -fx-padding: 2 10;");
            tabLogin.setStyle("-fx-background-color: white; -fx-text-fill: -fx-ink; -fx-font-family: 'Amatic SC'; -fx-font-size: 24px; -fx-font-weight: bold; -fx-border-color: -fx-ink; -fx-border-width: 2; -fx-cursor: hand; -fx-padding: 2 10;");
            formContainer.getChildren().setAll(registerForm);
            statusLabel.setText("");
        };

        tabLogin.setOnAction(e -> selectLoginTab.run());
        tabRegister.setOnAction(e -> selectRegisterTab.run());
        gotoRegisterLink.setOnAction(e -> selectRegisterTab.run());
        gotoLoginLink.setOnAction(e -> selectLoginTab.run());

        selectLoginTab.run(); // Mặc định hiển thị tab Đăng nhập
        tabBox.getChildren().addAll(tabLogin, tabRegister);

        // Nhấn Enter để gửi
        loginPassField.setOnAction(e -> loginBtn.fire());
        regConfirmPassField.setOnAction(e -> registerBtn.fire());

        // Action Đăng nhập
        loginBtn.setOnAction(e -> {
            String user = loginUserField.getText() != null ? loginUserField.getText().trim() : "";
            String pass = loginPassField.getText();

            try {
                serverUrl = normalizeServerUrl(serverField.getText());
                serverAddressInput = serverField.getText().trim();
            } catch (IllegalArgumentException ex) {
                statusLabel.setText(ex.getMessage());
                return;
            }

            if (user.isEmpty() || !user.matches("^[a-zA-Z0-9_]{3,30}$")) {
                statusLabel.setStyle("-fx-text-fill: #D8000C; -fx-font-weight: bold; -fx-font-size: 15px; -fx-font-family: 'Patrick Hand';");
                statusLabel.setText("Tên đăng nhập không hợp lệ (3-30 ký tự, chữ/số/gạch dưới)!");
                return;
            }
            if (pass == null || pass.isEmpty() || pass.length() > 64) {
                statusLabel.setStyle("-fx-text-fill: #D8000C; -fx-font-weight: bold; -fx-font-size: 15px; -fx-font-family: 'Patrick Hand';");
                statusLabel.setText("Mật khẩu không được để trống (tối đa 64 ký tự)!");
                return;
            }

            statusLabel.setStyle("-fx-text-fill: -fx-ink; -fx-font-weight: bold; -fx-font-size: 15px; -fx-font-family: 'Patrick Hand';");
            statusLabel.setText("Đang kết nối tới máy chủ...");
            loginBtn.setDisable(true);
            lastEnteredUsername = user;
            connectAndLogin(user, pass);
        });

        // Action Đăng ký
        registerBtn.setOnAction(e -> {
            String user = regUserField.getText() != null ? regUserField.getText().trim() : "";
            String disp = regDisplayField.getText() != null ? regDisplayField.getText().trim() : "";
            String pass = regPassField.getText();
            String confirmPass = regConfirmPassField.getText();

            try {
                serverUrl = normalizeServerUrl(serverField.getText());
                serverAddressInput = serverField.getText().trim();
            } catch (IllegalArgumentException ex) {
                statusLabel.setText(ex.getMessage());
                return;
            }

            if (user.isEmpty() || !user.matches("^[a-zA-Z0-9_]{3,30}$")) {
                statusLabel.setStyle("-fx-text-fill: #D8000C; -fx-font-weight: bold; -fx-font-size: 15px; -fx-font-family: 'Patrick Hand';");
                statusLabel.setText("Tên đăng nhập phải từ 3-30 ký tự, chỉ gồm chữ, số và gạch dưới!");
                return;
            }
            if (pass == null || pass.length() < 4 || pass.length() > 64) {
                statusLabel.setStyle("-fx-text-fill: #D8000C; -fx-font-weight: bold; -fx-font-size: 15px; -fx-font-family: 'Patrick Hand';");
                statusLabel.setText("Mật khẩu phải từ 4 đến 64 ký tự!");
                return;
            }
            if (!pass.equals(confirmPass)) {
                statusLabel.setStyle("-fx-text-fill: #D8000C; -fx-font-weight: bold; -fx-font-size: 15px; -fx-font-family: 'Patrick Hand';");
                statusLabel.setText("Mật khẩu xác nhận không trùng khớp!");
                return;
            }
            if (!disp.isEmpty() && (disp.length() < 2 || disp.length() > 30)) {
                statusLabel.setStyle("-fx-text-fill: #D8000C; -fx-font-weight: bold; -fx-font-size: 15px; -fx-font-family: 'Patrick Hand';");
                statusLabel.setText("Tên hiển thị phải từ 2 đến 30 ký tự!");
                return;
            }

            statusLabel.setStyle("-fx-text-fill: -fx-ink; -fx-font-weight: bold; -fx-font-size: 15px; -fx-font-family: 'Patrick Hand';");
            statusLabel.setText("Đang kết nối tới máy chủ...");
            registerBtn.setDisable(true);
            lastRegisteredUsername = user;
            lastRegisteredPassword = pass;
            connectAndRegister(user, pass, disp.isEmpty() ? user : disp);
        });

        card.getChildren().addAll(serverLabel, serverField, tabBox, formContainer, statusLabel);
        root.getChildren().addAll(titleLabel, subtitleLabel, card);

        Scene scene = new Scene(root, 800, 600);
        applyTheme(scene);
        primaryStage.setScene(scene);
        primaryStage.show();
    }

    private static String normalizeServerUrl(String input) {
        String value = input == null ? "" : input.trim();
        if (value.isEmpty()) throw new IllegalArgumentException("Vui lòng nhập IP hoặc tên miền máy chủ.");
        if (!value.startsWith("ws://") && !value.startsWith("wss://")) value = "ws://" + value;
        try {
            java.net.URI uri = java.net.URI.create(value);
            if (uri.getHost() == null || uri.getHost().isBlank() || uri.getUserInfo() != null
                    || (uri.getPort() != -1 && (uri.getPort() < 1 || uri.getPort() > 65535))) {
                throw new IllegalArgumentException("Địa chỉ máy chủ không hợp lệ.");
            }
            String path = uri.getPath();
            if (path == null || path.isEmpty() || "/".equals(path)) path = "/ws/game";
            if (!"/ws/game".equals(path) || uri.getQuery() != null || uri.getFragment() != null) {
                throw new IllegalArgumentException("Đường dẫn WebSocket phải là /ws/game.");
            }
            int port = uri.getPort() == -1 ? 8080 : uri.getPort();
            String host = uri.getHost();
            if (host.contains(":") && !host.startsWith("[")) host = "[" + host + "]";
            return uri.getScheme() + "://" + host + ":" + port + path;
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("Địa chỉ máy chủ không hợp lệ. Ví dụ: 192.168.1.180:8080");
        }
    }

    private void ensureWebSocketClient() {
        if (webSocketClient != null && serverUrl.equals(connectedServerUrl)) return;
        if (webSocketClient != null) webSocketClient.close();
        webSocketClient = new GameWebSocketClient(serverUrl, envelope -> Platform.runLater(() -> handleMessage(envelope)));
        connectedServerUrl = serverUrl;
    }

    private void connectAndLogin(String username, String password) {
        ensureWebSocketClient();

        webSocketClient.connect().thenAccept(ws -> {
            if (ws != null) {
                Platform.runLater(() -> statusLabel.setText("Đang xác thực thông tin..."));
                Envelope loginEnv = new Envelope(MessageType.LOGIN);
                loginEnv.put("username", username);
                loginEnv.put("password", password);
                webSocketClient.send(loginEnv);
            } else {
                Platform.runLater(() -> {
                    if (loginBtn != null) loginBtn.setDisable(false);
                    if (registerBtn != null) registerBtn.setDisable(false);
                    statusLabel.setStyle("-fx-text-fill: #D8000C; -fx-font-weight: bold;");
                    statusLabel.setText("Không thể kết nối máy chủ (" + serverUrl + ")!");
                });
            }
        });
    }

    private void connectAndRegister(String username, String password, String displayName) {
        ensureWebSocketClient();

        webSocketClient.connect().thenAccept(ws -> {
            if (ws != null) {
                Platform.runLater(() -> {
                    statusLabel.setStyle("-fx-text-fill: #1890ff; -fx-font-weight: bold;");
                    statusLabel.setText("Đang gửi yêu cầu đăng ký...");
                });
                Envelope regEnv = new Envelope(MessageType.REGISTER);
                regEnv.put("username", username);
                regEnv.put("password", password);
                regEnv.put("displayName", displayName);
                webSocketClient.send(regEnv);
            } else {
                Platform.runLater(() -> {
                    if (registerBtn != null) registerBtn.setDisable(false);
                    statusLabel.setStyle("-fx-text-fill: #D8000C; -fx-font-weight: bold;");
                    statusLabel.setText("Không thể kết nối máy chủ (" + serverUrl + ")!");
                });
            }
        });
    }

    private LobbyScreen lobbyScreen;
    private com.nhom8.client.ui.RoomScreen roomScreen;
    private GameScreen gameScreen;

    public GameWebSocketClient getWsClient() {
        return webSocketClient;
    }

    public String getCurrentUsername() {
        return currentUsername;
    }

    public String getCurrentDisplayName() {
        return currentDisplayName != null && !currentDisplayName.isEmpty() ? currentDisplayName : currentUsername;
    }

    public Stage getPrimaryStage() {
        return primaryStage;
    }

    public void setCurrentDisplayName(String currentDisplayName) {
        this.currentDisplayName = currentDisplayName;
        if (lobbyScreen != null) {
            lobbyScreen.updateDisplayName(currentDisplayName);
        }
    }

    public void openProfileDialog() {
        if (profileDialog == null) {
            profileDialog = new ProfileDialog(this);
        }
        profileDialog.show();
    }

    private void handleMessage(Envelope envelope) {
        if (envelope.getType() == MessageType.LOGIN_RESULT || envelope.getType() == MessageType.LOGIN_RESPONSE) {
            if (envelope.isSuccess()) {
                if (envelope.getData() != null && envelope.getData().has("player")) {
                    com.fasterxml.jackson.databind.JsonNode playerNode = envelope.getData().get("player");
                    if (playerNode.has("username")) currentUsername = playerNode.get("username").asText();
                    if (playerNode.has("displayName")) currentDisplayName = playerNode.get("displayName").asText();
                }
                if (currentUsername == null || currentUsername.isEmpty()) {
                    currentUsername = lastEnteredUsername;
                    currentDisplayName = lastEnteredUsername;
                }
                currentRole = envelope.getString("role") == null ? "USER" : envelope.getString("role");
                lobbyScreen = null;
                roomScreen = null;
                gameScreen = null;
                adminScreen = null;
                if (statusLabel != null) statusLabel.setText("");
                showLobbyScreen();
            } else {
                if (loginBtn != null) loginBtn.setDisable(false);
                if (statusLabel != null) {
                    statusLabel.setStyle("-fx-text-fill: #D8000C; -fx-font-weight: bold;");
                    statusLabel.setText(envelope.getContent());
                }
                DoodleDialog.error(primaryStage, "Đăng nhập thất bại", envelope.getContent());
            }
        } else if (envelope.getType() == MessageType.REGISTER || envelope.getType() == MessageType.REGISTER_RESPONSE) {
            if (envelope.isSuccess()) {
                if (registerBtn != null) registerBtn.setDisable(false);
                statusLabel.setStyle("-fx-text-fill: #52c41a; -fx-font-weight: bold;");
                statusLabel.setText("Đăng ký thành công! Đang tự động đăng nhập...");
                DoodleDialog.info(primaryStage, "Đăng ký thành công", "Đăng ký tài khoản thành công! Đang tự động đăng nhập vào sảnh chờ.");
                lastEnteredUsername = lastRegisteredUsername;
                connectAndLogin(lastRegisteredUsername, lastRegisteredPassword);
            } else {
                if (registerBtn != null) registerBtn.setDisable(false);
                statusLabel.setStyle("-fx-text-fill: #D8000C; -fx-font-weight: bold;");
                statusLabel.setText(envelope.getContent() != null ? envelope.getContent() : "Đăng ký không thành công!");
                DoodleDialog.error(primaryStage, "Đăng ký thất bại", envelope.getContent());
            }
        } else if (envelope.getType() == MessageType.ROOM_DISSOLVED) {
            DoodleDialog.info(primaryStage, "Phòng giải tán", envelope.getContent() != null ? envelope.getContent() : "Phòng đã bị giải tán.");
            showLobbyScreen();
        } else if (envelope.getType() == MessageType.ROOM_PASSWORD_REQUIRED) {
            String roomId = envelope.getString("roomId");
            String roomName = envelope.getString("roomName");
            String reason = envelope.getContent();
            DoodleDialog.password(primaryStage,
                    (roomName == null ? "phòng #" + roomId : "phòng \"" + roomName + "\"")
                            + (reason == null ? "" : "\n" + reason), password -> {
                        if (password == null || webSocketClient == null || !webSocketClient.isConnected()) return;
                        Envelope join = new Envelope(MessageType.ROOM_JOIN);
                        join.put("roomId", roomId);
                        join.put("roomPassword", password);
                        webSocketClient.send(join);
                    });
            return;
        } else if (envelope.getType() == MessageType.ROOM_INVITE_NOTIFY) {
            String inviter = envelope.getString("senderDisplayName");
            if (inviter == null) inviter = envelope.getString("senderUsername");
            String rId = envelope.getString("roomId");
            DoodleDialog.confirm(primaryStage, "💌 Lời mời chơi game",
                    "Người bạn " + (inviter != null ? inviter : "bạn bè") + " vừa mời bạn vào phòng #" + rId + "!\nBạn có muốn tham gia ngay không?",
                    "THAM GIA NGAY", "ĐỂ SAU", yes -> {
                        if (yes && rId != null && webSocketClient != null) {
                            Envelope joinEnv = new Envelope(MessageType.ROOM_JOIN);
                            joinEnv.put("roomId", rId);
                            webSocketClient.send(joinEnv);
                        }
                    });
            return;
        }

        // Xử lý Hồ sơ cá nhân và Lịch sử đấu
        if (envelope.getType() == MessageType.PROFILE || envelope.getType() == MessageType.PROFILE_RESPONSE) {
            if (profileDialog != null) {
                profileDialog.handleProfileResponse(envelope);
            }
            return;
        }

        if (envelope.getType() == MessageType.UPDATE_PROFILE || envelope.getType() == MessageType.UPDATE_PROFILE_RESPONSE) {
            if (profileDialog != null) {
                profileDialog.handleUpdateProfileResponse(envelope);
            }
            return;
        }

        if (envelope.getType() == MessageType.MATCH_HISTORY || envelope.getType() == MessageType.MATCH_HISTORY_RESPONSE) {
            if (profileDialog != null) {
                profileDialog.handleMatchHistoryResponse(envelope);
            }
            return;
        }

        // Xử lý tin nhắn riêng 1-1 (Private Chat với bạn bè)
        if (envelope.getType() == MessageType.CHAT_INBOX_RESPONSE) {
            if (lobbyScreen != null) lobbyScreen.updateInbox(envelope);
            return;
        }

        if (envelope.getType() == MessageType.DIRECT_MESSAGE_RECEIVE) {
            String senderUsername = envelope.getString("senderUsername");
            String targetUsername = envelope.getString("targetUsername");
            boolean isSelf = envelope.getBoolean("isSelf", false);
            String chatPartner = isSelf ? targetUsername : senderUsername;

            if (chatPartner != null && !chatPartner.isEmpty()) {
                PrivateChatWindow win = openChatWindows.get(chatPartner);
                if (win != null) {
                    win.handleReceiveMessage(envelope);
                    if (!isSelf && win.isShowing()) markConversationRead(chatPartner);
                } else if (!isSelf) {
                    requestChatInbox();
                }
            }
            return;
        }

        if (envelope.getType() == MessageType.CHAT_HISTORY_RESPONSE) {
            String targetUser = envelope.getString("targetUsername");
            if (targetUser != null && !targetUser.isEmpty()) {
                PrivateChatWindow win = openChatWindows.get(targetUser);
                if (win != null) {
                    win.handleHistoryResponse(envelope);
                }
            }
            return;
        }

        // Cập nhật sảnh chờ cho các tin nhắn ROOM_LIST, PLAYER_LIST, bạn bè
        if (envelope.getType() == MessageType.ROOM_LIST
                || envelope.getType() == MessageType.PLAYER_LIST
                || envelope.getType() == MessageType.ONLINE_LIST
                || envelope.getType() == MessageType.FRIEND_LIST
                || envelope.getType() == MessageType.FRIEND_PENDING_LIST
                || envelope.getType() == MessageType.FRIEND_REQUEST
                || envelope.getType() == MessageType.FRIEND_RESPONSE
                || envelope.getType() == MessageType.FRIEND_REMOVE
                || envelope.getType() == MessageType.FRIEND_REQUEST_NOTIFY
                || envelope.getType() == MessageType.FRIEND_UPDATE
                || envelope.getType() == MessageType.ROOM_INVITE_NOTIFY) {
            if (lobbyScreen != null) {
                lobbyScreen.handleMessage(envelope);
            }
        }

        // Luôn cập nhật thông tin phòng cho roomScreen khi nhận tin nhắn phòng
        if (envelope.getType() == MessageType.ROOM_UPDATE || envelope.getType() == MessageType.ROOM_UPDATE_SETTINGS) {
            lastRoomUpdate = envelope;
            if (roomScreen != null) {
                roomScreen.handleMessage(envelope);
            }
        }

        if (roomScreen != null && primaryStage.getScene() == roomScreen.getScene()) {
            if (envelope.getType() != MessageType.ROOM_UPDATE && envelope.getType() != MessageType.ROOM_UPDATE_SETTINGS) {
                roomScreen.handleMessage(envelope);
            }
        } else if (gameScreen != null && primaryStage.getScene() == gameScreen.getScene()) {
            gameScreen.handleMessage(envelope);
        } else if (lobbyScreen != null && primaryStage.getScene() == lobbyScreen.getScene()) {
            lobbyScreen.handleMessage(envelope);
        }
        if (adminScreen != null && primaryStage.getScene() == adminScreen.createScene()
                && envelope.getType().name().startsWith("ADMIN_")) {
            adminScreen.handleMessage(envelope);
        }
    }

    public void openPrivateChat(String targetUsername, String targetDisplayName) {
        if (targetUsername == null || targetUsername.equalsIgnoreCase(currentUsername)) return;
        PrivateChatWindow win = openChatWindows.get(targetUsername);
        if (win == null) {
            win = new PrivateChatWindow(this, targetUsername, targetDisplayName);
            openChatWindows.put(targetUsername, win);
            win.show();
        } else {
            win.toFront();
        }
    }

    public void onPrivateChatClosed(String targetUsername) {
        if (targetUsername != null) {
            openChatWindows.remove(targetUsername);
        }
    }

    public void requestChatInbox() {
        if (webSocketClient != null && webSocketClient.isConnected()) {
            webSocketClient.send(new Envelope(MessageType.CHAT_INBOX_REQUEST));
        }
    }

    public void markConversationRead(String targetUsername) {
        if (targetUsername == null || webSocketClient == null || !webSocketClient.isConnected()) return;
        Envelope request = new Envelope(MessageType.CHAT_MARK_READ);
        request.put("targetUsername", targetUsername);
        webSocketClient.send(request);
    }

    public void showLobbyScreen() {
        if (lobbyScreen == null) {
            lobbyScreen = new LobbyScreen(this);
        }
        Scene scene = lobbyScreen.createScene();
        applyTheme(scene);
        primaryStage.setScene(scene);

        // Yêu cầu máy chủ gửi lại dữ liệu phòng, người chơi và bạn bè mới nhất
        if (webSocketClient != null && webSocketClient.isConnected()) {
            webSocketClient.send(new Envelope(MessageType.ROOM_LIST));
            webSocketClient.send(new Envelope(MessageType.PLAYER_LIST));
            webSocketClient.send(new Envelope(MessageType.FRIEND_LIST));
            webSocketClient.send(new Envelope(MessageType.FRIEND_PENDING_LIST));
            requestChatInbox();
        }
    }

    public boolean isAdmin() { return "ADMIN".equals(currentRole); }

    public void showAdminScreen() {
        if (!isAdmin()) return;
        if (adminScreen == null) adminScreen = new AdminScreen(this);
        primaryStage.setScene(adminScreen.createScene());
        adminScreen.refresh();
    }

    public void showRoomScreen(String roomId, String roomName) {
        roomScreen = new com.nhom8.client.ui.RoomScreen(this, roomId, roomName);
        Scene scene = roomScreen.createScene();
        applyTheme(scene);
        primaryStage.setScene(scene);
        if (lastRoomUpdate != null && roomId.equals(lastRoomUpdate.getString("roomId"))) {
            roomScreen.handleMessage(lastRoomUpdate);
        }
    }

    public com.nhom8.client.ui.RoomScreen getRoomScreen() {
        return roomScreen;
    }

    public void showGameScreen(String roomId) {
        gameScreen = new GameScreen(this, roomId);
        Scene scene = gameScreen.createScene();
        applyTheme(scene);
        primaryStage.setScene(scene);
    }

    @Override
    public void stop() {
        if (webSocketClient != null) {
            webSocketClient.close();
        }
    }

    public static void main(String[] args) {
        launch(args);
    }
}
