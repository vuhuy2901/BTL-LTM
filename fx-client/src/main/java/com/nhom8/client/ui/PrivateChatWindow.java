package com.nhom8.client.ui;

import com.fasterxml.jackson.databind.JsonNode;
import com.nhom8.client.MainApplication;
import com.nhom8.common.message.Envelope;
import com.nhom8.common.message.MessageType;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.Stage;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

/**
 * Cửa sổ nhắn tin riêng 1-1 với bạn bè ở sảnh chờ (Private Chat).
 * Phong cách nét vẽ tay Sketch Doodle chuẩn mực của game.
 * Hỗ trợ:
 * - Tự động tải lịch sử chat từ máy chủ
 * - Nhắn tin thời gian thực qua WebSocket
 * - Hiển thị bong bóng thoại trực quan
 */
public class PrivateChatWindow {

    private final MainApplication app;
    private final String targetUsername;
    private final String targetDisplayName;
    private final Stage stage;

    private VBox messageContainer;
    private ScrollPane scrollPane;
    private TextField inputField;
    private Button sendBtn;
    private Label statusLabel;

    public PrivateChatWindow(MainApplication app, String targetUsername, String targetDisplayName) {
        this.app = app;
        this.targetUsername = targetUsername;
        this.targetDisplayName = (targetDisplayName != null && !targetDisplayName.isEmpty()) ? targetDisplayName : targetUsername;
        this.stage = new Stage();
        initUI();
    }

    private void initUI() {
        BorderPane root = new BorderPane();
        root.setStyle("-fx-background-color: #FDFBF7;");

        // --- TOP BAR ---
        HBox topBar = new HBox(12);
        topBar.setAlignment(Pos.CENTER_LEFT);
        topBar.setPadding(new Insets(12, 16, 12, 16));
        topBar.setStyle("-fx-background-color: #F5C800; -fx-border-color: -fx-ink; -fx-border-width: 0 0 2 0;");

        Label avatarLbl = new Label("🎨");
        avatarLbl.setStyle("-fx-font-size: 26px;");

        VBox titleBox = new VBox(2);
        Label nameLbl = new Label(targetDisplayName);
        nameLbl.setStyle("-fx-font-family: 'Patrick Hand'; -fx-font-size: 20px; -fx-font-weight: bold; -fx-text-fill: -fx-ink;");

        statusLabel = new Label("💬 Chat riêng với @" + targetUsername);
        statusLabel.setStyle("-fx-font-family: 'Patrick Hand'; -fx-font-size: 13px; -fx-text-fill: #333333;");

        titleBox.getChildren().addAll(nameLbl, statusLabel);

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        Button refreshBtn = new Button("🔄");
        refreshBtn.setTooltip(new Tooltip("Tải lại lịch sử tin nhắn"));
        refreshBtn.getStyleClass().add("pencil-button-sm");
        refreshBtn.setStyle("-fx-padding: 2 10; -fx-font-size: 16px;");
        refreshBtn.setOnAction(e -> requestHistory());

        topBar.getChildren().addAll(avatarLbl, titleBox, spacer, refreshBtn);
        root.setTop(topBar);

        // --- CENTER: MESSAGES CONTAINER IN SCROLL PANE ---
        messageContainer = new VBox(10);
        messageContainer.setPadding(new Insets(15));
        messageContainer.setStyle("-fx-background-color: #FDFBF7;");

        scrollPane = new ScrollPane(messageContainer);
        scrollPane.setFitToWidth(true);
        scrollPane.setStyle("-fx-background: #FDFBF7; -fx-background-color: #FDFBF7; -fx-border-color: transparent;");
        // Tự động cuộn xuống dưới cùng khi có tin nhắn mới
        messageContainer.heightProperty().addListener((obs, oldVal, newVal) -> {
            Platform.runLater(() -> scrollPane.setVvalue(1.0));
        });
        root.setCenter(scrollPane);

        // --- BOTTOM: INPUT BAR ---
        HBox inputBar = new HBox(10);
        inputBar.setAlignment(Pos.CENTER);
        inputBar.setPadding(new Insets(12, 16, 12, 16));
        inputBar.setStyle("-fx-background-color: white; -fx-border-color: -fx-ink; -fx-border-width: 2 0 0 0;");

        inputField = new TextField();
        inputField.setPromptText("Nhập tin nhắn cho " + targetDisplayName + "... (Enter để gửi)");
        inputField.setStyle("-fx-font-family: 'Patrick Hand'; -fx-font-size: 16px; -fx-padding: 8 12; -fx-border-color: -fx-ink; -fx-border-width: 2;");
        HBox.setHgrow(inputField, Priority.ALWAYS);
        inputField.setOnAction(e -> sendMessage());

        sendBtn = new Button("GỬI ➔");
        sendBtn.getStyleClass().add("pencil-button-sm");
        sendBtn.setStyle("-fx-padding: 6 16;");
        sendBtn.setOnAction(e -> sendMessage());

        inputBar.getChildren().addAll(inputField, sendBtn);
        root.setBottom(inputBar);

        Scene scene = new Scene(root, 440, 520);
        MainApplication.applyTheme(scene);
        stage.setScene(scene);
        stage.setTitle("💬 Nhắn tin: " + targetDisplayName + " (@" + targetUsername + ")");

        stage.setOnCloseRequest(e -> app.onPrivateChatClosed(targetUsername));
        stage.focusedProperty().addListener((observable, wasFocused, isFocused) -> {
            if (isFocused) app.markConversationRead(targetUsername);
        });

        // Tải lịch sử tin nhắn
        requestHistory();
    }

    public void show() {
        stage.show();
        stage.toFront();
        Platform.runLater(() -> inputField.requestFocus());
    }

    public void toFront() {
        stage.toFront();
        Platform.runLater(() -> inputField.requestFocus());
    }

    public Stage getStage() {
        return stage;
    }

    public boolean isShowing() {
        return stage.isShowing() && stage.isFocused();
    }

    public String getTargetUsername() {
        return targetUsername;
    }

    public void requestHistory() {
        if (app.getWsClient() != null && app.getWsClient().isConnected()) {
            Envelope env = new Envelope(MessageType.CHAT_HISTORY_REQUEST);
            env.put("targetUsername", targetUsername);
            app.getWsClient().send(env);
        }
    }

    private void sendMessage() {
        String text = inputField.getText();
        if (text == null || text.trim().isEmpty()) return;
        text = text.trim();

        if (app.getWsClient() != null && app.getWsClient().isConnected()) {
            Envelope env = new Envelope(MessageType.DIRECT_MESSAGE_SEND);
            env.put("targetUsername", targetUsername);
            env.put("content", text);
            app.getWsClient().send(env);
            inputField.clear();
        } else {
            addSystemMessage("⚠ Mất kết nối tới máy chủ!");
        }
    }

    public void handleReceiveMessage(Envelope env) {
        Platform.runLater(() -> {
            String content = env.getString("content");
            if (content == null) content = env.getContent();
            boolean isSelf = env.getBoolean("isSelf", false);
            String time = formatTime(env.getString("timestamp"));
            String sender = env.getString("senderDisplayName");
            if (sender == null) sender = env.getString("senderUsername");
            if (sender == null) sender = isSelf ? "Bạn" : targetDisplayName;

            addBubble(sender, content, time, isSelf);
        });
    }

    public void handleHistoryResponse(Envelope env) {
        Platform.runLater(() -> {
            messageContainer.getChildren().clear();
            addSystemMessage("📜 Đã tải lịch sử trò chuyện với " + targetDisplayName);

            if (env.getData() != null && env.getData().has("history")) {
                JsonNode historyNode = env.getData().get("history");
                if (historyNode.isArray()) {
                    int startIndex = Math.max(0, historyNode.size() - 100);
                    for (int i = startIndex; i < historyNode.size(); i++) {
                        JsonNode item = historyNode.get(i);
                        String sender = item.has("senderDisplayName") ? item.get("senderDisplayName").asText() : "";
                        String content = item.has("content") ? item.get("content").asText() : "";
                        String time = item.has("timestamp") ? formatTime(item.get("timestamp").asText()) : "";
                        boolean isSelf = item.has("isSelf") && item.get("isSelf").asBoolean();
                        addBubble(sender, content, time, isSelf);
                    }
                }
            }

            if (messageContainer.getChildren().size() <= 1) {
                addSystemMessage("Chưa có tin nhắn nào. Hãy gửi lời chào đầu tiên! 👋");
            }
        });
    }

    private void addBubble(String sender, String content, String time, boolean isSelf) {
        HBox row = new HBox();
        row.setAlignment(isSelf ? Pos.CENTER_RIGHT : Pos.CENTER_LEFT);

        VBox bubble = new VBox(4);
        bubble.setMaxWidth(300);
        bubble.setPadding(new Insets(8, 12, 8, 12));

        if (isSelf) {
            bubble.setStyle("-fx-background-color: -fx-ink; -fx-background-radius: 12 12 2 12; -fx-effect: dropshadow(three-pass-box, rgba(0,0,0,0.15), 4, 0, 0, 1);");
            Label nameLabel = new Label("Bạn  •  " + time);
            nameLabel.setStyle("-fx-font-family: 'Patrick Hand'; -fx-font-size: 11px; -fx-text-fill: #F5C800;");

            Label contentLabel = new Label(content);
            contentLabel.setWrapText(true);
            contentLabel.setStyle("-fx-font-family: 'Patrick Hand'; -fx-font-size: 16px; -fx-text-fill: white;");

            bubble.getChildren().addAll(nameLabel, contentLabel);
        } else {
            bubble.setStyle("-fx-background-color: white; -fx-border-color: -fx-ink; -fx-border-width: 2; -fx-background-radius: 12 12 12 2; -fx-border-radius: 12 12 12 2; -fx-effect: dropshadow(three-pass-box, rgba(0,0,0,0.15), 4, 0, 0, 1);");
            Label nameLabel = new Label(sender + "  •  " + time);
            nameLabel.setStyle("-fx-font-family: 'Patrick Hand'; -fx-font-size: 11px; -fx-text-fill: #666666; -fx-font-weight: bold;");

            Label contentLabel = new Label(content);
            contentLabel.setWrapText(true);
            contentLabel.setStyle("-fx-font-family: 'Patrick Hand'; -fx-font-size: 16px; -fx-text-fill: -fx-ink;");

            bubble.getChildren().addAll(nameLabel, contentLabel);
        }

        row.getChildren().add(bubble);
        messageContainer.getChildren().add(row);
        while (messageContainer.getChildren().size() > 100) {
            messageContainer.getChildren().remove(0);
        }
    }

    private void addSystemMessage(String text) {
        HBox row = new HBox();
        row.setAlignment(Pos.CENTER);
        Label label = new Label(text);
        label.setStyle("-fx-font-family: 'Patrick Hand'; -fx-font-size: 13px; -fx-text-fill: #777777; -fx-background-color: #EFEFEF; -fx-padding: 3 10; -fx-background-radius: 10;");
        row.getChildren().add(label);
        messageContainer.getChildren().add(row);
    }

    private String formatTime(String rawTimestamp) {
        if (rawTimestamp == null || rawTimestamp.isEmpty()) {
            return LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm"));
        }
        try {
            if (rawTimestamp.contains("T")) {
                String timePart = rawTimestamp.substring(rawTimestamp.indexOf("T") + 1);
                if (timePart.length() >= 5) {
                    return timePart.substring(0, 5);
                }
            }
        } catch (Exception ignored) {}
        return LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm"));
    }
}
