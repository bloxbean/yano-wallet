package com.bloxbean.cardano.yano.wallet.ui;

import com.bloxbean.cardano.yano.wallet.ui.contract.WalletUiController;
import com.bloxbean.cardano.yano.wallet.ui.screens.DashboardScreen;
import com.bloxbean.cardano.yano.wallet.ui.screens.HistoryScreen;
import com.bloxbean.cardano.yano.wallet.ui.screens.ReceiveScreen;
import com.bloxbean.cardano.yano.wallet.ui.screens.SendScreen;
import com.bloxbean.cardano.yano.wallet.ui.screens.SettingsScreen;
import com.bloxbean.cardano.yano.wallet.ui.screens.GovernanceScreen;
import com.bloxbean.cardano.yano.wallet.ui.screens.ProposalsScreen;
import com.bloxbean.cardano.yano.wallet.ui.screens.LiveScreen;
import com.bloxbean.cardano.yano.wallet.ui.screens.StakingScreen;
import com.bloxbean.cardano.yano.wallet.ui.live.LiveBlocksView;
import com.bloxbean.cardano.yano.wallet.ui.live.LiveChainModel;
import com.bloxbean.cardano.yano.wallet.ui.live.LivePrefs;
import com.bloxbean.cardano.yano.wallet.ui.util.Icons;
import com.bloxbean.cardano.yano.wallet.ui.util.Ui;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.geometry.Side;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.Tooltip;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.util.Duration;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Main window after unlock: icon sidebar navigation, live node status pill,
 * screen container, and the toast overlay. Each screen is an independent
 * component created lazily and refreshed on activation.
 */
public class Shell {
    private final WalletUiController controller;
    private final Runnable onLock;
    /** Leaves the shell for the Connect screen so the node/network can be changed. */
    private Runnable onChangeNetwork;
    private final Consumer<WalletUiController.WalletItem> onSwitchAccount;
    private final StackPane overlay = new StackPane();
    private final StackPane content = new StackPane();
    private final Map<String, Supplier<Screen>> registry = new LinkedHashMap<>();
    private final Map<String, Screen> screens = new LinkedHashMap<>();
    private final Map<String, Button> navButtons = new LinkedHashMap<>();
    private final Label syncPill = new Label("connecting…");
    private final ProgressBar syncBar = new ProgressBar();
    private final Tooltip syncTooltip = new Tooltip();
    private final Timeline statusPoller;
    private final LiveChainModel liveModel = new LiveChainModel();
    private LiveBlocksView ambient;
    private String active;
    // Delta tracking to tell "advancing" from "stalled" during catch-up without
    // any network-tip math: remember the highest block seen and when it last rose.
    private long lastBlock = -1;
    private long lastAdvanceMs;
    /** No block progress for this long while syncing → surface a "stalled?" hint. */
    private static final long STALL_MS = 30_000;

    public interface Screen {
        Node root();

        /** Called every time the screen becomes visible. */
        void refresh();

        /**
         * Periodic silent refresh while the screen is the active one (driven by
         * the shell's status poller). Unlike {@link #refresh()} it must not toast
         * on transient errors — it keeps the last-good data so a live view (e.g.
         * a pending tx flipping to confirmed) updates on its own. Default: no-op.
         */
        default void poll() {
        }
    }

    /**
     * @param onLock          returns to onboarding after the session is discarded
     * @param onSwitchAccount opens onboarding at the unlock step for a sibling
     *                        account (ADR-037 v1: switching is a re-open)
     */
    public Shell(WalletUiController controller, Runnable onLock,
                 Consumer<WalletUiController.WalletItem> onSwitchAccount,
                 Runnable onChangeNetwork) {
        this.controller = controller;
        this.onLock = onLock;
        this.onSwitchAccount = onSwitchAccount;
        this.onChangeNetwork = onChangeNetwork;
        registry.put("Dashboard", () -> new DashboardScreen(controller, overlay, this::navigate));
        registry.put("Send", () -> new SendScreen(controller, overlay));
        registry.put("Receive", () -> new ReceiveScreen(controller, overlay));
        registry.put("History", () -> new HistoryScreen(controller, overlay));
        registry.put("Staking", () -> new StakingScreen(controller, overlay));
        registry.put("Governance", () -> new GovernanceScreen(controller, overlay));
        registry.put("Proposals", () -> new ProposalsScreen(controller, overlay));
        registry.put("Live", () -> new LiveScreen(controller, overlay, liveModel));
        registry.put("Settings", () -> new SettingsScreen(controller, overlay, this::changeNetwork));

        statusPoller = new Timeline(new KeyFrame(Duration.seconds(5), e -> tick()));
        statusPoller.setCycleCount(Timeline.INDEFINITE);
    }

    public StackPane root() {
        BorderPane frame = new BorderPane();
        frame.getStyleClass().add("shell");
        frame.setLeft(buildSidebar());
        // Ambient live-chain background sits behind every screen; screens have
        // transparent gaps, so it shows through without touching their styling.
        ambient = new LiveBlocksView(liveModel, true);
        StackPane center = new StackPane(ambient, content);
        frame.setCenter(center);
        overlay.getChildren().add(frame);
        syncAmbient();
        LivePrefs.setOnChange(() -> javafx.application.Platform.runLater(this::syncAmbient));
        navigate("Dashboard");
        statusPoller.play();
        pollStatus();
        return overlay;
    }

    public void dispose() {
        statusPoller.stop();
        LivePrefs.setOnChange(null);
        if (ambient != null) {
            ambient.dispose();
        }
    }

    /** Reflect the ambient-background toggle: show/hide + start/stop the animation. */
    private void syncAmbient() {
        boolean on = LivePrefs.ambientEnabled();
        if (ambient != null) {
            ambient.setVisible(on);
            ambient.setManaged(on);
            ambient.setActive(on);
        }
        if (on) {
            pollLiveChain();
        }
    }

    private boolean liveNeeded() {
        return LivePrefs.ambientEnabled() || "Live".equals(active);
    }

    private void pollLiveChain() {
        Ui.onFx(controller.liveChain(),
                view -> liveModel.update(view, System.nanoTime()),
                error -> liveModel.update(WalletUiController.LiveChainView.unreachable(), System.nanoTime()));
    }

    /** The Yano mark for the sidebar brand lockup, or null if the asset is missing. */
    private ImageView logoView() {
        try (var in = getClass().getResourceAsStream("/icons/logo-256.png")) {
            if (in != null) {
                ImageView view = new ImageView(new Image(in, 26, 26, true, true));
                view.setFitWidth(26);
                view.setFitHeight(26);
                return view;
            }
        } catch (Exception ignored) {
            // A missing logo must never block the UI.
        }
        return null;
    }

    /**
     * Leaves the shell for the Connect screen. The session is bound to the
     * current backend, so it is discarded first — and each network keeps its own
     * wallets on disk, so this genuinely changes which wallets are on offer.
     */
    private void changeNetwork() {
        if (onChangeNetwork == null) {
            return;
        }
        dispose();
        controller.lock();
        onChangeNetwork.run();
    }

    private Node buildSidebar() {
        VBox side = new VBox(6);
        side.getStyleClass().add("sidebar");
        side.setPadding(new Insets(18, 12, 18, 12));
        side.setPrefWidth(196);

        Label brand = new Label("Yano Wallet", logoView());
        brand.getStyleClass().add("brand");
        brand.setGraphicTextGap(9);
        HBox brandBox = new HBox(brand);
        brandBox.setAlignment(Pos.CENTER_LEFT);
        brandBox.setPadding(new Insets(0, 6, 12, 6));

        side.getChildren().addAll(brandBox, buildAccountSwitcher());
        addNav(side, "Dashboard", Icons.DASHBOARD);
        addNav(side, "Send", Icons.SEND);
        addNav(side, "Receive", Icons.RECEIVE);
        addNav(side, "History", Icons.HISTORY);
        addNav(side, "Staking", Icons.STAKING);
        addNav(side, "Governance", Icons.GOVERNANCE);
        addNav(side, "Proposals", Icons.PROPOSALS);
        addNav(side, "Live", Icons.LIVE);
        addNav(side, "Settings", Icons.SETTINGS);

        Region grow = new Region();
        VBox.setVgrow(grow, Priority.ALWAYS);

        syncPill.getStyleClass().addAll("chip", "chip-sync");
        syncPill.setMaxWidth(Double.MAX_VALUE);
        syncPill.setAlignment(Pos.CENTER);
        syncTooltip.setShowDelay(Duration.millis(200));
        syncPill.setTooltip(syncTooltip);

        // A thin indeterminate bar under the pill, shown only while catching up.
        syncBar.getStyleClass().add("sync-progress");
        syncBar.setMaxWidth(Double.MAX_VALUE);
        syncBar.setProgress(ProgressBar.INDETERMINATE_PROGRESS);
        syncBar.setVisible(false);
        syncBar.setManaged(false);

        // Clickable: the network pill is where people look when they want to
        // change networks, so it goes to the same place Settings does.
        Button network = new Button(controller.networkLabel(controller.networkId()));
        network.getStyleClass().addAll("chip", "chip-network", "chip-button");
        network.setMaxWidth(Double.MAX_VALUE);
        network.setAlignment(Pos.CENTER);
        Tooltip networkTip = new Tooltip("Change network or node");
        networkTip.setShowDelay(Duration.millis(200));
        network.setTooltip(networkTip);
        network.setOnAction(e -> changeNetwork());

        Button lock = new Button("Lock", Icons.icon(Icons.LOCK, 14, "nav-icon"));
        lock.getStyleClass().add("nav-button");
        lock.setMaxWidth(Double.MAX_VALUE);
        lock.setOnAction(e -> {
            dispose();
            controller.lock();
            onLock.run();
        });

        side.getChildren().addAll(grow, network, syncPill, syncBar, lock);
        return side;
    }

    /**
     * The active account, clickable to switch to a sibling of the same seed
     * (ADR-037). Siblings are listed lazily on click so the menu reflects
     * accounts added since the shell opened.
     */
    private Node buildAccountSwitcher() {
        WalletUiController.WalletItem active = controller.activeWallet();
        if (active == null) {
            return new Region();
        }
        Button chip = new Button(active.accountLabel());
        chip.getStyleClass().add("account-chip");
        chip.setMaxWidth(Double.MAX_VALUE);
        chip.setAlignment(Pos.CENTER_LEFT);
        chip.setOnAction(e -> Ui.onFx(controller.listWallets(), wallets -> {
            ContextMenu menu = new ContextMenu();
            wallets.stream()
                    .filter(wallet -> wallet.seedId().equals(active.seedId()))
                    .sorted(Comparator.comparingInt(WalletUiController.WalletItem::accountIndex))
                    .forEach(wallet -> {
                        MenuItem item = new MenuItem(wallet.accountLabel());
                        boolean isActive = wallet.walletId().equals(active.walletId());
                        item.setDisable(isActive);
                        item.setOnAction(ev -> {
                            dispose();
                            controller.lock();
                            onSwitchAccount.accept(wallet);
                        });
                        menu.getItems().add(item);
                    });
            menu.show(chip, Side.BOTTOM, 0, 4);
        }, error -> Ui.toast(overlay, "Unable to list accounts: " + error.getMessage(), true)));
        return chip;
    }

    private void addNav(VBox side, String name, String iconPath) {
        Button button = new Button(name, Icons.icon(iconPath, 16, "nav-icon"));
        button.getStyleClass().add("nav-button");
        button.setMaxWidth(Double.MAX_VALUE);
        button.setAlignment(Pos.CENTER_LEFT);
        button.setGraphicTextGap(12);
        button.setOnAction(e -> navigate(name));
        navButtons.put(name, button);
        side.getChildren().add(button);
    }

    public void navigate(String name) {
        Screen screen = screens.computeIfAbsent(name, key -> registry.get(key).get());
        content.getChildren().setAll(screen.root());
        navButtons.forEach((key, button) -> button.getStyleClass().remove("nav-active"));
        Button activeButton = navButtons.get(name);
        if (activeButton != null && !activeButton.getStyleClass().contains("nav-active")) {
            activeButton.getStyleClass().add("nav-active");
        }
        active = name;
        screen.refresh();
        if (liveNeeded()) {
            pollLiveChain();
        }
    }

    /** One poller cycle: refresh the sync pill and let the active screen poll. */
    private void tick() {
        pollStatus();
        if (liveNeeded()) {
            pollLiveChain();
        }
        Screen screen = active != null ? screens.get(active) : null;
        if (screen != null) {
            screen.poll();
        }
    }

    private void pollStatus() {
        Ui.onFx(controller.nodeStatus(), this::updateSyncStatus,
                error -> {
                    resetProgress();
                    setPill("node offline", "sync-bad", "The wallet can't reach the node.");
                    showSyncBar(false);
                });
    }

    private void updateSyncStatus(WalletUiController.NodeStatusView status) {
        if (!status.reachable()) {
            resetProgress();
            setPill("node offline", "sync-bad", "The wallet can't reach the node.");
            showSyncBar(false);
            return;
        }
        long now = System.currentTimeMillis();
        if (status.blockNumber() > lastBlock) {
            lastBlock = status.blockNumber();
            lastAdvanceMs = now;
        }
        String tip = "slot " + status.slot() + " · block " + status.blockNumber()
                + (status.utxoIndexEnabled() ? " · index lag " + status.utxoLagBlocks() : " · index off");
        if (status.caughtUp()) {
            setPill("synced · block " + status.blockNumber(), "sync-ok", "Synced with the network.\n" + tip);
            showSyncBar(false);
        } else {
            // "Stalled" only fires when the block height hasn't moved for a while —
            // during real catch-up blocks arrive many-per-second, so this stays quiet.
            boolean stalled = lastAdvanceMs > 0 && (now - lastAdvanceMs) > STALL_MS;
            setPill("syncing · block " + status.blockNumber() + (stalled ? " (stalled?)" : ""),
                    stalled ? "sync-bad" : "sync-warn",
                    (stalled ? "No new blocks for a while — the node may be stuck; check the log in Settings → Advanced.\n"
                             : "Catching up with the network…\n") + tip);
            showSyncBar(true);
        }
    }

    private void resetProgress() {
        lastBlock = -1;
        lastAdvanceMs = 0;
    }

    private void showSyncBar(boolean visible) {
        syncBar.setVisible(visible);
        syncBar.setManaged(visible);
    }

    private void setPill(String text, String state, String tooltip) {
        syncPill.setText(text);
        syncPill.getStyleClass().removeAll("sync-ok", "sync-warn", "sync-bad");
        syncPill.getStyleClass().add(state);
        syncTooltip.setText(tooltip);
    }
}
