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
 * Công thức điểm người đoán: Điểm(r) = 100 - (r-1) × floor(80/(m-1))
 * Công thức điểm người vẽ: floor((số người đoán đúng / tổng số người đoán) × 100)
 */
public class GameSession {

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

    /** Timer gửi cập nhật đồng hồ mỗi giây */
    private ScheduledFuture<?> countdownTimer;

    /** Kết quả điểm trận đấu (username → MatchResult) */
    private ConcurrentHashMap<String, MatchResult> matchResults;

    /** Cờ đánh dấu trận đấu đang diễn ra */
    private boolean gameActive;

    /** Bộ đếm nanoTime khi bắt đầu lượt đoán — dùng cho tính thời gian chính xác */
    private volatile long guessStartNanoTime;

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
        this.scheduler = Executors.newScheduledThreadPool(2);
        this.gameActive = false;
    }

    // ========================================================================
    // GIAI ĐOẠN 0: KHỞI TẠO TRẬN ĐẤU
    // ========================================================================

    /**
     * Bắt đầu trận đấu: cố định thứ tự người chơi, tạo bản ghi Match trong DB,
     * và khởi động giai đoạn chọn chủ đề.
     */
    public void startGame() {
        gameActive = true;
        room.setStatus(Room.RoomStatus.PLAYING);

        // Cố định thứ tự người chơi
        playerOrder = new ArrayList<>(room.getPlayerUsernames());

        // Tạo bản ghi trận đấu trong DB
        match = new Match(room.getRoomId());
        getGameService().getMatchRepository().save(match);

        // Khởi tạo MatchResult cho mỗi người chơi
        for (String username : playerOrder) {
            User user = getGameService().getUserRepository().findByUsername(username);
            MatchResult result = new MatchResult(match, user);
            matchResults.put(username, result);
        }

        // Thông báo trận đấu bắt đầu
        Envelope startMsg = new Envelope(MessageType.GAME_START);
        startMsg.put("playerOrder", new ArrayList<>(playerOrder));
        room.broadcast(startMsg);

        // Bắt đầu giai đoạn chọn chủ đề
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
    private void startTopicSelection() {
        int playerCount = playerOrder.size();
        List<List<String>> allTopicSets = TopicPool.getTopicOptionsForRoom(playerCount);

        for (int i = 0; i < playerCount; i++) {
            String username = playerOrder.get(i);
            List<String> topics = allTopicSets.get(i);
            topicOptions.put(username, topics);

            // Gửi 3 chủ đề cho Client
            Envelope msg = new Envelope(MessageType.TOPIC_OPTIONS);
            msg.put("topics", new ArrayList<>(topics));
            msg.put("timeLimit", TOPIC_SELECT_TIME / 1000);

            ClientSession handler = room.getPlayer(username);
            if (handler != null) {
                handler.sendMessage(msg);
            }
        }

        // Bắt đầu đếm ngược 10 giây
        startCountdown(TOPIC_SELECT_TIME / 1000, "TOPIC_SELECT");

        // Timer hết giờ chọn chủ đề
        currentTimer = scheduler.schedule(() -> {
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
    public void onTopicSelected(String username, String topic) {
        if (!gameActive) return;
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
    private void startDrawingPhase() {
        stopCountdown();
        submittedPlayers.clear();

        int drawingTime = room.getDrawTime() * 1000;
        Envelope drawStartMsg = new Envelope(MessageType.DRAW_START);
        drawStartMsg.put("timeLimit", room.getDrawTime());
        room.broadcast(drawStartMsg);

        // Đếm ngược
        startCountdown(room.getDrawTime(), "DRAWING");

        // Timer hết giờ vẽ
        currentTimer = scheduler.schedule(() -> {
            // Người chưa nộp → coi như nộp tranh trắng
            for (String username : playerOrder) {
                if (!submittedPlayers.contains(username)) {
                    submittedDrawings.putIfAbsent(username, new ArrayList<>());
                    submittedPlayers.add(username);
                }
                
                // Lưu painting vào DB cho tất cả người chơi
                String topic = selectedTopics.get(username);
                User painter = getGameService().getUserRepository().findByUsername(username);
                if (painter != null && topic != null) {
                    Painting painting = new Painting(match, painter, topic);
                    painting.setStrokeData(submittedDrawings.getOrDefault(username, new ArrayList<>()).toString());
                    getGameService().getPaintingRepository().save(painting);
                }
            }
            startGuessingPhase();
        }, drawingTime, TimeUnit.MILLISECONDS);
    }

    /**
     * Xử lý khi nhận từng phần dữ liệu vẽ (chunk) từ người chơi, hoặc thao tác Undo/Clear.
     */
    @SuppressWarnings("unchecked")
    public void onDrawingDataReceived(String username, Envelope request) {
        if (!gameActive) return;
        
        List<StrokeDTO> drawDataList = JsonUtil.convertList(request.get("drawData"), StrokeDTO.class);
        if (drawDataList == null || drawDataList.isEmpty()) return;

        List<StrokeDTO> currentData = submittedDrawings.computeIfAbsent(username, k -> Collections.synchronizedList(new ArrayList<>()));

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
    public void onDrawingSubmitted(String username, Envelope request) {
        if (!gameActive) return;
        if (submittedPlayers.contains(username)) return;

        List<StrokeDTO> drawDataList = JsonUtil.convertList(request.get("drawData"), StrokeDTO.class);
        if (drawDataList != null && !drawDataList.isEmpty()) {
            submittedDrawings.put(username, Collections.synchronizedList(new ArrayList<>(drawDataList)));
        } else {
            submittedDrawings.putIfAbsent(username, Collections.synchronizedList(new ArrayList<>()));
        }
        submittedPlayers.add(username);

        // Lưu painting vào DB
        String topic = selectedTopics.get(username);
        User painter = getGameService().getUserRepository().findByUsername(username);
        if (painter != null && topic != null) {
            Painting painting = new Painting(match, painter, topic);
            painting.setStrokeData(submittedDrawings.getOrDefault(username, new ArrayList<>()).toString()); // Lưu dạng text
            getGameService().getPaintingRepository().save(painting);
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
    private void startGuessingPhase() {
        stopCountdown();
        currentPaintingIndex = 0;
        showNextPainting();
    }

    /**
     * Hiển thị bức tranh tiếp theo cho cả phòng đoán.
     */
    private void showNextPainting() {
        if (currentPaintingIndex >= playerOrder.size()) {
            // Đã đoán hết tất cả bức tranh → tổng kết
            endGame();
            return;
        }

        correctGuessers.clear();
        String painterUsername = playerOrder.get(currentPaintingIndex);
        String topic = selectedTopics.get(painterUsername);
        List<StrokeDTO> drawData = submittedDrawings.getOrDefault(painterUsername, new ArrayList<>());
        String hint = TopicPool.generateHint(topic);

        // Khởi tạo danh sách kết quả đoán cho bức tranh này
        guessResults.put(painterUsername, Collections.synchronizedList(new ArrayList<>()));

        int guessingTime = room.getDrawTime() * 1000 / 2; // Đoán tranh bằng nửa thời gian vẽ
        if (guessingTime < 15000) guessingTime = 15000;
        
        Envelope guessStartMsg = new Envelope(MessageType.GUESS_START);
        guessStartMsg.put("painterUsername", painterUsername);
        guessStartMsg.put("paintingIndex", currentPaintingIndex + 1);
        guessStartMsg.put("totalPaintings", playerOrder.size());
        guessStartMsg.put("timeLimit", guessingTime / 1000);
        room.broadcast(guessStartMsg);

        // Gửi dữ liệu bức tranh
        Envelope paintingMsg = new Envelope(MessageType.PAINTING_DISPLAY);
        paintingMsg.put("painterUsername", painterUsername);
        paintingMsg.put("drawData", new ArrayList<>(drawData));
        paintingMsg.put("hint", hint);
        room.broadcast(paintingMsg);

        // Đếm ngược
        guessStartNanoTime = System.nanoTime();
        guessSequenceCounter = 0;
        startCountdown(guessingTime / 1000, "GUESSING");

        // Timer hết giờ đoán
        currentTimer = scheduler.schedule(() -> {
            finishCurrentRound();
        }, guessingTime, TimeUnit.MILLISECONDS);
    }

    /**
     * Xử lý đáp án đoán tranh từ người chơi — REAL-TIME, CHÍNH XÁC.
     *
     * Cơ chế hoạt động tương tự message queue (RabbitMQ):
     * 1. Mỗi đáp án được gán sequenceId tăng đơn điệu trong synchronized block
     *    → đảm bảo thứ tự tuyệt đối dù nhiều Client gửi đồng thời.
     * 2. Thời gian phản hồi được ghi bằng System.nanoTime() (chính xác nano-giây)
     *    → phân biệt được 2 người đoán đúng cách nhau vài mili-giây.
     * 3. Mọi lượt đoán (cả đúng lẫn sai) đều được lưu lại (audit log).
     * 4. Khi đoán đúng → tính điểm NGAY LẬP TỨC và broadcast bảng xếp hạng
     *    real-time cho cả phòng, không chờ hết giờ.
     */
    public void onGuessSubmitted(String username, String guess) {
        if (!gameActive) return;

        String painterUsername = playerOrder.get(currentPaintingIndex);

        // Người vẽ không được đoán tranh của mình
        if (username.equals(painterUsername)) return;

        // Đã đoán đúng rồi thì không nhận thêm đáp án
        if (correctGuessers.contains(username)) return;

        // === CRITICAL SECTION: synchronized để đảm bảo thứ tự tuyệt đối ===
        // Giống consumer trong RabbitMQ — chỉ 1 message được xử lý tại 1 thời điểm
        GuessResult result;
        boolean isCorrect;
        int currentRank = 0;
        int earnedPoints = 0;

        synchronized (guessLock) {
            // Ghi nhận thời gian chính xác bằng nanoTime
            long elapsedNanos = System.nanoTime() - guessStartNanoTime;

            // Gán sequenceId tăng đơn điệu — thứ tự xử lý tuyệt đối
            long seqId = ++guessSequenceCounter;

            String topic = selectedTopics.get(painterUsername);
            isCorrect = normalizeAnswer(guess).equals(normalizeAnswer(topic));

            // Tạo GuessResult với đầy đủ thông tin audit
            result = new GuessResult(seqId, username, guess, isCorrect, elapsedNanos);

            if (isCorrect) {
                // Ghi nhận đoán đúng — rank được gán trong synchronized block
                // → đảm bảo KHÔNG BAO GIỜ 2 người có cùng rank
                correctGuessers.add(username);
                currentRank = correctGuessers.size();
                result.rank = currentRank;

                // === TÍNH ĐIỂM NGAY LẬP TỨC (không chờ hết lượt) ===
                int totalGuessers = playerOrder.size() - 1;
                earnedPoints = calculateGuessPoints(currentRank, totalGuessers);
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
        feedbackMsg.setSender(username);

        if (isCorrect) {
            feedbackMsg.put("rank", result.rank);
            feedbackMsg.put("points", result.points);
            feedbackMsg.setContent("Đoán đúng! Hạng #" + result.rank
                    + " (" + formatTime(result.elapsedMs) + ") — +" + result.points + " điểm");
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
        int totalGuessers = playerOrder.size() - 1;
        if (correctGuessers.size() == totalGuessers) {
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
        rankingMsg.put("totalGuessers", playerOrder.size() - 1);
        room.broadcast(rankingMsg);
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
    private void finishCurrentRound() {
        stopCountdown();

        String painterUsername = playerOrder.get(currentPaintingIndex);
        int totalGuessers = playerOrder.size() - 1;
        int correctCount = correctGuessers.size();

        // === Điểm người đoán đã được tính real-time trong onGuessSubmitted ===
        // (không cần tính lại ở đây — khác với phiên bản batch cũ)

        // === Tính điểm người vẽ ===
        int painterPoints = 0;
        if (totalGuessers > 0) {
            if (correctCount == 0) {
                painterPoints = -20; // Trừ 20 điểm nếu không ai đoán đúng
            } else {
                painterPoints = (int) Math.floor((double) correctCount / totalGuessers * 100);
            }
        }
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
        roundResult.put("earlyFinish", totalGuessers > 0 && correctCount == totalGuessers);

        // Bổ sung bảng điểm tổng lũy kế thời gian thực của mọi người chơi
        ArrayList<HashMap<String, Object>> currentScores = new ArrayList<>();
        for (String u : playerOrder) {
            MatchResult mr = matchResults.get(u);
            HashMap<String, Object> sc = new HashMap<>();
            sc.put("username", u);
            sc.put("totalPoints", mr != null ? mr.getTotalPoints() : 0);
            sc.put("pointsDrawn", mr != null ? mr.getPointsDrawn() : 0);
            sc.put("pointsGuess", mr != null ? mr.getPointsGuess() : 0);
            currentScores.add(sc);
        }
        roundResult.put("scores", currentScores);

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

        room.broadcast(roundResult);

        // Chuyển sang bức tranh tiếp theo (delay 3 giây để xem kết quả)
        currentPaintingIndex++;
        scheduler.schedule(this::showNextPainting, 3, TimeUnit.SECONDS);
    }

    // ========================================================================
    // TỔNG KẾT TRẬN ĐẤU
    // ========================================================================

    /**
     * Kết thúc trận đấu: lưu kết quả vào DB, cập nhật bảng xếp hạng,
     * gửi bảng tổng kết cho cả phòng.
     */
    public synchronized void endGame() {
        if (!gameActive) return;
        gameActive = false;
        stopCountdown();
        cancelCurrentTimer();

        room.setStatus(Room.RoomStatus.FINISHED);

        // === Lưu kết quả vào DB ===
        match.endMatch();
        getGameService().getMatchRepository().save(match);

        List<MatchResult> allResults = new ArrayList<>(matchResults.values());
        getGameService().getMatchResultRepository().saveAll(allResults);

        // === Cập nhật điểm hạng (ranking score) cho từng người chơi ===
        for (MatchResult mr : allResults) {
            int totalPoints = mr.getTotalPoints();
            if (totalPoints > 0 && mr.getUser() != null) {
                getGameService().getUserRepository().addRankingScore(
                        mr.getUser().getUserId(), totalPoints);
            }
        }

        // === Gửi bảng tổng kết trận ===
        Envelope gameResult = new Envelope(MessageType.GAME_RESULT);

        ArrayList<HashMap<String, Object>> scoreboard = new ArrayList<>();
        allResults.sort((a, b) -> b.getTotalPoints() - a.getTotalPoints());
        int rank = 1;
        for (MatchResult mr : allResults) {
            HashMap<String, Object> entry = new HashMap<>();
            entry.put("rank", rank++);
            entry.put("username", mr.getUser() != null ? mr.getUser().getUsername() : "Người chơi");
            entry.put("pointsDrawn", mr.getPointsDrawn());
            entry.put("pointsGuess", mr.getPointsGuess());
            entry.put("totalPoints", mr.getTotalPoints());
            scoreboard.add(entry);
        }
        gameResult.put("scoreboard", scoreboard);

        // Thêm chi tiết từng bức tranh
        ArrayList<HashMap<String, Object>> paintingSummary = new ArrayList<>();
        for (String painter : playerOrder) {
            HashMap<String, Object> pInfo = new HashMap<>();
            pInfo.put("painterUsername", painter);
            pInfo.put("topic", selectedTopics.get(painter));

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

    // ========================================================================
    // TÍNH ĐIỂM
    // ========================================================================

    /**
     * Tính điểm cho người đoán đúng theo công thức:
     * Điểm(r) = 100 - (r-1) × floor(80/(m-1))
     * 
     * Trong đó:
     * - r: thứ hạng đoán đúng (1 = nhanh nhất)
     * - m: tổng số người tham gia đoán bức tranh đó
     * 
     * @param rank thứ hạng đoán đúng
     * @param totalGuessers tổng số người đoán
     * @return điểm đạt được (tối thiểu 20)
     */
    private int calculateGuessPoints(int rank, int totalGuessers) {
        if (totalGuessers <= 1) return 100;
        int step = (int) Math.floor(80.0 / (totalGuessers - 1));
        int points = 100 - (rank - 1) * step;
        return Math.max(points, 20); // Tối thiểu 20 điểm
    }

    // ========================================================================
    // TIỆN ÍCH
    // ========================================================================

    /**
     * Chuẩn hóa đáp án: chuyển thường, bỏ dấu cách thừa, trim.
     */
    private String normalizeAnswer(String answer) {
        if (answer == null) return "";
        return answer.trim().toLowerCase().replaceAll("\\s+", " ");
    }

    /**
     * Bắt đầu đồng hồ đếm ngược gửi cập nhật mỗi giây.
     */
    private void startCountdown(int totalSeconds, String phase) {
        final int[] remaining = {totalSeconds};

        countdownTimer = scheduler.scheduleAtFixedRate(() -> {
            if (remaining[0] >= 0) {
                Envelope timerMsg = new Envelope(MessageType.TIMER_UPDATE);
                timerMsg.put("remaining", remaining[0]);
                timerMsg.put("phase", phase);
                room.broadcast(timerMsg);
                remaining[0]--;
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
        selectedTopics.clear();
        topicOptions.clear();
        submittedDrawings.clear();
        submittedPlayers.clear();
        guessResults.clear();
        correctGuessers.clear();
        matchResults.clear();
        currentPaintingIndex = 0;
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
}


