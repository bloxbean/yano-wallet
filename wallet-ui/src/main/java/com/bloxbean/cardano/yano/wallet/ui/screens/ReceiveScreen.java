package com.bloxbean.cardano.yano.wallet.ui.screens;

import com.bloxbean.cardano.yano.wallet.ui.Shell;
import com.bloxbean.cardano.yano.wallet.ui.contract.WalletUiController;
import com.bloxbean.cardano.yano.wallet.ui.util.Icons;
import com.bloxbean.cardano.yano.wallet.ui.util.Ui;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

/** Receive addresses with one-click copy. */
public class ReceiveScreen implements Shell.Screen {
    private static final int ADDRESS_COUNT = 5;

    private final WalletUiController controller;
    private final StackPane overlay;
    private final Label primaryAddress = new Label();
    private final VBox addressList = new VBox(8);
    private final ScrollPane root;

    public ReceiveScreen(WalletUiController controller, StackPane overlay) {
        this.controller = controller;
        this.overlay = overlay;
        this.root = build();
    }

    private ScrollPane build() {
        Label title = new Label("Receive");
        title.getStyleClass().add("screen-title");

        primaryAddress.getStyleClass().addAll("mono", "primary-address");
        primaryAddress.setWrapText(true);
        Button copy = new Button("Copy address", Icons.icon(Icons.COPY, 14, "nav-icon"));
        copy.getStyleClass().add("primary-button");
        copy.setOnAction(e -> {
            Ui.copyToClipboard(primaryAddress.getText());
            Ui.toast(overlay, "Address copied", false);
        });
        VBox primaryCard = Ui.card("Your address", primaryAddress, copy);

        VBox listCard = Ui.card("Receive addresses (CIP-1852)", addressList);

        VBox column = new VBox(16, title, primaryCard, listCard);
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
        WalletUiController.WalletItem wallet = controller.activeWallet();
        if (wallet != null) {
            primaryAddress.setText(wallet.baseAddress());
        }
        Ui.onFx(controller.addresses(ADDRESS_COUNT), addresses -> {
            addressList.getChildren().clear();
            addresses.forEach(address -> {
                Label index = Ui.chip("#" + address.index(), "chip-neutral");
                Label path = Ui.muted(address.derivationPath());
                Label value = new Label(Ui.middleEllipsis(address.address(), 20));
                value.getStyleClass().add("mono");
                Button copy = new Button("Copy");
                copy.getStyleClass().add("ghost-button-small");
                copy.setOnAction(e -> {
                    Ui.copyToClipboard(address.address());
                    Ui.toast(overlay, "Address #" + address.index() + " copied", false);
                });
                addressList.getChildren().add(Ui.row(12, index, value, path, Ui.spacer(), copy));
            });
        }, error -> Ui.toast(overlay, "Addresses failed: " + error.getMessage(), true));
    }
}
