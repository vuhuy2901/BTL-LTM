package com.nhom8.client.ui;

import com.nhom8.client.MainApplication;
import com.nhom8.common.message.Envelope;
import javafx.animation.FadeTransition;
import javafx.animation.PauseTransition;
import javafx.animation.SequentialTransition;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.stage.Modality;
import javafx.stage.Popup;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.util.Duration;

import java.util.function.Consumer;
import java.util.function.BiConsumer;

/**
 * Hệ thống Modal Dialog và Toast Notification phong cách Doodle Sketch
 * Thay thế hoàn toàn các Alert / TextInputDialog mặc định của JavaFX.
 */
public class DoodleDialog {

    public static void roomInvitation(Stage owner, Envelope invitation, Consumer<Boolean> callback) {
        if (!Platform.isFxApplicationThread()) {
            Platform.runLater(() -> roomInvitation(owner, invitation, callback));
            return;
        }
        ModalOverlay[] dialog = new ModalOverlay[1];
        VBox box = new VBox(12);
        box.getStyleClass().add("doodle-dialog-box");
        box.setAlignment(Pos.CENTER);
        box.setMaxWidth(540);

        Label title = new Label("💌 Lời mời vào phòng");
        title.setStyle("-fx-font-family: 'Amatic SC'; -fx-font-size: 34px; -fx-font-weight: bold; -fx-text-fill: -fx-ink;");
        String inviter = invitationText(invitation, "inviterDisplayName",
                invitationText(invitation, "inviterUsername", "Một người bạn"));
        Label sender = new Label(inviter + " mời bạn cùng chơi");
        sender.setWrapText(true);
        sender.setStyle("-fx-font-family: 'Patrick Hand'; -fx-font-size: 19px; -fx-text-fill: -fx-ink;");
        Label roomName = new Label(invitationText(invitation, "roomName", "Phòng chơi"));
        roomName.setWrapText(true);
        roomName.setStyle("-fx-font-family: 'Patrick Hand'; -fx-font-size: 25px; -fx-font-weight: bold; -fx-text-fill: -fx-ink;");

        javafx.scene.layout.GridPane details = new javafx.scene.layout.GridPane();
        details.setHgap(22);
        details.setVgap(7);
        details.setPadding(new Insets(12, 16, 12, 16));
        details.setStyle("-fx-background-color: #FFF9E8; -fx-border-color: #D6B85A; -fx-border-width: 1;");
        String players = invitation.getInt("currentPlayers", -1) >= 0
                ? invitation.getInt("currentPlayers", 0) + "/" + invitation.getInt("maxPlayers", 8) + " người" : "—";
        String settings = invitation.getInt("maxRounds", 0) > 0
                ? invitation.getInt("maxRounds", 0) + " vòng · " + invitation.getInt("drawTime", 60) + " giây vẽ/lượt" : "—";
        String[][] rows = {
                {"Mã phòng", "#" + invitationText(invitation, "roomId", "—")},
                {"Chủ phòng", invitationText(invitation, "hostDisplayName",
                        invitationText(invitation, "hostUsername", "—"))},
                {"Người chơi", players},
                {"Cài đặt trận", settings}
        };
        for (int i = 0; i < rows.length; i++) {
            Label key = new Label(rows[i][0]);
            key.setStyle("-fx-font-family: 'Patrick Hand'; -fx-font-size: 17px; -fx-text-fill: #666666;");
            Label value = new Label(rows[i][1]);
            value.setWrapText(true);
            value.setStyle("-fx-font-family: 'Patrick Hand'; -fx-font-size: 17px; -fx-font-weight: bold; -fx-text-fill: -fx-ink;");
            javafx.scene.layout.GridPane.setHgrow(value, Priority.ALWAYS);
            details.addRow(i, key, value);
        }
        Label note = new Label("Thông tin phòng tại thời điểm gửi lời mời.");
        note.setWrapText(true);
        note.setStyle("-fx-font-family: 'Patrick Hand'; -fx-font-size: 14px; -fx-text-fill: #666666;");
        Button later = new Button("ĐỂ SAU");
        later.getStyleClass().add("pencil-button-danger");
        later.setOnAction(e -> dialog[0].close(() -> { if (callback != null) callback.accept(false); }));
        Button join = new Button("THAM GIA NGAY");
        join.getStyleClass().add("pencil-button-success");
        join.setOnAction(e -> dialog[0].close(() -> { if (callback != null) callback.accept(true); }));
        HBox buttons = new HBox(14, later, join);
        buttons.setAlignment(Pos.CENTER);
        box.getChildren().addAll(title, sender, roomName, details, note, buttons);
        javafx.scene.control.ScrollPane scroll = new javafx.scene.control.ScrollPane(box);
        scroll.setFitToWidth(true);
        scroll.setStyle("-fx-background-color: transparent; -fx-background: transparent;");
        dialog[0] = ModalOverlay.show(owner, scroll, 560, 490, null);
        dialog[0].waitForClose();
    }

    private static String invitationText(Envelope invitation, String key, String fallback) {
        String value = invitation.getString(key);
        return value == null || value.isBlank() ? fallback : value;
    }

    public static void roomSetup(Stage owner, BiConsumer<String, String> callback) {
        if (!Platform.isFxApplicationThread()) {
            Platform.runLater(() -> roomSetup(owner, callback));
            return;
        }

        ModalOverlay[] dialog = new ModalOverlay[1];

        VBox box = new VBox(12);
        box.getStyleClass().add("doodle-dialog-box");
        box.setAlignment(Pos.CENTER);
        box.setMinWidth(440);
        box.setMaxWidth(520);

        Label title = new Label("🎨 Tạo phòng mới");
        title.setStyle("-fx-font-family: 'Amatic SC'; -fx-font-size: 34px; -fx-font-weight: bold; -fx-text-fill: -fx-ink;");
        Label nameLabel = new Label("Tên phòng");
        nameLabel.setStyle("-fx-font-family: 'Patrick Hand'; -fx-font-size: 17px; -fx-font-weight: bold;");
        TextField nameField = new TextField();
        nameField.setPromptText("Ví dụ: Phòng của bạn");
        Label passwordLabel = new Label("Mật khẩu phòng (không bắt buộc)");
        passwordLabel.setStyle("-fx-font-family: 'Patrick Hand'; -fx-font-size: 17px; -fx-font-weight: bold;");
        PasswordField passwordField = new PasswordField();
        passwordField.setPromptText("Để trống nếu không muốn đặt mật khẩu");
        Label hint = new Label("Bạn có thể để trống mật khẩu. Người được mời vào phòng sẽ vào trực tiếp.");
        hint.setWrapText(true);
        hint.setStyle("-fx-font-family: 'Patrick Hand'; -fx-font-size: 14px; -fx-text-fill: #666666;");
        Label error = new Label();
        error.setStyle("-fx-font-family: 'Patrick Hand'; -fx-text-fill: #C62828; -fx-font-weight: bold;");

        Button cancel = new Button("HỦY BỎ");
        cancel.getStyleClass().add("pencil-button-danger");
        cancel.setOnAction(e -> dialog[0].close(() -> {
            if (callback != null) callback.accept(null, null);
        }));
        Button create = new Button("TẠO PHÒNG");
        create.getStyleClass().add("pencil-button-success");
        create.setOnAction(e -> {
            String password = passwordField.getText().trim();
            if (!password.isEmpty() && (password.length() < 4 || password.length() > 32)) {
                error.setText("Mật khẩu nếu đặt phải từ 4 đến 32 ký tự.");
                return;
            }
            String roomName = nameField.getText().trim();
            dialog[0].close(() -> {
                if (callback != null) callback.accept(roomName, password);
            });
        });

        HBox buttons = new HBox(14, cancel, create);
        buttons.setAlignment(Pos.CENTER);
        box.getChildren().addAll(title, nameLabel, nameField, passwordLabel, passwordField, hint, error, buttons);
        dialog[0] = ModalOverlay.show(owner, box, 520, 360, null);
        dialog[0].waitForClose();
    }

    public static void password(Stage owner, String roomName, Consumer<String> callback) {
        if (!Platform.isFxApplicationThread()) {
            Platform.runLater(() -> password(owner, roomName, callback));
            return;
        }

        ModalOverlay[] dialog = new ModalOverlay[1];
        VBox box = new VBox(14);
        box.getStyleClass().add("doodle-dialog-box");
        box.setAlignment(Pos.CENTER);
        box.setMinWidth(420);
        Label title = new Label("🔐 Phòng có mật khẩu");
        title.setStyle("-fx-font-family: 'Amatic SC'; -fx-font-size: 32px; -fx-font-weight: bold; -fx-text-fill: -fx-ink;");
        Label prompt = new Label("Nhập mật khẩu để vào " + (roomName == null ? "phòng" : roomName) + ":");
        prompt.setWrapText(true);
        prompt.setStyle("-fx-font-family: 'Patrick Hand'; -fx-font-size: 17px;");
        PasswordField field = new PasswordField();
        field.setPromptText("Mật khẩu phòng");
        Label error = new Label();
        error.setStyle("-fx-font-family: 'Patrick Hand'; -fx-text-fill: #C62828; -fx-font-weight: bold;");
        Button cancel = new Button("HỦY BỎ");
        cancel.getStyleClass().add("pencil-button-danger");
        cancel.setOnAction(e -> dialog[0].close(() -> {
            if (callback != null) callback.accept(null);
        }));
        Button join = new Button("VÀO PHÒNG");
        join.getStyleClass().add("pencil-button-success");
        join.setOnAction(e -> {
            String value = field.getText();
            if (value == null || value.isBlank()) {
                error.setText("Vui lòng nhập mật khẩu phòng.");
                return;
            }
            dialog[0].close(() -> {
                if (callback != null) callback.accept(value);
            });
        });
        HBox buttons = new HBox(14, cancel, join);
        buttons.setAlignment(Pos.CENTER);
        box.getChildren().addAll(title, prompt, field, error, buttons);
        dialog[0] = ModalOverlay.show(owner, box, 480, 300, null);
        field.setOnAction(e -> join.fire());
        Platform.runLater(field::requestFocus);
        dialog[0].waitForClose();
    }

    public static void info(Stage owner, String title, String message) {
        showCustomAlert(owner, "🎨 " + (title != null ? title : "Thông báo"), message, "ĐỒNG Ý", null);
    }

    public static void warning(Stage owner, String title, String message) {
        showCustomAlert(owner, "⚠ " + (title != null ? title : "Cảnh báo"), message, "ĐÃ HIỂU", null);
    }

    public static void error(Stage owner, String title, String message) {
        showCustomAlert(owner, "❌ " + (title != null ? title : "Lỗi xảy ra"), message, "ĐÓNG", null);
    }

    public static void confirm(Stage owner, String title, String message, String okText, String cancelText, Consumer<Boolean> callback) {
        if (!Platform.isFxApplicationThread()) {
            Platform.runLater(() -> confirm(owner, title, message, okText, cancelText, callback));
            return;
        }

        ModalOverlay[] dialog = new ModalOverlay[1];

        VBox box = new VBox(15);
        box.getStyleClass().add("doodle-dialog-box");
        box.setAlignment(Pos.CENTER);
        box.setMinWidth(420);
        box.setMaxWidth(520);

        Label titleLbl = new Label(title != null ? title : "❓ Xác nhận");
        titleLbl.setStyle("-fx-font-family: 'Amatic SC'; -fx-font-size: 32px; -fx-font-weight: bold; -fx-text-fill: -fx-ink;");

        Label msgLbl = new Label(message != null ? message : "");
        msgLbl.setWrapText(true);
        msgLbl.setStyle("-fx-font-family: 'Patrick Hand'; -fx-font-size: 19px; -fx-text-fill: -fx-ink; -fx-alignment: center;");

        HBox btnBox = new HBox(15);
        btnBox.setAlignment(Pos.CENTER);

        Button cancelBtn = new Button(cancelText != null ? cancelText : "TỪ CHỐI");
        cancelBtn.getStyleClass().add("pencil-button-danger");
        cancelBtn.setOnAction(e -> dialog[0].close(() -> {
            if (callback != null) callback.accept(false);
        }));

        Button okBtn = new Button(okText != null ? okText : "ĐỒNG Ý");
        okBtn.getStyleClass().add("pencil-button-success");
        okBtn.setOnAction(e -> dialog[0].close(() -> {
            if (callback != null) callback.accept(true);
        }));

        btnBox.getChildren().addAll(cancelBtn, okBtn);
        box.getChildren().addAll(titleLbl, msgLbl, btnBox);

        dialog[0] = ModalOverlay.show(owner, box, 520, 300, null);
        dialog[0].waitForClose();
    }

    public static void input(Stage owner, String title, String prompt, String defaultValue, Consumer<String> callback) {
        if (!Platform.isFxApplicationThread()) {
            Platform.runLater(() -> input(owner, title, prompt, defaultValue, callback));
            return;
        }

        ModalOverlay[] dialog = new ModalOverlay[1];

        VBox box = new VBox(15);
        box.getStyleClass().add("doodle-dialog-box");
        box.setAlignment(Pos.CENTER);
        box.setMinWidth(420);
        box.setMaxWidth(520);

        Label titleLbl = new Label(title != null ? title : "✏ Nhập thông tin");
        titleLbl.setStyle("-fx-font-family: 'Amatic SC'; -fx-font-size: 32px; -fx-font-weight: bold; -fx-text-fill: -fx-ink;");

        Label promptLbl = new Label(prompt != null ? prompt : "");
        promptLbl.setWrapText(true);
        promptLbl.setStyle("-fx-font-family: 'Patrick Hand'; -fx-font-size: 18px; -fx-text-fill: -fx-ink;");

        TextField textField = new TextField(defaultValue != null ? defaultValue : "");
        textField.setStyle("-fx-font-family: 'Patrick Hand'; -fx-font-size: 18px; -fx-padding: 6 12;");
        textField.setMaxWidth(Double.MAX_VALUE);

        HBox btnBox = new HBox(15);
        btnBox.setAlignment(Pos.CENTER);

        Button cancelBtn = new Button("HỦY BỎ");
        cancelBtn.getStyleClass().add("pencil-button-danger");
        cancelBtn.setOnAction(e -> dialog[0].close(() -> {
            if (callback != null) callback.accept(null);
        }));

        Button okBtn = new Button("XÁC NHẬN");
        okBtn.getStyleClass().add("pencil-button-success");
        okBtn.setOnAction(e -> {
            String value = textField.getText().trim();
            dialog[0].close(() -> {
                if (callback != null) callback.accept(value);
            });
        });

        textField.setOnAction(e -> okBtn.fire());

        btnBox.getChildren().addAll(cancelBtn, okBtn);
        box.getChildren().addAll(titleLbl, promptLbl, textField, btnBox);

        dialog[0] = ModalOverlay.show(owner, box, 520, 300, null);
        Platform.runLater(textField::requestFocus);
        dialog[0].waitForClose();
    }

    private static void showCustomAlert(Stage owner, String title, String message, String okText, Runnable onClose) {
        if (!Platform.isFxApplicationThread()) {
            Platform.runLater(() -> showCustomAlert(owner, title, message, okText, onClose));
            return;
        }

        ModalOverlay[] dialog = new ModalOverlay[1];

        VBox box = new VBox(15);
        box.getStyleClass().add("doodle-dialog-box");
        box.setAlignment(Pos.CENTER);
        box.setMinWidth(400);
        box.setMaxWidth(500);

        Label titleLbl = new Label(title != null ? title : "Thông báo");
        titleLbl.setStyle("-fx-font-family: 'Amatic SC'; -fx-font-size: 32px; -fx-font-weight: bold; -fx-text-fill: -fx-ink;");

        Label msgLbl = new Label(message != null ? message : "");
        msgLbl.setWrapText(true);
        msgLbl.setStyle("-fx-font-family: 'Patrick Hand'; -fx-font-size: 19px; -fx-text-fill: -fx-ink; -fx-alignment: center;");

        Button okBtn = new Button(okText != null ? okText : "ĐỒNG Ý");
        okBtn.getStyleClass().add("pencil-button-sm");
        okBtn.setOnAction(e -> dialog[0].close(() -> {
            if (onClose != null) onClose.run();
        }));

        box.getChildren().addAll(titleLbl, msgLbl, okBtn);

        dialog[0] = ModalOverlay.show(owner, box, 500, 260, null);
        dialog[0].waitForClose();
    }

    /**
     * Hiển thị thông báo Toast nổi nhẹ nhàng, tự động biến mất sau 2.5 giây.
     */
    public static void showToast(Stage owner, String message) {
        if (!Platform.isFxApplicationThread()) {
            Platform.runLater(() -> showToast(owner, message));
            return;
        }
        if (owner == null) return;

        Popup popup = new Popup();
        Label toastLabel = new Label(message);
        toastLabel.getStyleClass().add("doodle-toast");

        popup.getContent().add(toastLabel);
        popup.setAutoHide(true);

        toastLabel.applyCss();
        double w = toastLabel.prefWidth(-1);
        if (w <= 0) w = 300;
        double x = owner.getX() + (owner.getWidth() - w) / 2.0;
        double y = owner.getY() + owner.getHeight() - 110;
        popup.show(owner, x, y);

        FadeTransition fadeIn = new FadeTransition(Duration.millis(250), toastLabel);
        fadeIn.setFromValue(0.0);
        fadeIn.setToValue(1.0);

        PauseTransition stay = new PauseTransition(Duration.seconds(2.2));

        FadeTransition fadeOut = new FadeTransition(Duration.millis(350), toastLabel);
        fadeOut.setFromValue(1.0);
        fadeOut.setToValue(0.0);
        fadeOut.setOnFinished(e -> popup.hide());

        SequentialTransition seq = new SequentialTransition(fadeIn, stay, fadeOut);
        seq.play();
    }
}
