package com.nhom8.client.ui;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nhom8.client.MainApplication;
import com.nhom8.common.dto.StrokeDTO;
import com.nhom8.common.message.Envelope;
import com.nhom8.common.message.MessageType;
import javafx.animation.PauseTransition;
import javafx.animation.FadeTransition;
import javafx.animation.ScaleTransition;
import javafx.animation.Timeline;
import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
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
import javafx.util.Duration;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class GameScreen {
    private final MainApplication app;
    private final String roomId;
    private final ObservableList<String> chatMessages;
    private final ObservableList<String> playerList;

    private Canvas canvas;
    private Pane paintingLayer;
    private GraphicsContext gc;

    // Trạng thái phiên đấu
    private boolean isDrawingPhase = false;
    private boolean isGuessingPhase = false;
    /** A correct guesser may chat for the remainder of the current painting. */
    private boolean hasGuessedCurrentPainting;
    private boolean hasSubmittedDrawing = false;
    private boolean submissionPending;
    private javafx.animation.Timeline draftSync;
    private boolean strokeInProgress;
    private String drawingId;
    private String guessDrawingId;
    private long draftVersion;
    private JsonNode latestScores;
    private final java.util.Set<String> reconnectingPlayers = new java.util.HashSet<>();
    public record DraftRecovery(String drawingId, long version, List<StrokeDTO> strokes, boolean pending) {}

    public DraftRecovery captureDraft() {
        if (draftSync != null) draftSync.stop();
        if (!isDrawingPhase || drawingId == null) return null;
        if (!currentXPoints.isEmpty()) sendStrokeChunk();
        return new DraftRecovery(drawingId, draftVersion, new ArrayList<>(myStrokes), submissionPending);
    }
    private String currentPainter = "";
    private String mySelectedTopic = "";
    private int currentRound = 1;
    private int maxRounds = 4;
    private int lastTimerSecond = -1;
    private String lastTimerPhase = "";

    // Bộ nhớ vẽ tranh của bản thân
    private final List<StrokeDTO> myStrokes = new ArrayList<>();
    private final List<Double> currentXPoints = new ArrayList<>();
    private final List<Double> currentYPoints = new ArrayList<>();
    private Color currentColor = Color.BLACK;
    private double currentWidth = 3.0;
    private boolean eraserSelected = false;

    // UI Controls
    private Label topInfoLabel;
    private Label timerLabel;
    private Label phaseLabel;
    private Label currentPaintingLabel;
    private FlowPane toolbar;
    private Button submitDrawingBtn;
    private TextField inputField;
    private Label guessFeedbackLabel;
    private Timeline wrongGuessAnimation;
    private PauseTransition wrongGuessReset;
    private ListView<String> chatView;

    // Overlays
    private VBox topicSelectionBox;
    private HBox topicButtonBox;
    private Label topicTimerLabel;

    private VBox roundResultBox;
    private Label roundResultTitle;
    private Label roundResultDetails;
    private PauseTransition roundResultTimer;

    private VBox gameOverBox;
    private VBox gameOverScoresBox;
    private StackPane phaseAnnouncementOverlay;
    private Label phaseAnnouncementTitle;
    private Label phaseAnnouncementDetails;
    private PauseTransition phaseAnnouncementTimer;

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
        leaveBtn.getStyleClass().add("pencil-button-sm");
        leaveBtn.setOnAction(e -> {
            app.confirmLeave(true);
        });

        topInfoLabel = new Label("Phòng: " + roomId + " | Đang khởi tạo...");
        topInfoLabel.setStyle("-fx-font-family: 'Patrick Hand'; -fx-font-size: 22px; -fx-font-weight: bold; -fx-text-fill: -fx-ink;");

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        phaseLabel = new Label("Vòng 1/" + maxRounds + " • Chuẩn bị");
        phaseLabel.getStyleClass().add("name-tag");

        timerLabel = new Label("⏱ --s");
        timerLabel.setStyle("-fx-font-family: 'Amatic SC'; -fx-font-size: 28px; -fx-font-weight: bold; -fx-text-fill: white; -fx-background-color: -fx-ink; -fx-padding: 2 14; -fx-border-color: white; -fx-border-width: 2;");

        Button soundButton = new Button(GameSound.isEnabled() ? "🔊" : "🔇");
        soundButton.getStyleClass().add("pencil-button-sm");
        soundButton.setTooltip(new Tooltip("Bật/tắt âm thanh"));
        soundButton.setOnAction(e -> {
            GameSound.setEnabled(!GameSound.isEnabled());
            if (!GameSound.isEnabled()) {
                PencilSound.stopDraw();
            }
            soundButton.setText(GameSound.isEnabled() ? "🔊" : "🔇");
        });

        topBox.getChildren().addAll(leaveBtn, topInfoLabel, spacer, phaseLabel, timerLabel, soundButton);
        root.setTop(topBox);

        // --- CENTER: CANVAS + TOOLBAR ---
        VBox centerBox = new VBox(10);
        centerBox.setAlignment(Pos.CENTER);
        centerBox.setPadding(new Insets(10));
        centerBox.setMinWidth(0);
        VBox.setVgrow(centerBox, Priority.ALWAYS);

        // Khung Canvas với viền nổi bật kiểu doodle
        StackPane canvasContainer = new StackPane();
        canvasContainer.setStyle("-fx-background-color: white; -fx-border-color: -fx-ink; -fx-border-width: 3; -fx-effect: dropshadow(three-pass-box, rgba(0,0,0,0.2), 8, 0, 0, 2);");
        canvasContainer.setMinSize(0, 0);
        VBox.setVgrow(canvasContainer, Priority.ALWAYS);
        canvasContainer.setMaxWidth(Double.MAX_VALUE);
        canvasContainer.setMaxHeight(Double.MAX_VALUE);

        canvas = new Canvas(640, 460);
        paintingLayer = new Pane();
        paintingLayer.setPrefSize(640, 460);
        paintingLayer.setMinSize(640, 460);
        paintingLayer.setMaxSize(640, 460);
        paintingLayer.setMouseTransparent(true);
        paintingLayer.setVisible(false);
        gc = canvas.getGraphicsContext2D();
        gc.setFill(Color.WHITE);
        gc.fillRect(0, 0, canvas.getWidth(), canvas.getHeight());
        gc.setLineCap(StrokeLineCap.ROUND);
        gc.setLineJoin(StrokeLineJoin.ROUND);

        // Keep the drawing surface inside the space left by the player and chat
        // panels. Canvas coordinates stay at 640x460, so networked strokes remain
        // compatible even when the window is narrower than the preferred layout.
        Runnable fitCanvas = () -> {
            double availableWidth = Math.max(0, canvasContainer.getWidth() - 12);
            double availableHeight = Math.max(0, canvasContainer.getHeight() - 12);
            if (availableWidth <= 0 || availableHeight <= 0) return;
            double scale = Math.min(
                    availableWidth / canvas.getWidth(),
                    availableHeight / canvas.getHeight());
            canvas.setScaleX(scale);
            canvas.setScaleY(scale);
            paintingLayer.setScaleX(scale);
            paintingLayer.setScaleY(scale);
        };
        canvasContainer.widthProperty().addListener((obs, oldValue, newValue) -> fitCanvas.run());
        canvasContainer.heightProperty().addListener((obs, oldValue, newValue) -> fitCanvas.run());

        setupCanvasEvents();
        canvasContainer.getChildren().addAll(canvas, paintingLayer);

        // Toolbar vẽ
        toolbar = new FlowPane(10, 8);
        toolbar.setAlignment(Pos.CENTER);
        toolbar.setPadding(new Insets(8, 15, 8, 15));
        toolbar.setMaxWidth(Double.MAX_VALUE);
        toolbar.setStyle("-fx-background-color: white; -fx-border-color: -fx-ink; -fx-border-width: 2;");

        FlowPane colorPalette = new FlowPane(5, 5);
        colorPalette.setAlignment(Pos.CENTER_LEFT);
        colorPalette.setMaxWidth(330);
        List<Color> paletteColors = List.of(
                Color.web("#000000"), Color.web("#6C757D"), Color.web("#5C4033"),
                Color.web("#8B1E3F"), Color.web("#D62828"), Color.web("#F77F00"),
                Color.web("#F2C14E"), Color.web("#6A994E"), Color.web("#2A9D8F"),
                Color.web("#00A6A6"), Color.web("#277DA1"), Color.web("#4361EE"),
                Color.web("#3A0CA3"), Color.web("#7209B7"), Color.web("#B5179E"),
                Color.web("#F15BB5")
        );
        List<Button> colorButtons = new ArrayList<>();
        for (Color paletteColor : paletteColors) {
            Button swatch = new Button();
            swatch.setMinSize(22, 22);
            swatch.setPrefSize(22, 22);
            swatch.setMaxSize(22, 22);
            swatch.setFocusTraversable(false);
            swatch.setTooltip(new Tooltip(toHexString(paletteColor)));
            colorButtons.add(swatch);
            colorPalette.getChildren().add(swatch);
        }
        Runnable refreshPaletteSelection = () -> {
            for (int i = 0; i < colorButtons.size(); i++) {
                Button swatch = colorButtons.get(i);
                Color swatchColor = paletteColors.get(i);
                String borderColor = swatchColor.equals(currentColor) ? "#171717" : "#FFFFFF";
                String borderWidth = swatchColor.equals(currentColor) ? "3" : "1";
                swatch.setStyle("-fx-background-color: " + toHexString(swatchColor)
                        + "; -fx-background-radius: 4; -fx-border-color: " + borderColor
                        + "; -fx-border-width: " + borderWidth
                        + "; -fx-border-radius: 4; -fx-cursor: hand;");
            }
        };
        refreshPaletteSelection.run();

        Slider widthSlider = new Slider(1, 25, 3);
        widthSlider.setPrefWidth(100);
        widthSlider.valueProperty().addListener((obs, oldVal, newVal) -> currentWidth = newVal.doubleValue());

        Button eraserBtn = new Button("⌫ Tẩy");
        eraserBtn.getStyleClass().add("pencil-button-sm");
        eraserBtn.setOnAction(e -> {
            eraserSelected = !eraserSelected;
            eraserBtn.setText(eraserSelected ? "✎ Bút vẽ" : "⌫ Tẩy");
        });
        for (int i = 0; i < colorButtons.size(); i++) {
            Color paletteColor = paletteColors.get(i);
            colorButtons.get(i).setOnAction(e -> {
                currentColor = paletteColor;
                eraserSelected = false;
                eraserBtn.setText("⌫ Tẩy");
                refreshPaletteSelection.run();
            });
        }
        Button clearBtn = new Button("🗑 Xóa bảng");
        clearBtn.getStyleClass().add("pencil-button-sm");
        clearBtn.setOnAction(e -> clearCanvas());

        submitDrawingBtn = new Button("📤 Nộp tranh");
        submitDrawingBtn.getStyleClass().add("pencil-button-sm");
        submitDrawingBtn.setOnAction(e -> submitMyDrawing());

        toolbar.getChildren().addAll(
                new Label("Màu:"), colorPalette,
                new Label("Cỡ bút:"), widthSlider, eraserBtn,
                clearBtn,
                submitDrawingBtn
        );
        toolbar.setVisible(false);

        centerBox.getChildren().addAll(canvasContainer, toolbar);
        root.setCenter(centerBox);

        // --- LEFT: PLAYERS & SCORES ---
        VBox leftBox = new VBox(10);
        leftBox.setPadding(new Insets(10));
        leftBox.setMinWidth(155);
        leftBox.setPrefWidth(190);

        Label pLabel = new Label("🏆 BXH trận · cập nhật trực tiếp");
        pLabel.setStyle("-fx-font-family: 'Patrick Hand'; -fx-font-size: 22px; -fx-font-weight: bold; -fx-text-fill: -fx-ink;");

        ListView<String> pList = new ListView<>(playerList);
        VBox.setVgrow(pList, Priority.ALWAYS);
        pList.setMaxHeight(Double.MAX_VALUE);

        currentPaintingLabel = new Label("🖼 Chưa bắt đầu lượt đoán");
        currentPaintingLabel.setWrapText(true);
        currentPaintingLabel.setStyle("-fx-font-family: 'Patrick Hand'; -fx-font-size: 16px; -fx-font-weight: bold; -fx-text-fill: -fx-ink; -fx-background-color: #FFF9E8; -fx-border-color: -fx-ink; -fx-border-width: 2; -fx-padding: 8;");

        leftBox.getChildren().addAll(pLabel, pList, currentPaintingLabel);
        root.setLeft(leftBox);

        // --- RIGHT: CHAT & GUESS INPUT ---
        VBox rightBox = new VBox(10);
        rightBox.setPadding(new Insets(10));
        rightBox.setMinWidth(245);
        rightBox.setPrefWidth(270);

        VBox chatTitleBox = new VBox(2);
        Label chatTitle = new Label("💬 CHAT PHÒNG & ĐOÁN TỪ");
        chatTitle.setStyle("-fx-font-family: 'Patrick Hand'; -fx-font-size: 20px; -fx-font-weight: bold; -fx-text-fill: -fx-ink;");
        Label chatSubtitle = new Label("🔒 Nội bộ phòng #" + roomId);
        chatSubtitle.setStyle("-fx-font-family: 'Patrick Hand'; -fx-font-size: 13px; -fx-text-fill: #555555;");
        chatTitleBox.getChildren().addAll(chatTitle, chatSubtitle);

        chatView = new ListView<>(chatMessages);
        chatView.setCellFactory(list -> new ChatMessageCell());
        chatView.setMinHeight(80);
        VBox.setVgrow(chatView, Priority.ALWAYS);
        chatView.setMaxHeight(Double.MAX_VALUE);

        guessFeedbackLabel = new Label("");
        guessFeedbackLabel.setStyle("-fx-font-weight: bold; -fx-font-size: 15px; -fx-text-fill: -fx-ink; -fx-font-family: 'Patrick Hand';");

        HBox inputBox = new HBox(8);
        inputField = new TextField();
        inputField.setPromptText("Nhập đáp án hoặc chat...");
        inputField.setPrefHeight(38);
        HBox.setHgrow(inputField, Priority.ALWAYS);
        inputField.setOnAction(e -> handleInputSubmit());

        Button sendBtn = new Button("Gửi");
        sendBtn.getStyleClass().add("pencil-button-sm");
        sendBtn.setMinWidth(58);
        sendBtn.setOnAction(e -> handleInputSubmit());

        inputBox.getChildren().addAll(inputField, sendBtn);

        rightBox.getChildren().addAll(chatTitleBox, chatView, guessFeedbackLabel, inputBox);
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
        gameOverBox = new VBox(15);
        gameOverBox.setAlignment(Pos.CENTER);
        gameOverBox.setStyle("-fx-background-color: rgba(15, 23, 42, 0.95); -fx-padding: 30;");
        gameOverBox.setVisible(false);

        Label gameOverTitle = new Label("🏆 BẢNG XẾP HẠNG TỔNG KẾT 🏆");
        gameOverTitle.setStyle("-fx-font-size: 28px; -fx-text-fill: #F5B800; -fx-font-weight: bold;");

        Label gameOverSubtitle = new Label("Điểm số tổng kết sau các vòng đấu");
        gameOverSubtitle.setStyle("-fx-font-size: 14px; -fx-text-fill: #CBD5E1;");

        gameOverScoresBox = new VBox(8);
        gameOverScoresBox.setAlignment(Pos.CENTER);
        gameOverScoresBox.setPadding(new Insets(10));

        ScrollPane scoreScroll = new ScrollPane(gameOverScoresBox);
        scoreScroll.setFitToWidth(true);
        scoreScroll.setPrefWidth(620);
        scoreScroll.setMaxWidth(620);
        scoreScroll.setPrefHeight(320);
        scoreScroll.setMaxHeight(320);
        scoreScroll.setStyle("-fx-background: transparent; -fx-background-color: transparent;");

        Button okBtn = new Button("OK");
        okBtn.setStyle("-fx-background-color: #52c41a; -fx-text-fill: white; -fx-font-size: 18px; -fx-font-weight: bold; -fx-padding: 10 60; -fx-background-radius: 25; -fx-cursor: hand; -fx-effect: dropshadow(three-pass-box, rgba(0,0,0,0.3), 6, 0, 0, 2);");
        okBtn.setOnAction(e -> {
            app.getWsClient().send(new Envelope(MessageType.ROOM_LEAVE));
            app.showLobbyScreen();
        });

        gameOverBox.getChildren().addAll(gameOverTitle, gameOverSubtitle, scoreScroll, okBtn);

        // Full-screen phase card shown briefly on each automatic phase transition.
        phaseAnnouncementOverlay = new StackPane();
        phaseAnnouncementOverlay.setStyle("-fx-background-color: rgba(15, 23, 42, 0.78);");
        phaseAnnouncementOverlay.setMouseTransparent(true);
        phaseAnnouncementOverlay.setVisible(false);

        VBox phaseCard = new VBox(12);
        phaseCard.setAlignment(Pos.CENTER);
        phaseCard.setMaxWidth(720);
        phaseCard.setPadding(new Insets(36, 52, 36, 52));
        phaseCard.setStyle("-fx-background-color: #FFF9E8; -fx-border-color: #171717; -fx-border-width: 4; -fx-background-radius: 18; -fx-border-radius: 16; -fx-effect: dropshadow(three-pass-box, rgba(0,0,0,0.35), 16, 0, 0, 6);");

        phaseAnnouncementTitle = new Label();
        phaseAnnouncementTitle.setWrapText(true);
        phaseAnnouncementTitle.setAlignment(Pos.CENTER);
        phaseAnnouncementTitle.setStyle("-fx-font-family: 'Amatic SC'; -fx-font-size: 56px; -fx-font-weight: bold; -fx-text-fill: #171717;");

        phaseAnnouncementDetails = new Label();
        phaseAnnouncementDetails.setWrapText(true);
        phaseAnnouncementDetails.setAlignment(Pos.CENTER);
        phaseAnnouncementDetails.setStyle("-fx-font-family: 'Patrick Hand'; -fx-font-size: 22px; -fx-text-fill: #475569;");
        phaseCard.getChildren().addAll(phaseAnnouncementTitle, phaseAnnouncementDetails);
        phaseAnnouncementOverlay.getChildren().add(phaseCard);

        StackPane mainStack = new StackPane(root, roundResultBox, topicSelectionBox, gameOverBox,
                phaseAnnouncementOverlay);
        scene = new Scene(mainStack, 1150, 650);
        MainApplication.applyTheme(scene);
        return scene;
    }

    public Scene getScene() {
        return scene;
    }

    private void setupCanvasEvents() {
        canvas.setOnMousePressed(e -> {
            if (!isDrawingPhase || hasSubmittedDrawing || submissionPending) return;
            strokeInProgress = true;
            PencilSound.playDraw(eraserSelected);
            currentXPoints.clear();
            currentYPoints.clear();
            gc.setStroke(eraserSelected ? Color.WHITE : currentColor);
            gc.setLineWidth(eraserSelected ? Math.max(16, currentWidth * 2) : currentWidth);
            // Vẽ chấm nếu chỉ click chuột tại 1 điểm
            gc.strokeLine(e.getX(), e.getY(), e.getX(), e.getY());
            gc.beginPath();
            gc.moveTo(e.getX(), e.getY());
            currentXPoints.add(e.getX());
            currentYPoints.add(e.getY());
        });

        canvas.setOnMouseDragged(e -> {
            if (!isDrawingPhase || hasSubmittedDrawing || submissionPending) return;
            PencilSound.playDraw(eraserSelected);
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
            PencilSound.stopDraw();
            if (!isDrawingPhase || hasSubmittedDrawing || submissionPending) return;
            strokeInProgress = false;
            gc.lineTo(e.getX(), e.getY());
            gc.stroke();
            currentXPoints.add(e.getX());
            currentYPoints.add(e.getY());
            sendStrokeChunk();
        });

        canvas.setOnMouseExited(e -> {
            PencilSound.stopDraw();
        });
    }

    private void sendStrokeChunk() {
        if (currentXPoints.isEmpty()) return;

        StrokeDTO stroke = new StrokeDTO(
                eraserSelected ? StrokeDTO.TOOL_ERASER : StrokeDTO.TOOL_BRUSH,
                eraserSelected ? "#FFFFFF" : toHexString(currentColor),
                eraserSelected ? Math.max(16, currentWidth * 2) : currentWidth,
                new ArrayList<>(currentXPoints),
                new ArrayList<>(currentYPoints)
        );
        myStrokes.add(stroke);

        Envelope env = new Envelope(MessageType.STROKE_BATCH);
        env.put("drawData", Collections.singletonList(stroke));
        env.put("drawingId", drawingId);
        env.put("draftVersion", ++draftVersion);
        app.getWsClient().send(env);

        currentXPoints.clear();
        currentYPoints.clear();
    }

    private void clearCanvas() {
        if (!isDrawingPhase || hasSubmittedDrawing) return;
        gc.setFill(Color.WHITE);
        gc.fillRect(0, 0, canvas.getWidth(), canvas.getHeight());

        myStrokes.clear();

        StrokeDTO clearDto = new StrokeDTO();
        clearDto.setTool(StrokeDTO.TOOL_CLEAR);
        clearDto.setClear(true);
        myStrokes.add(clearDto);

        Envelope env = new Envelope(MessageType.STROKE_BATCH);
        env.put("drawData", Collections.singletonList(clearDto));
        env.put("drawingId", drawingId);
        env.put("draftVersion", ++draftVersion);
        app.getWsClient().send(env);
    }

    private void submitMyDrawing() {
        if (!isDrawingPhase || hasSubmittedDrawing || submissionPending) return;
        submissionPending = true;
        submitDrawingBtn.setDisable(true);
        submitDrawingBtn.setText("Đang xác nhận…");
        toolbar.setDisable(true);

        Envelope submitEnv = new Envelope(MessageType.DRAW_SUBMIT);
        submitEnv.put("drawData", myStrokes);
        submitEnv.put("drawingId", drawingId);
        app.getWsClient().send(submitEnv);

        topInfoLabel.setText("Đang chờ máy chủ xác nhận đã nhận tranh...");
    }

    private void handleInputSubmit() {
        String text = inputField.getText();
        if (text == null || text.trim().isEmpty()) return;
        text = text.trim();

        if (isGuessingPhase) {
            if (currentPainter.equals(app.getCurrentUsername()) || hasGuessedCurrentPainting) {
                sendChat(text);
            } else {
                Envelope guessEnv = new Envelope(MessageType.GUESS_SUBMIT);
                guessEnv.put("drawingId", guessDrawingId);
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
                case GAME_START:
                case MATCH_START:
                    onGameStart(env);
                    break;
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
                case DRAWING_RESUME:
                    onDrawingResume(env);
                    break;
                case DRAWING_SUBMITTED:
                    if (java.util.Objects.equals(drawingId, env.getString("drawingId"))) {
                        submissionPending = false; hasSubmittedDrawing = true;
                        submitDrawingBtn.setDisable(true); submitDrawingBtn.setText("✔ Đã nộp");
                        toolbar.setDisable(true);
                        GameSound.playDrawingSubmitted();
                    }
                    break;
                case DRAWING_SKIPPED:
                    appendChatMessage("Tranh của " + env.getString("username") + " bị bỏ qua do mất kết nối khi hết giờ; không tính điểm vẽ.");
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
                    appendChatMessage("⚡ " + env.getContent());
                    break;
                case GUESS_RANKING_UPDATE:
                    if (env.getData() != null && env.getData().has("scores")) {
                        updateScoreboard(env.getData().get("scores"));
                    }
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
                    String senderUsername = env.getString("senderUsername");
                    boolean mine = senderUsername != null && senderUsername.equalsIgnoreCase(app.getCurrentUsername());
                    appendChatMessage((mine ? "Bạn" : sender) + ": " + content);
                    break;
                case ROOM_UPDATE:
                    if (env.getData() != null && env.getData().has("players")) {
                        reconnectingPlayers.clear();
                        env.getData().get("players").forEach(p -> {
                            if (!p.path("connected").asBoolean(true)) reconnectingPlayers.add(p.path("username").asText());
                        });
                        if (latestScores != null) updateScoreboard(latestScores);
                    }
                    break;
                default:
                    break;
            }
        });
    }

    private void onTopicOptions(Envelope env) {
        if (env.getInt("currentRound", 0) > 0) this.currentRound = env.getInt("currentRound", 1);
        if (env.getInt("maxRounds", 0) > 0) this.maxRounds = env.getInt("maxRounds", 4);

        if (roundResultTimer != null) {
            roundResultTimer.stop();
        }
        roundResultBox.setVisible(false);
        gameOverBox.setVisible(false);

        topicButtonBox.getChildren().clear();
        topicSelectionBox.setVisible(true);
        phaseLabel.setText("Vòng " + currentRound + "/" + maxRounds + " • Chọn chủ đề");

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

    private void onGameStart(Envelope env) {
        if (env.getInt("maxRounds", 0) > 0) this.maxRounds = env.getInt("maxRounds", this.maxRounds);
        if (env.getData() != null && env.getData().has("scores")) {
            updateScoreboard(env.getData().get("scores"));
        }
        phaseLabel.setText("Vòng 1/" + maxRounds + " • Chuẩn bị");
        if (currentPaintingLabel != null) currentPaintingLabel.setText("🖼 Chưa bắt đầu lượt đoán");
    }

    private void onTopicConfirmed(Envelope env) {
        if (roundResultTimer != null) {
            roundResultTimer.stop();
        }
        roundResultBox.setVisible(false);
        topicSelectionBox.setVisible(false);
        mySelectedTopic = env.getString("topic");
        topInfoLabel.setText("Chủ đề vẽ của bạn: " + mySelectedTopic);
    }

    private void onDrawStart(Envelope env) {
        if (draftSync != null) draftSync.stop();
        draftSync = new javafx.animation.Timeline(new javafx.animation.KeyFrame(Duration.seconds(2), event -> {
            if (app.getPrimaryStage().getScene() != scene) { draftSync.stop(); return; }
            if (isDrawingPhase && !hasSubmittedDrawing && !submissionPending && strokeInProgress && !currentXPoints.isEmpty()
                    && app.getWsClient().isConnected()) {
                double x = currentXPoints.getLast(), y = currentYPoints.getLast();
                sendStrokeChunk(); currentXPoints.add(x); currentYPoints.add(y);
            }
        }));
        draftSync.setCycleCount(javafx.animation.Timeline.INDEFINITE);
        draftSync.play();
        drawingId = env.getString("drawingId");
        draftVersion = env.getData().path("draftVersion").asLong(0);
        submissionPending = false;
        if (env.getInt("currentRound", 0) > 0) this.currentRound = env.getInt("currentRound", 1);
        if (env.getInt("maxRounds", 0) > 0) this.maxRounds = env.getInt("maxRounds", 4);

        if (roundResultTimer != null) {
            roundResultTimer.stop();
        }
        roundResultBox.setVisible(false);
        isDrawingPhase = true;
        isGuessingPhase = false;
        paintingLayer.getChildren().clear();
        paintingLayer.setVisible(false);
        GameSound.playPhaseStart();
        hasSubmittedDrawing = env.getBoolean("submitted", false);
        myStrokes.clear();
        currentXPoints.clear();
        currentYPoints.clear();

        gc.setFill(Color.WHITE);
        gc.fillRect(0, 0, canvas.getWidth(), canvas.getHeight());

        toolbar.setVisible(true);
        toolbar.setDisable(hasSubmittedDrawing);
        submitDrawingBtn.setDisable(hasSubmittedDrawing);
        submitDrawingBtn.setText(hasSubmittedDrawing ? "✔ Đã nộp" : "📤 Nộp tranh");

        phaseLabel.setText("Vòng " + currentRound + "/" + maxRounds + " • Vẽ tranh");
        topInfoLabel.setText("Vẽ chủ đề của bạn: " + (mySelectedTopic != null && !mySelectedTopic.isEmpty() ? mySelectedTopic : "Hãy vẽ thật đẹp!"));
        if (currentPaintingLabel != null) currentPaintingLabel.setText("🎨 Cả phòng đang vẽ");
        showPhaseAnnouncement("VÒNG " + currentRound + " · VẼ TRANH",
                "Bắt đầu pha vẽ! Hãy hoàn thành bức tranh trước khi hết giờ.");
    }

    private void onDrawingResume(Envelope env) {
        JsonNode drawData = env.getData() == null ? null : env.getData().get("drawData");
        if (drawData == null || !drawData.isArray()) return;
        DraftRecovery local = app.getLocalDraft();
        if (!hasSubmittedDrawing && local != null && java.util.Objects.equals(drawingId, local.drawingId()) && local.version() > draftVersion) {
            drawData = mapper.valueToTree(local.strokes());
            draftVersion = local.version();
            app.getWsClient().send(new Envelope(MessageType.DRAWING_DRAFT_SAVE).put("drawingId", drawingId)
                    .put("draftVersion", draftVersion).put("drawData", local.strokes()));
        }
        for (JsonNode strokeNode : drawData) {
            try {
                StrokeDTO stroke = mapper.treeToValue(strokeNode, StrokeDTO.class);
                drawStrokeFromNetwork(stroke);
                if (stroke != null) myStrokes.add(stroke);
            } catch (Exception ex) {
                System.err.println("Không thể khôi phục nét vẽ sau khi kết nối lại: " + ex.getMessage());
            }
        }
        appendChatMessage("↩ Đã khôi phục nét vẽ của bạn sau khi kết nối lại.");
        if (!hasSubmittedDrawing && local != null && java.util.Objects.equals(drawingId, local.drawingId()) && local.pending()) submitMyDrawing();
    }

    private void onGuessStart(Envelope env) {
        guessDrawingId = env.getString("drawingId");
        if (draftSync != null) draftSync.stop();
        if (env.getInt("currentRound", 0) > 0) this.currentRound = env.getInt("currentRound", 1);
        if (env.getInt("maxRounds", 0) > 0) this.maxRounds = env.getInt("maxRounds", 4);

        if (roundResultTimer != null) {
            roundResultTimer.stop();
        }
        roundResultBox.setVisible(false);

        // Máy chủ đã chốt các tranh hợp lệ ở hạn cuối; không gửi bài nộp trễ.

        isDrawingPhase = false;
        PencilSound.stopDraw();
        isGuessingPhase = true;
        hasGuessedCurrentPainting = env.getBoolean("alreadyGuessed", false);
        resetWrongGuessFeedback();
        paintingLayer.setVisible(true);
        GameSound.playPhaseStart();
        toolbar.setVisible(false);
        guessFeedbackLabel.setText("");

        currentPainter = env.getString("painterUsername");
        if (currentPainter == null) currentPainter = "";
        int index = env.getInt("paintingIndex", 1);
        int total = env.getInt("totalPaintings", 1);

        phaseLabel.setText("Vòng " + currentRound + "/" + maxRounds + " • Đoán tranh (" + index + "/" + total + ")");
        if (currentPaintingLabel != null) {
            currentPaintingLabel.setText("🖼 Tranh " + index + "/" + total + " của " + currentPainter);
        }

        if (currentPainter.equals(app.getCurrentUsername())) {
            topInfoLabel.setText("🖼 Tranh của BẠN đang được chiếu! (Không được đoán)");
            inputField.setPromptText("Chat với mọi người...");
            guessFeedbackLabel.setText("Mọi người đang đoán tranh của bạn...");
            guessFeedbackLabel.setStyle("-fx-text-fill: #FFF3CD; -fx-font-weight: bold;");
        } else {
            topInfoLabel.setText("🔍 Đang đoán tranh của: " + currentPainter);
            inputField.setPromptText(hasGuessedCurrentPainting ? "Nhắn tin với mọi người..." : "Nhập từ khóa đoán vào đây...");
            guessFeedbackLabel.setText("Hãy nhanh tay đoán từ khóa!");
            if (hasGuessedCurrentPainting) guessFeedbackLabel.setText("✔ Bạn đã đoán đúng — giờ có thể nhắn tin.");
            guessFeedbackLabel.setStyle("-fx-text-fill: white; -fx-font-weight: bold;");
        }

        showPhaseAnnouncement("VÒNG " + currentRound + " · ĐOÁN TRANH",
                "Lượt tranh " + index + "/" + total + " — "
                        + (currentPainter.equals(app.getCurrentUsername())
                        ? "mọi người đang đoán tranh của bạn."
                        : "hãy nhập đáp án vào khung chat."));
    }

    private void showPhaseAnnouncement(String title, String details) {
        if (phaseAnnouncementOverlay == null) return;
        if (phaseAnnouncementTimer != null) phaseAnnouncementTimer.stop();

        phaseAnnouncementTitle.setText(title);
        phaseAnnouncementDetails.setText(details);
        phaseAnnouncementOverlay.setOpacity(0);
        phaseAnnouncementOverlay.setVisible(true);

        FadeTransition fadeIn = new FadeTransition(Duration.millis(220), phaseAnnouncementOverlay);
        fadeIn.setFromValue(0);
        fadeIn.setToValue(1);
        fadeIn.play();

        phaseAnnouncementTimer = new PauseTransition(Duration.millis(1700));
        phaseAnnouncementTimer.setOnFinished(event -> {
            FadeTransition fadeOut = new FadeTransition(Duration.millis(280), phaseAnnouncementOverlay);
            fadeOut.setToValue(0);
            fadeOut.setOnFinished(fadeEvent -> phaseAnnouncementOverlay.setVisible(false));
            fadeOut.play();
        });
        phaseAnnouncementTimer.playFromStart();
    }

    private void onPaintingDisplay(Envelope env) {
        GameSound.playPaintingRevealed();
        if (env.getInt("currentRound", 0) > 0) this.currentRound = env.getInt("currentRound", 1);
        if (env.getInt("maxRounds", 0) > 0) this.maxRounds = env.getInt("maxRounds", 4);

        gc.setFill(Color.WHITE);
        gc.fillRect(0, 0, canvas.getWidth(), canvas.getHeight());
        paintingLayer.getChildren().clear();
        paintingLayer.setVisible(true);

        String hint = env.getString("hint");
        if (hint != null && !hint.isEmpty() && !currentPainter.equals(app.getCurrentUsername())) {
            topInfoLabel.setText("🔍 Đoán tranh của: " + currentPainter + "  |  Gợi ý: " + hint);
        }

        if (env.getData() != null && env.getData().has("drawData")) {
            JsonNode drawDataNode = env.getData().get("drawData");
            if (drawDataNode.isTextual()) {
                try {
                    drawDataNode = mapper.readTree(drawDataNode.asText());
                } catch (Exception ignored) {}
            }
            if (drawDataNode != null && drawDataNode.isArray()) {
                for (JsonNode strokeNode : drawDataNode) {
                    try {
                        StrokeDTO stroke = mapper.treeToValue(strokeNode, StrokeDTO.class);
                        drawPaintingStroke(stroke);
                    } catch (Exception ex) {
                        ex.printStackTrace();
                    }
                }
            }
        }
    }

    private void drawPaintingStroke(StrokeDTO stroke) {
        if (stroke == null) return;
        if (stroke.isClear()) {
            paintingLayer.getChildren().clear();
            return;
        }
        if (stroke.isUndo()) return;
        List<Double> xs = stroke.getxPoints();
        List<Double> ys = stroke.getyPoints();
        if (xs == null || ys == null || xs.isEmpty() || ys.isEmpty()) return;
        Color color;
        try {
            color = StrokeDTO.TOOL_ERASER.equals(stroke.getTool()) ? Color.WHITE : Color.web(stroke.getColor());
        } catch (IllegalArgumentException ex) {
            color = Color.BLACK;
        }
        double width = stroke.getWidth();
        javafx.scene.shape.Path path = new javafx.scene.shape.Path();
        path.setManaged(false);
        path.setFill(null);
        path.setStroke(color);
        path.setStrokeWidth(width);
        path.setStrokeLineCap(StrokeLineCap.ROUND);
        path.setStrokeLineJoin(StrokeLineJoin.ROUND);
        path.getElements().add(new javafx.scene.shape.MoveTo(xs.get(0), ys.get(0)));
        int count = Math.min(xs.size(), ys.size());
        if (count == 1) {
            path.getElements().add(new javafx.scene.shape.LineTo(xs.get(0), ys.get(0)));
        } else {
            for (int i = 1; i < count; i++) {
                path.getElements().add(new javafx.scene.shape.LineTo(xs.get(i), ys.get(i)));
            }
        }
        paintingLayer.getChildren().add(path);
    }

    private void onGuessResult(Envelope env) {
        boolean correct = env.getBoolean("correct", false);
        if (correct) {
            resetWrongGuessFeedback();
            hasGuessedCurrentPainting = true;
            GameSound.playCorrect();
            int points = env.getInt("points", 0);
            int rank = env.getInt("rank", 0);
            long elapsedMs = env.getData().path("elapsedMs").asLong(0);
            guessFeedbackLabel.setText("🎉 Đoán đúng thứ " + rank + "! +" + points + " điểm · "
                    + String.format(java.util.Locale.ROOT, "%.3f", elapsedMs / 1000.0) + " giây");
            guessFeedbackLabel.setStyle("-fx-text-fill: #52c41a; -fx-font-weight: bold; -fx-font-size: 13px;");
            inputField.setPromptText("Nhắn tin với mọi người...");
        } else {
            GameSound.playWrongGuess();
            guessFeedbackLabel.setText("❌ Sai rồi! Hãy thử lại...");
            guessFeedbackLabel.setStyle("-fx-text-fill: #ff4d4f; -fx-font-weight: bold; -fx-font-size: 13px;");
            showWrongGuessFeedback();
        }
    }

    private void showWrongGuessFeedback() {
        resetWrongGuessFeedback();
        inputField.setStyle("-fx-border-color: #D62828; -fx-border-width: 2; -fx-background-color: #FFE2E2;");
        wrongGuessAnimation = new Timeline(
                new KeyFrame(Duration.ZERO, new KeyValue(inputField.translateXProperty(), 0)),
                new KeyFrame(Duration.millis(55), new KeyValue(inputField.translateXProperty(), -6)),
                new KeyFrame(Duration.millis(110), new KeyValue(inputField.translateXProperty(), 6)),
                new KeyFrame(Duration.millis(165), new KeyValue(inputField.translateXProperty(), -4)),
                new KeyFrame(Duration.millis(220), new KeyValue(inputField.translateXProperty(), 4)),
                new KeyFrame(Duration.millis(280), new KeyValue(inputField.translateXProperty(), 0)));
        wrongGuessAnimation.playFromStart();
        wrongGuessReset = new PauseTransition(Duration.millis(900));
        wrongGuessReset.setOnFinished(e -> resetWrongGuessFeedback());
        wrongGuessReset.playFromStart();
    }

    private void resetWrongGuessFeedback() {
        if (wrongGuessAnimation != null) wrongGuessAnimation.stop();
        if (wrongGuessReset != null) wrongGuessReset.stop();
        if (inputField != null) {
            inputField.setTranslateX(0);
            inputField.setStyle("");
        }
    }

    private void onGuessRankingUpdate(Envelope env) {
        if (env.getData() != null && env.getData().has("rankings")) {
            JsonNode rankings = env.getData().get("rankings");
            if (rankings.size() > 0) {
                JsonNode latest = rankings.get(rankings.size() - 1);
                String u = latest.has("username") ? latest.get("username").asText() : "";
                if (!u.equalsIgnoreCase(app.getCurrentUsername())) GameSound.playRoomCorrect();
                int pts = latest.has("points") ? latest.get("points").asInt() : 0;
                int r = latest.has("rank") ? latest.get("rank").asInt() : 1;
                appendChatMessage("🏆 " + u + " đoán đúng: +" + pts + " điểm (thứ tự #" + r + ")");
            }
        }
    }

    private void onRoundResult(Envelope env) {
        if (env.getInt("currentRound", 0) > 0) this.currentRound = env.getInt("currentRound", 1);
        if (env.getInt("maxRounds", 0) > 0) this.maxRounds = env.getInt("maxRounds", 4);
        phaseLabel.setText("Vòng " + currentRound + "/" + maxRounds + " • Kết quả lượt");

        boolean isEndOfRound = env.getBoolean("isEndOfRound", false);
        boolean isLastRound = env.getBoolean("isLastRound", false);

        String topic = env.getString("topic");
        String painter = env.getString("painterUsername");
        int painterPoints = env.getInt("painterPoints", 0);
        int correctCount = env.getInt("correctCount", 0);
        int total = env.getInt("totalGuessers", 0);
        boolean earlyFinish = env.getBoolean("earlyFinish", false);

        if (!earlyFinish) GameSound.playTimeout();

        if (earlyFinish) {
            roundResultTitle.setText("🎉 Đã có người đoán đúng! Đáp án: " + topic);
        } else {
            roundResultTitle.setText("⏰ Hết thời gian! Đáp án là: " + topic);
        }

        StringBuilder details = new StringBuilder();
        details.append("🎨 Người vẽ: ").append(painter)
               .append(painterPoints >= 0 ? " (+" : " (")
               .append(painterPoints).append(" điểm)\n\n");
        if (env.getBoolean("drawingPenalty", false)) details.append("Phạt vẽ: -20 vì không ai đoán đúng trong số người đủ điều kiện.\n");
        else if (total == 0) details.append("Không có người đủ cơ hội đoán: 0 điểm, không phạt người vẽ.\n");

        details.append("💡 ").append(correctCount).append("/").append(total).append(" người đủ điều kiện đoán đúng:\n");
        if (correctCount > 0 && total > 0) {
            details.append("Điểm vẽ = làm tròn(100 × ").append(correctCount).append("/").append(total).append(") = ")
                   .append(painterPoints).append(".\n");
        }

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

        if (isEndOfRound) {
            if (isLastRound) {
                details.append("\n🏁 ĐÃ HOÀN THÀNH TẤT CẢ ").append(maxRounds).append(" VÒNG! Đang chuyển đến bảng xếp hạng...");
            } else {
                details.append("\n🎉 ĐÃ HOÀN THÀNH VÒNG ").append(currentRound).append("/").append(maxRounds).append("! Chuẩn bị sang Vòng tiếp theo...");
            }
        } else {
            details.append("\n⏱ Chuẩn bị sang bức tranh tiếp theo (3s)...");
        }

        roundResultDetails.setText(details.toString());
        roundResultBox.setVisible(true);

        if (roundResultTimer != null) {
            roundResultTimer.stop();
        }
        roundResultTimer = new PauseTransition(Duration.seconds(2.8));
        roundResultTimer.setOnFinished(e -> roundResultBox.setVisible(false));
        roundResultTimer.play();

        // Cập nhật bảng điểm thời gian thực bên thanh trái
        if (env.getData() != null && env.getData().has("scores")) {
            updateScoreboard(env.getData().get("scores"));
        }
    }

    private void updateScoreboard(JsonNode scoresNode) {
        if (scoresNode == null || !scoresNode.isArray()) return;
        latestScores = scoresNode;
        List<JsonNode> sortedScores = new ArrayList<>();
        scoresNode.forEach(sortedScores::add);
        sortedScores.sort((first, second) -> {
            int byScore = Integer.compare(second.path("totalPoints").asInt(0), first.path("totalPoints").asInt(0));
            if (byScore != 0) return byScore;
            return first.path("username").asText("").compareToIgnoreCase(second.path("username").asText(""));
        });

        List<String> rows = new ArrayList<>();
        for (int index = 0; index < sortedScores.size(); index++) {
            JsonNode score = sortedScores.get(index);
            String name = score.path("displayName").asText(score.path("username").asText("Người chơi"));
            int total = score.path("totalPoints").asInt(0);
            rows.add((index + 1) + ".  " + name + "  ·  " + total + " đ"
                    + (reconnectingPlayers.contains(score.path("username").asText()) ? " · Đang kết nối lại..." : ""));
        }
        playerList.setAll(rows);
    }

    private void onGameOver(Envelope env) {
        if (draftSync != null) draftSync.stop();
        if (roundResultTimer != null) {
            roundResultTimer.stop();
        }
        roundResultBox.setVisible(false);
        topicSelectionBox.setVisible(false);
        gameOverBox.setVisible(true);
        gameOverScoresBox.getChildren().clear();

        if (env.getInt("totalRounds", 0) > 0) {
            this.maxRounds = env.getInt("totalRounds", this.maxRounds);
        }
        phaseLabel.setText("Kết thúc • Đã hoàn thành " + maxRounds + " vòng");

        if (env.getData() != null && env.getData().has("scoreboard")) {
            JsonNode scoreboard = env.getData().get("scoreboard");
            if (scoreboard.size() == 0) {
                Label emptyLbl = new Label("Không có người chơi nào hoàn thành toàn bộ trận đấu.");
                emptyLbl.setStyle("-fx-font-size: 15px; -fx-text-fill: #CBD5E1; -fx-padding: 20;");
                gameOverScoresBox.getChildren().add(emptyLbl);
                return;
            }

            for (JsonNode row : scoreboard) {
                int rank = row.has("rank") ? row.get("rank").asInt() : 1;
                String user = row.has("username") ? row.get("username").asText() : "";
                String dName = row.has("displayName") ? row.get("displayName").asText() : user;
                int totalPoints = row.has("totalPoints") ? row.get("totalPoints").asInt() : 0;
                int pointsDrawn = row.has("pointsDrawn") ? row.get("pointsDrawn").asInt() : 0;
                int pointsGuess = row.has("pointsGuess") ? row.get("pointsGuess").asInt() : 0;

                HBox card = new HBox(15);
                card.setAlignment(Pos.CENTER_LEFT);
                card.setPadding(new Insets(10, 20, 10, 20));
                card.setPrefWidth(580);

                String bgStyle;
                String medal;
                if (rank == 1) {
                    bgStyle = "-fx-background-color: #FFF9DB; -fx-border-color: #F59F00; -fx-border-width: 2;";
                    medal = "🥇 1";
                } else if (rank == 2) {
                    bgStyle = "-fx-background-color: #F1F3F5; -fx-border-color: #ADB5BD; -fx-border-width: 2;";
                    medal = "🥈 2";
                } else if (rank == 3) {
                    bgStyle = "-fx-background-color: #FFF4E6; -fx-border-color: #F76707; -fx-border-width: 2;";
                    medal = "🥉 3";
                } else {
                    bgStyle = "-fx-background-color: #FFFFFF; -fx-border-color: #CED4DA; -fx-border-width: 1;";
                    medal = String.valueOf(rank);
                }
                card.setStyle(bgStyle + " -fx-background-radius: 8; -fx-border-radius: 8;");

                Label medalLbl = new Label(medal);
                medalLbl.setStyle("-fx-font-size: 22px; -fx-font-weight: bold;");

                VBox infoBox = new VBox(3);
                Label nameLbl = new Label(dName + (dName.equals(user) ? "" : " (@" + user + ")"));
                nameLbl.setStyle("-fx-font-size: 16px; -fx-font-weight: bold; -fx-text-fill: #1E293B;");

                int adjustment = row.path("pointsAdjustment").asInt(0);
                Label detailScoreLbl = new Label(String.format("Vẽ: %+d | Đoán: %+d | Thưởng/phạt: %+d", pointsDrawn, pointsGuess, adjustment));
                detailScoreLbl.setStyle("-fx-font-size: 13px; -fx-text-fill: #64748B;");
                infoBox.getChildren().addAll(nameLbl, detailScoreLbl);
                HBox.setHgrow(infoBox, Priority.ALWAYS);

                Label totalScoreLbl = new Label(totalPoints + " đ");
                totalScoreLbl.setStyle("-fx-font-size: 22px; -fx-font-weight: bold; -fx-text-fill: #DC2626;");

                card.getChildren().addAll(medalLbl, infoBox, totalScoreLbl);
                gameOverScoresBox.getChildren().add(card);
            }
        }
    }

    private void onTimerUpdate(Envelope env) {
        int remaining = env.getInt("remaining", 0);
        String phase = env.getString("phase");
        timerLabel.setText("⏱ " + remaining + "s");

        if (phase == null) phase = "";
        if (!phase.equals(lastTimerPhase)) {
            lastTimerPhase = phase;
            lastTimerSecond = -1;
        }
        if (remaining != lastTimerSecond && remaining >= 1 && remaining <= 10
                && ("DRAWING".equals(phase) || "GUESSING".equals(phase))) {
            GameSound.playCountdownTick(remaining);
        }
        lastTimerSecond = remaining;

        if (remaining <= 5 && ("DRAWING".equals(phase) || "GUESSING".equals(phase))) {
            timerLabel.setStyle("-fx-font-family: 'Amatic SC'; -fx-font-size: 30px; -fx-font-weight: bold; -fx-text-fill: white; -fx-background-color: #C92A2A; -fx-padding: 2 14; -fx-border-color: white; -fx-border-width: 2;");
            ScaleTransition pulse = new ScaleTransition(Duration.millis(180), timerLabel);
            pulse.setFromX(1.0);
            pulse.setFromY(1.0);
            pulse.setToX(1.12);
            pulse.setToY(1.12);
            pulse.setAutoReverse(true);
            pulse.setCycleCount(2);
            pulse.playFromStart();
        } else {
            timerLabel.setStyle("-fx-font-family: 'Amatic SC'; -fx-font-size: 28px; -fx-font-weight: bold; -fx-text-fill: white; -fx-background-color: -fx-ink; -fx-padding: 2 14; -fx-border-color: white; -fx-border-width: 2;");
            timerLabel.setScaleX(1.0);
            timerLabel.setScaleY(1.0);
        }

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
        if (stroke.isUndo()) {
            return; // Undo không thể tái hiện từ dữ liệu tĩnh (đã xử lý trên Server)
        }

        List<Double> xs = stroke.getxPoints();
        List<Double> ys = stroke.getyPoints();
        if (xs == null || xs.isEmpty() || ys == null || ys.isEmpty()) return;

        // Xử lý công cụ tẩy: dùng màu nền trắng
        boolean isEraserStroke = StrokeDTO.TOOL_ERASER.equals(stroke.getTool());
        try {
            if (isEraserStroke) {
                gc.setStroke(Color.WHITE);
            } else {
                gc.setStroke(Color.web(stroke.getColor() != null ? stroke.getColor() : "#000000"));
            }
        } catch (Exception e) {
            gc.setStroke(isEraserStroke ? Color.WHITE : Color.BLACK);
        }
        gc.setLineWidth(stroke.getWidth() > 0 ? stroke.getWidth() : 3.0);
        gc.setLineCap(StrokeLineCap.ROUND);
        gc.setLineJoin(StrokeLineJoin.ROUND);

        if (xs.size() == 1) {
            gc.strokeLine(xs.get(0), ys.get(0), xs.get(0), ys.get(0));
        } else {
            gc.beginPath();
            gc.moveTo(xs.get(0), ys.get(0));
            for (int i = 1; i < Math.min(xs.size(), ys.size()); i++) {
                gc.lineTo(xs.get(i), ys.get(i));
            }
            gc.stroke();
            // Không gọi gc.closePath() — tránh nối điểm cuối về điểm đầu làm biến dạng nét vẽ
        }
    }

    private String toHexString(Color color) {
        return String.format("#%02X%02X%02X",
                (int) (color.getRed() * 255),
                (int) (color.getGreen() * 255),
                (int) (color.getBlue() * 255));
    }

    private void appendChatMessage(String msg) {
        chatMessages.add(msg);
        scrollToBottom();
    }

    private void scrollToBottom() {
        if (chatView != null && !chatMessages.isEmpty()) {
            chatView.scrollTo(chatMessages.size() - 1);
        }
    }
}
