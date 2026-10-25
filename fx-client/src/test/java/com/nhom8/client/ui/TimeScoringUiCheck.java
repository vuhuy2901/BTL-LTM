package com.nhom8.client.ui;

import com.nhom8.client.MainApplication;
import com.nhom8.client.ws.GameWebSocketClient;
import com.nhom8.common.message.*;
import javafx.application.*;
import javafx.scene.*;
import javafx.scene.control.Label;
import javafx.scene.layout.*;
import javafx.stage.Stage;
import java.util.*;

/** Kiểm tra giao diện sử dụng đúng điểm Server, kể cả điểm âm và thưởng/phạt riêng. */
public class TimeScoringUiCheck extends Application {
    private static Throwable failure;
    @Override public void start(Stage stage) {
        try {
            MainApplication app = new MainApplication();
            RecoveryUiCheck.set(app,"primaryStage",stage); RecoveryUiCheck.set(app,"currentUsername","p1");
            RecoveryUiCheck.set(app,"webSocketClient",new GameWebSocketClient("ws://localhost:8080/ws/game",e -> {}) {
                @Override public void send(Envelope event) {}
                @Override public boolean isConnected() { return true; }
            });
            stage.setScene(new Scene(new VBox(),1100,760));
            app.showGameScreen("score-room",new Envelope(MessageType.GAME_START).put("scores",List.of(Map.of("username","p1","totalPoints",0))));
            GameScreen game = (GameScreen) RecoveryUiCheck.field(app,"gameScreen");
            game.handleMessage(new Envelope(MessageType.GUESS_START).put("painterUsername","p2").put("drawingId","current-picture").put("totalPaintings",3));
            game.handleMessage(new Envelope(MessageType.GUESS_RESULT).putBoolean("correct",true).put("points",100).put("rank",1).put("elapsedMs",20000));
            game.handleMessage(new Envelope(MessageType.ROUND_RESULT).put("topic","con mèo").put("painterUsername","p2")
                    .put("painterPoints",75).put("correctCount",3).put("totalGuessers",4).put("guessDetails",List.of(
                            Map.of("username","p1","points",100,"timeFormatted","0.000s"),
                            Map.of("username","p3","points",75,"timeFormatted","15.000s"),
                            Map.of("username","p4","points",50,"timeFormatted","29.999s"))));
            Platform.runLater(() -> {
                try {
                    require(((Label) RecoveryUiCheck.field(game,"guessFeedbackLabel")).getText().contains("+100"),"Điểm đúng phải lấy từ Server");
                    String details = ((Label) RecoveryUiCheck.field(game,"roundResultDetails")).getText();
                    require(details.contains("75 điểm") && details.contains("100 × 3/4") && details.contains("3/4"),"Kết quả lượt phải hiển thị tỷ lệ điểm vẽ và người đủ điều kiện");
                    ((Parent) RecoveryUiCheck.field(game,"phaseAnnouncementOverlay")).setVisible(false);
                    stage.getScene().getRoot().applyCss(); stage.getScene().getRoot().layout();
                    var image = stage.getScene().snapshot(null);
                    var bitmap = new java.awt.image.BufferedImage((int)image.getWidth(),(int)image.getHeight(),java.awt.image.BufferedImage.TYPE_INT_ARGB);
                    for(int y=0;y<bitmap.getHeight();y++) for(int x=0;x<bitmap.getWidth();x++) bitmap.setRGB(x,y,image.getPixelReader().getArgb(x,y));
                    javax.imageio.ImageIO.write(bitmap,"png",new java.io.File("target/time-scoring-preview.png"));
                    game.handleMessage(new Envelope(MessageType.GAME_RESULT).put("scoreboard",List.of(Map.of("rank",1,"username","p1",
                            "pointsDrawn",-20,"pointsGuess",100,"pointsAdjustment",20,"totalPoints",100))));
                    Platform.runLater(() -> {
                        try {
                            VBox scores = (VBox) RecoveryUiCheck.field(game,"gameOverScoresBox"); scores.applyCss(); scores.layout();
                            require(scores.lookupAll(".label").stream().anyMatch(n -> n instanceof Label l && l.getText().contains("Vẽ: -20")
                                    && l.getText().contains("Thưởng/phạt: +20")),"Tổng kết phải hiển thị đúng dấu và khoản thưởng/phạt");
                            System.out.println("PASS: authoritative guess score, exposure count, artist score and signed final breakdown.");
                        } catch(Throwable error) { failure=error; error.printStackTrace(); }
                        finally { Platform.exit(); }
                    });
                } catch(Throwable error) { failure=error; error.printStackTrace(); Platform.exit(); }
            });
        } catch(Throwable error) { failure=error; error.printStackTrace(); Platform.exit(); }
    }
    private void require(boolean valid,String message) { if(!valid) throw new AssertionError(message); }
    public static void main(String[] args) { launch(args); if(failure!=null) System.exit(1); }
}
