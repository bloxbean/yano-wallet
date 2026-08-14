package com.bloxbean.cardano.yano.wallet.ui.screens;

import com.bloxbean.cardano.yano.wallet.ui.contract.WalletUiController;
import com.bloxbean.cardano.yano.wallet.ui.util.Ui;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.scene.Node;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.TextArea;
import javafx.scene.layout.VBox;
import javafx.util.Duration;

/**
 * Live progress of a managed node that is still starting: what it is doing, how
 * far through it is, roughly how much longer, and its log on request.
 *
 * <p>Shared by the Connect screen (where the node is launched) and the
 * Onboarding screen (where the user waits out the rest of the start while
 * creating or restoring a wallet), so both describe the same event the same way.
 *
 * <p>The position matters more than it looks. A first start on a public network
 * spends over an hour rebuilding the account-history index before it binds an
 * HTTP port; with only a spinner to go on, that is indistinguishable from a
 * hang, and the honest response to a hang is to kill it — which throws the work
 * away and starts it again from the beginning.
 */
public class NodeWarmupView {
    /** After this long with no visible position, point the user at the log. */
    private static final long SLOW_HINT_MS = 30_000;

    private final WalletUiController controller;
    private final VBox root = new VBox(10);
    private final ProgressBar bar = new ProgressBar();
    private final Label detail = new Label();
    private final Label eta = new Label();
    private final Hyperlink logToggle = new Hyperlink("Show node log");
    private final TextArea logArea = new TextArea();
    private final Label slowHint = new Label();

    private Timeline poller;
    private long startedMs;
    private boolean logVisible;

    public NodeWarmupView(WalletUiController controller) {
        this.controller = controller;
        build();
    }

    public Node root() {
        return root;
    }

    private void build() {
        bar.setMaxWidth(Double.MAX_VALUE);
        bar.getStyleClass().add("sync-progress");
        bar.setProgress(ProgressBar.INDETERMINATE_PROGRESS);

        detail.setWrapText(true);
        detail.getStyleClass().add("muted");
        eta.getStyleClass().add("muted");

        logToggle.setOnAction(e -> toggleLog());
        logToggle.getStyleClass().add("muted");

        logArea.setEditable(false);
        logArea.setWrapText(false);
        logArea.setPrefRowCount(12);
        logArea.getStyleClass().add("node-log");
        setManagedVisible(logArea, false);

        slowHint.setWrapText(true);
        slowHint.getStyleClass().add("muted");
        setManagedVisible(slowHint, false);

        root.getChildren().setAll(bar, detail, eta, logToggle, logArea, slowHint);
        setManagedVisible(root, false);
    }

    /** Shows the view and begins polling the node's startup state. */
    public void start() {
        startedMs = System.currentTimeMillis();
        bar.setProgress(ProgressBar.INDETERMINATE_PROGRESS);
        detail.getStyleClass().remove("warning-text");
        detail.setText("Starting the local node…");
        eta.setText("");
        setManagedVisible(slowHint, false);
        setManagedVisible(root, true);
        if (poller == null) {
            poller = new Timeline(new KeyFrame(Duration.seconds(1.2), e -> poll()));
            poller.setCycleCount(Timeline.INDEFINITE);
        }
        poller.playFromStart();
        poll();
    }

    /** Stops polling and hides the view. */
    public void stop() {
        stopPolling();
        setManagedVisible(root, false);
    }

    public void stopPolling() {
        if (poller != null) {
            poller.stop();
        }
    }

    /** Leaves the view up showing a failure, with the log opened for diagnosis. */
    public void showFailure(String message) {
        showFailure(message, null);
    }

    /**
     * @param guidance what to do next, appended after the headline; may be null
     */
    public void showFailure(String message, String guidance) {
        stopPolling();
        setManagedVisible(root, true);
        bar.setProgress(0);
        detail.setText(headline(message) + (guidance == null ? "" : "  " + guidance));
        if (!detail.getStyleClass().contains("warning-text")) {
            detail.getStyleClass().add("warning-text");
        }
        eta.setText("");
        setManagedVisible(slowHint, false);
        showLog();
        refreshLog();
    }

    /**
     * The first line of a failure, without the log tail the launcher appends to
     * make its reason self-contained. Here that tail is redundant — the log is
     * opened directly below — and long enough to push the rest of the screen out
     * of view, which is how a failure ends up hiding the wallets it is telling
     * the user are safe.
     */
    private static String headline(String message) {
        if (message == null) {
            return "The node stopped before it was ready.";
        }
        int tail = message.indexOf("Last lines of");
        String head = tail > 0 ? message.substring(0, tail) : message;
        int newline = head.indexOf('\n');
        return (newline > 0 ? head.substring(0, newline) : head).strip();
    }

    private void poll() {
        Ui.onFx(controller.nodeStartupStatus(), status -> {
            if (status.failed()) {
                return; // the connect future reports the failure; don't race it
            }
            detail.setText(status.detail());
            detail.getStyleClass().remove("warning-text");
            if (status.determinate()) {
                bar.setProgress(status.fraction());
                eta.setText(percent(status.fraction())
                        + (status.remainingSeconds() > 0
                                ? " · about " + humanDuration(status.remainingSeconds()) + " left"
                                : ""));
                setManagedVisible(slowHint, false);
            } else {
                bar.setProgress(ProgressBar.INDETERMINATE_PROGRESS);
                eta.setText("");
                if (!status.reachable() && System.currentTimeMillis() - startedMs > SLOW_HINT_MS) {
                    slowHint.setText("Still working — this is normal for a first start on a public "
                            + "network. Open the node log to watch it directly.");
                    setManagedVisible(slowHint, true);
                }
            }
        }, error -> { /* transient; keep the last message */ });
        if (logVisible) {
            refreshLog();
        }
    }

    private static String percent(double fraction) {
        return Math.round(fraction * 100) + "%";
    }

    /**
     * Rounded deliberately coarsely — the estimate comes from an average rate
     * over the phase so far, and "about 1 hour 10 minutes" claims exactly as much
     * as that supports, where "1:09:43" would claim far more.
     */
    static String humanDuration(long seconds) {
        if (seconds < 90) {
            return "a minute";
        }
        long minutes = Math.round(seconds / 60.0);
        if (minutes < 60) {
            return minutes + " minutes";
        }
        long hours = minutes / 60;
        long rest = Math.round((minutes % 60) / 5.0) * 5;
        String hourPart = hours + (hours == 1 ? " hour" : " hours");
        return rest == 0 ? hourPart : hourPart + " " + rest + " minutes";
    }

    private void toggleLog() {
        if (logVisible) {
            logVisible = false;
            logToggle.setText("Show node log");
            setManagedVisible(logArea, false);
        } else {
            showLog();
            refreshLog();
        }
    }

    private void showLog() {
        logVisible = true;
        logToggle.setText("Hide node log");
        setManagedVisible(logArea, true);
    }

    private void refreshLog() {
        Ui.onFx(controller.nodeLogTail(200), lines -> {
            logArea.setText(lines.isEmpty() ? "(no log yet)" : String.join("\n", lines));
            logArea.setScrollTop(Double.MAX_VALUE);
        }, error -> { /* ignore — best effort */ });
    }

    private static void setManagedVisible(Node node, boolean visible) {
        node.setVisible(visible);
        node.setManaged(visible);
    }
}
