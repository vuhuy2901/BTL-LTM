package com.nhom8.client.ui;

import com.nhom8.common.dto.AvatarData;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.geometry.Rectangle2D;
import javafx.scene.Cursor;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.*;
import javafx.scene.shape.Circle;
import javafx.stage.FileChooser;
import javafx.stage.Window;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.awt.image.BufferedImage;
import java.io.*;
import java.util.Base64;
import java.util.function.Consumer;

/** Chọn, căn ảnh và xem trước; chỉ gửi thay đổi khi người dùng nhấn Lưu. */
public final class AvatarEditor extends VBox {
    private static final double FRAME_SIZE = 240;
    private final StackPane frame = new StackPane();
    private final StackPane preview = new StackPane();
    private final Label status = new Label("Chọn biểu tượng hoặc tải ảnh của bạn lên.");
    private final Button save = new Button("Lưu ảnh đại diện");
    private final Button cancel = new Button("Hủy thay đổi");
    private final FlowPane choices = new FlowPane(6, 6);
    private final Button upload = new Button("Tải ảnh từ máy…");
    private final Window owner;
    private Image source;
    private ImageView editorImage;
    private ImageView previewImage;
    private String saved = "1";
    private String selected = "1";
    private boolean dirty;
    private boolean saving;
    private final javafx.animation.PauseTransition saveTimeout =
            new javafx.animation.PauseTransition(javafx.util.Duration.seconds(15));
    private double centerX, centerY, pressX, pressY, startX, startY;

    public AvatarEditor(Window owner, Consumer<String> onSave) {
        super(10);
        this.owner = owner;
        setPadding(new Insets(8, 0, 4, 0));
        Label title = new Label("Ảnh đại diện");
        title.setStyle("-fx-font-size: 18px; -fx-font-weight: bold;");
        Label hint = new Label("Kéo ảnh trong khung tròn để căn chỉnh vị trí khuôn mặt.");
        hint.setWrapText(true);
        hint.setMaxWidth(Double.MAX_VALUE);
        hint.setMinHeight(Region.USE_PREF_SIZE);
        frame.setMinSize(FRAME_SIZE, FRAME_SIZE);
        frame.setPrefSize(FRAME_SIZE, FRAME_SIZE);
        frame.setMaxSize(FRAME_SIZE, FRAME_SIZE);
        frame.setClip(new Circle(FRAME_SIZE / 2, FRAME_SIZE / 2, FRAME_SIZE / 2));
        frame.setStyle("-fx-background-color: #E2E8F0; -fx-background-radius: 999;");
        frame.setCursor(Cursor.MOVE);
        preview.setMinSize(72, 72);
        preview.setMaxSize(72, 72);
        preview.setClip(new Circle(36, 36, 36));
        VBox smallPreview = new VBox(8, preview, new Label("Xem trước"));
        smallPreview.setAlignment(Pos.CENTER);
        HBox images = new HBox(28, frame, smallPreview);
        images.setAlignment(Pos.CENTER_LEFT);
        frame.setOnMousePressed(e -> {
            pressX = e.getSceneX(); pressY = e.getSceneY(); startX = centerX; startY = centerY;
        });
        frame.setOnMouseDragged(e -> {
            if (source == null || saving) return;
            double ratio = cropSide() / FRAME_SIZE;
            centerX = startX - (e.getSceneX() - pressX) * ratio;
            centerY = startY - (e.getSceneY() - pressY) * ratio;
            dirty = true; updateCrop(); updateControls();
        });
        for (int i = 0; i < AvatarView.ICONS.length; i++) {
            String id = String.valueOf(i + 1);
            Button button = new Button(AvatarView.ICONS[i]);
            button.setUserData(id);
            button.setStyle("-fx-font-family: 'Segoe UI Emoji'; -fx-font-size: 20px;");
            button.setOnAction(e -> {
                selected = id; source = null; dirty = !saved.equals(id); showSelection();
                status.setText(dirty ? "Biểu tượng chưa được lưu. Nhấn Lưu ảnh đại diện để xác nhận." : "Đang sử dụng biểu tượng đã lưu.");
            });
            choices.getChildren().add(button);
        }
        upload.getStyleClass().add("pencil-button-sm");
        upload.setOnAction(e -> chooseFile());
        save.getStyleClass().add("pencil-button-sm");
        saveTimeout.setOnFinished(e -> saveFailed("Máy chủ chưa phản hồi. Vui lòng kiểm tra kết nối rồi thử lưu lại."));
        save.setOnAction(e -> {
            try {
                String value = valueToSave();
                saving = true; status.setText("Đang lưu ảnh đại diện…"); updateControls();
                saveTimeout.playFromStart();
                onSave.accept(value);
            } catch (Exception ex) { saveFailed("Không thể lưu ảnh: " + ex.getMessage()); }
        });
        cancel.setOnAction(e -> discard());
        cancel.getStyleClass().add("pencil-button-sm");
        status.setWrapText(true);
        status.setMaxWidth(Double.MAX_VALUE);
        status.setMinHeight(Region.USE_PREF_SIZE);
        getChildren().addAll(title, hint, images, upload, choices, new HBox(10, save, cancel), status);
        showSelection();
    }

    private void chooseFile() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Chọn ảnh đại diện");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Ảnh PNG, JPG", "*.png", "*.jpg", "*.jpeg", "*.PNG", "*.JPG", "*.JPEG"));
        File file = chooser.showOpenDialog(owner);
        if (file == null) return;
        try { loadImage(readImage(file)); }
        catch (Exception ex) { status.setText("Không thể mở ảnh: " + ex.getMessage()); }
    }

    private static Image readImage(File file) throws IOException {
        if (file.length() > 10 * 1024 * 1024) throw new IOException("Vui lòng chọn ảnh nhỏ hơn 10 MB.");
        try (ImageInputStream input = ImageIO.createImageInputStream(file)) {
            var readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) throw new IOException("Tệp không phải ảnh PNG hoặc JPG hợp lệ.");
            ImageReader reader = readers.next();
            try {
                reader.setInput(input);
                String format = reader.getFormatName();
                if (!format.equalsIgnoreCase("png") && !format.equalsIgnoreCase("jpeg"))
                    throw new IOException("Chỉ hỗ trợ ảnh PNG và JPG.");
                int width = reader.getWidth(0), height = reader.getHeight(0);
                if ((long) width * height > 40_000_000) throw new IOException("Ảnh quá lớn (tối đa 40 triệu điểm ảnh).");
                var params = reader.getDefaultReadParam();
                int sample = Math.max(1, (int) Math.ceil(Math.max(width, height) / 2048.0));
                params.setSourceSubsampling(sample, sample, 0, 0);
                BufferedImage decoded = reader.read(0, params);
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                ImageIO.write(decoded, "png", bytes);
                return new Image(new ByteArrayInputStream(bytes.toByteArray()));
            } finally { reader.dispose(); }
        }
    }

    void loadImage(Image image) {
        if (image == null || image.isError() || image.getWidth() < 1 || image.getHeight() < 1)
            throw new IllegalArgumentException("Ảnh bị hỏng hoặc không đọc được.");
        source = image;
        centerX = image.getWidth() / 2;
        centerY = image.getHeight() / 2;
        editorImage = new ImageView(source);
        editorImage.setFitWidth(FRAME_SIZE); editorImage.setFitHeight(FRAME_SIZE);
        previewImage = new ImageView(source);
        previewImage.setFitWidth(72); previewImage.setFitHeight(72);
        frame.getChildren().setAll(editorImage);
        preview.getChildren().setAll(previewImage);
        dirty = true;
        status.setText("Ảnh chưa được lưu. Kéo ảnh để căn khuôn mặt vào giữa khung.");
        updateCrop(); updateControls();
    }

    private double cropSide() { return Math.min(source.getWidth(), source.getHeight()); }

    private void updateCrop() {
        if (source == null || editorImage == null) return;
        double side = cropSide(), half = side / 2;
        centerX = Math.max(half, Math.min(source.getWidth() - half, centerX));
        centerY = Math.max(half, Math.min(source.getHeight() - half, centerY));
        Rectangle2D viewport = new Rectangle2D(centerX - half, centerY - half, side, side);
        editorImage.setViewport(viewport);
        previewImage.setViewport(viewport);
    }

    String valueToSave() throws IOException {
        if (source == null) return selected;
        Rectangle2D crop = editorImage.getViewport();
        int size = AvatarData.OUTPUT_SIZE;
        BufferedImage output = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        var pixels = source.getPixelReader();
        for (int y = 0; y < size; y++) for (int x = 0; x < size; x++) {
            int sx = Math.min((int) source.getWidth() - 1, (int) (crop.getMinX() + (x + .5) * crop.getWidth() / size));
            int sy = Math.min((int) source.getHeight() - 1, (int) (crop.getMinY() + (y + .5) * crop.getHeight() / size));
            output.setRGB(x, y, pixels.getArgb(sx, sy));
        }
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        ImageIO.write(output, "png", bytes);
        return AvatarData.PNG_PREFIX + Base64.getEncoder().encodeToString(bytes.toByteArray());
    }

    public void loadSaved(String value) {
        if (value == null || saving) return;
        saved = value;
        if (!dirty) { selected = saved; showSelection(); }
    }

    public boolean isSaving() { return saving; }

    public void saved(String value) {
        saveTimeout.stop();
        saved = value; selected = value; dirty = false; saving = false;
        showSelection(); status.setText("Đã lưu ảnh đại diện.");
    }

    public void saveFailed(String message) {
        saveTimeout.stop();
        saving = false; status.setText(message); updateControls();
    }

    public void discard() {
        if (saving) return;
        selected = saved; dirty = false;
        showSelection(); status.setText("Chọn biểu tượng hoặc tải ảnh PNG/JPG (tối đa 10 MB).");
    }

    private void showSelection() {
        source = null;
        frame.getChildren().setAll(AvatarView.create(selected, FRAME_SIZE));
        preview.getChildren().setAll(AvatarView.create(selected, 72));
        updateControls();
    }

    private void updateControls() {
        upload.setDisable(saving); choices.setDisable(saving);
        save.setDisable(!dirty || saving); cancel.setDisable(!dirty || saving);
        for (var node : choices.getChildren()) {
            Button button = (Button) node;
            button.setStyle("-fx-font-family: 'Segoe UI Emoji'; -fx-font-size: 20px; -fx-background-color: "
                    + (source == null && button.getUserData().equals(selected) ? "#F5C800" : "#FFFFFF") + ";");
        }
    }
}
