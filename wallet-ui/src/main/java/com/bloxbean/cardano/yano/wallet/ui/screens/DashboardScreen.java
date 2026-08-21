package com.bloxbean.cardano.yano.wallet.ui.screens;

import com.bloxbean.cardano.yano.wallet.ui.Shell;
import com.bloxbean.cardano.yano.wallet.ui.contract.WalletUiController;
import com.bloxbean.cardano.yano.wallet.ui.util.Ui;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

import java.util.List;
import java.util.function.Consumer;

/** Balance hero, quick actions, assets, and recent activity. */
public class DashboardScreen implements Shell.Screen {
    private final WalletUiController controller;
    private final StackPane overlay;
    private final Consumer<String> navigate;

    private final Label walletName = new Label();
    private final Label balanceAda = new Label("—");
    private final Label balanceDetail = new Label("");
    private final VBox assetsBox = new VBox(8);
    private final VBox activityBox = new VBox(8);
    private final ScrollPane root;

    public DashboardScreen(WalletUiController controller, StackPane overlay, Consumer<String> navigate) {
        this.controller = controller;
        this.overlay = overlay;
        this.navigate = navigate;
        this.root = build();
    }

    private ScrollPane build() {
        walletName.getStyleClass().add("screen-title");
        balanceAda.getStyleClass().add("balance-hero");
        balanceDetail.getStyleClass().add("muted");

        Button send = new Button("Send");
        send.getStyleClass().add("primary-button");
        send.setOnAction(e -> navigate.accept("Send"));
        Button receive = new Button("Receive");
        receive.getStyleClass().add("ghost-button");
        receive.setOnAction(e -> navigate.accept("Receive"));
        HBox actions = Ui.row(10, send, receive);

        VBox hero = new VBox(6, Ui.muted("Total balance"), balanceAda, balanceDetail, actions);
        hero.getStyleClass().addAll("card", "hero-card");
        hero.setSpacing(10);

        VBox assetsCard = Ui.card("Assets", assetsBox);
        VBox activityCard = Ui.card("Recent activity", activityBox);

        VBox column = new VBox(16, walletName, hero, assetsCard, activityCard);
        column.setPadding(new Insets(24));
        column.setMaxWidth(860);

        ScrollPane scroll = new ScrollPane(column);
        scroll.setFitToWidth(true);
        scroll.getStyleClass().add("screen-scroll");
        return scroll;
    }

    @Override
    public Node root() {
        return root;
    }

    @Override
    public void refresh() {
        load(false);
    }

    /**
     * Silent periodic refresh (shell status poller). Keeps last-good balance and
     * activity on a transient node error so, e.g., a pending tx flips to
     * confirmed on its own without the user re-navigating.
     */
    @Override
    public void poll() {
        load(true);
    }

    private void load(boolean silent) {
        WalletUiController.WalletItem wallet = controller.activeWallet();
        walletName.setText(wallet != null ? wallet.name() : "");

        Ui.onFx(controller.balance(), balance -> {
            balanceAda.setText("₳ " + balance.ada());
            balanceDetail.setText(balance.utxoCount() + " UTXOs · "
                    + balance.addressesScanned() + " addresses scanned");
            assetsBox.getChildren().clear();
            if (balance.assets().isEmpty()) {
                assetsBox.getChildren().add(Ui.muted("No native assets"));
            } else {
                balance.assets().forEach(asset -> {
                    Label unit = new Label(Ui.middleEllipsis(asset.unit(), 14));
                    unit.getStyleClass().add("mono");
                    Label qty = new Label(asset.quantity());
                    assetsBox.getChildren().add(Ui.row(10, unit, Ui.spacer(), qty));
                });
            }
        }, error -> {
            if (!silent) {
                Ui.toast(overlay, "Balance failed: " + error.getMessage(), true);
            }
        });

        Ui.onFx(controller.history(1, 5), history -> {
            activityBox.getChildren().clear();
            if (history.localOnly()) {
                // Said here as well as on History, because this card is where a
                // user checks whether a payment arrived — and a local-only list
                // cannot show one that did. Balance is unaffected either way.
                Label localChip = Ui.chip("Local history only", "chip-warn");
                Label explain = Ui.muted("sent from this wallet; received funds are not listed");
                activityBox.getChildren().add(Ui.row(8, localChip, explain));
            }
            var txs = history.items();
            if (txs.isEmpty()) {
                activityBox.getChildren().add(Ui.muted("No transactions yet"));
            } else {
                txs.forEach(tx -> activityBox.getChildren().add(txRow(tx)));
            }
        }, error -> {
            if (!silent) {
                activityBox.getChildren().setAll(Ui.muted("History unavailable: " + error.getMessage()));
            }
        });
    }

    private Node txRow(WalletUiController.TxItem tx) {
        Node hash = Ui.txHash(tx.txHash(), tx.explorerUrl(), 10);
        Label status = Ui.chip(tx.status(), statusClass(tx.status()));
        Label time = Ui.muted(tx.timeText());
        HBox row = Ui.row(12, hash, status, Ui.spacer(),
                tx.amountText() != null ? new Label(tx.amountText()) : new Label(""), time);
        row.getStyleClass().add("list-row");
        row.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(row, Priority.ALWAYS);
        return row;
    }

    static String statusClass(String status) {
        return switch (status == null ? "" : status.toLowerCase()) {
            case "confirmed", "in_block" -> "chip-ok";
            case "pending", "submitted" -> "chip-warn";
            case "failed", "expired", "rolled_back" -> "chip-bad";
            default -> "chip-neutral";
        };
    }
}
