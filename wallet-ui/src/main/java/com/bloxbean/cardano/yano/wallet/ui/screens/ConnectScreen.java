package com.bloxbean.cardano.yano.wallet.ui.screens;

import com.bloxbean.cardano.yano.wallet.ui.contract.WalletUiController;
import com.bloxbean.cardano.yano.wallet.ui.util.NetworkPrefs;
import com.bloxbean.cardano.yano.wallet.ui.util.Ui;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.util.Duration;

import java.util.function.Consumer;

/**
 * First-run / reconnect screen: choose a network and how to reach it — a
 * managed local node the wallet launches (recommended, zero-config), or an
 * external Yano node URL (your own, or a LAN/remote node).
 *
 * <p>While a managed connection is being established the screen polls the
 * node's startup state and shows live progress plus an optional log peek, so a
 * slow first sync (which can take minutes) is legible instead of a blank spinner.
 */
public class ConnectScreen {
    /** After this long with no progress, point the user at the node log. */
    private static final long SLOW_HINT_MS = 30_000;

    private final WalletUiController controller;
    private final StackPane overlay;
    private final Consumer<WalletUiController.ConnectionInfo> onConnected;
    private final VBox content = new VBox(16);
    private final StackPane root = new StackPane();

    private final ComboBox<String> networkPicker = new ComboBox<>();
    private final ToggleGroup modeGroup = new ToggleGroup();
    private final ToggleButton managedToggle = new ToggleButton("Run a local node");
    private final ToggleButton externalToggle = new ToggleButton("Connect to my node");
    private final TextField urlField = new TextField();
    private final Button connectButton = new Button("Connect");
    /** Escape hatch from an in-flight attempt — without it a saved connection is a trap. */
    private final Button cancelButton = new Button("Change network");
    private final Label statusLabel = new Label();
    private final ProgressIndicator spinner = new ProgressIndicator();

    // Managed-node startup progress (hidden until a managed connect is running).
    private final VBox startupBox = new VBox(10);
    private final Label startupDetail = new Label();
    private final ProgressBar startupBar = new ProgressBar();
    private final Hyperlink logToggle = new Hyperlink("Show node log");
    private final TextArea logArea = new TextArea();
    private final Label slowHint = new Label();
    private Timeline startupPoller;
    private long startupStartedMs;
    private boolean logVisible;
    private boolean managedAttempt;
    /** Set while a cancel is in flight so the aborted future's failure stays silent. */
    private boolean cancelled;
    private final boolean autoConnect;

    public ConnectScreen(WalletUiController controller, StackPane overlay,
                         Consumer<WalletUiController.ConnectionInfo> onConnected) {
        this(controller, overlay, true, onConnected);
    }

    /**
     * @param autoConnect reconnect to the saved node immediately. False when the
     *                    user came here deliberately to change networks — they
     *                    must not be dragged straight back to the old one.
     */
    public ConnectScreen(WalletUiController controller, StackPane overlay, boolean autoConnect,
                         Consumer<WalletUiController.ConnectionInfo> onConnected) {
        this.controller = controller;
        this.overlay = overlay;
        this.autoConnect = autoConnect;
        this.onConnected = onConnected;
        content.setAlignment(Pos.CENTER);
        content.setMaxWidth(560);
        content.setPadding(new Insets(32));
        root.getStyleClass().add("onboarding");
        root.getChildren().add(content);
        build();
    }

    public Node root() {
        return root;
    }

    private void build() {
        Label brand = new Label("Yano Wallet");
        brand.getStyleClass().add("brand-large");
        Label tagline = new Label("Connect to a Cardano node to begin.");
        tagline.getStyleClass().add("brand-sub");

        networkPicker.setItems(FXCollections.observableArrayList(controller.availableNetworks()));
        // Show names, keep ids: every downstream call (connect, storage paths,
        // fromId) works on the id, so converting only at the edge avoids
        // threading a second identifier through the whole screen.
        networkPicker.setConverter(new javafx.util.StringConverter<>() {
            @Override
            public String toString(String networkId) {
                return networkId == null ? "" : controller.networkLabel(networkId);
            }

            @Override
            public String fromString(String label) {
                return controller.availableNetworks().stream()
                        .filter(id -> controller.networkLabel(id).equals(label))
                        .findFirst()
                        .orElse(label);
            }
        });
        networkPicker.setValue(controller.availableNetworks().contains("preprod")
                ? "preprod" : controller.availableNetworks().get(0));
        networkPicker.setMaxWidth(Double.MAX_VALUE);

        managedToggle.setToggleGroup(modeGroup);
        externalToggle.setToggleGroup(modeGroup);
        managedToggle.getStyleClass().add("mode-toggle");
        externalToggle.getStyleClass().add("mode-toggle");
        managedToggle.setMaxWidth(Double.MAX_VALUE);
        externalToggle.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(managedToggle, javafx.scene.layout.Priority.ALWAYS);
        HBox.setHgrow(externalToggle, javafx.scene.layout.Priority.ALWAYS);
        HBox modeRow = new HBox(10, managedToggle, externalToggle);

        urlField.setPromptText("http://localhost:7070/api/v1/");
        urlField.setManaged(false);
        urlField.setVisible(false);

        Label managedHint = Ui.muted("");
        managedHint.setWrapText(true);

        modeGroup.selectedToggleProperty().addListener((obs, old, sel) -> {
            boolean external = sel == externalToggle;
            urlField.setManaged(external);
            urlField.setVisible(external);
            managedHint.setVisible(!external);
            managedHint.setManaged(!external);
        });
        managedToggle.setSelected(true);

        // Some networks are served by a backend the wallet can't launch (a Yaci
        // DevKit devnet runs on yaci-store) — force external and offer its URL.
        networkPicker.valueProperty().addListener((obs, old, network) -> {
            if (network == null) {
                return;
            }
            boolean canManage = controller.supportsManagedNode(network);
            managedToggle.setDisable(!canManage);
            if (!canManage) {
                externalToggle.setSelected(true);
                String preset = controller.defaultBaseUrl(network);
                if (preset != null && urlField.getText().isBlank()) {
                    urlField.setText(preset);
                }
            } else if (!NetworkPrefs.managed(network, true)) {
                // Restore how this network was last reached, rather than carrying
                // the previously selected network's mode over to it.
                externalToggle.setSelected(true);
                urlField.setText(NetworkPrefs.url(network, controller.defaultBaseUrl(network)));
            } else {
                managedToggle.setSelected(true);
            }
            updateManagedHint(managedHint, network);
        });
        updateManagedHint(managedHint, networkPicker.getValue());

        connectButton.getStyleClass().add("primary-button");
        connectButton.setMaxWidth(Double.MAX_VALUE);
        connectButton.setOnAction(e -> connect());

        cancelButton.getStyleClass().add("ghost-button");
        cancelButton.setMaxWidth(Double.MAX_VALUE);
        cancelButton.setOnAction(e -> cancel());
        setManagedVisible(cancelButton, false);

        spinner.setMaxSize(22, 22);
        spinner.setVisible(false);
        statusLabel.getStyleClass().add("muted");
        statusLabel.setWrapText(true);
        HBox statusRow = new HBox(10, spinner, statusLabel);
        statusRow.setAlignment(Pos.CENTER_LEFT);

        buildStartupBox();

        VBox card = Ui.card("Node connection",
                Ui.muted("Network"), networkPicker,
                modeRow, managedHint, urlField,
                connectButton, cancelButton, statusRow, startupBox);

        content.getChildren().setAll(brand, tagline, card);
        prefillAndMaybeAutoConnect();
    }

    /**
     * Sets expectations before a potentially very long start: resuming existing
     * chainstate is quick, a first sync on a public network is not.
     */
    private void updateManagedHint(Label hint, String network) {
        if (network == null) {
            return;
        }
        hint.setText(controller.hasLocalChainstate(network)
                ? "The wallet runs its own local node. This network already has local chain data, "
                        + "so it should pick up where it left off."
                : "The wallet starts and manages a local node on its own port. No local data for this "
                        + "network yet — the first sync can take a long time on a public network.");
    }

    private void buildStartupBox() {
        startupDetail.setWrapText(true);
        startupDetail.getStyleClass().add("muted");

        startupBar.setMaxWidth(Double.MAX_VALUE);
        startupBar.getStyleClass().add("sync-progress");
        startupBar.setProgress(ProgressBar.INDETERMINATE_PROGRESS);

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

        startupBox.getChildren().setAll(startupBar, startupDetail, logToggle, logArea, slowHint);
        setManagedVisible(startupBox, false);
    }

    private void prefillAndMaybeAutoConnect() {
        WalletUiController.ConnectionInfo saved = controller.savedConnection();
        if (saved == null) {
            return;
        }
        if (controller.availableNetworks().contains(saved.networkId())) {
            networkPicker.setValue(saved.networkId());
        }
        if (saved.managed()) {
            managedToggle.setSelected(true);
        } else {
            externalToggle.setSelected(true);
            urlField.setText(saved.baseUrl());
        }
        if (!autoConnect) {
            // A returning user lands here with their last choice prefilled but
            // NOT connected. Say which network is about to be used and what the
            // button will do — starting a local node can sync for a long time,
            // so it should never be a surprise consequence of opening the app.
            connectButton.setText(saved.managed() ? "Start node" : "Connect");
            statusLabel.setText(saved.managed()
                    ? "Ready to start the local " + controller.networkLabel(saved.networkId()) + " node."
                    + "  Choose a different network above to change it."
                    : "Ready to connect to " + controller.networkLabel(saved.networkId())
                    + " at " + saved.baseUrl()
                    + ".  Choose a different network above to change it.");
            return;
        }
        // Returning user with a saved connection: reconnect automatically so
        // subsequent launches are one-click. Name the target rather than hiding
        // it behind "your saved node", and keep Cancel available throughout —
        // otherwise a saved connection can never be changed.
        beginAttempt(saved.managed(), "Reconnecting to " + controller.networkLabel(saved.networkId())
                + (saved.managed() ? " · local node…" : " · " + saved.baseUrl()));
        runConnect(controller.reconnectSaved());
    }

    private void connect() {
        String network = networkPicker.getValue();
        boolean managed = managedToggle.isSelected();
        String url = urlField.getText() == null ? "" : urlField.getText().trim();
        NetworkPrefs.remember(network, managed, url);
        beginAttempt(managed, managed
                ? "Starting your local node for " + network + "…"
                : "Connecting to " + url + "…");
        runConnect(managed
                ? controller.connectManaged(network)
                : controller.connectExternal(network, url));
    }

    /**
     * Aborts the in-flight attempt and hands the form back. The pending future
     * still fails afterwards, so {@link #cancelled} keeps that failure quiet.
     */
    private void cancel() {
        cancelled = true;
        controller.cancelConnect();
        stopStartupPoller();
        setIdle();
        setManagedVisible(startupBox, false);
        statusLabel.setText("Stopped. Choose a network and how to reach it.");
    }

    /** Enter the busy state; for a managed attempt also start the live startup poller. */
    private void beginAttempt(boolean managed, String message) {
        this.managedAttempt = managed;
        this.cancelled = false;
        connectButton.setDisable(true);
        setManagedVisible(cancelButton, true);
        statusLabel.setText(message);
        if (managed) {
            spinner.setVisible(false);
            beginManagedStartup();
        } else {
            spinner.setVisible(true);
            setManagedVisible(startupBox, false);
        }
    }

    private void runConnect(java.util.concurrent.CompletableFuture<WalletUiController.ConnectionInfo> future) {
        Ui.onFx(future, info -> {
            stopStartupPoller();
            setIdle();
            setManagedVisible(startupBox, false);
            onConnected.accept(info);
        }, error -> {
            if (cancelled) {
                // The user aborted this attempt; its failure is the expected
                // outcome, not something to report.
                cancelled = false;
                return;
            }
            stopStartupPoller();
            setIdle();
            String message = "Connection failed: " + error.getMessage();
            statusLabel.setText(message);
            if (managedAttempt) {
                // Show the real cause + the node log inline so a start failure is
                // diagnosable without leaving the screen.
                startupBar.setProgress(0);
                startupDetail.setText(message);
                if (!startupDetail.getStyleClass().contains("warning-text")) {
                    startupDetail.getStyleClass().add("warning-text");
                }
                setManagedVisible(startupBox, true);
                setManagedVisible(slowHint, false);
                showLog();
                refreshLog();
            }
            Ui.toast(overlay, message, true);
        });
    }

    private void beginManagedStartup() {
        startupStartedMs = System.currentTimeMillis();
        startupBar.setProgress(ProgressBar.INDETERMINATE_PROGRESS);
        startupDetail.getStyleClass().remove("warning-text");
        startupDetail.setText("Starting the local node…");
        setManagedVisible(slowHint, false);
        setManagedVisible(startupBox, true);
        if (startupPoller == null) {
            startupPoller = new Timeline(new KeyFrame(Duration.seconds(1.2), e -> pollStartup()));
            startupPoller.setCycleCount(Timeline.INDEFINITE);
        }
        startupPoller.playFromStart();
        pollStartup();
    }

    private void pollStartup() {
        Ui.onFx(controller.nodeStartupStatus(), status -> {
            if (!status.failed()) {
                startupDetail.setText(status.detail());
                startupDetail.getStyleClass().remove("warning-text");
            }
            long elapsed = System.currentTimeMillis() - startupStartedMs;
            if (!status.reachable() && !status.failed() && elapsed > SLOW_HINT_MS) {
                slowHint.setText("Still working — this is normal for a first sync on a public network. "
                        + "Open the node log above to watch block-by-block progress.");
                setManagedVisible(slowHint, true);
            }
        }, error -> { /* transient; keep the last message */ });
        if (logVisible) {
            refreshLog();
        }
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

    private void stopStartupPoller() {
        if (startupPoller != null) {
            startupPoller.stop();
        }
    }

    private void setIdle() {
        connectButton.setDisable(false);
        spinner.setVisible(false);
        setManagedVisible(cancelButton, false);
    }

    private static void setManagedVisible(Node node, boolean visible) {
        node.setVisible(visible);
        node.setManaged(visible);
    }
}
