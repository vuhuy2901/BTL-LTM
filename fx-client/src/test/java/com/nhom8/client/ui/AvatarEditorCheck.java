package com.nhom8.client.ui;

import com.nhom8.common.dto.AvatarData;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.image.WritableImage;
import javafx.scene.image.ImageView;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.*;
import javafx.stage.Stage;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.*;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicReference;

/** Kiểm tra ảnh cắt, thao tác kéo/thu phóng, hủy và trạng thái trước khi lưu. */
public class AvatarEditorCheck extends Application {
    private static volatile Throwable failure;

    @Override public void start(Stage stage) {
        try {
            AtomicReference<String> sent = new AtomicReference<>();
            AvatarEditor editor = new AvatarEditor(stage, sent::set);
            editor.setPadding(new javafx.geometry.Insets(24));
            editor.setStyle("-fx-background-color: #FFFFFF;");
            Scene scene = new Scene(editor, 650, 680);
            String css = getClass().getResource("/css/style.css").toExternalForm();
            scene.getStylesheets().add(css);
            stage.setScene(scene);
            WritableImage source = new WritableImage(800, 400);
            for (int y = 0; y < 400; y++) for (int x = 0; x < 800; x++)
                source.getPixelWriter().setArgb(x, y, 0xff000000 | ((x % 256) << 16) | ((y % 256) << 8));
            editor.loadSaved("3");
            editor.loadImage(source);
            StackPane frame = (StackPane) ((HBox) editor.getChildren().get(2)).getChildren().getFirst();
            ImageView image = (ImageView) frame.getChildren().getFirst();
            require(image.getViewport().getMinX() == 200, "Ảnh ngang phải được cắt ở giữa");
            frame.getOnMousePressed().handle(mouse(MouseEvent.MOUSE_PRESSED, 0, 0));
            frame.getOnMouseDragged().handle(mouse(MouseEvent.MOUSE_DRAGGED, 10000, 10000));
            require(image.getViewport().getMinX() == 0 && image.getViewport().getMinY() == 0,
                    "Kéo ảnh không được tạo khoảng trống trong khung");
            String value = editor.valueToSave();
            BufferedImage crop = ImageIO.read(new ByteArrayInputStream(Base64.getDecoder()
                    .decode(value.substring(AvatarData.PNG_PREFIX.length()))));
            require(crop.getWidth() == 256 && crop.getHeight() == 256, "Ảnh lưu phải có kích thước 256 × 256");
            require(crop.getRGB(128, 128) == source.getPixelReader().getArgb(200, 200), "Ảnh lưu phải khớp vùng xem trước");
            require(sent.get() == null, "Ảnh xem trước không được tự động lưu");
            editor.discard();
            require("3".equals(editor.valueToSave()), "Hủy phải phục hồi avatar đã lưu");
            editor.loadImage(source);
            Button save = (Button) ((HBox) editor.getChildren().get(5)).getChildren().getFirst();
            save.fire();
            require(sent.get() != null && editor.isSaving(), "Nút Lưu phải gửi ảnh và chờ xác nhận");
            editor.saveFailed("Thử lại");
            require(!save.isDisabled(), "Lưu lỗi phải cho phép thử lại");
            editor.saved(value);
            require(AvatarView.image(value) != null, "Ảnh đã lưu phải hiển thị lại được");
            editor.loadImage(new WritableImage(400, 800));
            require(((ImageView) frame.getChildren().getFirst()).getViewport().getMinY() == 200,
                    "Ảnh dọc phải được cắt ở giữa");
            editor.loadImage(source);
            editor.applyCss(); editor.layout();
            WritableImage snapshot = scene.snapshot(null);
            BufferedImage screenshot = new BufferedImage((int) snapshot.getWidth(), (int) snapshot.getHeight(), BufferedImage.TYPE_INT_ARGB);
            for (int y = 0; y < screenshot.getHeight(); y++) for (int x = 0; x < screenshot.getWidth(); x++)
                screenshot.setRGB(x, y, snapshot.getPixelReader().getArgb(x, y));
            ImageIO.write(screenshot, "png", new File("target/avatar-editor-preview.png"));
            System.out.println("PASS: avatar crop, drag bounds, preview, cancel, save and retry.");
        } catch (Throwable ex) { failure = ex; ex.printStackTrace(); }
        finally { Platform.exit(); }
    }

    private MouseEvent mouse(javafx.event.EventType<MouseEvent> type, double x, double y) {
        return new MouseEvent(type, x, y, x, y, MouseButton.PRIMARY, 1,
                false, false, false, false, true, false, false, false, false, false, null);
    }

    private void require(boolean valid, String message) { if (!valid) throw new AssertionError(message); }

    public static void main(String[] args) {
        launch(args);
        if (failure != null) System.exit(1);
    }
}
