package com.bloxbean.cardano.yano.wallet.ui.screens;

import com.bloxbean.cardano.yano.wallet.ui.contract.WalletUiController;
import com.bloxbean.cardano.yano.wallet.ui.util.Ui;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ContentDisplay;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Landing flow: pick/unlock an existing wallet, create a new one (with a
 * mnemonic-backup step), or restore from a recovery phrase.
 */
public class OnboardingScreen {
    private final WalletUiController controller;
    private final StackPane overlay;
    private final Consumer<WalletUiController.WalletItem> onUnlocked;
    private final Runnable onChangeNetwork;
    private final VBox content = new VBox(16);
    /**
     * Forms (create/restore/unlock/backup) live in their own scroller, centred
     * while they fit and scrolling once they don't — a 24-word backup or a long
     * discovered-account list must never be clipped on a short window.
     */
    private final ScrollPane formScroll = new ScrollPane(new StackPane(content));
    private final StackPane root = new StackPane();

    public OnboardingScreen(WalletUiController controller, StackPane overlay,
                            Consumer<WalletUiController.WalletItem> onUnlocked) {
        this(controller, overlay, onUnlocked, null);
    }

    /**
     * @param onChangeNetwork returns to the Connect screen; may be null. Without
     *                        it this screen is a dead end for anyone who picked
     *                        the wrong network: the wallets on offer here are the
     *                        ones stored for the CURRENT network, so a user who
     *                        wants a different chain has nothing to click.
     */
    public OnboardingScreen(WalletUiController controller, StackPane overlay,
                            Consumer<WalletUiController.WalletItem> onUnlocked,
                            Runnable onChangeNetwork) {
        this.controller = controller;
        this.overlay = overlay;
        this.onUnlocked = onUnlocked;
        this.onChangeNetwork = onChangeNetwork;
        content.setAlignment(Pos.CENTER);
        // Wide enough for the three entry buttons on one line, and for account
        // rows (name + address + action) to breathe.
        content.setMaxWidth(700);
        content.setPadding(new Insets(32));
        // fitToHeight keeps a short form vertically centred (the holder stretches
        // to the viewport); a tall one keeps its preferred height and scrolls.
        formScroll.setFitToWidth(true);
        formScroll.setFitToHeight(true);
        formScroll.getStyleClass().add("screen-scroll");
        root.getStyleClass().add("onboarding");
        showHome();
    }

    public Node root() {
        return root;
    }

    /** Swaps the root to a centred, scrollable form view. */
    private void form(Node... nodes) {
        content.getChildren().setAll(nodes);
        root.getChildren().setAll(formScroll);
        formScroll.setVvalue(0);
    }

    public void showHome() {
        Label brand = new Label("Yano Wallet");
        brand.getStyleClass().add("brand-large");
        Label tagline = new Label("Your keys. Your node. Nothing in between.");
        tagline.getStyleClass().add("brand-sub");
        Label network = Ui.chip(controller.networkId(), "chip-network");
        // Beside the network it changes, so the label and the thing it affects
        // are read together.
        Node networkControls = network;
        if (onChangeNetwork != null) {
            Button switchNetwork = new Button("Switch network");
            switchNetwork.getStyleClass().add("link-button");
            switchNetwork.setTooltip(new Tooltip(
                    "Choose a different network or node. The wallets listed here are the ones "
                            + "stored for " + controller.networkId() + "."));
            switchNetwork.setOnAction(e -> onChangeNetwork.run());
            networkControls = Ui.row(8, network, switchNetwork);
        }
        VBox header = new VBox(6, Ui.row(12, brand, Ui.spacer(), networkControls), tagline);
        header.getStyleClass().add("onboarding-header");

        VBox walletList = new VBox(16);
        walletList.getStyleClass().add("wallet-list");

        Button create = new Button("Create new wallet");
        create.getStyleClass().add("primary-button");
        create.setOnAction(e -> showCreate());
        Button restore = new Button("Restore from recovery phrase");
        restore.getStyleClass().add("ghost-button");
        restore.setOnAction(e -> showRestore());
        Button hardware = new Button("Connect hardware wallet");
        hardware.getStyleClass().add("ghost-button");
        hardware.setOnAction(e -> showConnectHardware());

        // FlowPane, not a fixed row: the three fit on one line, but a longer
        // translation or a larger system font wraps instead of clipping.
        FlowPane actions = new FlowPane(10, 10, create, restore, hardware);
        actions.setAlignment(Pos.CENTER);
        actions.getStyleClass().add("onboarding-footer");

        // Only the account list grows, so only the account list scrolls: the brand
        // stays put and these three entry actions stay reachable no matter how many
        // wallets exist. (They used to sit below the list, which pushed them off
        // screen entirely once a few accounts were added.)
        ScrollPane listScroll = new ScrollPane(walletList);
        listScroll.setFitToWidth(true);
        listScroll.getStyleClass().add("screen-scroll");

        BorderPane home = new BorderPane();
        home.setTop(header);
        home.setCenter(listScroll);
        home.setBottom(actions);
        home.setMaxWidth(700);
        root.getChildren().setAll(home);

        Ui.onFx(controller.listWallets(), wallets -> {
            walletList.getChildren().clear();
            if (wallets.isEmpty()) {
                walletList.getChildren().add(Ui.muted("No wallets on this machine yet"));
                return;
            }
            walletGroups(wallets).forEach((seedId, accounts) ->
                    walletList.getChildren().add(walletGroup(accounts)));
        }, error -> Ui.toast(overlay, "Unable to list wallets: " + error.getMessage(), true));
    }

    /**
     * Groups accounts by seed (ADR-037), preserving first-seen order so the list
     * doesn't reshuffle between refreshes; accounts sort by index within a group.
     */
    static Map<String, List<WalletUiController.WalletItem>> walletGroups(
            List<WalletUiController.WalletItem> wallets) {
        Map<String, List<WalletUiController.WalletItem>> groups = new LinkedHashMap<>();
        wallets.forEach(wallet -> groups
                .computeIfAbsent(wallet.seedId(), key -> new ArrayList<>()).add(wallet));
        groups.values().forEach(accounts ->
                accounts.sort(Comparator.comparingInt(WalletUiController.WalletItem::accountIndex)));
        return groups;
    }

    /**
     * One group per seed: a lightweight section header (name, hardware chip and a
     * compact add button) over the account rows. A header rather than a card —
     * card chrome plus a full-width "+ Add account" row cost more vertical space
     * per group than the accounts themselves.
     */
    private Node walletGroup(List<WalletUiController.WalletItem> accounts) {
        WalletUiController.WalletItem first = accounts.get(0);
        // The group takes its name from account 0 — the name given at create/restore.
        Label name = new Label(first.name().toUpperCase(Locale.ROOT));
        name.getStyleClass().add("group-header");

        int nextIndex = accounts.get(accounts.size() - 1).accountIndex() + 1;
        Button addAccount = new Button("+");
        addAccount.getStyleClass().add("ghost-button-small");
        addAccount.setTooltip(new Tooltip("Add an account to " + first.name()));
        addAccount.setOnAction(e -> {
            if (first.hardware()) {
                showAddHardwareAccount(first, nextIndex);
            } else {
                showAddAccount(first, nextIndex);
            }
        });

        // Hardware-ness is a property of the seed, so it belongs on the group
        // header instead of being repeated on every row.
        HBox head = first.hardware()
                ? Ui.row(8, name, Ui.chip("hardware", "chip-neutral"), Ui.spacer(), addAccount)
                : Ui.row(8, name, Ui.spacer(), addAccount);

        VBox group = new VBox(6, head);
        accounts.forEach(account -> group.getChildren().add(accountRow(account)));
        return group;
    }

    /**
     * The whole row is the click target. It is a Button rather than a styled HBox
     * so it stays keyboard-reachable (Tab/Enter) and gets focus and hover states
     * for free; the name/address/chevron ride along as its graphic.
     */
    private Node accountRow(WalletUiController.WalletItem wallet) {
        Label name = new Label(wallet.accountLabel());
        name.getStyleClass().add("wallet-name");
        Label address = new Label(Ui.middleEllipsis(wallet.baseAddress(), 14));
        address.getStyleClass().add("row-address");
        Label chevron = new Label("›");
        chevron.getStyleClass().add("chevron");

        HBox graphic = new HBox(12, new VBox(1, name, address), Ui.spacer(), chevron);
        graphic.setAlignment(Pos.CENTER_LEFT);

        Button row = new Button();
        row.getStyleClass().add("account-row");
        row.setGraphic(graphic);
        row.setContentDisplay(ContentDisplay.GRAPHIC_ONLY);
        row.setMaxWidth(Double.MAX_VALUE);
        // A Button doesn't stretch its graphic, so track the button's width to keep
        // the chevron pinned to the trailing edge (30 ≈ the row's horizontal padding).
        graphic.prefWidthProperty().bind(row.widthProperty().subtract(30));

        if (wallet.hardware()) {
            // Hardware wallets have no passphrase — opening is device-present only.
            row.setOnAction(e -> {
                row.setDisable(true);
                Ui.onFx(controller.unlockHardware(wallet.walletId()), onUnlocked::accept, error -> {
                    row.setDisable(false);
                    Ui.toast(overlay, "Unlock failed: " + error.getMessage(), true);
                });
            });
        } else {
            row.setOnAction(e -> showUnlock(wallet));
        }
        return row;
    }

    /** Add-account form: the passphrase is the seed's, so it derives the next index. */
    private void showAddAccount(WalletUiController.WalletItem seedWallet, int nextIndex) {
        Label title = new Label("Add account to " + seedWallet.name());
        title.getStyleClass().add("screen-title");
        Label hint = Ui.muted("A new CIP-1852 account from the same recovery phrase — its own addresses, "
                + "stake key and rewards. Unlock it with the same spending passphrase.");
        hint.setWrapText(true);

        TextField name = new TextField();
        name.setPromptText("Account name");
        name.setText("Account " + nextIndex);
        PasswordField passphrase = new PasswordField();
        passphrase.setPromptText("Spending passphrase");

        Label status = Ui.muted("");
        status.setWrapText(true);

        Button add = new Button("Add account");
        add.getStyleClass().add("primary-button");
        Button scan = new Button("Scan for existing accounts");
        scan.getStyleClass().add("ghost-button");
        Button back = new Button("Cancel");
        back.getStyleClass().add("ghost-button");
        back.setOnAction(e -> showHome());

        add.setOnAction(e -> {
            add.setDisable(true);
            Ui.onFx(controller.createAccount(seedWallet.seedId(), name.getText(),
                            passphrase.getText().toCharArray()),
                    created -> {
                        Ui.toast(overlay, "Added " + created.accountLabel(), false);
                        showUnlock(created);
                    },
                    error -> {
                        add.setDisable(false);
                        Ui.toast(overlay, "Could not add account: " + error.getMessage(), true);
                    });
        });
        // A restored seed only creates account 0 — find the rest from the chain.
        scan.setOnAction(e -> {
            scan.setDisable(true);
            status.setText("Scanning the chain for accounts with history…");
            // Capture the passphrase the scan actually used: the field may be
            // edited while the scan runs (it is slow — a KDF unlock plus probes).
            char[] scanned = passphrase.getText().toCharArray();
            Ui.onFx(controller.discoverAccounts(seedWallet.seedId(), scanned),
                    found -> {
                        scan.setDisable(false);
                        if (found.isEmpty()) {
                            status.setText("No other accounts with history found.");
                        } else {
                            showDiscoveredAccounts(seedWallet, scanned, found);
                        }
                    },
                    error -> {
                        scan.setDisable(false);
                        status.setText("");
                        Ui.toast(overlay, "Scan failed: " + error.getMessage(), true);
                    });
        });
        passphrase.setOnAction(e -> add.fire());

        form(Ui.card(null, title, hint, name, passphrase,
                Ui.row(10, add, back), scan, status));
    }

    /** Confirmation step for a scan: the user picks which found accounts to add. */
    private void showDiscoveredAccounts(WalletUiController.WalletItem seedWallet, char[] passphrase,
                                        List<WalletUiController.DiscoveredAccountView> found) {
        Label title = new Label("Accounts found");
        title.getStyleClass().add("screen-title");
        Label hint = Ui.muted("These accounts of " + seedWallet.name() + " have on-chain history but "
                + "aren't in this wallet yet. Add the ones you want to use.");
        hint.setWrapText(true);

        VBox rows = new VBox(8);
        List<CheckBox> checks = new ArrayList<>();
        found.forEach(account -> {
            CheckBox check = new CheckBox("Account " + account.accountIndex());
            check.setSelected(true);
            checks.add(check);
            var row = Ui.row(12, check, Ui.spacer(),
                    Ui.muted(Ui.middleEllipsis(account.baseAddress(), 14)));
            row.getStyleClass().add("list-row");
            rows.getChildren().add(row);
        });

        Button add = new Button("Add selected");
        add.getStyleClass().add("primary-button");
        Button back = new Button("Cancel");
        back.getStyleClass().add("ghost-button");
        back.setOnAction(e -> showHome());

        add.setOnAction(e -> {
            add.setDisable(true);
            List<WalletUiController.DiscoveredAccountView> selected = new ArrayList<>();
            for (int i = 0; i < found.size(); i++) {
                if (checks.get(i).isSelected()) {
                    selected.add(found.get(i));
                }
            }
            if (selected.isEmpty()) {
                showHome();
                return;
            }
            addDiscovered(seedWallet, passphrase, selected, 0);
        });

        form(Ui.card(null, title, hint, rows, Ui.row(10, add, back)));
    }

    /** Adds found accounts one at a time — each derive needs its own vault unlock. */
    private void addDiscovered(WalletUiController.WalletItem seedWallet, char[] passphrase,
                               List<WalletUiController.DiscoveredAccountView> selected, int position) {
        if (position >= selected.size()) {
            Ui.toast(overlay, "Added " + selected.size() + " account(s)", false);
            showHome();
            return;
        }
        // The discovered index, NOT the next free one: the user may add only some
        // of the found accounts, and each must derive the account they approved.
        int accountIndex = selected.get(position).accountIndex();
        Ui.onFx(controller.createAccountAt(seedWallet.seedId(), "Account " + accountIndex,
                        passphrase, accountIndex),
                created -> addDiscovered(seedWallet, passphrase, selected, position + 1),
                error -> {
                    Ui.toast(overlay, "Could not add account " + accountIndex + ": " + error.getMessage(), true);
                    showHome();
                });
    }

    /**
     * Add-account for a hardware group: no passphrase — the next account's public
     * key is read from the device (which may prompt), then opened.
     */
    private void showAddHardwareAccount(WalletUiController.WalletItem seedWallet, int nextIndex) {
        Label title = new Label("Add account to " + seedWallet.name());
        title.getStyleClass().add("screen-title");
        Label hint = Ui.muted("Connect the same device this wallet was imported from, unlock it and open "
                + "the Cardano app. Account " + nextIndex + " will be read from it — approve the export "
                + "on the device if prompted.");
        hint.setWrapText(true);

        TextField name = new TextField();
        name.setPromptText("Account name");
        name.setText("Account " + nextIndex);
        Label status = Ui.muted("");

        Button add = new Button("Add account");
        add.getStyleClass().add("primary-button");
        Button back = new Button("Cancel");
        back.getStyleClass().add("ghost-button");
        back.setOnAction(e -> showHome());

        add.setOnAction(e -> {
            add.setDisable(true);
            status.setText("Reading account " + nextIndex + " from your device — approve on the Ledger if prompted…");
            Ui.onFx(controller.importHardwareAccount(seedWallet.seedId(), name.getText(), nextIndex),
                    created -> {
                        Ui.toast(overlay, "Added " + created.accountLabel(), false);
                        showUnlockFor(created);
                    },
                    error -> {
                        add.setDisable(false);
                        status.setText("");
                        Ui.toast(overlay, "Could not add account: " + error.getMessage(), true);
                    });
        });

        form(Ui.card(null, title, hint, name, status, Ui.row(10, add, back)));
    }

    private void showConnectHardware() {
        Label title = new Label("Connect hardware wallet");
        title.getStyleClass().add("screen-title");
        Label hint = Ui.muted("Connect and unlock your Ledger, open the Cardano app, then continue. "
                + "You may be asked to approve exporting the account on the device.");
        hint.setWrapText(true);
        TextField name = new TextField();
        name.setPromptText("Wallet name (e.g. Ledger)");
        Label status = Ui.muted("");
        Button connect = new Button("Connect");
        connect.getStyleClass().add("primary-button");
        Button back = backButton();
        connect.setOnAction(e -> {
            String walletName = name.getText() == null || name.getText().isBlank() ? "Ledger" : name.getText().trim();
            connect.setDisable(true);
            status.setText("Reading the account from your device — approve on the Ledger if prompted…");
            Ui.onFx(controller.importHardwareWallet(walletName, 0),
                    item -> Ui.onFx(controller.unlockHardware(item.walletId()), onUnlocked::accept, err -> {
                        connect.setDisable(false);
                        status.setText("");
                        Ui.toast(overlay, "Unlock failed: " + err.getMessage(), true);
                    }),
                    error -> {
                        connect.setDisable(false);
                        status.setText("");
                        Ui.toast(overlay, "Connect failed: " + error.getMessage(), true);
                    });
        });
        form(title, Ui.card(null, hint, name, status, Ui.row(10, connect, back)));
    }

    /**
     * Opens straight at {@code wallet}'s unlock step — used by the account
     * switcher (ADR-037), where switching means re-opening the target account.
     * Hardware accounts need no passphrase, so they open immediately.
     */
    public void showUnlockFor(WalletUiController.WalletItem wallet) {
        if (!wallet.hardware()) {
            showUnlock(wallet);
            return;
        }
        Ui.onFx(controller.unlockHardware(wallet.walletId()), onUnlocked::accept, error -> {
            showHome();
            Ui.toast(overlay, "Unlock failed: " + error.getMessage(), true);
        });
    }

    private void showUnlock(WalletUiController.WalletItem wallet) {
        Label title = new Label("Unlock " + wallet.accountLabel());
        title.getStyleClass().add("screen-title");
        PasswordField passphrase = new PasswordField();
        passphrase.setPromptText("Spending passphrase");
        PasswordField pin = new PasswordField();
        pin.setPromptText("Security key PIN");
        hide(pin);
        Label keyNote = Ui.muted("");
        hide(keyNote);
        Button unlock = new Button("Unlock");
        unlock.getStyleClass().add("primary-button");
        Button back = backButton();

        // Learn upfront whether this wallet is protected by a security key so we
        // can reveal a PIN field and prompt for a touch (ADR-036, opt-in).
        WalletUiController.SecurityKeyView[] status = {null};
        Ui.onFx(controller.securityKey(wallet.walletId()), s -> {
            status[0] = s;
            if (s.protectedByKey()) {
                show(keyNote);
                if (s.requiresPin()) {
                    show(pin);
                }
                if (s.passwordless()) {
                    hide(passphrase); // no passphrase — the key + PIN opens it (ADR-040)
                    keyNote.setText(s.label() + " — unlock with your security key + PIN.");
                    pin.setOnAction(e -> unlock.fire());
                } else {
                    keyNote.setText(s.label() + " — you'll be asked to touch it.");
                }
            }
        }, error -> { /* status unknown — treat as passphrase-only */ });

        unlock.setOnAction(e -> {
            unlock.setDisable(true);
            char[] pass = passphrase.getText().toCharArray();
            WalletUiController.SecurityKeyView s = status[0];
            if (s == null || !s.protectedByKey()) {
                Ui.onFx(controller.unlock(wallet.walletId(), pass), onUnlocked::accept, error -> {
                    unlock.setDisable(false);
                    Ui.toast(overlay, "Unlock failed — check your passphrase", true);
                });
                return;
            }
            char[] pinChars = s.requiresPin() ? pin.getText().toCharArray() : null;
            java.util.function.Supplier<char[]> pinProvider = pinChars == null ? null : pinChars::clone;
            Runnable onTouch = () -> Platform.runLater(() -> Ui.toast(overlay, "Touch your security key", false));
            Ui.onFx(controller.unlockWithSecurityKey(wallet.walletId(), pass, pinProvider, onTouch),
                    onUnlocked::accept,
                    error -> {
                        unlock.setDisable(false);
                        Ui.toast(overlay, "Unlock failed: " + error.getMessage(), true);
                    });
        });
        passphrase.setOnAction(e -> unlock.fire());
        form(title, Ui.card(null, passphrase, pin, keyNote, Ui.row(10, unlock, back)));
        passphrase.requestFocus();
    }

    private static void hide(Node node) {
        node.setVisible(false);
        node.setManaged(false);
    }

    private static void show(Node node) {
        node.setVisible(true);
        node.setManaged(true);
    }

    private void showCreate() {
        Label title = new Label("Create wallet");
        title.getStyleClass().add("screen-title");
        TextField name = new TextField();
        name.setPromptText("Wallet name");
        PasswordField passphrase = new PasswordField();
        passphrase.setPromptText("Spending passphrase");
        PasswordField confirmPassphrase = new PasswordField();
        confirmPassphrase.setPromptText("Confirm passphrase");
        Button create = new Button("Create");
        create.getStyleClass().add("primary-button");
        Button back = backButton();
        create.setOnAction(e -> {
            if (name.getText() == null || name.getText().isBlank()) {
                Ui.toast(overlay, "Wallet name is required", true);
                return;
            }
            if (!passphrase.getText().equals(confirmPassphrase.getText())) {
                Ui.toast(overlay, "Passphrases do not match", true);
                return;
            }
            create.setDisable(true);
            Ui.onFx(controller.createWallet(name.getText().trim(), passphrase.getText().toCharArray()),
                    this::showMnemonicBackup,
                    error -> {
                        create.setDisable(false);
                        Ui.toast(overlay, "Create failed: " + error.getMessage(), true);
                    });
        });
        form(title,
                Ui.card(null, name, passphrase, confirmPassphrase, Ui.row(10, create, back)));
    }

    private void showMnemonicBackup(WalletUiController.CreatedWallet created) {
        Label title = new Label("Write down your recovery phrase");
        title.getStyleClass().add("screen-title");
        Label warning = new Label("These 24 words are the ONLY way to recover your funds. "
                + "Write them down in order and keep them offline. Anyone with these words owns this wallet.");
        warning.getStyleClass().add("warning-text");
        warning.setWrapText(true);

        FlowPane words = new FlowPane(8, 8);
        List<String> mnemonicWords = List.of(created.mnemonic().split("\\s+"));
        for (int i = 0; i < mnemonicWords.size(); i++) {
            Label word = new Label((i + 1) + ". " + mnemonicWords.get(i));
            word.getStyleClass().add("mnemonic-word");
            words.getChildren().add(word);
        }

        Button done = new Button("I wrote them down — open wallet");
        done.getStyleClass().add("primary-button");
        done.setOnAction(e -> showUnlock(created.wallet()));

        form(title, Ui.card(null, warning, words, done));
    }

    private void showRestore() {
        Label title = new Label("Restore wallet");
        title.getStyleClass().add("screen-title");
        TextField name = new TextField();
        name.setPromptText("Wallet name");
        TextArea mnemonic = new TextArea();
        mnemonic.setPromptText("Recovery phrase (12–24 words, space separated)");
        mnemonic.setPrefRowCount(3);
        mnemonic.setWrapText(true);
        PasswordField passphrase = new PasswordField();
        passphrase.setPromptText("New spending passphrase");
        Button restore = new Button("Restore");
        restore.getStyleClass().add("primary-button");
        Button back = backButton();
        restore.setOnAction(e -> {
            restore.setDisable(true);
            Ui.onFx(controller.restoreWallet(
                            name.getText() == null ? "" : name.getText().trim(),
                            mnemonic.getText(),
                            passphrase.getText().toCharArray()),
                    this::showUnlock,
                    error -> {
                        restore.setDisable(false);
                        Ui.toast(overlay, "Restore failed: " + error.getMessage(), true);
                    });
        });
        form(title,
                Ui.card(null, name, mnemonic, passphrase, Ui.row(10, restore, back)));
    }

    private Button backButton() {
        Button back = new Button("Back");
        back.getStyleClass().add("ghost-button");
        back.setOnAction(e -> showHome());
        return back;
    }
}
