package com.bloxbean.cardano.yano.wallet.ui.screens;

import com.bloxbean.cardano.yano.wallet.ui.Shell;
import com.bloxbean.cardano.yano.wallet.ui.contract.WalletUiController;
import com.bloxbean.cardano.yano.wallet.ui.util.Ui;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

/**
 * Full transaction history — from the node's address-tx index (ADR-033 M2), or
 * from the wallet's own record of what it sent when the node has no such index
 * (ADR-043), in which case the screen says so.
 */
public class HistoryScreen implements Shell.Screen {
    private static final int PAGE_SIZE = 25;

    private static final String NODE_NOTE =
            "Transactions you submit appear as pending until your node's history confirms them. "
                    + "One that is still unseen after 5 minutes is marked failed and sorted into the "
                    + "list by the time it was sent — it was submitted but never reached the chain, "
                    + "which also happens when a devnet is reset.";
    private static final String LOCAL_NOTE =
            "Transactions you submit appear as pending until they are seen in a block. One that is "
                    + "still unseen after 5 minutes is marked failed and sorted into the list by the "
                    + "time it was sent — it was submitted but never reached the chain, which also "
                    + "happens when a devnet is reset.";

    private final WalletUiController controller;
    private final StackPane overlay;
    private final VBox listBox = new VBox(8);
    private final Button moreButton = new Button("Load more");
    private final Label localChip = Ui.chip("Local history only", "chip-warn");
    private final Label note = Ui.muted(NODE_NOTE);
    private final Label localNote = Ui.muted(
            "This node keeps no transaction index, so this list is the wallet's own record of what "
                    + "it sent — including transactions a connected dApp submitted through it. Funds "
                    + "received from elsewhere are counted in your balance but do not appear here.");
    private final ScrollPane root;
    private int page = 1;

    public HistoryScreen(WalletUiController controller, StackPane overlay) {
        this.controller = controller;
        this.overlay = overlay;
        this.root = build();
    }

    private ScrollPane build() {
        Label title = new Label("History");
        title.getStyleClass().add("screen-title");

        // Hidden until a page actually comes back local-only, so the ordinary
        // case shows nothing extra and the label never claims a limitation the
        // connected node does not have.
        localNote.setWrapText(true);
        showLocalOnly(false);

        moreButton.getStyleClass().add("ghost-button");
        moreButton.setOnAction(e -> loadPage(page + 1, false));

        VBox card = Ui.card(null, listBox, moreButton);

        // Explains the two rows that are NOT confirmed chain history, so neither
        // needs to be puzzled over: what "pending" means, and why something the
        // user submitted can end up marked failed. The wording follows the mode —
        // in local mode there is no "node's history" for it to point at.
        note.setWrapText(true);

        VBox column = new VBox(16, Ui.row(12, title, localChip), localNote, card, note);
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
        loadPage(1, true);
    }

    private void loadPage(int newPage, boolean reset) {
        Ui.onFx(controller.history(newPage, PAGE_SIZE), history -> {
            if (reset) {
                listBox.getChildren().clear();
            }
            page = newPage;
            showLocalOnly(history.localOnly());
            var txs = history.items();
            if (txs.isEmpty() && reset) {
                listBox.getChildren().add(Ui.muted("No transactions yet"));
            }
            txs.forEach(tx -> listBox.getChildren().add(txRow(tx)));
            // Page 1 may include a few local pending rows on top of the node
            // page, so use >= (the node returned a full page → there may be more).
            boolean maybeMore = txs.size() >= PAGE_SIZE;
            moreButton.setVisible(maybeMore);
            moreButton.setManaged(maybeMore);
        }, error -> {
            if (reset) {
                listBox.getChildren().setAll(Ui.muted("History unavailable: " + error.getMessage()));
                moreButton.setVisible(false);
                moreButton.setManaged(false);
            } else {
                Ui.toast(overlay, "History failed: " + error.getMessage(), true);
            }
        });
    }

    private void showLocalOnly(boolean localOnly) {
        note.setText(localOnly ? LOCAL_NOTE : NODE_NOTE);
        localChip.setVisible(localOnly);
        localChip.setManaged(localOnly);
        localNote.setVisible(localOnly);
        localNote.setManaged(localOnly);
    }

    private Node txRow(WalletUiController.TxItem tx) {
        Node hash = Ui.txHash(tx.txHash(), tx.explorerUrl(), 12);
        Label status = Ui.chip(tx.status(), DashboardScreen.statusClass(tx.status()));
        Label block = Ui.muted(tx.blockHeight() > 0 ? "block " + tx.blockHeight() : "");
        Label time = Ui.muted(tx.timeText());
        Button copy = new Button("Copy");
        copy.getStyleClass().add("ghost-button-small");
        copy.setOnAction(e -> {
            Ui.copyToClipboard(tx.txHash());
            Ui.toast(overlay, "Tx hash copied", false);
        });
        var row = Ui.row(12, hash, status, block, Ui.spacer(),
                tx.amountText() != null ? new Label(tx.amountText()) : new Label(""), time, copy);
        row.getStyleClass().add("list-row");
        return row;
    }
}
