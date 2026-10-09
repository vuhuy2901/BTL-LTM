package com.nhom8.client.ui;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nhom8.client.MainApplication;
import com.nhom8.common.dto.StrokeDTO;
import com.nhom8.common.message.Envelope;
import com.nhom8.common.message.MessageType;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.shape.StrokeLineCap;
import javafx.scene.shape.StrokeLineJoin;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class GameScreen {
    private final MainApplication app;
    private final String roomId;
    private final ObservableList<String> chatMessages;
    private final ObservableList<String> playerList;

    private Canvas canvas;
    private GraphicsContext gc;

    // Trạng thái phiên đấu
    private boolean isDrawingPhase = false;
    private boolean isGuessingPhase = false;
    private boolean hasSubmittedDrawing = false;
    private String currentPainter = "";
    private String mySelectedTopic = "";

    // Bộ nhớ vẽ tranh của bản thân
    private final List<StrokeDTO> myStrokes = new ArrayList<>();
    private final List<Double> currentXPoints = new ArrayList<>();
    private final List<Double> currentYPoints = new ArrayList<>();
    private Color currentColor = Color.BLACK;
    private double currentWidth = 3.0;

    // UI Controls
    private Label topInfoLabel;
    private Label timerLabel;
    private Label phaseLabel;
    private HBox toolbar;
    private Button submitDrawingBtn;
    private TextField inputField;
    private Label guessFeedbackLabel;
    private ListView<String> chatView;

    // Overlays
    private VBox topicSelectionBox;
    private HBox topicButtonBox;
    private Label topicTimerLabel;

    private VBox roundResultBox;
    private Label roundResultTitle;
    private Label roundResultDetails;

    private VBox gameOverBox;
    private VBox gameOverScoresBox;

    private Scene scene;
    private final ObjectMapper mapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    public GameScreen(MainApplication app, String roomId) {
        this.app = app;
        this.roomId = roomId;
        this.chatMessages = FXCollections.observableArrayList();
        this.playerList = FXCollections.observableArrayList();
    }

    public Scene createScene() {
        if (scene != null) return scene;

        BorderPane root = new BorderPane();
        root.setStyle("-fx-background-color: #F5B800;");

        // --- TOP BAR ---
        HBox topBox = new HBox(20);
        topBox.setAlignment(Pos.CENTER_LEFT);
        topBox.setPadding(new Insets(12, 20, 12, 20));
        topBox.setStyle("-fx-background-color: rgba(0, 0, 0, 0.15);");

        Button leaveBtn = new Button("⬅ Thoát");
        leaveBtn.setStyle("-fx-background-color: white; -fx-text-fill: #D8000C; -fx-font-weight: bold; -fx-background-radius: 6; -fx-cursor: hand;");
        leaveBtn.setOnAction(e -> {
            app.getWsClient().send(new Envelope(MessageType.ROOM_LEAVE));
            app.showLobbyScreen();
        });

        topInfoLabel = new Label("Phòng: " + roomId + " | Đang khởi tạo...");
        topInfoLabel.setStyle("-fx-font-size: 18px; -fx-font-weight: bold; -fx-text-fill: white;");

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        phaseLabel = new Label("Giai đoạn: Chuẩn bị");
        phaseLabel.setStyle("-fx-font-size: 14px; -fx-font-weight: bold; -fx-text-fill: white; -fx-background-color: rgba(0,0,0,0.2); -fx-padding: 4 10; -fx-background-radius: 10;");

        timerLabel = new Label("⏱ --s");
        timerLabel.setStyle("-fx-font-size: 18px; -fx-font-weight: bold; -fx-text-fill: white; -fx-background-color: #D8000C; -fx-padding: 4 12; -fx-background-radius: 12;");

        topBox.getChildren().addAll(leaveBtn, topInfoLabel, spacer, phaseLabel, timerLabel);
        root.setTop(topBox);

        // --- CENTER: CANVAS + TOOLBAR ---
        VBox centerBox = new VBox(10);
        centerBox.setAlignment(Pos.CENTER);
        centerBox.setPadding(new Insets(10));

        // Khung Canvas với viền nổi bật
        StackPane canvasContainer = new StackPane();
        canvasContainer.setStyle("-fx-background-color: white; -fx-border-color: #333333; -fx-border-width: 3; -fx-border-radius: 8; -fx-background-radius: 8; -fx-effect: dropshadow(three-pass-box, rgba(0,0,0,0.2), 8, 0, 0, 2);");
        canvas = new Canvas(640, 460);
        gc = canvas.getGraphicsContext2D();
        gc.setFill(Color.WHITE);
        gc.fillRect(0, 0, canvas.getWidth(), canvas.getHeight());
        gc.setLineCap(StrokeLineCap.ROUND);
        gc.setLineJoin(StrokeLineJoin.ROUND);

        setupCanvasEvents();
        canvasContainer.getChildren().add(canvas);

        // Toolbar vẽ
        toolbar = new HBox(12);
        toolbar.setAlignment(Pos.CENTER);
        toolbar.setPadding(new Insets(8, 15, 8, 15));
        toolbar.setStyle("-fx-background-color: white; -fx-background-radius: 8;");

        ColorPicker colorPicker = new ColorPicker(Color.BLACK);
        colorPicker.setStyle("-fx-cursor: hand;");
        colorPicker.setOnAction(e -> currentColor = colorPicker.getValue());

        Slider widthSlider = new Slider(1, 25, 3);
        widthSlider.setPrefWidth(100);
        widthSlider.valueProperty().addListener((obs, oldVal, newVal) -> currentWidth = newVal.doubleValue());

        Button clearBtn = new Button("🗑 Xóa bảng");
        clearBtn.setStyle("-fx-background-color: #ff4d4f; -fx-text-fill: white; -fx-font-weight: bold; -fx-background-radius: 6; -fx-cursor: hand;");
        clearBtn.setOnAction(e -> clearCanvas());

        submitDrawingBtn = new Button("📤 Nộp tranh");
        submitDrawingBtn.setStyle("-fx-background-color: #52c41a; -fx-text-fill: white; -fx-font-weight: bold; -fx-font-size: 13px; -fx-background-radius: 6; -fx-cursor: hand; -fx-padding: 6 16;");
        submitDrawingBtn.setOnAction(e -> submitMyDrawing());

        toolbar.getChildren().addAll(
                new Label("Màu:"), colorPicker,
                new Label("Cỡ bút:"), widthSlider,
                clearBtn,
                submitDrawingBtn
        );
        toolbar.setVisible(false);

        centerBox.getChildren().addAll(canvasContainer, toolbar);
        root.setCenter(centerBox);

        // --- LEFT: PLAYERS & SCORES ---
        VBox leftBox = new VBox(10);
        leftBox.setPadding(new Insets(10));
        leftBox.setPrefWidth(190);

        Label pLabel = new Label("🏆 Bảng điểm:");
        pLabel.setStyle("-fx-font-size: 16px; -fx-font-weight: bold; -fx-text-fill: white;");

        ListView<String> pList = new ListView<>(playerList);
        pList.setPrefHeight(480);
        pList.setStyle("-fx-background-radius: 8;");

        leftBox.getChildren().addAll(pLabel, pList);
        root.setLeft(leftBox);

        // --- RIGHT: CHAT & GUESS INPUT ---
        VBox rightBox = new VBox(10);
        rightBox.setPadding(new Insets(10));
        rightBox.setPrefWidth(270);

        Label chatTitle = new Label("💬 Trò chuyện / Đoán:");
        chatTitle.setStyle("-fx-font-size: 16px; -fx-font-weight: bold; -fx-text-fill: white;");

        chatView = new ListView<>(chatMessages);
        chatView.setPrefHeight(420);
        chatView.setStyle("-fx-background-radius: 8;");

        guessFeedbackLabel = new Label("");
        guessFeedbackLabel.setStyle("-fx-font-weight: bold; -fx-font-size: 12px; -fx-text-fill: white;");

        HBox inputBox = new HBox(8);
        inputField = new TextField();
        inputField.setPromptText("Nhập đáp án hoặc chat...");
        HBox.setHgrow(inputField, Priority.ALWAYS);
        inputField.setOnAction(e -> handleInputSubmit());

        Button sendBtn = new Button("Gửi");
        sendBtn.setStyle("-fx-background-color: white; -fx-text-fill: #F5B800; -fx-font-weight: bold; -fx-background-radius: 6; -fx-cursor: hand;");
        sendBtn.setOnAction(e -> handleInputSubmit());

        inputBox.getChildren().addAll(inputField, sendBtn);

        rightBox.getChildren().addAll(chatTitle, chatView, guessFeedbackLabel, inputBox);
        root.setRight(rightBox);

        // --- OVERLAY 1: TOPIC SELECTION ---
        topicSelectionBox = new VBox(20);
        topicSelectionBox.setAlignment(Pos.CENTER);
        topicSelectionBox.setStyle("-fx-background-color: rgba(0, 0, 0, 0.85);");
        topicSelectionBox.setVisible(false);

        Label lblTopicTitle = new Label("🎨 Hãy chọn 1 chủ đề để vẽ:");
        lblTopicTitle.setStyle("-fx-font-size: 26px; -fx-text-fill: white; -fx-font-weight: bold;");

        topicTimerLabel = new Label("Thời gian còn lại: 10s");
        topicTimerLabel.setStyle("-fx-font-size: 18px; -fx-text-fill: #F5B800; -fx-font-weight: bold;");

        topicButtonBox = new HBox(15);
        topicButtonBox.setAlignment(Pos.CENTER);

        topicSelectionBox.getChildren().addAll(lblTopicTitle, topicTimerLabel, topicButtonBox);

        // --- OVERLAY 2: ROUND RESULT ---
        roundResultBox = new VBox(15);
        roundResultBox.setAlignment(Pos.CENTER);
        roundResultBox.setStyle("-fx-background-color: rgba(0, 0, 0, 0.85); -fx-padding: 30;");
        roundResultBox.setVisible(false);

        roundResultTitle = new Label("🔔 Kết quả lượt đoán");
        roundResultTitle.setStyle("-fx-font-size: 24px; -fx-text-fill: white; -fx-font-weight: bold;");

        roundResultDetails = new Label("");
        roundResultDetails.setStyle("-fx-font-size: 15px; -fx-text-fill: #E0E0E0; -fx-alignment: center;");

        roundResultBox.getChildren().addAll(roundResultTitle, roundResultDetails);

        // --- OVERLAY 3: GAME OVER / MATCH SUMMARY ---
        gameOverBox = new VBox(20);
        gameOverBox.setAlignment(Pos.CENTER);
        gameOverBox.setStyle("-fx-background-color: rgba(0, 0, 0, 0.9); -fx-padding: 40;");
        gameOverBox.setVisible(false);

        Label gameOverTitle = new Label("🏆 TỔNG KẾT TRẬN ĐẤU 🏆");
        gameOverTitle.setStyle("-fx-font-size: 30px; -fx-text-fill: #F5B800; -fx-font-weight: bold;");

        gameOverScoresBox = new VBox(10);
        gameOverScoresBox.setAlignment(Pos.CENTER);

        Button returnLobbyBtn = new Button("Quay lại sảnh chờ");
        returnLobbyBtn.setStyle("-fx-background-color: #52c41a; -fx-text-fill: white; -fx-font-size: 16px; -fx-font-weight: bold; -fx-padding: 10 25; -fx-background-radius: 8; -fx-cursor: hand;");
        returnLobbyBtn.setOnAction(e -> {
            app.showLobbyScreen();
        });

        gameOverBox.getChildren().addAll(gameOverTitle, gameOverScoresBox, returnLobbyBtn);

        StackPane mainStack = new StackPane(root, topicSelectionBox, roundResultBox, gameOverBox);
        scene = new Scene(mainStack, 1150, 650);
        return scene;
    }

    public Scene getScene() {
        return scene;
    }

    private void setupCanvasEvents() {
        canvas.setOnMousePressed(e -> {
            if (!isDrawingPhase || hasSubmittedDrawing) return;
            currentXPoints.clear();
            currentYPoints.clear();
            gc.setStroke(currentColor);
            gc.setLineWidth(currentWidth);
            // Vẽ chấm nếu chỉ click chuột tại 1 điểm
            gc.strokeLine(e.getX(), e.getY(), e.getX(), e.getY());
            gc.beginPath();
            gc.moveTo(e.getX(), e.getY());
            currentXPoints.add(e.getX());
            currentYPoints.add(e.getY());
        });

        canvas.setOnMouseDragged(e -> {
            if (!isDrawingPhase || hasSubmittedDrawing) return;
            gc.lineTo(e.getX(), e.getY());
            gc.stroke();
            currentXPoints.add(e.getX());
            currentYPoints.add(e.getY());

            if (currentXPoints.size() >= 15) {
                double lastX = currentXPoints.get(currentXPoints.size() - 1);
                double lastY = currentYPoints.get(currentYPoints.size() - 1);
                sendStrokeChunk();
                // Nối tiếp điểm cuối để đường vẽ không bị đứt đoạn
                currentXPoints.add(lastX);
                currentYPoints.add(lastY);
            }
        });

        canvas.setOnMouseReleased(e -> {
            if (!isDrawingPhase || hasSubmittedDrawing) return;
            gc.lineTo(e.getX(), e.getY());
            gc.stroke();
            gc.closePath();
            currentXPoints.add(e.getX());
            currentYPoints.add(e.getY());
            sendStrokeChunk();
        });
    }

    private void sendStrokeChunk() {
        if (currentXPoints.isEmpty()) return;

        StrokeDTO stroke = new StrokeDTO(
                StrokeDTO.TOOL_BRUSH,
                toHexString(currentColor),
                currentWidth,
                new ArrayList<>(currentXPoints),
                new ArrayList<>(currentYPoints)
        );
        myStrokes.add(stroke);

        Envelope env = new Envelope(MessageType.STROKE_BATCH);
        env.put("drawData", Collections.singletonList(stroke));
        app.getWsClient().send(env);

        currentXPoints.clear();
        currentYPoints.clear();
    }

    private void clearCanvas() {
        if (!isDrawingPhase || hasSubmittedDrawing) return;
        gc.setFill(Color.WHITE);
        gc.fillRect(0, 0, canvas.getWidth(), canvas.getHeight());

        StrokeDTO clearDto = new StrokeDTO();
        clearDto.setTool(StrokeDTO.TOOL_CLEAR);
        clearDto.setClear(true);
        myStrokes.add(clearDto);

        Envelope env = new Envelope(MessageType.STROKE_BATCH);
        env.put("drawData", Collections.singletonList(clearDto));
        app.getWsClient().send(env);
    }

    private void submitMyDrawing() {
        if (!isDrawingPhase || hasSubmittedDrawing) return;
        hasSubmittedDrawing = true;
        submitDrawingBtn.setDisable(true);
        submitDrawingBtn.setText("Đã nộp ✔");
        toolbar.setDisable(true);

        Envelope submitEnv = new Envelope(MessageType.DRAW_SUBMIT);
        submitEnv.put("drawData", myStrokes);
        app.getWsClient().send(submitEnv);

        topInfoLabel.setText("Đã nộp bức tranh! Đang chờ người chơi khác hoàn thành...");
    }

    private void handleInputSubmit() {
        String text = inputField.getText();
        if (text == null || text.trim().isEmpty()) return;
        text = text.trim();

        if (isGuessingPhase) {
            if (currentPainter.equals(app.getCurrentUsername())) {
                sendChat(text);
            } else {
                Envelope guessEnv = new Envelope(MessageType.GUESS_SUBMIT);
                guessEnv.setContent(text);
                app.getWsClient().send(guessEnv);
                guessFeedbackLabel.setText("Đã gửi đáp án: \"" + text + "\"");
                guessFeedbackLabel.setStyle("-fx-text-fill: white; -fx-font-weight: bold;");
            }
        } else {
            sendChat(text);
        }
        inputField.clear();
    }

    private void sendChat(String text) {
        Envelope chatEnv = new Envelope(MessageType.ROOM_CHAT);
        chatEnv.put("content", text);
        app.getWsClient().send(chatEnv);
    }

    public void handleMessage(Envelope env) {
        Platform.runLater(() -> {
            switch (env.getType()) {
                case TOPIC_OPTIONS:
                    onTopicOptions(env);
                    break;
                case TOPIC_CONFIRMED:
                    onTopicConfirmed(env);
                    break;
                case DRAW_START:
                case DRAW_PHASE_START:
                    onDrawStart(env);
                    break;
                case GUESS_START:
                case GUESS_PHASE_START:
                    onGuessStart(env);
                    break;
                case PAINTING_DISPLAY:
                    onPaintingDisplay(env);
                    break;
                case GUESS_RESULT:
                    onGuessResult(env);
                    break;
                case GUESS_LOG:
                    chatMessages.add("⚡ " + env.getContent());
                    scrollToBottom();
                    break;
                case GUESS_RANKING_UPDATE:
                    onGuessRankingUpdate(env);
                    break;
                case ROUND_RESULT:
                    onRoundResult(env);
                    break;
                case GAME_RESULT:
                case MATCH_SUMMARY:
                    onGameOver(env);
                    break;
                case TIMER_UPDATE:
                    onTimerUpdate(env);
                    break;
                case ROOM_CHAT_RECEIVE:
                case ROOM_CHAT:
                    String sender = env.getString("senderDisplayName");
                    if (sender == null) sender = env.getString("senderUsername");
                    if (sender == null) sender = env.getSender() != null ? env.getSender() : "Ẩn danh";
                    String content = env.getString("content");
                    if (content == null) content = env.getContent();
                    chatMessages.add(sender + ": " + content);
                    scrollToBottom();
                    break;
                case ROOM_UPDATE:
                    if (env.getData() != null && env.getData().has("players")) {
                        playerList.clear();
                        env.getData().get("players").forEach(p -> {
                            String name = p.has("displayName") ? p.get("displayName").asText() : p.get("username").asText();
                            int score = p.has("score") ? p.get("score").asInt() : 0;
                            playerList.add(name + " (" + score + " đ)");
                        });
                    }
                    break;
                default:
                    break;
            }
        });
    }

    private void onTopicOptions(Envelope env) {
        topicButtonBox.getChildren().clear();
        topicSelectionBox.setVisible(true);
        phaseLabel.setText("Giai đoạn: Chọn chủ đề");

        int timeLimit = env.getInt("timeLimit", 10);
        topicTimerLabel.setText("Thời gian còn lại: " + timeLimit + "s");

        if (env.getData() != null && env.getData().has("topics")) {
            env.getData().get("topics").forEach(tNode -> {
                String topic = tNode.asText();
                Button btn = new Button(topic);
                btn.setStyle("-fx-font-size: 16px; -fx-padding: 12 24; -fx-background-color: white; -fx-text-fill: #F5B800; -fx-font-weight: bold; -fx-background-radius: 8; -fx-cursor: hand;");
                btn.setOnAction(e -> {
                    Envelope selEnv = new Envelope(MessageType.TOPIC_SELECT);
                    selEnv.put("topic", topic);
                    app.getWsClient().send(selEnv);
                    topicSelectionBox.setVisible(false);
                    mySelectedTopic = topic;
                    topInfoLabel.setText("Bạn đã chọn: " + topic);
                });
                topicButtonBox.getChildren().add(btn);
            });
        }
    }

    private void onTopicConfirmed(Envelope env) {
        topicSelectionBox.setVisible(false);
        mySelectedTopic = env.getString("topic");
        topInfoLabel.setText("Chủ đề vẽ của bạn: " + mySelectedTopic);
    }

    private void onDrawStart(Envelope env) {
        roundResultBox.setVisible(false);
        isDrawingPhase = true;
        isGuessingPhase = false;
        hasSubmittedDrawing = false;
        myStrokes.clear();

        gc.setFill(Color.WHITE);
        gc.fillRect(0, 0, canvas.getWidth(), canvas.getHeight());

        toolbar.setVisible(true);
        toolbar.setDisable(false);
        submitDrawingBtn.setDisable(false);
        submitDrawingBtn.setText("📤 Nộp tranh");

        phaseLabel.setText("Giai đoạn: Vẽ tranh");
        topInfoLabel.setText("Vẽ chủ đề của bạn: " + (mySelectedTopic != null && !mySelectedTopic.isEmpty() ? mySelectedTopic : "Hãy vẽ thật đẹp!"));
    }

    private void onGuessStart(Envelope env) {
        roundResultBox.setVisible(false);
        isDrawingPhase = false;
        isGuessingPhase = true;
        toolbar.setVisible(false);
        guessFeedbackLabel.setText("");

        currentPainter = env.getString("painterUsername");
        int index = env.getInt("paintingIndex", 1);
        int total = env.getInt("totalPaintings", 1);

        phaseLabel.setText("Giai đoạn: Đoán tranh (" + index + "/" + total + ")");

        if (currentPainter.equals(app.getCurrentUsername())) {
            topInfoLabel.setText("🖼 Tranh của BẠN đang được chiếu! (Không được đoán)");
            inputField.setPromptText("Chat với mọi người...");
            guessFeedbackLabel.setText("Mọi người đang đoán tranh của bạn...");
            guessFeedbackLabel.setStyle("-fx-text-fill: #FFF3CD; -fx-font-weight: bold;");
        } else {
            topInfoLabel.setText("🔍 Đang đoán tranh của: " + currentPainter);
            inputField.setPromptText("Nhập từ khóa đoán vào đây...");
            guessFeedbackLabel.setText("Hãy nhanh tay đoán từ khóa!");
            guessFeedbackLabel.setStyle("-fx-text-fill: white; -fx-font-weight: bold;");
        }
    }

    private void onPaintingDisplay(Envelope env) {
        gc.setFill(Color.WHITE);
        gc.fillRect(0, 0, canvas.getWidth(), canvas.getHeight());

        String hint = env.getString("hint");
        if (hint != null && !hint.isEmpty() && !currentPainter.equals(app.getCurrentUsername())) {
            topInfoLabel.setText("🔍 Đoán tranh của: " + currentPainter + "  |  Gợi ý: " + hint);
        }

        if (env.getData() != null && env.getData().has("drawData")) {
            JsonNode drawDataNode = env.getData().get("drawData");
            for (JsonNode strokeNode : drawDataNode) {
                try {
                    StrokeDTO stroke = mapper.treeToValue(strokeNode, StrokeDTO.class);
                    drawStrokeFromNetwork(stroke);
                } catch (Exception ex) {
                    ex.printStackTrace();
                }
            }
        }
    }

    private void onGuessResult(Envelope env) {
        boolean correct = env.getBoolean("correct", false);
        if (correct) {
            int rank = env.getInt("rank", 1);
            int points = env.getInt("points", 0);
            guessFeedbackLabel.setText("🎉 CHÍNH XÁC! Hạng #" + rank + " (+" + points + " điểm)");
            guessFeedbackLabel.setStyle("-fx-text-fill: #52c41a; -fx-font-weight: bold; -fx-font-size: 13px;");
        } else {
            guessFeedbackLabel.setText("❌ Sai rồi! Hãy thử lại...");
            guessFeedbackLabel.setStyle("-fx-text-fill: #ff4d4f; -fx-font-weight: bold; -fx-font-size: 13px;");
        }
    }

    private void onGuessRankingUpdate(Envelope env) {
        if (env.getData() != null && env.getData().has("rankings")) {
            JsonNode rankings = env.getData().get("rankings");
            if (rankings.size() > 0) {
                JsonNode latest = rankings.get(rankings.size() - 1);
                String u = latest.has("username") ? latest.get("username").asText() : "";
                int pts = latest.has("points") ? latest.get("points").asInt() : 0;
                int r = latest.has("rank") ? latest.get("rank").asInt() : 1;
                chatMessages.add("🏆 " + u + " đã đoán đúng hạng #" + r + " (+" + pts + " đ)");
                scrollToBottom();
            }
        }
    }

    private void onRoundResult(Envelope env) {
        String topic = env.getString("topic");
        String painter = env.getString("painterUsername");
        int painterPoints = env.getInt("painterPoints", 0);
        int correctCount = env.getInt("correctCount", 0);
        int total = env.getInt("totalGuessers", 0);
        boolean earlyFinish = env.getBoolean("earlyFinish", false) || (total > 0 && correctCount == total);

        if (earlyFinish) {
            roundResultTitle.setText("🎉 Đã có người đoán đúng! Đáp án: " + topic);
        } else {
            roundResultTitle.setText("⏰ Hết thời gian! Đáp án là: " + topic);
        }

        StringBuilder details = new StringBuilder();
        details.append("🎨 Người vẽ: ").append(painter)
               .append(painterPoints >= 0 ? " (+" : " (")
               .append(painterPoints).append(" điểm)\n\n");

        details.append("💡 Người đoán đúng (").append(correctCount).append("/").append(total).append("):\n");

        if (env.getData() != null && env.getData().has("guessDetails") && env.getData().get("guessDetails").size() > 0) {
            JsonNode guessDetails = env.getData().get("guessDetails");
            int rankCounter = 1;
            for (JsonNode g : guessDetails) {
                String u = g.has("username") ? g.get("username").asText() : "";
                int pts = g.has("points") ? g.get("points").asInt() : 0;
                String timeStr = g.has("timeFormatted") ? g.get("timeFormatted").asText() : "";
                String medal = (rankCounter == 1) ? "🥇" : (rankCounter == 2) ? "🥈" : (rankCounter == 3) ? "🥉" : "•";
                details.append("   ").append(medal).append(" ").append(u)
                       .append(" (+").append(pts).append(" điểm");
                if (!timeStr.isEmpty()) {
                    details.append(", ").append(timeStr);
                }
                details.append(")\n");
                rankCounter++;
            }
        } else {
            details.append("   (Chưa có ai đoán đúng trong lượt này)\n");
        }

        details.append("\n⏱ Chuẩn bị sang bức tranh tiếp theo (3s)...");
        roundResultDetails.setText(details.toString());
        roundResultBox.setVisible(true);

        // Cập nhật bảng điểm thời gian thực bên thanh trái
        if (env.getData() != null && env.getData().has("scores")) {
            updateScoreboard(env.getData().get("scores"));
        }
    }

    private void updateScoreboard(JsonNode scoresNode) {
        if (scoresNode == null || !scoresNode.isArray()) return;
        playerList.clear();
        scoresNode.forEach(s -> {
            String u = s.has("username") ? s.get("username").asText() : "";
            int total = s.has("totalPoints") ? s.get("totalPoints").asInt() : 0;
            playerList.add(u + " (" + total + " đ)");
        });
    }

    private void onGameOver(Envelope env) {
        roundResultBox.setVisible(false);
        topicSelectionBox.setVisible(false);
        gameOverBox.setVisible(true);
        gameOverScoresBox.getChildren().clear();

        if (env.getData() != null && env.getData().has("scoreboard")) {
            JsonNode scoreboard = env.getData().get("scoreboard");
            scoreboard.forEach(row -> {
                int rank = row.has("rank") ? row.get("rank").asInt() : 1;
                String user = row.has("username") ? row.get("username").asText() : "";
                int totalPoints = row.has("totalPoints") ? row.get("totalPoints").asInt() : 0;
                int pointsDrawn = row.has("pointsDrawn") ? row.get("pointsDrawn").asInt() : 0;
                int pointsGuess = row.has("pointsGuess") ? row.get("pointsGuess").asInt() : 0;

                String medal = (rank == 1) ? "🥇" : (rank == 2) ? "🥈" : (rank == 3) ? "🥉" : "  ";
                Label rowLbl = new Label(medal + " Hạng " + rank + ":  " + user + "  —  " + totalPoints + " đ (Vẽ: " + pointsDrawn + ", Đoán: " + pointsGuess + ")");
                rowLbl.setStyle("-fx-font-size: 16px; -fx-font-weight: bold; -fx-text-fill: white;");
                gameOverScoresBox.getChildren().add(rowLbl);
            });
        }
    }

    private void onTimerUpdate(Envelope env) {
        int remaining = env.getInt("remaining", 0);
        String phase = env.getString("phase");
        timerLabel.setText("⏱ " + remaining + "s");

        if ("TOPIC_SELECT".equals(phase) && topicSelectionBox.isVisible()) {
            topicTimerLabel.setText("Thời gian còn lại: " + remaining + "s");
        }
    }

    private void drawStrokeFromNetwork(StrokeDTO stroke) {
        if (stroke == null) return;
        if (stroke.isClear()) {
            gc.setFill(Color.WHITE);
            gc.fillRect(0, 0, canvas.getWidth(), canvas.getHeight());
            return;
        }

        List<Double> xs = stroke.getxPoints();
        List<Double> ys = stroke.getyPoints();
        if (xs == null || xs.isEmpty() || ys == null || ys.isEmpty()) return;

        try {
            gc.setStroke(Color.web(stroke.getColor() != null ? stroke.getColor() : "#000000"));
        } catch (Exception e) {
            gc.setStroke(Color.BLACK);
        }
        gc.setLineWidth(stroke.getWidth() > 0 ? stroke.getWidth() : 3.0);

        if (xs.size() == 1) {
            gc.strokeLine(xs.get(0), ys.get(0), xs.get(0), ys.get(0));
        } else {
            gc.beginPath();
            gc.moveTo(xs.get(0), ys.get(0));
            for (int i = 1; i < Math.min(xs.size(), ys.size()); i++) {
                gc.lineTo(xs.get(i), ys.get(i));
            }
            gc.stroke();
            gc.closePath();
        }
    }

    private String toHexString(Color color) {
        return String.format("#%02X%02X%02X",
                (int) (color.getRed() * 255),
                (int) (color.getGreen() * 255),
                (int) (color.getBlue() * 255));
    }

    private void scrollToBottom() {
        if (chatView != null && !chatMessages.isEmpty()) {
            chatView.scrollTo(chatMessages.size() - 1);
        }
    }
}
