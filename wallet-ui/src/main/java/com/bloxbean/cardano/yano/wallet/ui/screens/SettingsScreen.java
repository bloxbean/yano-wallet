package com.bloxbean.cardano.yano.wallet.ui.screens;

import com.bloxbean.cardano.yano.wallet.ui.Shell;
import com.bloxbean.cardano.yano.wallet.ui.contract.WalletUiController;
import com.bloxbean.cardano.yano.wallet.ui.live.LivePrefs;
import com.bloxbean.cardano.yano.wallet.ui.util.ThemeManager;
import com.bloxbean.cardano.yano.wallet.ui.util.Ui;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.RadioButton;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextArea;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

/** Node connection, wallet info, and connected dApps. */
public class SettingsScreen implements Shell.Screen {
    private final WalletUiController controller;
    private final StackPane overlay;
    private final Label nodeLine = new Label();
    private final Label nodeState = new Label();
    private final Label walletLine = new Label();
    private final VBox dappsBox = new VBox(8);
    private final Label securityLine = new Label();
    private final VBox securityBox = new VBox(8);
    private final Label dataDirLine = new Label();
    private final TextArea nodeLog = new TextArea();
    private final ScrollPane root;

    private final Runnable onChangeNetwork;

    public SettingsScreen(WalletUiController controller, StackPane overlay) {
        this(controller, overlay, null);
    }

    public SettingsScreen(WalletUiController controller, StackPane overlay, Runnable onChangeNetwork) {
        this.controller = controller;
        this.overlay = overlay;
        this.onChangeNetwork = onChangeNetwork;
        this.root = build();
    }

    private ScrollPane build() {
        Label title = new Label("Settings");
        title.getStyleClass().add("screen-title");

        nodeLine.getStyleClass().add("mono");
        nodeState.getStyleClass().add("muted");
        walletLine.getStyleClass().add("mono");
        walletLine.setWrapText(true);

        VBox nodeCard = Ui.card("Node connection",
                nodeLine, nodeState,
                Ui.muted("Your wallet talks only to this node — no third-party APIs. "
                        + "Run it with the 'wallet' profile for full history and rewards."),
                changeNetworkControls());
        VBox walletCard = Ui.card("Active wallet", walletLine,
                Ui.muted("Keys live in an Argon2id-encrypted vault on this machine. "
                        + "Keep your recovery phrase offline."));
        VBox dappsCard = Ui.card("Connected dApps",
                Ui.muted("Sites you've connected via the Yano browser extension (CIP-30). "
                        + "Disconnect a site to make it ask for approval again."),
                dappsBox);

        // Native Messaging host installer (ADR-035 M5): once installed, the
        // browser launches the connector itself — no localhost port involved.
        Button installHost = new Button("Install browser connector (Native Messaging)");
        installHost.getStyleClass().add("ghost-button");
        installHost.setOnAction(e -> {
            installHost.setDisable(true);
            Ui.onFx(controller.installNativeMessagingHost(),
                    summary -> {
                        installHost.setDisable(false);
                        Ui.toast(overlay, summary, false);
                    },
                    error -> {
                        installHost.setDisable(false);
                        Ui.toast(overlay, "Install failed: " + error.getMessage(), true);
                    });
        });
        VBox connectorCard = Ui.card("Browser connector",
                Ui.muted("Registers the secure Native Messaging transport with your browser: Chrome "
                        + "verifies the Yano extension's identity and launches the connector on demand, "
                        + "replacing the localhost WebSocket. Restart the browser afterwards."),
                installHost);

        securityLine.setWrapText(true);
        VBox securityCard = Ui.card("Security key (optional)",
                Ui.muted("Add a FIDO2 security key as a second factor. The vault then needs the key "
                        + "AND your passphrase to open. Your 24-word recovery phrase is still the "
                        + "ultimate backup if the key is lost."),
                securityLine, securityBox);

        // Advanced: where the data lives + the managed node's log (the only place
        // to see what a slow sync is actually doing).
        dataDirLine.getStyleClass().add("mono");
        dataDirLine.setWrapText(true);
        nodeLog.setEditable(false);
        nodeLog.setWrapText(false);
        nodeLog.setPrefRowCount(16);
        nodeLog.getStyleClass().add("node-log");
        Button refreshLog = new Button("Refresh");
        refreshLog.getStyleClass().add("ghost-button");
        refreshLog.setOnAction(e -> loadNodeLog());
        VBox advancedCard = Ui.card("Advanced",
                Ui.muted("Data directory (wallets + node database)"),
                dataDirLine,
                Ui.muted("Managed node log (last 100 lines)"),
                refreshLog,
                nodeLog);

        VBox column = new VBox(16, title, appearanceCard(), nodeCard, walletCard, securityCard,
                dappsCard, connectorCard, advancedCard);
        column.setPadding(new Insets(24));
        column.setMaxWidth(860);
        ScrollPane scroll = new ScrollPane(column);
        scroll.setFitToWidth(true);
        scroll.getStyleClass().add("screen-scroll");
        return scroll;
    }

    /**
     * "Change network or node", with an inline confirm. Each network keeps its own
     * wallets and chain data on disk, so switching shows a different set of
     * wallets — that is a consequence worth stating before it happens, not after.
     */
    private Node changeNetworkControls() {
        if (onChangeNetwork == null) {
            return new VBox(); // no navigation host (e.g. a standalone/test view)
        }
        Button change = new Button("Change network or node…");
        change.getStyleClass().add("ghost-button");

        Label warning = Ui.muted("This locks the wallet and returns to the connection screen. "
                + "Each network has its own wallets, so a different network shows a different list.");
        warning.setWrapText(true);
        Button confirm = new Button("Continue");
        confirm.getStyleClass().add("primary-button");
        confirm.setOnAction(e -> onChangeNetwork.run());
        Button cancel = new Button("Cancel");
        cancel.getStyleClass().add("ghost-button");

        VBox confirmBox = new VBox(10, warning, Ui.row(10, confirm, cancel));
        confirmBox.setVisible(false);
        confirmBox.setManaged(false);
        change.setOnAction(e -> {
            confirmBox.setVisible(true);
            confirmBox.setManaged(true);
            change.setVisible(false);
            change.setManaged(false);
        });
        cancel.setOnAction(e -> {
            confirmBox.setVisible(false);
            confirmBox.setManaged(false);
            change.setVisible(true);
            change.setManaged(true);
        });
        return new VBox(10, change, confirmBox);
    }

    @Override
    public Node root() {
        return root;
    }

    @Override
    public void refresh() {
        nodeLine.setText(controller.nodeBaseUrl() + "  ·  " + controller.networkId());
        WalletUiController.WalletItem wallet = controller.activeWallet();
        if (wallet != null) {
            walletLine.setText(wallet.name() + "  ·  account #" + wallet.accountIndex()
                    + "\n" + wallet.baseAddress());
        }
        Ui.onFx(controller.nodeStatus(), status -> nodeState.setText(
                        "slot " + status.slot() + " · block " + status.blockNumber()
                                + (status.caughtUp() ? " · synced"
                                : " · syncing (lag " + status.utxoLagBlocks() + ")")),
                error -> nodeState.setText("Node unreachable: " + error.getMessage()));

        dataDirLine.setText(controller.dataDir());
        loadNodeLog();
        loadDapps();
        loadSecurityKey();
    }

    private VBox appearanceCard() {
        ComboBox<String> themePicker = new ComboBox<>();
        ThemeManager.themes().forEach(theme -> themePicker.getItems().add(theme.label()));
        themePicker.setValue(ThemeManager.labelFor(ThemeManager.currentId()));
        themePicker.setOnAction(e -> {
            String label = themePicker.getValue();
            ThemeManager.themes().stream()
                    .filter(theme -> theme.label().equals(label))
                    .findFirst()
                    .ifPresent(theme -> {
                        var scene = root.getScene();
                        if (scene != null) {
                            ThemeManager.apply(scene, theme.id());
                        }
                    });
        });
        CheckBox liveBackground = new CheckBox("Animated live-chain background");
        liveBackground.setSelected(LivePrefs.ambientEnabled());
        liveBackground.setOnAction(e -> LivePrefs.setAmbientEnabled(liveBackground.isSelected()));
        return Ui.card("Appearance",
                Ui.muted("Color theme — applies instantly and is remembered."),
                themePicker,
                liveBackground,
                Ui.muted("A subtle animation of live blocks behind the app. Opt-in; it pauses when the "
                        + "window isn't focused. Open the Live page for the full view."));
    }

    private void loadNodeLog() {
        if (!controller.managedNode()) {
            nodeLog.setText("Connected to an external node — no local node log.");
            return;
        }
        Ui.onFx(controller.nodeLogTail(100), lines -> {
            nodeLog.setText(lines.isEmpty() ? "(no log yet)" : String.join("\n", lines));
            nodeLog.setScrollTop(Double.MAX_VALUE);
        }, error -> nodeLog.setText("Unable to read the node log: " + error.getMessage()));
    }

    private void loadSecurityKey() {
        WalletUiController.WalletItem wallet = controller.activeWallet();
        securityBox.getChildren().clear();
        if (wallet == null || wallet.hardware()) {
            securityLine.setText("");
            securityBox.getChildren().add(Ui.muted(wallet == null
                    ? "Unlock a software wallet to manage its security key."
                    : "Hardware wallets are already protected by the device."));
            return;
        }
        Ui.onFx(controller.securityKey(wallet.walletId()), status -> {
            securityLine.setText(status.label());
            securityBox.getChildren().clear();
            if (status.protectedByKey()) {
                Button remove = new Button("Remove security key");
                remove.getStyleClass().add("ghost-button");
                remove.setOnAction(e -> showRemove(wallet, status));
                securityBox.getChildren().add(remove);
            } else {
                Button protect = new Button("Protect with a security key");
                protect.getStyleClass().add("ghost-button");
                protect.setOnAction(e -> showEnroll(wallet));
                securityBox.getChildren().add(protect);
            }
        }, error -> securityLine.setText("Security-key status unavailable: " + error.getMessage()));
    }

    private void showEnroll(WalletUiController.WalletItem wallet) {
        PasswordField passphrase = new PasswordField();
        passphrase.setPromptText("Spending passphrase");

        ToggleGroup modes = new ToggleGroup();
        RadioButton touchOnly = new RadioButton("Passphrase + touch — tap the key to unlock");
        touchOnly.setToggleGroup(modes);
        touchOnly.setSelected(true);
        RadioButton withPin = new RadioButton("Passphrase + PIN — tap + PIN (stronger)");
        withPin.setToggleGroup(modes);
        RadioButton passwordless = new RadioButton("No passphrase — unlock with the key + PIN only");
        passwordless.setToggleGroup(modes);

        PasswordField pin = new PasswordField();
        pin.setPromptText("Your key's FIDO2 PIN");
        Button setPin = new Button("Set a PIN…");
        setPin.getStyleClass().add("ghost-button");
        setPin.setOnAction(e -> showSetPin());
        VBox pinBox = new VBox(8,
                Ui.muted("Enter your key's FIDO2 PIN — or set one below if you haven't."), pin, setPin);
        setManagedVisible(pinBox, false);
        modes.selectedToggleProperty().addListener((o, a, b) ->
                setManagedVisible(pinBox, withPin.isSelected() || passwordless.isSelected()));

        CheckBox recovery = new CheckBox("I still have my 24-word recovery phrase saved somewhere safe");
        Button enroll = new Button("Protect");
        enroll.getStyleClass().add("primary-button");
        Button cancel = new Button("Cancel");
        cancel.getStyleClass().add("ghost-button");

        StackPane scrim = modalScrim();
        VBox card = modalCard("Protect with a security key",
                Ui.muted("Adds your FIDO2 security key as a second factor. \"No passphrase\" makes the "
                        + "key + PIN the only way in. Either way, lose the key and only your recovery "
                        + "phrase can restore the wallet — keep it safe."),
                passphrase, touchOnly, withPin, passwordless, pinBox, recovery,
                Ui.row(10, enroll, cancel));
        scrim.getChildren().add(card);
        overlay.getChildren().add(scrim);
        cancel.setOnAction(e -> overlay.getChildren().remove(scrim));
        enroll.setOnAction(e -> {
            if (!recovery.isSelected()) {
                Ui.toast(overlay, "Confirm you still have your recovery phrase first", true);
                return;
            }
            boolean pwless = passwordless.isSelected();
            boolean uv = withPin.isSelected() || pwless;
            if (uv && pin.getText().isEmpty()) {
                Ui.toast(overlay, "Enter your key's FIDO2 PIN (or set one)", true);
                return;
            }
            char[] pinChars = uv ? pin.getText().toCharArray() : null;
            java.util.function.Supplier<char[]> pinProvider = pinChars == null ? null : pinChars::clone;
            enroll.setDisable(true);
            Runnable onTouch = () -> Platform.runLater(() -> Ui.toast(overlay, "Touch your security key", false));
            Ui.onFx(controller.enrollSecurityKey(wallet.walletId(), passphrase.getText().toCharArray(),
                            "fido2", 0, pwless, pinProvider, onTouch),
                    v -> {
                        overlay.getChildren().remove(scrim);
                        Ui.toast(overlay, pwless
                                ? "Wallet protected — unlock with your key + PIN (no passphrase)"
                                : "Wallet protected with your security key", false);
                        loadSecurityKey();
                    },
                    error -> {
                        enroll.setDisable(false);
                        Ui.toast(overlay, "Couldn't protect: " + error.getMessage(), true);
                    });
        });
    }

    /** In-app FIDO2 PIN set (key-global; loud warning). Opens on top of the enrol modal. */
    private void showSetPin() {
        PasswordField newPin = new PasswordField();
        newPin.setPromptText("New FIDO2 PIN (4+ characters)");
        Button set = new Button("Set PIN");
        set.getStyleClass().add("primary-button");
        Button cancel = new Button("Cancel");
        cancel.getStyleClass().add("ghost-button");

        StackPane scrim = modalScrim();
        VBox card = modalCard("Set a FIDO2 PIN",
                Ui.muted("This sets your key's FIDO2 PIN. It applies to ALL FIDO2 use of this key "
                        + "(e.g. website passkeys) and can only be removed by resetting the key's FIDO2 "
                        + "app. Tap-only security-key logins are unaffected."),
                newPin, Ui.row(10, set, cancel));
        scrim.getChildren().add(card);
        overlay.getChildren().add(scrim);
        cancel.setOnAction(e -> overlay.getChildren().remove(scrim));
        set.setOnAction(e -> {
            set.setDisable(true);
            Runnable onTouch = () -> Platform.runLater(() -> Ui.toast(overlay, "Touch your security key", false));
            Ui.onFx(controller.setFido2Pin(newPin.getText().toCharArray(), onTouch),
                    v -> {
                        overlay.getChildren().remove(scrim);
                        Ui.toast(overlay, "PIN set — now enter it above to enable PIN protection", false);
                    },
                    error -> {
                        set.setDisable(false);
                        Ui.toast(overlay, "Couldn't set PIN: " + error.getMessage(), true);
                    });
        });
    }

    private static void setManagedVisible(Node node, boolean visible) {
        node.setVisible(visible);
        node.setManaged(visible);
    }

    private void showRemove(WalletUiController.WalletItem wallet, WalletUiController.SecurityKeyView status) {
        PasswordField passphrase = new PasswordField();
        passphrase.setPromptText(status.passwordless() ? "Set a passphrase (required)" : "Spending passphrase");
        PasswordField pin = new PasswordField();
        pin.setPromptText("Security key PIN");
        pin.setVisible(status.requiresPin());
        pin.setManaged(status.requiresPin());
        Button remove = new Button("Remove");
        remove.getStyleClass().add("primary-button");
        Button cancel = new Button("Cancel");
        cancel.getStyleClass().add("ghost-button");

        String note = status.passwordless()
                ? "This vault has no passphrase. Removing the key sets the passphrase above and returns "
                        + "to passphrase-only. Touch the key + enter its PIN."
                : "The vault goes back to passphrase-only. You'll be asked to touch the key.";
        StackPane scrim = modalScrim();
        VBox card = modalCard("Remove security key", passphrase, pin, Ui.muted(note),
                Ui.row(10, remove, cancel));
        scrim.getChildren().add(card);
        overlay.getChildren().add(scrim);
        cancel.setOnAction(e -> overlay.getChildren().remove(scrim));
        remove.setOnAction(e -> {
            remove.setDisable(true);
            char[] pinChars = status.requiresPin() ? pin.getText().toCharArray() : null;
            java.util.function.Supplier<char[]> pinProvider = pinChars == null ? null : pinChars::clone;
            Runnable onTouch = () -> Platform.runLater(() -> Ui.toast(overlay, "Touch your security key", false));
            Ui.onFx(controller.removeSecurityKey(wallet.walletId(), passphrase.getText().toCharArray(),
                            pinProvider, onTouch),
                    v -> {
                        overlay.getChildren().remove(scrim);
                        Ui.toast(overlay, "Security key removed", false);
                        loadSecurityKey();
                    },
                    error -> {
                        remove.setDisable(false);
                        Ui.toast(overlay, "Couldn't remove: " + error.getMessage(), true);
                    });
        });
    }

    private StackPane modalScrim() {
        StackPane scrim = new StackPane();
        scrim.getStyleClass().add("modal-scrim");
        scrim.setOnMouseClicked(javafx.scene.input.MouseEvent::consume);
        return scrim;
    }

    private VBox modalCard(String title, Node... content) {
        Label heading = new Label(title);
        heading.getStyleClass().add("card-title");
        VBox card = new VBox(12, heading);
        card.getStyleClass().addAll("card", "modal-card");
        card.setMaxWidth(460);
        card.getChildren().addAll(content);
        StackPane.setAlignment(card, Pos.CENTER);
        return card;
    }

    private void loadDapps() {
        Ui.onFx(controller.connectedDapps(), origins -> {
            dappsBox.getChildren().clear();
            if (origins.isEmpty()) {
                dappsBox.getChildren().add(Ui.muted("No dApps connected yet."));
                return;
            }
            for (String origin : origins) {
                Label label = new Label(origin);
                label.getStyleClass().add("mono");
                Button disconnect = new Button("Disconnect");
                disconnect.getStyleClass().add("ghost-button");
                disconnect.setOnAction(e -> {
                    disconnect.setDisable(true);
                    Ui.onFx(controller.forgetDapp(origin), v -> {
                        Ui.toast(overlay, "Disconnected " + origin, false);
                        loadDapps();
                    }, error -> {
                        disconnect.setDisable(false);
                        Ui.toast(overlay, "Couldn't disconnect: " + error.getMessage(), true);
                    });
                });
                dappsBox.getChildren().add(Ui.row(10, label, Ui.spacer(), disconnect));
            }
        }, error -> dappsBox.getChildren().setAll(
                Ui.muted("Couldn't load connected dApps: " + error.getMessage())));
    }
}
