package com.bloxbean.cardano.yano.wallet.ui.util;

import javafx.animation.FadeTransition;
import javafx.animation.PauseTransition;
import javafx.animation.SequentialTransition;
import javafx.application.HostServices;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Hyperlink;
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

    /**
     * An abbreviated transaction hash, linked to an explorer where the network has
     * one. Mainnet, preprod and preview link to Cardanoscan and a Yaci DevKit to
     * the local Yaci Viewer; a Yano devnet has no explorer, so {@code explorerUrl}
     * is null there and the hash renders as plain text.
     *
     * <p>Shared so that every list showing a hash links it the same way — a hash
     * that is clickable in History and inert on the dashboard reads as a bug.
     */
    public static Node txHash(String txHash, String explorerUrl, int keep) {
        String text = middleEllipsis(txHash, keep);
        if (explorerUrl == null || explorerUrl.isBlank()) {
            Label label = new Label(text);
            label.getStyleClass().add("mono");
            return label;
        }
        Hyperlink link = new Hyperlink(text);
        link.getStyleClass().add("mono");
        link.setOnAction(e -> openUrl(explorerUrl));
        return link;
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
    /**
     * Bridges a controller future onto the FX thread.
     *
     * <p>Failures are ALWAYS logged here, in addition to being handed to
     * {@code onError} for display. Every screen funnels through this method, so
     * this is the one place that guarantees a failure leaves a trace.
     *
     * <p>This is not defensive tidiness. Without it a failure exists only as
     * text in a toast the user cannot copy and a stack trace nobody ever sees —
     * during the native-image work three separate bugs (Jackson vault
     * deserialisation, JNA/hid4java init, and node discovery) each cost a
     * round-trip purely because the log was silent.
     */
    public static <T> void onFx(CompletableFuture<T> future, Consumer<T> onSuccess, Consumer<Throwable> onError) {
        future.whenComplete((value, error) -> Platform.runLater(() -> {
            if (error != null) {
                Throwable cause = unwrap(error);
                System.err.println("[ui] operation failed: " + cause);
                cause.printStackTrace();
                onError.accept(cause);
            } else {
                onSuccess.accept(value);
            }
        }));
    }

    public static Throwable unwrap(Throwable t) {
        return t instanceof java.util.concurrent.CompletionException && t.getCause() != null ? t.getCause() : t;
    }
}
