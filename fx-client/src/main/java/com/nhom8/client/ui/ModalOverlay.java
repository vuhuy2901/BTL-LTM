package com.nhom8.client.ui;

import javafx.animation.FadeTransition;
import javafx.animation.ScaleTransition;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.Region;
import javafx.stage.Stage;
import javafx.util.Duration;

/** A CSS-styled modal card rendered inside the application's existing window. */
public final class ModalOverlay {
    private final Scene scene;
    private final Parent originalRoot;
    private final StackPane layer;
    private final StackPane page;
    private final Node content;
    private final Runnable onClose;
    private final Object loopKey = new Object();
    private boolean showing;
    private boolean waiting;

    private ModalOverlay(Scene scene, Node content, double width, double height, Runnable onClose,
                         boolean closeOnOutsideClick) {
        this.scene = scene;
        this.originalRoot = scene.getRoot();
        this.layer = new StackPane();
        this.content = content;
        this.onClose = onClose;

        scene.setRoot(layer);
        this.page = new StackPane(originalRoot);

        StackPane scrim = new StackPane();
        scrim.getStyleClass().add("modal-scrim");
        scrim.setOnMouseClicked(e -> {
            if (closeOnOutsideClick) close();
            e.consume();
        });

        if (content instanceof Region region) {
            region.getStyleClass().add("modal-card");
            double availableWidth = scene.getWidth() > 0 ? scene.getWidth() - 48 : width;
            double availableHeight = scene.getHeight() > 0 ? scene.getHeight() - 48 : height;
            double cardWidth = Math.min(width, Math.max(320, availableWidth));
            double cardHeight = Math.min(height, Math.max(240, availableHeight));
            region.setMaxSize(cardWidth, cardHeight);
            region.setPrefSize(cardWidth, cardHeight);
        }
        layer.getChildren().addAll(this.page, scrim, content);
        layer.setPickOnBounds(true);
        showing = true;

        layer.setOpacity(0);
        content.setScaleX(0.97);
        content.setScaleY(0.97);
        FadeTransition fade = new FadeTransition(Duration.millis(150), layer);
        fade.setToValue(1);
        ScaleTransition scale = new ScaleTransition(Duration.millis(150), content);
        scale.setToX(1);
        scale.setToY(1);
        fade.play();
        scale.play();

        layer.addEventFilter(javafx.scene.input.KeyEvent.KEY_PRESSED, e -> {
            if (e.getCode() == KeyCode.ESCAPE) {
                close();
                e.consume();
            }
        });
    }

    public static ModalOverlay show(Stage owner, Node content, double width, double height, Runnable onClose) {
        return show(owner, content, width, height, onClose, false);
    }

    public static ModalOverlay show(Stage owner, Node content, double width, double height, Runnable onClose,
                                    boolean closeOnOutsideClick) {
        if (owner == null || owner.getScene() == null) throw new IllegalStateException("Modal requires an active owner scene");
        return new ModalOverlay(owner.getScene(), content, width, height, onClose, closeOnOutsideClick);
    }

    public void close() {
        close(null);
    }

    public void close(Runnable afterClose) {
        if (!showing) return;
        showing = false;
        FadeTransition fade = new FadeTransition(Duration.millis(110), layer);
        fade.setToValue(0);
        fade.setOnFinished(e -> {
            layer.getChildren().remove(content);
            page.getChildren().remove(originalRoot);
            layer.getChildren().clear();
            scene.setRoot(originalRoot);
            if (onClose != null) onClose.run();
            if (afterClose != null) afterClose.run();
            if (waiting) Platform.exitNestedEventLoop(loopKey, null);
        });
        fade.play();
    }

    public void bringToFront() {
        if (showing) layer.toFront();
    }

    public boolean isShowing() {
        return showing;
    }

    public void waitForClose() {
        if (!showing) return;
        waiting = true;
        Platform.enterNestedEventLoop(loopKey);
        waiting = false;
    }
}
