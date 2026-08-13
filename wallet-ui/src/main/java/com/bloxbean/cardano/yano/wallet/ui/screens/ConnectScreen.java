package com.bloxbean.cardano.yano.wallet.ui.screens;

import com.bloxbean.cardano.yano.wallet.ui.contract.WalletUiController;
import com.bloxbean.cardano.yano.wallet.ui.util.NetworkPrefs;
import com.bloxbean.cardano.yano.wallet.ui.util.Ui;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.TextField;
import javafx.scene.control.TitledPane;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

import java.util.function.Consumer;

/**
 * First-run / reconnect screen: choose a network and how to reach it — a
 * managed local node the wallet launches (recommended, zero-config), or an
 * external Yano node URL (your own, or a LAN/remote node).
 *
 * <p>While a managed connection is being established the screen shows the node's
 * live startup progress plus an optional log peek ({@link NodeWarmupView}). It
 * hands over to the next screen as soon as the node PROCESS is up, rather than
 * when its API is: the two can be more than an hour apart on a first start, and
 * everything the user does next — create or restore a wallet — is local.
 */
public class ConnectScreen {
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
    /**
     * Optional credential for an external backend (ADR-043); empty for a local
     * node. A PasswordField because it is a credential — the note below says
     * which network the key is for, so nothing needs it visible to be checked.
     */
    private final javafx.scene.control.PasswordField apiKeyField = new javafx.scene.control.PasswordField();
    private final Label apiKeyNote = new Label();
    private final Button connectButton = new Button("Connect");
    /** Escape hatch from an in-flight attempt — without it a saved connection is a trap. */
    private final Button cancelButton = new Button("Change network");
    private final Label statusLabel = new Label();
    /** Collapsed relay editor, hidden for networks with no relays to set (E18). */
    private final TitledPane relayPane = new TitledPane("Upstream relays", new VBox());
    private final ProgressIndicator spinner = new ProgressIndicator();

    // Managed-node startup progress (hidden until a managed connect is running).
    private final NodeWarmupView warmup;
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
        this.warmup = new NodeWarmupView(controller);
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

        buildApiKeyField();

        Label managedHint = Ui.muted("");
        managedHint.setWrapText(true);

        modeGroup.selectedToggleProperty().addListener((obs, old, sel) -> {
            boolean external = sel == externalToggle;
            urlField.setManaged(external);
            urlField.setVisible(external);
            setManagedVisible(apiKeyField, external);
            setManagedVisible(apiKeyNote, external);
            managedHint.setVisible(!external);
            managedHint.setManaged(!external);
            updateConnectLabel();
            updateApiKeyNote();
        });
        managedToggle.setSelected(true);
        updateConnectLabel();

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
            refreshRelayPane(network);
        });
        updateManagedHint(managedHint, networkPicker.getValue());
        refreshRelayPane(networkPicker.getValue());

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

        relayPane.setExpanded(false);
        VBox card = Ui.card("Node connection",
                Ui.muted("Network"), networkPicker,
                modeRow, managedHint, urlField, apiKeyField, apiKeyNote,
                connectButton, cancelButton, statusRow, warmup.root(), relayPane);

        content.getChildren().setAll(brand, tagline, card);
        prefillAndMaybeAutoConnect();
    }

    /**
     * Rebuilds the relay pane for the selected network (E18).
     *
     * <p>Here, and not only in Settings, because this is where it is needed most:
     * if a network's relays have stopped resolving, the alternative is committing
     * to a start that cannot succeed and then hunting for the fix. Node readiness
     * is deliberately "the REST API answers" rather than "the chain is advancing",
     * so a user in that state can still get in — but fixing it before is better
     * than fixing it after.
     *
     * <p>Hidden entirely when the network has no relays to set, rather than shown
     * with an explanation as in Settings: this screen is the first thing a new
     * user sees, and it should not open with a note about a thing they cannot
     * configure and have not asked about.
     */
    private void refreshRelayPane(String network) {
        // Keyed off whether the network HAS relays, not whether they apply to the
        // connection in force. On this screen the user is choosing what to do
        // next, so the previous connection's mode is beside the point.
        boolean applicable = network != null
                && !controller.upstreamRelays(network).shipped().isEmpty();
        setManagedVisible(relayPane, applicable);
        if (applicable) {
            relayPane.setContent(RelayEditor.build(controller, network, null));
        }
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
            // Without this the field is empty on a return visit, and clicking
            // Connect would rebuild the connection WITHOUT the key that made it
            // work — a 403 for no reason the user could see.
            apiKeyField.setText(controller.savedApiKey());
        }
        if (!autoConnect) {
            // A returning user lands here with their last choice prefilled but
            // NOT connected. Say which network is about to be used and what the
            // button will do — starting a local node can sync for a long time,
            // so it should never be a surprise consequence of opening the app.
            updateConnectLabel();
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

    /**
     * The primary button does two different things, so it says which: a managed
     * connection LAUNCHES a node here, while an external one dials a node
     * someone else is already running (your own, or a Yaci DevKit devnet).
     *
     * <p>Kept to one word each. "Start node" named the object as well as the
     * verb, which then read oddly beside the mode buttons that just named it —
     * and there is nothing else on this screen the button could be starting.
     */
    private void updateConnectLabel() {
        connectButton.setText(managedToggle.isSelected() ? "Start" : "Connect");
    }

    /** The typed credential, or null when the field is empty. */
    private String apiKey() {
        String key = apiKeyField.getText();
        return key == null || key.isBlank() ? null : key.trim();
    }

    /**
     * Optional credential for an external backend (ADR-043): a Blockfrost
     * {@code project_id}, or whatever an auth gateway in front of a node checks.
     * Empty is the normal case and stays the default — a local node needs none.
     */
    private void buildApiKeyField() {
        apiKeyField.setPromptText("API key / project ID — optional");
        setManagedVisible(apiKeyField, false);
        apiKeyNote.setWrapText(true);
        apiKeyNote.getStyleClass().add("muted");
        setManagedVisible(apiKeyNote, false);
        // React as it is typed or pasted, so the endpoint and network fill in
        // before the user goes looking for them.
        apiKeyField.textProperty().addListener((obs, old, key) -> onApiKeyChanged(key));
    }

    /**
     * A pasted key names its own network and endpoint, so use it rather than
     * making the user find both (ADR-043 §4). The network is only switched when
     * the URL field is empty or already points at Blockfrost — someone who typed
     * their own node's address and then added a gateway credential must not have
     * it replaced.
     */
    private void onApiKeyChanged(String key) {
        WalletUiController.ApiKeyHint hint = controller.apiKeyHint(key);
        if (hint == null) {
            updateApiKeyNote();
            return;
        }
        String url = urlField.getText() == null ? "" : urlField.getText().trim();
        if (url.isBlank() || url.toLowerCase(java.util.Locale.ROOT).contains("blockfrost.io")) {
            urlField.setText(hint.baseUrl());
            if (!hint.networkId().equals(networkPicker.getValue())
                    && controller.availableNetworks().contains(hint.networkId())) {
                networkPicker.setValue(hint.networkId());
            }
        }
        updateApiKeyNote();
    }

    /**
     * Says what the wallet made of the key, and — the part that matters — warns
     * when it is for a different chain than the one selected. Blockfrost refuses
     * such a request itself ("Network token mismatch"), so this only turns a late
     * and cryptic failure into an early and plain one.
     */
    private void updateApiKeyNote() {
        if (!externalToggle.isSelected()) {
            return;
        }
        WalletUiController.ApiKeyHint hint = controller.apiKeyHint(apiKeyField.getText());
        String selected = networkPicker.getValue();
        if (hint == null) {
            boolean hasKey = apiKeyField.getText() != null && !apiKeyField.getText().isBlank();
            apiKeyNote.setText(hasKey
                    ? "The key will be sent to this backend. Leave it empty for a node that needs none."
                    : "");
            apiKeyNote.getStyleClass().remove("warning-text");
            return;
        }
        if (!hint.networkId().equals(selected)) {
            apiKeyNote.setText("This is a " + controller.networkLabel(hint.networkId()) + " "
                    + hint.provider() + " key, but " + controller.networkLabel(selected)
                    + " is selected. " + hint.provider() + " will refuse it — pick "
                    + controller.networkLabel(hint.networkId()) + ", or use a key for "
                    + controller.networkLabel(selected) + ".");
            if (!apiKeyNote.getStyleClass().contains("warning-text")) {
                apiKeyNote.getStyleClass().add("warning-text");
            }
            return;
        }
        apiKeyNote.getStyleClass().remove("warning-text");
        // The trade being made, in the words that actually describe it: this is
        // the only place the wallet ever says it (ADR-043 §7).
        apiKeyNote.setText(hint.provider() + " serves " + controller.networkLabel(hint.networkId())
                + ". Your addresses and balance lookups go to " + hint.provider()
                + " rather than staying on this machine, and what the wallet shows you — including "
                + "the effect of a transaction you are about to sign — is what they report.");
    }

    private void connect() {
        String network = networkPicker.getValue();
        boolean managed = managedToggle.isSelected();
        String url = urlField.getText() == null ? "" : urlField.getText().trim();
        // Refuse a key scoped to another chain before spending a round trip on
        // it (ADR-043 §4). The provider refuses it too, but this says which two
        // things disagree, where their error only says that they do.
        WalletUiController.ApiKeyHint hint = managed ? null : controller.apiKeyHint(apiKey());
        if (hint != null && !hint.networkId().equals(network)) {
            String message = "That " + hint.provider() + " key is for "
                    + controller.networkLabel(hint.networkId()) + ", not "
                    + controller.networkLabel(network) + ".";
            statusLabel.setText(message);
            Ui.toast(overlay, message, true);
            return;
        }
        NetworkPrefs.remember(network, managed, url);
        beginAttempt(managed, managed
                ? "Starting your local node for " + network + "…"
                : "Connecting to " + url + "…");
        runConnect(managed
                ? controller.connectManaged(network)
                : controller.connectExternal(network, url, apiKey()));
    }

    /**
     * Aborts the in-flight attempt and hands the form back. The pending future
     * still fails afterwards, so {@link #cancelled} keeps that failure quiet.
     */
    private void cancel() {
        cancelled = true;
        controller.cancelConnect();
        warmup.stop();
        setIdle();
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
            warmup.start();
        } else {
            spinner.setVisible(true);
            warmup.stop();
        }
    }

    /**
     * For a managed connection this future completes once the node PROCESS is
     * up, not once its API is — so this hands over to the next screen while the
     * node is still starting, and the wait continues there (see
     * {@link NodeWarmupView}). Local work is available throughout; only the
     * chain-backed parts wait.
     */
    private void runConnect(java.util.concurrent.CompletableFuture<WalletUiController.ConnectionInfo> future) {
        Ui.onFx(future, info -> {
            // Stop polling but leave the view as it is: the screen is about to be
            // replaced, and blanking the progress first only flashes.
            warmup.stopPolling();
            setIdle();
            onConnected.accept(info);
        }, error -> {
            if (cancelled) {
                // The user aborted this attempt; its failure is the expected
                // outcome, not something to report.
                cancelled = false;
                return;
            }
            setIdle();
            String message = "Connection failed: " + error.getMessage();
            statusLabel.setText(message);
            if (managedAttempt) {
                // Show the real cause + the node log inline so a start failure is
                // diagnosable without leaving the screen.
                warmup.showFailure(message);
            }
            Ui.toast(overlay, message, true);
        });
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
