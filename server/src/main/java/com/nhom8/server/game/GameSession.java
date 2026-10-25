package com.nhom8.server.game;

import java.util.*;
import java.util.concurrent.*;

import com.nhom8.server.model.Match;
import com.nhom8.server.model.MatchResult;
import com.nhom8.server.model.Painting;
import com.nhom8.server.model.User;
import com.nhom8.common.dto.StrokeDTO;
import com.nhom8.common.message.Envelope;
import com.nhom8.common.message.MessageType;
import com.nhom8.server.service.GameService;
import com.nhom8.server.service.ScoringService;
import com.nhom8.server.util.JsonUtil;
import com.nhom8.server.util.TopicPool;
import com.nhom8.server.ws.ClientSession;

/**
 * Lớp điều khiển toàn bộ luồng logic của một trận đấu trong trò chơi "Vẽ Hình Đoán Ý".
 * Quản lý các giai đoạn:
 * 1. Chọn chủ đề (10 giây): Mỗi người chơi nhận 3 chủ đề, chọn 1
 * 2. Vẽ tranh (60 giây): Tất cả cùng vẽ đồng thời
 * 3. Đoán tranh (30 giây/bức): Chiếu tuần tự từng bức, cả phòng đoán
 * 4. Tổng kết: Tính điểm, lưu kết quả, cập nhật bảng xếp hạng
 * 
 * Điểm đoán phụ thuộc thứ tự đúng và số người có quyền đoán; điểm vẽ theo số người đoán đúng.
 */
public class GameSession {

    /** Điểm phạt/cộng khi một người chơi rời trận giữa chừng. */
    private static final int FORFEIT_POINTS = 20;

    /** Thời gian chọn chủ đề (mili-giây) */
    private static final int TOPIC_SELECT_TIME = 10_000;

    /** Tham chiếu tới phòng chơi chứa phiên đấu này */
    private Room room;

    /** Trận đấu trong cơ sở dữ liệu */
    private Match match;

    /** Chủ đề mỗi người chơi đã chọn (username → topic) */
    private ConcurrentHashMap<String, String> selectedTopics;

    /** Bộ 3 chủ đề gửi cho mỗi người (username → List<3 topics>) */
    private ConcurrentHashMap<String, List<String>> topicOptions;

    /** Dữ liệu bức tranh đã nộp (username → List<StrokeDTO>) */
    private ConcurrentHashMap<String, List<StrokeDTO>> submittedDrawings;

    /** Danh sách người chơi đã nộp tranh */
    private Set<String> submittedPlayers;

    /** Kết quả đoán của mỗi bức tranh (painterUsername → List<GuessResult>) */
    private ConcurrentHashMap<String, List<GuessResult>> guessResults;

    /** Danh sách thứ tự người chơi (cố định trong suốt trận) */
    private List<String> playerOrder;

    /** Index bức tranh đang được chiếu để đoán */
    private int currentPaintingIndex;

    /** Danh sách người đã đoán đúng bức tranh hiện tại */
    private Set<String> correctGuessers;

    /** Scheduler cho các timer (đồng hồ đếm ngược) */
    private ScheduledExecutorService scheduler;

    /** Future của timer đang chạy (để cancel khi cần) */
    private ScheduledFuture<?> currentTimer;
    private ScheduledFuture<?> startCountdownTask;
    private volatile long startCountdownDeadlineMillis;

    /** Timer gửi cập nhật đồng hồ mỗi giây */
    private ScheduledFuture<?> countdownTimer;

    /** Kết quả điểm trận đấu (username → MatchResult) */
    private ConcurrentHashMap<String, MatchResult> matchResults;

    /** Cờ đánh dấu trận đấu đang diễn ra */
    private boolean gameActive;

    /** Pha đang chạy và thời điểm kết thúc pha để đồng bộ người chơi quay lại. */
    private volatile String currentPhase = "";
    private volatile long phaseDeadlineMillis;
    private long phaseSequence;
    private final Map<String, String> drawingIds = new HashMap<>();
    private final Map<String, Long> draftVersions = new HashMap<>();
    private final Map<String, List<StrokeDTO>> drafts = new HashMap<>();
    private final Map<String, String> drawingStates = new HashMap<>();
    private final List<String> paintingOrder = new ArrayList<>();
    private final Set<String> forfeitedPlayers = new HashSet<>();
    private Envelope latestResult;
    private final Set<String> latestResultMembers = new HashSet<>();
    private Envelope latestRoundResult;

    /** Vòng chơi hiện tại */
    private int currentRound = 1;

    /** Đồng hồ đơn điệu của máy chủ, tính theo mili giây, độc lập đồng hồ Client. */
    private java.util.function.LongSupplier scoringClock = () -> System.nanoTime() / 1_000_000;
    private long guessStartedAtMillis;
    private GuessingWindow guessingWindow;
    private final Set<String> finalizedDrawings = new HashSet<>();

    /** Lock object đảm bảo thứ tự xử lý đáp án tuần tự (kiểu message queue) */
    private final Object guessLock = new Object();

    /** Bộ đếm toàn cục cho sequenceId (đảm bảo thứ tự tuyệt đối như RabbitMQ) */
    private volatile long guessSequenceCounter = 0;

    /**
     * Lớp nội bộ lưu trữ chi tiết mỗi lần đoán của người chơi.
     * Ghi nhận TOÀN BỘ lượt đoán (cả đúng lẫn sai) với thời gian chính xác
     * đến nano-giây, đảm bảo thứ tự xử lý tuyệt đối giống cơ chế message queue
     * trong các hệ thống phân tán (tương tự RabbitMQ).
     *
     * Mỗi GuessResult mang theo:
     * - sequenceId: số thứ tự toàn cục (tăng đơn điệu), đảm bảo thứ tự xử lý
     * - elapsedNanos: thời gian phản hồi tính từ lúc bắt đầu lượt đoán (nano giây)
     * - elapsedMs: thời gian phản hồi (mili giây, dùng hiển thị)
     * - processedAt: System.currentTimeMillis() tại thời điểm server xử lý
     */
    private static class GuessResult {

        long sequenceId;        // Số thứ tự toàn cục (message queue ordering)
        String guesserUsername;  // Tên người đoán
        String guessContent;     // Nội dung đáp án (lưu lại để audit)
        boolean correct;         // Đoán đúng hay sai
        long elapsedNanos;       // Thời gian phản hồi chính xác (nano giây)
        long elapsedMs;          // Thời gian phản hồi (mili giây, hiển thị)
        long processedAt;        // Timestamp server xử lý (System.currentTimeMillis)
        int rank;                // Thứ hạng đoán đúng (1, 2, 3..., 0 nếu sai)
        int points;              // Điểm nhận được (0 nếu sai)

        GuessResult(long sequenceId, String guesserUsername, String guessContent,
                    boolean correct, long elapsedNanos) {
            this.sequenceId = sequenceId;
            this.guesserUsername = guesserUsername;
            this.guessContent = guessContent;
            this.correct = correct;
            this.elapsedNanos = elapsedNanos;
            this.elapsedMs = elapsedNanos / 1_000_000; // chuyển nano → mili
            this.processedAt = System.currentTimeMillis();
        }
    }

    /**
     * Khởi tạo phiên chơi cho phòng.
     */
    public GameSession(Room room) {
        this.room = room;
        this.selectedTopics = new ConcurrentHashMap<>();
        this.topicOptions = new ConcurrentHashMap<>();
        this.submittedDrawings = new ConcurrentHashMap<>();
        this.submittedPlayers = ConcurrentHashMap.newKeySet();
        this.guessResults = new ConcurrentHashMap<>();
        this.correctGuessers = ConcurrentHashMap.newKeySet();
        this.matchResults = new ConcurrentHashMap<>();
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "game-phase-timer"); thread.setDaemon(true); return thread;
        });
        this.gameActive = false;
    }

    // ========================================================================
    // GIAI ĐOẠN 0: KHỞI TẠO TRẬN ĐẤU
    // ========================================================================

    public synchronized void beginStartCountdown() {
        if (gameActive || room.isPlaying() || startCountdownDeadlineMillis > 0 || !room.allPlayersReady()) return;
        startCountdownDeadlineMillis = System.currentTimeMillis() + 3_000;
        getGameService().broadcastRoomUpdate(room);
        startCountdownTask = scheduler.schedule(() -> {
            synchronized (this) {
                if (startCountdownDeadlineMillis == 0) return;
                startCountdownDeadlineMillis = 0;
                startCountdownTask = null;
                if (room.allPlayersReady()) startGame();
                else getGameService().broadcastRoomUpdate(room);
            }
        }, 3, TimeUnit.SECONDS);
    }

    public synchronized void cancelStartCountdown() {
        if (startCountdownDeadlineMillis == 0) return;
        startCountdownDeadlineMillis = 0;
        if (startCountdownTask != null) startCountdownTask.cancel(false);
        startCountdownTask = null;
        getGameService().broadcastRoomUpdate(room);
    }

    public long getStartCountdownDeadlineMillis() { return startCountdownDeadlineMillis; }

    /**
     * Bắt đầu trận đấu: cố định thứ tự người chơi, tạo bản ghi Match trong DB,
     * và khởi động giai đoạn chọn chủ đề.
     */
    public synchronized void startGame() {
        if (gameActive || room.isPlaying()) return;
        if (room.getPlayerCount() < Room.MIN_PLAYERS || room.getPlayerCount() > Room.MAX_PLAYERS
                || !room.allPlayersReady()) return;
        gameActive = true;
        finalizedDrawings.clear();
        latestResult = null;
        latestResultMembers.clear();
        forfeitedPlayers.clear();
        currentRound = 1;
        room.setStatus(Room.RoomStatus.PLAYING);

        // Cố định thứ tự người chơi
        playerOrder = new ArrayList<>(room.getPlayerUsernames());

        // Tạo bản ghi trận đấu trong DB
        match = new Match(room.getRoomId());
        try {
            match = getGameService().getMatchRepository().save(match);
        } catch (Exception e) {
            System.err.println("[GameSession] Lỗi lưu Match vào DB: " + e.getMessage());
            e.printStackTrace();
        }

        // Cập nhật sảnh chờ: phòng chuyển sang PLAYING, người chơi chuyển sang 'Đang chơi'
        getGameService().broadcastPlayerList();
        getGameService().broadcastRoomList();

        // Khởi tạo MatchResult cho mỗi người chơi
        matchResults.clear();
        for (String username : playerOrder) {
            User user = getGameService().getUserRepository().findByUsername(username);
            MatchResult result = new MatchResult(match, user);
            matchResults.put(username, result);
        }

        // Thông báo trận đấu bắt đầu
        Envelope startMsg = new Envelope(MessageType.GAME_START);
        startMsg.put("playerOrder", new ArrayList<>(playerOrder));
        startMsg.put("currentRound", currentRound);
        startMsg.put("maxRounds", room.getMaxRounds());
        startMsg.put("scores", buildCurrentScores());
        startMsg.put("scoringVersion", ScoringService.VERSION);
        room.broadcast(startMsg);

        // Bắt đầu vòng 1
        startNewRound();
    }

    /**
     * Bắt đầu một vòng chơi mới (reset dữ liệu vẽ và chủ đề của vòng trước).
     */
    private synchronized void startNewRound() {
        if (!gameActive) return;
        stopCountdown();
        cancelCurrentTimer();

        // Đảm bảo loại bỏ người chơi đã out khỏi playerOrder
        Set<String> activePlayers = room.getPlayerUsernames();
        playerOrder.removeIf(u -> !activePlayers.contains(u));

        if (playerOrder.size() < Room.MIN_ACTIVE_PLAYERS) {
            System.out.println("[GameSession] Không đủ người chơi để tiếp tục vòng mới");
            endGame();
            return;
        }

        // Reset dữ liệu vẽ và đoán của vòng cũ
        selectedTopics.clear();
        topicOptions.clear();
        submittedDrawings.clear();
        submittedPlayers.clear();
        guessResults.clear();
        correctGuessers.clear();
        currentPaintingIndex = 0;
        paintingOrder.clear(); drawingIds.clear(); draftVersions.clear(); drafts.clear(); drawingStates.clear();
        latestRoundResult = null;

        // Bắt đầu chọn chủ đề cho vòng mới
        startTopicSelection();
    }

    // ========================================================================
    // GIAI ĐOẠN 1: CHỌN CHỦ ĐỀ (10 GIÂY)
    // ========================================================================

    /**
     * Bắt đầu giai đoạn chọn chủ đề.
     * Server lấy (3 × số người) chủ đề không trùng, chia bộ 3 cho mỗi người.
     * Người chơi có 10 giây để chọn 1 trong 3.
     */
    private synchronized void startTopicSelection() {
        currentPhase = "TOPIC_SELECT";
        phaseSequence++;
        phaseDeadlineMillis = System.currentTimeMillis() + TOPIC_SELECT_TIME;
        int playerCount = playerOrder.size();
        List<List<String>> allTopicSets = TopicPool.getTopicOptionsForRoom(playerCount);

        for (int i = 0; i < playerCount; i++) {
            String username = playerOrder.get(i);
            List<String> topics = allTopicSets.get(i);
            topicOptions.put(username, topics);
            drawingIds.put(username, UUID.randomUUID().toString());
            drawingStates.put(username, "DRAFT");

            // Gửi 3 chủ đề cho Client
            Envelope msg = new Envelope(MessageType.TOPIC_OPTIONS);
            msg.put("topics", new ArrayList<>(topics));
            msg.put("timeLimit", TOPIC_SELECT_TIME / 1000);
            msg.put("currentRound", currentRound);
            msg.put("maxRounds", room.getMaxRounds());
            stamp(msg);
            msg.put("drawingId", drawingIds.get(username));

            ClientSession handler = room.getPlayer(username);
            if (handler != null) {
                handler.sendMessage(msg);
            }
        }

        // Bắt đầu đếm ngược 10 giây
        startCountdown(TOPIC_SELECT_TIME / 1000, "TOPIC_SELECT");

        // Timer hết giờ chọn chủ đề
        currentTimer = scheduleTask(() -> {
            // Auto-select cho những ai chưa chọn
            for (String username : playerOrder) {
                if (!selectedTopics.containsKey(username)) {
                    List<String> options = topicOptions.get(username);
                    if (options != null && !options.isEmpty()) {
                        // Lấy chủ đề đầu tiên làm mặc định nếu người chơi không chọn (theo yêu cầu)
                        String autoTopic = options.get(0);
                        selectedTopics.put(username, autoTopic);
                        notifyTopicConfirmed(username, autoTopic, true);
                    }
                }
            }
            // Chuyển sang giai đoạn vẽ
            startDrawingPhase();
        }, TOPIC_SELECT_TIME, TimeUnit.MILLISECONDS);
    }

    /**
     * Xử lý khi người chơi chọn chủ đề.
     */
    public synchronized void onTopicSelected(String username, String topic) {
        if (!gameActive || !"TOPIC_SELECT".equals(currentPhase) || System.currentTimeMillis() >= phaseDeadlineMillis) return;
        if (selectedTopics.containsKey(username)) return; // Đã chọn rồi

        // Kiểm tra topic hợp lệ (nằm trong 3 lựa chọn)
        List<String> options = topicOptions.get(username);
        if (options == null || !options.contains(topic)) return;

        selectedTopics.put(username, topic);
        notifyTopicConfirmed(username, topic, false);

        // Kiểm tra nếu tất cả đã chọn → chuyển sang vẽ sớm
        if (selectedTopics.size() == playerOrder.size()) {
            cancelCurrentTimer();
            startDrawingPhase();
        }
    }

    /**
     * Gửi xác nhận chủ đề đã chọn cho Client.
     */
    private void notifyTopicConfirmed(String username, String topic, boolean autoSelected) {
        Envelope msg = new Envelope(MessageType.TOPIC_CONFIRMED);
        msg.put("topic", topic);
        msg.put("autoSelected", autoSelected);

        ClientSession handler = room.getPlayer(username);
        if (handler != null) {
            handler.sendMessage(msg);
        }
    }

    // ========================================================================
    // GIAI ĐOẠN 2: VẼ TRANH (60 GIÂY)
    // ========================================================================

    /**
     * Bắt đầu giai đoạn vẽ tranh.
     * Tất cả người chơi cùng vẽ đồng thời trong 60 giây.
     */
    private synchronized void startDrawingPhase() {
        if (!gameActive || !"TOPIC_SELECT".equals(currentPhase)) return;
        stopCountdown();
        submittedPlayers.clear();

        int drawingTime = room.getDrawTime() * 1000;
        currentPhase = "DRAWING";
        phaseSequence++;
        phaseDeadlineMillis = System.currentTimeMillis() + drawingTime;
        Envelope drawStartMsg = new Envelope(MessageType.DRAW_START);
        drawStartMsg.put("timeLimit", room.getDrawTime());
        drawStartMsg.put("currentRound", currentRound);
        drawStartMsg.put("maxRounds", room.getMaxRounds());
        stamp(drawStartMsg);
        for (String username : playerOrder) {
            ClientSession client = room.getPlayer(username);
            if (client != null) client.sendMessage(Envelope.fromJson(drawStartMsg.toJson())
                    .put("drawingId", drawingIds.get(username)));
        }

        // Đếm ngược
        startCountdown(room.getDrawTime(), "DRAWING");

        // Timer hết giờ vẽ
        currentTimer = scheduleTask(this::finishDrawingPhase, drawingTime, TimeUnit.MILLISECONDS);
    }

    private synchronized void finishDrawingPhase() {
        if (!gameActive || !"DRAWING".equals(currentPhase)) return;
            // Người đang kết nối được tự động nộp; bản nháp của người mất kết nối bị bỏ qua.
            for (String username : playerOrder) {
                if (!submittedPlayers.contains(username)) {
                    ClientSession client = room.getPlayer(username);
                    if (client == null || !client.isConnected()) {
                        drawingStates.put(username, "SKIPPED");
                        drafts.remove(username);
                        room.broadcast(new Envelope(MessageType.DRAWING_SKIPPED).put("username", username));
                        continue;
                    }
                    submittedDrawings.put(username, new ArrayList<>(drafts.getOrDefault(username, List.of())));
                    submittedDrawings.putIfAbsent(username, Collections.synchronizedList(new ArrayList<>()));
                    submittedPlayers.add(username);
                    drawingStates.put(username, "SUBMITTED");

                    // Lưu painting vào DB cho người chưa nộp thủ công — dùng JsonUtil.toJson()
                    try {
                        String topic = selectedTopics.get(username);
                        User painter = getGameService().getUserRepository().findByUsername(username);
                        if (painter != null && topic != null && match != null) {
                            Painting painting = new Painting(match, painter, topic);
                            if (getGameService().getTopicCatalog() != null) {
                                painting.setTopicEntry(getGameService().getTopicCatalog().findOrCreateHistoryWord(topic));
                            }
                            painting.setRoundNumber(currentRound);
                            painting.setTurnNumber(playerOrder.indexOf(username) + 1);
                            painting.setStrokeData(JsonUtil.toJson(submittedDrawings.getOrDefault(username, new ArrayList<>())));
                            getGameService().getPaintingRepository().save(painting);
                        }
                    } catch (Exception e) {
                        System.err.println("[GameSession] Lỗi lưu painting vào DB khi hết giờ: " + e.getMessage());
                        e.printStackTrace();
                    }
                }
            }
            startGuessingPhase();
    }

    /**
     * Xử lý khi nhận từng phần dữ liệu vẽ (chunk) từ người chơi, hoặc thao tác Undo/Clear.
     */
    @SuppressWarnings("unchecked")
    public synchronized void onDrawingDataReceived(String username, Envelope request) {
        if (!canDraw(username, request, true)) return;
        long version = request.getData().path("draftVersion").asLong(-1);
        if (version >= 0 && version <= draftVersions.getOrDefault(username, -1L)) return;
        if (version >= 0) draftVersions.put(username, version);
        
        List<StrokeDTO> drawDataList = JsonUtil.convertList(request.get("drawData"), StrokeDTO.class);
        if (drawDataList == null || drawDataList.isEmpty()) return;

        List<StrokeDTO> currentData = drafts.computeIfAbsent(username, k -> new ArrayList<>());

        for (StrokeDTO chunk : drawDataList) {
            if (chunk.isClear()) {
                currentData.clear();
            } else if (chunk.isUndo()) {
                if (!currentData.isEmpty()) {
                    currentData.remove(currentData.size() - 1);
                }
            } else {
                currentData.add(chunk);
            }
        }
    }

    /**
     * Xử lý khi người chơi nộp tranh chính thức.
     */
    @SuppressWarnings("unchecked")
    public synchronized void onDrawingSubmitted(String username, Envelope request) {
        if (!Objects.equals(drawingIds.get(username), request.getString("drawingId"))) return;
        if (submittedPlayers.contains(username)) { acknowledgeSubmission(username); return; }
        if (!canDraw(username, request, true)) return;

        List<StrokeDTO> drawDataList = JsonUtil.convertList(request.get("drawData"), StrokeDTO.class);
        if (drawDataList != null && !drawDataList.isEmpty()) {
            submittedDrawings.put(username, Collections.synchronizedList(new ArrayList<>(drawDataList)));
        } else {
            submittedDrawings.putIfAbsent(username, Collections.synchronizedList(new ArrayList<>()));
        }
        submittedPlayers.add(username);
        drawingStates.put(username, "SUBMITTED");
        acknowledgeSubmission(username);

        // Lưu painting vào DB — dùng JsonUtil.toJson() để lưu dạng JSON hợp lệ
        try {
            String topic = selectedTopics.get(username);
            User painter = getGameService().getUserRepository().findByUsername(username);
            if (painter != null && topic != null && match != null) {
                Painting painting = new Painting(match, painter, topic);
                if (getGameService().getTopicCatalog() != null) {
                    painting.setTopicEntry(getGameService().getTopicCatalog().findOrCreateHistoryWord(topic));
                }
                painting.setRoundNumber(currentRound);
                painting.setTurnNumber(playerOrder.indexOf(username) + 1);
                painting.setStrokeData(JsonUtil.toJson(submittedDrawings.getOrDefault(username, new ArrayList<>())));
                getGameService().getPaintingRepository().save(painting);
            }
        } catch (Exception e) {
            System.err.println("[GameSession] Lỗi lưu painting vào DB khi nộp tranh: " + e.getMessage());
            e.printStackTrace();
        }

        // Kiểm tra tất cả đã nộp → chuyển sang đoán sớm
        if (submittedPlayers.size() == playerOrder.size()) {
            cancelCurrentTimer();
            startGuessingPhase();
        }
    }

    // ========================================================================
    // GIAI ĐOẠN 3: ĐOÁN TRANH (30 GIÂY / BỨC)
    // ========================================================================

    /**
     * Bắt đầu giai đoạn đoán tranh.
     * Chiếu tuần tự từng bức tranh cho cả phòng đoán.
     */
    private synchronized void startGuessingPhase() {
        stopCountdown();
        paintingOrder.clear();
        for (String username : playerOrder) if (submittedPlayers.contains(username)) paintingOrder.add(username);
        currentPaintingIndex = 0;
        showNextPainting();
    }

    /**
     * Hiển thị bức tranh tiếp theo cho cả phòng đoán.
     */
    private synchronized void showNextPainting() {
        if (!gameActive) return;

        // Loại bỏ người chơi đã out khỏi playerOrder
        Set<String> activePlayers = room.getPlayerUsernames();
        playerOrder.removeIf(u -> !activePlayers.contains(u));

        if (currentPaintingIndex >= paintingOrder.size()) {
            // Đã đoán hết tất cả bức tranh của vòng hiện tại!
            if (currentRound < room.getMaxRounds()) {
                currentRound++;
                System.out.println("[GameSession] Hoàn thành vòng " + (currentRound - 1) + ". Chuẩn bị bắt đầu Vòng " + currentRound + "/" + room.getMaxRounds());

                Envelope roundAnnounce = new Envelope(MessageType.ROOM_CHAT);
                roundAnnounce.put("content", "🔔 ĐÃ HOÀN THÀNH VÒNG " + (currentRound - 1) + "! Chuẩn bị bắt đầu Vòng " + currentRound + "/" + room.getMaxRounds() + "...");
                roundAnnounce.put("senderDisplayName", "HỆ THỐNG");
                room.broadcast(roundAnnounce);

                // Cho người chơi đọc thông báo 1.5 giây rồi chuyển sang chọn chủ đề vòng mới
                currentPhase = "INTERMISSION"; phaseSequence++;
                phaseDeadlineMillis = System.currentTimeMillis() + 1500;
                currentTimer = scheduleTask(this::startNewRound, 1500, TimeUnit.MILLISECONDS);
                return;
            } else {
                // Đã hoàn thành toàn bộ các vòng setting từ trước → Kết thúc trận
                endGame();
                return;
            }
        }

        correctGuessers.clear();
        String painterUsername = paintingOrder.get(currentPaintingIndex);
        drawingStates.put(painterUsername, "PUBLISHED");
        String topic = selectedTopics.get(painterUsername);
        List<StrokeDTO> drawData = submittedDrawings.getOrDefault(painterUsername, new ArrayList<>());
        String hint = TopicPool.generateHint(topic);

        // Khởi tạo danh sách kết quả đoán cho bức tranh này
        guessResults.put(painterUsername, Collections.synchronizedList(new ArrayList<>()));

        int guessingTime = (int) ScoringService.GUESS_DURATION_MS;
        currentPhase = "GUESSING";
        phaseSequence++;
        phaseDeadlineMillis = System.currentTimeMillis() + guessingTime;
        guessStartedAtMillis = scoringClock.getAsLong();
        Map<String, Boolean> candidates = new LinkedHashMap<>();
        for (String username : playerOrder) {
            if (!username.equals(painterUsername)) {
                ClientSession candidate = room.getPlayer(username);
                if (candidate != null) candidates.put(username, candidate.isConnected());
            }
        }
        guessingWindow = new GuessingWindow(guessStartedAtMillis, guessingTime,
                getGameService().getMinimumGuessExposureMillis(), candidates);
        
        Envelope guessStartMsg = new Envelope(MessageType.GUESS_START);
        guessStartMsg.put("painterUsername", painterUsername);
        guessStartMsg.put("drawingId", drawingIds.get(painterUsername));
        guessStartMsg.put("scoringVersion", ScoringService.VERSION);
        guessStartMsg.put("paintingIndex", currentPaintingIndex + 1);
        guessStartMsg.put("totalPaintings", paintingOrder.size());
        stamp(guessStartMsg);
        guessStartMsg.put("timeLimit", guessingTime / 1000);
        guessStartMsg.put("currentRound", currentRound);
        guessStartMsg.put("maxRounds", room.getMaxRounds());
        room.broadcast(guessStartMsg);

        // Gửi dữ liệu bức tranh
        Envelope paintingMsg = new Envelope(MessageType.PAINTING_DISPLAY);
        paintingMsg.put("painterUsername", painterUsername);
        paintingMsg.put("drawingId", drawingIds.get(painterUsername));
        paintingMsg.put("drawData", new ArrayList<>(drawData));
        paintingMsg.put("hint", hint);
        paintingMsg.put("currentRound", currentRound);
        paintingMsg.put("maxRounds", room.getMaxRounds());
        room.broadcast(paintingMsg);

        // Đếm ngược
        guessSequenceCounter = 0;
        startCountdown(guessingTime / 1000, "GUESSING");

        // Timer hết giờ đoán
        currentTimer = scheduleTask(() -> {
            finishCurrentRound();
        }, guessingTime, TimeUnit.MILLISECONDS);
    }

    /**
     * Xử lý đáp án đoán tranh từ người chơi — REAL-TIME, CHÍNH XÁC.
     *
     * Cơ chế hoạt động tương tự message queue (RabbitMQ):
     * 1. Mỗi đáp án được gán sequenceId tăng đơn điệu trong synchronized block
     *    → đảm bảo thứ tự tuyệt đối dù nhiều Client gửi đồng thời.
     * 2. Thời gian chấp nhận được đo bằng đồng hồ đơn điệu, chính xác mili giây.
     * 3. Mọi lượt đoán (cả đúng lẫn sai) đều được lưu lại (audit log).
     * 4. Khi đoán đúng → tính điểm NGAY LẬP TỨC và broadcast bảng xếp hạng
     *    real-time cho cả phòng, không chờ hết giờ.
     */
    public synchronized void onGuessSubmitted(String username, String guess) {
        // === CRITICAL SECTION: synchronized để đảm bảo thứ tự tuyệt đối ===
        // Giống consumer trong RabbitMQ — chỉ 1 message được xử lý tại 1 thời điểm
        GuessResult result;
        String painterUsername;
        boolean isCorrect;
        int currentRank = 0;
        int earnedPoints = 0;
        long acceptedAtMillis = scoringClock.getAsLong();

        synchronized (guessLock) {
            if (!gameActive || !"GUESSING".equals(currentPhase) || System.currentTimeMillis() >= phaseDeadlineMillis
                    || !playerOrder.contains(username) || currentPaintingIndex < 0 || currentPaintingIndex >= paintingOrder.size()) return;
            ClientSession connectedPlayer = room.getPlayer(username);
            if (connectedPlayer == null || !connectedPlayer.isConnected() || guessingWindow == null
                    || !ScoringService.isWithinGuessWindow(guessStartedAtMillis, acceptedAtMillis, ScoringService.GUESS_DURATION_MS)) return;
            painterUsername = paintingOrder.get(currentPaintingIndex);

            // Người vẽ không được đoán tranh của mình
            if (username.equals(painterUsername)) return;

            // Đã đoán đúng rồi thì không nhận thêm đáp án
            if (correctGuessers.contains(username)) return;

            // Ghi nhận thời gian chính xác bằng nanoTime
            long elapsedNanos = (acceptedAtMillis - guessStartedAtMillis) * 1_000_000;

            // Gán sequenceId tăng đơn điệu — thứ tự xử lý tuyệt đối
            long seqId = ++guessSequenceCounter;

            String topic = selectedTopics.get(painterUsername);
            isCorrect = normalizeAnswer(guess).equals(normalizeAnswer(topic));

            // Tạo GuessResult với đầy đủ thông tin audit
            result = new GuessResult(seqId, username, guess, isCorrect, elapsedNanos);

            if (isCorrect) {
                currentRank = correctGuessers.size() + 1;
                earnedPoints = guessingWindow.awardCorrect(username, acceptedAtMillis, currentRank);
                if (earnedPoints == 0) return;
                // Ghi nhận đoán đúng — rank được gán trong synchronized block
                // → đảm bảo KHÔNG BAO GIỜ 2 người có cùng rank
                correctGuessers.add(username);
                result.rank = currentRank;

                // === TÍNH ĐIỂM NGAY LẬP TỨC (không chờ hết lượt) ===
                result.points = earnedPoints;

                // Cộng điểm vào MatchResult ngay
                MatchResult mr = matchResults.get(username);
                if (mr != null) {
                    mr.addGuessPoints(earnedPoints);
                }
            }

            // Lưu vào danh sách kết quả (cả đúng lẫn sai đều lưu)
            List<GuessResult> results = guessResults.get(painterUsername);
            if (results != null) {
                results.add(result);
            }
        }
        // === END CRITICAL SECTION ===

        // --- Gửi phản hồi cho người đoán (ngoài synchronized để không block) ---
        Envelope feedbackMsg = new Envelope(MessageType.GUESS_RESULT);
        feedbackMsg.put("correct", isCorrect);
        feedbackMsg.put("guesserUsername", username);
        feedbackMsg.put("guessContent", guess);
        feedbackMsg.put("sequenceId", result.sequenceId);
        feedbackMsg.put("elapsedMs", result.elapsedMs);
        feedbackMsg.put("points", result.points);
        feedbackMsg.put("scoringVersion", ScoringService.VERSION);
        feedbackMsg.setSender(username);

        if (isCorrect) {
            feedbackMsg.put("rank", result.rank);
            feedbackMsg.put("points", result.points);
            feedbackMsg.setContent("Chính xác! +" + result.points + " điểm (" + formatTime(result.elapsedMs) + ")");
        } else {
            feedbackMsg.setContent("Sai rồi, thử lại!");
        }

        ClientSession handler = room.getPlayer(username);
        if (handler != null) {
            handler.sendMessage(feedbackMsg);
        }

        // --- Broadcast GUESS_LOG cho cả phòng (audit trail real-time) ---
        Envelope logMsg = new Envelope(MessageType.GUESS_LOG);
        logMsg.put("sequenceId", result.sequenceId);
        logMsg.put("guesserUsername", username);
        logMsg.put("correct", isCorrect);
        logMsg.put("elapsedMs", result.elapsedMs);
        logMsg.put("processedAt", result.processedAt);
        if (!isCorrect) {
            // Không gửi nội dung đáp án sai cho người khác (tránh spoil)
            logMsg.setContent(username + " đã đoán sai.");
        } else {
            logMsg.setContent(username + " đã đoán đúng! [" + formatTime(result.elapsedMs) + "]");
        }
        room.broadcast(logMsg);

        // --- Nếu đoán đúng: broadcast bảng xếp hạng realtime ---
        if (isCorrect) {
            broadcastRankingUpdate(painterUsername);
        }

        // --- Kiểm tra kết thúc sớm ---
        if (guessingWindow.everyoneCorrect(acceptedAtMillis)) {
            cancelCurrentTimer();
            finishCurrentRound();
        }
    }

    /**
     * Broadcast bảng xếp hạng realtime cho cả phòng sau mỗi lần có người đoán đúng.
     * Bao gồm: thứ hạng, tên người chơi, thời gian phản hồi chính xác, điểm đạt được.
     * Cập nhật liên tục — mỗi lần có người đoán đúng mới, bảng xếp hạng được gửi lại.
     */
    private void broadcastRankingUpdate(String painterUsername) {
        List<GuessResult> results = guessResults.get(painterUsername);
        if (results == null) return;

        ArrayList<HashMap<String, Object>> rankingList = new ArrayList<>();

        synchronized (guessLock) {
            for (GuessResult gr : results) {
                if (gr.correct) {
                    HashMap<String, Object> entry = new HashMap<>();
                    entry.put("rank", gr.rank);
                    entry.put("username", gr.guesserUsername);
                    entry.put("elapsedMs", gr.elapsedMs);
                    entry.put("timeFormatted", formatTime(gr.elapsedMs));
                    entry.put("points", gr.points);
                    entry.put("sequenceId", gr.sequenceId);
                    rankingList.add(entry);
                }
            }
        }

        // Sắp xếp theo rank (đã đúng thứ tự nhờ synchronized, nhưng sort lại cho chắc)
        rankingList.sort((a, b) -> ((Integer) a.get("rank")).compareTo((Integer) b.get("rank")));

        Envelope rankingMsg = new Envelope(MessageType.GUESS_RANKING_UPDATE);
        rankingMsg.put("painterUsername", painterUsername);
        rankingMsg.put("rankings", rankingList);
        rankingMsg.put("correctCount", rankingList.size());
        rankingMsg.put("totalGuessers", guessingWindow.eligibleGuessers(scoringClock.getAsLong()).size());
        rankingMsg.put("scores", buildCurrentScores());
        room.broadcast(rankingMsg);
    }

    private ArrayList<HashMap<String, Object>> buildCurrentScores() {
        ArrayList<HashMap<String, Object>> scores = new ArrayList<>();
        for (String username : playerOrder) {
            MatchResult result = matchResults.get(username);
            ClientSession player = room.getPlayer(username);
            HashMap<String, Object> entry = new HashMap<>();
            entry.put("username", username);
            entry.put("displayName", player != null && player.getDisplayName() != null
                    ? player.getDisplayName() : username);
            entry.put("totalPoints", result != null ? result.getTotalPoints() : 0);
            entry.put("pointsDrawn", result != null ? result.getPointsDrawn() : 0);
            entry.put("pointsGuess", result != null ? result.getPointsGuess() : 0);
            entry.put("pointsAdjustment", result != null ? result.getPointsAdjustment() : 0);
            scores.add(entry);
        }
        return scores;
    }

    /**
     * Định dạng thời gian từ mili-giây thành chuỗi dễ đọc.
     * Ví dụ: 2345ms → "2.345s", 15678ms → "15.678s"
     */
    private String formatTime(long elapsedMs) {
        if (elapsedMs < 1000) {
            return elapsedMs + "ms";
        }
        return String.format("%.3fs", elapsedMs / 1000.0);
    }

    /**
     * Kết thúc lượt đoán hiện tại: tính điểm và chuyển sang bức tiếp theo.
     */
    /**
     * Kết thúc lượt đoán hiện tại.
     * Điểm người đoán đã được tính NGAY LẬP TỨC trong onGuessSubmitted (real-time),
     * ở đây chỉ cần tính điểm người vẽ và gửi bảng tổng kết lượt.
     */
    private synchronized void finishCurrentRound() {
        if (!gameActive || !"GUESSING".equals(currentPhase)) return;
        stopCountdown();

        String painterUsername = paintingOrder.get(currentPaintingIndex);
        if (!finalizedDrawings.add(drawingIds.get(painterUsername))) return;
        long finalizedAt = scoringClock.getAsLong();
        int totalGuessers = guessingWindow.eligibleGuessers(finalizedAt).size();
        List<Integer> correctScores = guessingWindow.correctScores();
        int correctCount = correctScores.size();

        // === Điểm người đoán đã được tính real-time trong onGuessSubmitted ===
        // (không cần tính lại ở đây — khác với phiên bản batch cũ)

        // === Tính điểm người vẽ ===
        int painterPoints = ScoringService.calculateDrawingScore(totalGuessers, correctCount);
        MatchResult painterResult = matchResults.get(painterUsername);
        if (painterResult != null) {
            painterResult.addDrawnPoints(painterPoints);
        }

        // === Gửi kết quả ROUND cho cả phòng (bao gồm full audit log) ===
        Envelope roundResult = new Envelope(MessageType.ROUND_RESULT);
        roundResult.put("painterUsername", painterUsername);
        roundResult.put("topic", selectedTopics.get(painterUsername));
        roundResult.put("painterPoints", painterPoints);
        roundResult.put("correctCount", correctCount);
        roundResult.put("totalGuessers", totalGuessers);
        roundResult.put("eligibleGuessers", guessingWindow.eligibleGuessers(finalizedAt));
        roundResult.put("minimumExposureMs", getGameService().getMinimumGuessExposureMillis());
        roundResult.put("sumOfCorrectGuessScores", correctScores.stream().mapToInt(Integer::intValue).sum());
        roundResult.put("drawingPenalty", painterPoints < 0);
        roundResult.put("drawingState", "PUBLISHED");
        roundResult.put("drawingId", drawingIds.get(painterUsername));
        roundResult.put("scoringVersion", ScoringService.VERSION);
        roundResult.put("earlyFinish", guessingWindow.everyoneCorrect(finalizedAt));
        roundResult.put("currentRound", currentRound);
        roundResult.put("maxRounds", room.getMaxRounds());
        roundResult.put("paintingIndex", currentPaintingIndex + 1);
        roundResult.put("totalPaintings", paintingOrder.size());

        boolean isEndOfRound = (currentPaintingIndex + 1 >= paintingOrder.size());
        boolean isLastRound = isEndOfRound && (currentRound >= room.getMaxRounds());
        roundResult.put("isEndOfRound", isEndOfRound);
        roundResult.put("isLastRound", isLastRound);

        // Bổ sung bảng điểm tổng lũy kế thời gian thực của mọi người chơi
        roundResult.put("scores", buildCurrentScores());

        List<GuessResult> results = guessResults.get(painterUsername);
        ArrayList<HashMap<String, Object>> guessDetailsList = new ArrayList<>();
        ArrayList<HashMap<String, Object>> guessFullLog = new ArrayList<>();

        if (results != null) {
            synchronized (guessLock) {
                for (GuessResult gr : results) {
                    // Full log: tất cả lượt đoán (đúng + sai)
                    HashMap<String, Object> logEntry = new HashMap<>();
                    logEntry.put("sequenceId", gr.sequenceId);
                    logEntry.put("username", gr.guesserUsername);
                    logEntry.put("correct", gr.correct);
                    logEntry.put("elapsedMs", gr.elapsedMs);
                    logEntry.put("timeFormatted", formatTime(gr.elapsedMs));
                    logEntry.put("processedAt", gr.processedAt);
                    logEntry.put("guessContent", gr.correct ? "[đúng]" : gr.guessContent);
                    guessFullLog.add(logEntry);

                    // Chi tiết người đoán đúng (dùng hiển thị bảng xếp hạng)
                    if (gr.correct) {
                        HashMap<String, Object> detail = new HashMap<>();
                        detail.put("username", gr.guesserUsername);
                        detail.put("rank", gr.rank);
                        detail.put("points", gr.points);
                        detail.put("elapsedMs", gr.elapsedMs);
                        detail.put("timeFormatted", formatTime(gr.elapsedMs));
                        detail.put("sequenceId", gr.sequenceId);
                        guessDetailsList.add(detail);
                    }
                }
            }
        }

        roundResult.put("guessDetails", guessDetailsList);
        roundResult.put("guessFullLog", guessFullLog);
        roundResult.put("totalGuesses", guessFullLog.size());

        try {
            getGameService().getPaintingRepository()
                    .findForMatch(match.getMatchId())
                    .stream().filter(p -> java.util.Objects.equals(p.getRoundNumber(), currentRound)
                            && p.getPainter().getUsername().equals(painterUsername))
                    .findFirst().ifPresent(p -> {
                        p.setTurnNumber(currentPaintingIndex + 1);
                        p.setResultData(roundResult.toJson());
                        getGameService().getPaintingRepository().save(p);
                    });
        } catch (Exception e) {
            System.err.println("[GameSession] Lỗi lưu kết quả lượt vẽ: " + e.getMessage());
        }

        room.broadcast(roundResult);
        latestRoundResult = roundResult;
        currentPhase = "INTERMISSION"; phaseSequence++;
        phaseDeadlineMillis = System.currentTimeMillis() + 3000;

        // Chuyển sang bức tranh tiếp theo (delay 3 giây để xem kết quả)
        currentPaintingIndex++;
        currentTimer = scheduleTask(this::showNextPainting, 3, TimeUnit.SECONDS);
    }

    // ========================================================================
    // TỔNG KẾT TRẬN ĐẤU
    // ========================================================================

    /**
     * Kết thúc trận đấu: lưu kết quả vào DB, cập nhật bảng xếp hạng,
     * gửi bảng tổng kết cho cả phòng.
     * Xóa dữ liệu của những người out giữa chừng, chỉ tổng kết cho người hoàn thành.
     */
    public synchronized void endGame() {
        if (!gameActive) return;
        gameActive = false;
        currentPhase = "";
        phaseDeadlineMillis = 0;
        stopCountdown();
        cancelCurrentTimer();

        room.setStatus(Room.RoomStatus.FINISHED);

        // === Lưu kết quả vào DB ===
        match.endMatch();
        try {
            getGameService().getMatchRepository().save(match);
        } catch (Exception e) {
            System.err.println("[GameSession] Lỗi lưu match: " + e.getMessage());
        }

        // Xóa dữ liệu của những người out giữa chừng — chỉ giữ những người chơi còn lại trong phòng (hoàn thành trận đấu)
        Set<String> activePlayers = room.getPlayerUsernames();
        matchResults.keySet().removeIf(u -> !activePlayers.contains(u));

        List<MatchResult> validResults = new ArrayList<>(matchResults.values());
        try {
            getGameService().getMatchResultRepository().saveAll(validResults);
        } catch (Exception e) {
            System.err.println("[GameSession] Lỗi lưu MatchResult: " + e.getMessage());
        }

        // === Cập nhật điểm hạng (ranking score) CHỈ CHO người chơi hoàn thành trận đấu ===
        for (MatchResult mr : validResults) {
            int totalPoints = mr.getGameplayPoints();
            // Các khoản rời trận đã cập nhật tức thì, không cộng lại lần nữa ở đây.
            if (totalPoints != 0 && mr.getUser() != null) {
                try {
                    getGameService().getUserRepository().addRankingScore(
                            mr.getUser().getUserId(), totalPoints);
                } catch (Exception e) {
                    System.err.println("[GameSession] Lỗi addRankingScore: " + e.getMessage());
                }
            }
        }

        // === Gửi bảng tổng kết trận ===
        Envelope gameResult = new Envelope(MessageType.GAME_RESULT);

        ArrayList<HashMap<String, Object>> scoreboard = new ArrayList<>();
        validResults.sort((a, b) -> b.getTotalPoints() - a.getTotalPoints());
        int rank = 1;
        for (MatchResult mr : validResults) {
            HashMap<String, Object> entry = new HashMap<>();
            entry.put("rank", rank++);
            entry.put("username", mr.getUser() != null ? mr.getUser().getUsername() : "Người chơi");
            entry.put("displayName", (mr.getUser() != null && mr.getUser().getDisplayName() != null && !mr.getUser().getDisplayName().isEmpty()) ? mr.getUser().getDisplayName() : (mr.getUser() != null ? mr.getUser().getUsername() : "Người chơi"));
            entry.put("pointsDrawn", mr.getPointsDrawn());
            entry.put("pointsGuess", mr.getPointsGuess());
            entry.put("pointsAdjustment", mr.getPointsAdjustment());
            entry.put("totalPoints", mr.getTotalPoints());
            scoreboard.add(entry);
        }
        gameResult.put("scoreboard", scoreboard);
        gameResult.put("totalRounds", room.getMaxRounds());
        gameResult.put("scoringVersion", ScoringService.VERSION);

        // Thêm chi tiết từng bức tranh
        ArrayList<HashMap<String, Object>> paintingSummary = new ArrayList<>();
        for (String painter : playerOrder) {
            if (!activePlayers.contains(painter)) continue;
            HashMap<String, Object> pInfo = new HashMap<>();
            pInfo.put("painterUsername", painter);
            pInfo.put("topic", selectedTopics.get(painter));
            pInfo.put("drawingState", drawingStates.getOrDefault(painter, "SKIPPED"));

            List<GuessResult> gResults = guessResults.get(painter);
            ArrayList<String> correctList = new ArrayList<>();
            ArrayList<String> wrongList = new ArrayList<>();
            if (gResults != null) {
                for (GuessResult gr : gResults) {
                    if (gr.correct) correctList.add(gr.guesserUsername);
                    else wrongList.add(gr.guesserUsername);
                }
            }
            // Thêm người không đoán (timeout)
            for (String player : playerOrder) {
                if (!player.equals(painter) && !correctList.contains(player) && !wrongList.contains(player)) {
                    wrongList.add(player);
                }
            }
            pInfo.put("correctGuessers", correctList);
            pInfo.put("wrongGuessers", wrongList);
            paintingSummary.add(pInfo);
        }
        gameResult.put("paintingSummary", paintingSummary);
        latestResult = gameResult;
        latestResultMembers.clear(); latestResultMembers.addAll(activePlayers);

        room.broadcast(gameResult);

        // Reset phòng về trạng thái chờ
        room.setStatus(Room.RoomStatus.WAITING);
        room.resetReadyStatus();

        // Dọn dẹp dữ liệu phiên chơi
        cleanup();

        // Cập nhật lobby
        getGameService().broadcastPlayerList();
        getGameService().broadcastRoomList();
    }

    /**
     * Hủy trận đấu (khi phòng bị giải tán giữa chừng do không đủ người).
     * Khác với endGame(): KHÔNG lưu kết quả, KHÔNG cộng điểm.
     */
    public synchronized void cancelGame() {
        cancelStartCountdown();
        if (!gameActive) return;
        gameActive = false;
        currentPhase = "";
        phaseDeadlineMillis = 0;
        stopCountdown();
        cancelCurrentTimer();

        room.setStatus(Room.RoomStatus.WAITING);
        room.resetReadyStatus();

        // Dọn dẹp dữ liệu phiên chơi
        cleanup();
    }

    /**
     * Xử lý khi một người chơi out giữa chừng (disconnect hoặc rời phòng).
     * Xoá toàn bộ dữ liệu của người chơi này khỏi trận đấu hiện tại.
     */
    public synchronized void onPlayerLeft(String username) {
        if (!gameActive || username == null || !forfeitedPlayers.add(username)) return;
        if (guessingWindow != null && "GUESSING".equals(currentPhase))
            guessingWindow.permanentlyLeft(username, scoringClock.getAsLong());
        System.out.println("[GameSession] Người chơi out giữa chừng: " + username);

        applyForfeitScoring(username);

        // Xóa dữ liệu của người out giữa chừng khỏi matchResults
        matchResults.remove(username);
        if (!submittedPlayers.contains(username)) selectedTopics.remove(username);
        topicOptions.remove(username);
        drafts.remove(username);
        if (!submittedPlayers.contains(username)) drawingStates.put(username, "SKIPPED");
        // Người đã đoán đúng vẫn được tính là thành công dù rời/mất kết nối sau đó.

        if (playerOrder != null) {
            playerOrder.remove(username);
        }

        // Phiên đang chơi được tiếp tục khi còn ít nhất hai người.
        if (room.getPlayerCount() < Room.MIN_ACTIVE_PLAYERS) {
            return;
        }

        // Nếu người thoát là painter của bức tranh đang chiếu, bỏ qua và chuyển tranh tiếp theo
        // Tranh đã nộp vẫn được chiếu dù tác giả đã rời phòng.

        // Nếu đang trong giai đoạn chọn chủ đề, kiểm tra nếu những người còn lại đã chọn hết
        if ("TOPIC_SELECT".equals(currentPhase) && selectedTopics.keySet().containsAll(playerOrder)) {
            cancelCurrentTimer();
            startDrawingPhase();
            return;
        }

        // Nếu đang trong giai đoạn vẽ, kiểm tra nếu những người còn lại đã nộp hết
        if ("DRAWING".equals(currentPhase) && submittedPlayers.containsAll(playerOrder)) {
            cancelCurrentTimer();
            startGuessingPhase();
            return;
        }

        // Nếu đang trong giai đoạn đoán tranh, kiểm tra nếu những người còn lại đã đoán đúng hết
        if ("GUESSING".equals(currentPhase) && guessingWindow.everyoneCorrect(scoringClock.getAsLong())) {
            cancelCurrentTimer();
            finishCurrentRound();
        }
    }

    /**
     * Áp dụng điểm xếp hạng ngay khi một người rời trận đang diễn ra.
     * Người rời trận bị trừ 20 điểm; mỗi người còn trong phòng được cộng 20.
     */
    private void applyForfeitScoring(String leaverUsername) {
        try {
            User leaver = getGameService().getUserRepository().findByUsername(leaverUsername);
            List<Integer> remainingIds = new ArrayList<>();
            for (String remainingUsername : room.getPlayerUsernames()) {
                User remaining = getGameService().getUserRepository().findByUsername(remainingUsername);
                if (remaining != null && remaining.getUserId() != null) {
                    remainingIds.add(remaining.getUserId());
                }
            }
            if (leaver != null && leaver.getUserId() != null)
                getGameService().getUserRepository().applyLeaveScores(leaver.getUserId(), remainingIds, FORFEIT_POINTS);
            for (String username : room.getPlayerUsernames()) {
                MatchResult remaining = matchResults.get(username);
                if (remaining != null) remaining.addAdjustmentPoints(FORFEIT_POINTS);
            }
            room.broadcast(new Envelope(MessageType.GUESS_RANKING_UPDATE).put("scores", buildCurrentScores()));

            Envelope notice = new Envelope(MessageType.ROOM_CHAT_RECEIVE);
            notice.put("senderDisplayName", "Hệ thống");
            notice.put("content", leaverUsername + " đã rời trận: bị trừ " + FORFEIT_POINTS
                    + " điểm; mỗi người chơi còn lại được cộng " + FORFEIT_POINTS + " điểm.");
            room.broadcast(notice);
            getGameService().broadcastPlayerList();
        } catch (Exception e) {
            System.err.println("[GameSession] Lỗi cập nhật điểm khi người chơi bỏ trận: " + e.getMessage());
        }
    }

    // ========================================================================
    // TÍNH ĐIỂM
    // ========================================================================

    public synchronized void onConnectionChanged(String username, boolean connected) {
        if (guessingWindow != null && "GUESSING".equals(currentPhase))
            guessingWindow.connectionChanged(username, connected, scoringClock.getAsLong());
    }

    public synchronized void onGuessSubmitted(String username, Envelope request) {
        if (!"GUESSING".equals(currentPhase) || currentPaintingIndex >= paintingOrder.size()) return;
        String painter = paintingOrder.get(currentPaintingIndex);
        if (!Objects.equals(request.getString("drawingId"), drawingIds.get(painter))) return;
        onGuessSubmitted(username, request.getContent());
    }

    // ========================================================================
    // TIỆN ÍCH
    // ========================================================================

    /**
     * Chuẩn hóa đáp án: chuyển thường, bỏ dấu cách thừa, trim.
     */
    private String normalizeAnswer(String answer) {
        if (answer == null) return "";
        String normalized = java.text.Normalizer.normalize(answer.toLowerCase(Locale.ROOT),
                java.text.Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "")
                .replace('đ', 'd');
        return normalized.replaceAll("[^\\p{L}\\p{N}]+", " ").trim().replaceAll("\\s+", " ");
    }

    /** Hide room chat that would reveal any selected answer during the active game. */
    public synchronized boolean containsSecretAnswer(String message) {
        if (!gameActive || message == null || message.isBlank()) return false;
        String normalizedMessage = normalizeAnswer(message);
        if (normalizedMessage.isEmpty()) return false;
        String paddedMessage = " " + normalizedMessage + " ";
        for (String topic : selectedTopics.values()) {
            String normalizedTopic = normalizeAnswer(topic);
            if (!normalizedTopic.isEmpty() && paddedMessage.contains(" " + normalizedTopic + " ")) {
                return true;
            }
        }
        return false;
    }

    /**
     * Bắt đầu đồng hồ đếm ngược gửi cập nhật mỗi giây.
     */
    private void startCountdown(int totalSeconds, String phase) {
        long version = phaseSequence;
        countdownTimer = scheduler.scheduleAtFixedRate(() -> {
            synchronized (this) {
            if (version == phaseSequence && gameActive) {
                Envelope timerMsg = new Envelope(MessageType.TIMER_UPDATE);
                timerMsg.put("remaining", Math.max(0, (phaseDeadlineMillis - System.currentTimeMillis() + 999) / 1000));
                timerMsg.put("phase", phase);
                stamp(timerMsg);
                room.broadcast(timerMsg);
            }
            }
        }, 0, 1, TimeUnit.SECONDS);
    }

    /**
     * Dừng đồng hồ đếm ngược.
     */
    private void stopCountdown() {
        if (countdownTimer != null && !countdownTimer.isCancelled()) {
            countdownTimer.cancel(false);
        }
    }

    /**
     * Hủy timer hiện tại.
     */
    private void cancelCurrentTimer() {
        if (currentTimer != null && !currentTimer.isCancelled()) {
            currentTimer.cancel(false);
        }
    }

    /**
     * Dọn dẹp dữ liệu sau trận đấu.
     */
    private void cleanup() {
        currentPhase = "";
        phaseDeadlineMillis = 0;
        selectedTopics.clear();
        topicOptions.clear();
        submittedDrawings.clear();
        submittedPlayers.clear();
        guessResults.clear();
        correctGuessers.clear();
        matchResults.clear();
        currentPaintingIndex = 0;
        currentRound = 1;
    }

    /**
     * Tham chiếu tới GameService — được thiết lập từ Room constructor.
     */
    private GameService GameServiceRef;

    /**
     * Trả về tham chiếu tới GameService.
     */
    private GameService getGameService() {
        return GameServiceRef;
    }

    /**
     * Thiết lập tham chiếu tới GameService.
     * Được gọi từ Room constructor.
     */
    public void setGameService(GameService GameService) {
        this.GameServiceRef = GameService;
    }

    /** Sends a reconnecting player the current match phase and its saved data. */
    public synchronized void sendReconnectState(ClientSession handler) {
        if (handler == null || room.getPlayer(handler.getUsername()) != handler) return;
        List<Envelope> events = new ArrayList<>();
        if (!gameActive) {
            boolean completedMember = latestResult != null && latestResultMembers.contains(handler.getUsername());
            if (completedMember) {
                events.add(new Envelope(MessageType.GAME_START));
                events.add(latestResult);
            }
            handler.sendMessage(new Envelope(MessageType.GAME_STATE_SNAPSHOT).put("phase", completedMember ? "RESULTS" : "WAITING")
                    .put("events", events).put("serverTime", System.currentTimeMillis()));
            return;
        }
        if (playerOrder == null || !playerOrder.contains(handler.getUsername())) return;

        Envelope start = new Envelope(MessageType.GAME_START);
        start.put("playerOrder", new ArrayList<>(playerOrder));
        start.put("currentRound", currentRound);
        start.put("maxRounds", room.getMaxRounds());
        start.put("scores", buildCurrentScores());
        start.put("scoringVersion", ScoringService.VERSION);
        events.add(start);

        int remainingSeconds = (int) Math.max(0,
                Math.ceil((phaseDeadlineMillis - System.currentTimeMillis()) / 1000.0));
        switch (currentPhase) {
            case "TOPIC_SELECT" -> {
                if (selectedTopics.containsKey(handler.getUsername())) {
                    Envelope confirmed = new Envelope(MessageType.TOPIC_CONFIRMED);
                    confirmed.put("topic", selectedTopics.get(handler.getUsername()));
                    confirmed.put("autoSelected", false);
                    events.add(confirmed);
                } else {
                    Envelope options = new Envelope(MessageType.TOPIC_OPTIONS);
                    options.put("topics", new ArrayList<>(topicOptions.getOrDefault(handler.getUsername(), List.of())));
                    options.put("timeLimit", remainingSeconds);
                    options.put("currentRound", currentRound);
                    options.put("maxRounds", room.getMaxRounds());
                    options.put("drawingId", drawingIds.get(handler.getUsername()));
                    events.add(options);
                }
            }
            case "DRAWING" -> {
                Envelope confirmed = new Envelope(MessageType.TOPIC_CONFIRMED);
                confirmed.put("topic", selectedTopics.get(handler.getUsername()));
                confirmed.put("autoSelected", false);
                events.add(confirmed);

                Envelope drawStart = new Envelope(MessageType.DRAW_START);
                drawStart.put("timeLimit", remainingSeconds);
                drawStart.put("currentRound", currentRound);
                drawStart.put("maxRounds", room.getMaxRounds());
                drawStart.put("submitted", submittedPlayers.contains(handler.getUsername()));
                drawStart.put("drawingId", drawingIds.get(handler.getUsername()));
                drawStart.put("draftVersion", draftVersions.getOrDefault(handler.getUsername(), 0L));
                events.add(drawStart);

                Envelope savedDrawing = new Envelope(MessageType.DRAWING_RESUME);
                List<StrokeDTO> strokes = submittedPlayers.contains(handler.getUsername())
                        ? submittedDrawings.getOrDefault(handler.getUsername(), List.of()) : drafts.getOrDefault(handler.getUsername(), List.of());
                synchronized (strokes) {
                    savedDrawing.put("drawData", new ArrayList<>(strokes));
                }
                events.add(savedDrawing);
            }
            case "GUESSING" -> {
                if (currentPaintingIndex < 0 || currentPaintingIndex >= paintingOrder.size()) return;
                String painter = paintingOrder.get(currentPaintingIndex);
                Envelope guessStart = new Envelope(MessageType.GUESS_START);
                guessStart.put("painterUsername", painter);
                guessStart.put("drawingId", drawingIds.get(painter));
                guessStart.put("scoringVersion", ScoringService.VERSION);
                guessStart.put("paintingIndex", currentPaintingIndex + 1);
                guessStart.put("totalPaintings", paintingOrder.size());
                guessStart.put("timeLimit", remainingSeconds);
                guessStart.put("currentRound", currentRound);
                guessStart.put("maxRounds", room.getMaxRounds());
                guessStart.put("alreadyGuessed", correctGuessers.contains(handler.getUsername()));
                events.add(guessStart);

                Envelope painting = new Envelope(MessageType.PAINTING_DISPLAY);
                painting.put("painterUsername", painter);
                painting.put("drawingId", drawingIds.get(painter));
                painting.put("drawData", new ArrayList<>(submittedDrawings.getOrDefault(painter, List.of())));
                painting.put("hint", TopicPool.generateHint(selectedTopics.get(painter)));
                painting.put("currentRound", currentRound);
                painting.put("maxRounds", room.getMaxRounds());
                events.add(painting);
            }
            case "INTERMISSION" -> { if (latestRoundResult != null) events.add(latestRoundResult); }
            default -> { }
        }
        if (!currentPhase.isBlank()) {
            Envelope timer = new Envelope(MessageType.TIMER_UPDATE);
            timer.put("remaining", remainingSeconds);
            timer.put("phase", currentPhase);
            events.add(timer);
        }
        Envelope snapshot = new Envelope(MessageType.GAME_STATE_SNAPSHOT);
        stamp(snapshot);
        snapshot.put("phase", currentPhase);
        snapshot.put("events", events);
        snapshot.put("players", room.getPlayerListData());
        snapshot.put("scores", buildCurrentScores());
        snapshot.put("drawingState", drawingStates.get(handler.getUsername()));
        handler.sendMessage(snapshot);
    }


    private ScheduledFuture<?> scheduleTask(Runnable action, long delay, TimeUnit unit) {
        long version = phaseSequence;
        return scheduler.schedule(() -> {
            synchronized (this) { if (gameActive && version == phaseSequence) action.run(); }
        }, delay, unit);
    }

    private void stamp(Envelope message) {
        message.put("serverTime", System.currentTimeMillis());
        message.put("phaseDeadline", phaseDeadlineMillis);
        message.put("phaseSequence", phaseSequence);
    }

    private boolean canDraw(String username, Envelope request, boolean strictId) {
        ClientSession player = room.getPlayer(username);
        String id = request.getString("drawingId");
        return gameActive && "DRAWING".equals(currentPhase) && System.currentTimeMillis() < phaseDeadlineMillis
                && player != null && player.isConnected() && playerOrder.contains(username)
                && !submittedPlayers.contains(username) && selectedTopics.containsKey(username)
                && (!strictId && id == null || Objects.equals(id, drawingIds.get(username)));
    }

    public synchronized void onDraftSaved(String username, Envelope request) {
        if (!canDraw(username, request, true)) return;
        long version = request.getData().path("draftVersion").asLong(-1);
        if (version > draftVersions.getOrDefault(username, -1L)) {
            List<StrokeDTO> data = JsonUtil.convertList(request.get("drawData"), StrokeDTO.class);
            if (data == null || data.size() > 20000) return;
            drafts.put(username, new ArrayList<>(data));
            draftVersions.put(username, version);
        }
        room.getPlayer(username).sendMessage(new Envelope(MessageType.DRAWING_DRAFT_ACK)
                .put("drawingId", drawingIds.get(username)).put("draftVersion", draftVersions.getOrDefault(username, 0L)));
    }

    private void acknowledgeSubmission(String username) {
        ClientSession player = room.getPlayer(username);
        if (player != null) player.sendMessage(Envelope.success(MessageType.DRAWING_SUBMITTED, "Đã nhận tranh.")
                .put("drawingId", drawingIds.get(username)).put("state", drawingStates.get(username)));
    }

    public synchronized void forgetFinishedRecovery(String username) { latestResultMembers.remove(username); }
}
