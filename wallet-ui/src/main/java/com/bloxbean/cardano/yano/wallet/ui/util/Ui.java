package com.bloxbean.cardano.yano.wallet.ui.util;

import javafx.animation.FadeTransition;
import javafx.animation.PauseTransition;
import javafx.animation.SequentialTransition;
import javafx.application.HostServices;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/** Small view-building and formatting helpers shared by all screens. */
public final class Ui {
    private Ui() {
    }

    // Wired once at startup (JavaFX Application.getHostServices) so any screen
    // can open a link in the user's default browser without holding the Stage.
    private static volatile HostServices hostServices;

    public static void setHostServices(HostServices services) {
        hostServices = services;
    }

    /** Opens a URL in the user's default browser; no-op if unset or blank. */
    public static void openUrl(String url) {
        HostServices services = hostServices;
        if (services != null && url != null && !url.isBlank()) {
            services.showDocument(url);
        }
    }

    public static VBox card(String title, Node... content) {
        VBox box = new VBox(12);
        box.getStyleClass().add("card");
        if (title != null) {
            Label label = new Label(title);
            label.getStyleClass().add("card-title");
            box.getChildren().add(label);
        }
        box.getChildren().addAll(content);
        return box;
    }

    public static Label muted(String text) {
        Label label = new Label(text);
        label.getStyleClass().add("muted");
        return label;
    }

    public static Label chip(String text, String styleClass) {
        Label label = new Label(text);
        label.getStyleClass().addAll("chip", styleClass);
        return label;
    }

    public static HBox row(double spacing, Node... children) {
        HBox box = new HBox(spacing, children);
        box.setAlignment(Pos.CENTER_LEFT);
        return box;
    }

    public static Pane spacer() {
        Pane pane = new Pane();
        HBox.setHgrow(pane, javafx.scene.layout.Priority.ALWAYS);
        return pane;
    }

    public static String middleEllipsis(String value, int keep) {
        if (value == null || value.length() <= keep * 2 + 3) return value;
        return value.substring(0, keep) + "…" + value.substring(value.length() - keep);
    }

    public static void copyToClipboard(String value) {
        ClipboardContent content = new ClipboardContent();
        content.putString(value);
        Clipboard.getSystemClipboard().setContent(content);
    }

    /** Slides a transient toast into the given overlay layer. */
    public static void toast(StackPane overlay, String message, boolean error) {
        Label label = new Label(message);
        label.getStyleClass().addAll("toast", error ? "toast-error" : "toast-ok");
        StackPane.setAlignment(label, Pos.BOTTOM_CENTER);
        StackPane.setMargin(label, new Insets(0, 0, 28, 0));
        overlay.getChildren().add(label);

        FadeTransition in = new FadeTransition(javafx.util.Duration.millis(150), label);
        in.setFromValue(0);
        in.setToValue(1);
        PauseTransition stay = new PauseTransition(javafx.util.Duration.seconds(error ? 6 : 3));
        FadeTransition out = new FadeTransition(javafx.util.Duration.millis(300), label);
        out.setFromValue(1);
        out.setToValue(0);
        SequentialTransition sequence = new SequentialTransition(in, stay, out);
        sequence.setOnFinished(e -> overlay.getChildren().remove(label));
        sequence.play();
    }

    /** Runs the future's result (or error) on the FX thread. */
    public static <T> void onFx(CompletableFuture<T> future, Consumer<T> onSuccess, Consumer<Throwable> onError) {
        future.whenComplete((value, error) -> Platform.runLater(() -> {
            if (error != null) {
                onError.accept(unwrap(error));
            } else {
                onSuccess.accept(value);
            }
        }));
    }

    public static Throwable unwrap(Throwable t) {
        return t instanceof java.util.concurrent.CompletionException && t.getCause() != null ? t.getCause() : t;
    }
}
