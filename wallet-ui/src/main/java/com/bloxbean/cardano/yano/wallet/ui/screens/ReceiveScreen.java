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
import javafx.scene.control.TextField;
import javafx.scene.control.TitledPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

/** Receive addresses with one-click copy. */
public class ReceiveScreen implements Shell.Screen {
    private static final int ADDRESS_COUNT = 5;

    private final WalletUiController controller;
    private final StackPane overlay;
    private final Label primaryAddress = new Label();
    private final VBox addressList = new VBox(8);
    private final VBox detailsCard = new VBox(14);
    private String walletId;
    private int detailsRequest;
    private int selectedIndex = -1;
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
        Button details = new Button("Address details");
        details.getStyleClass().add("ghost-button-small");
        details.setOnAction(e -> showDetails(0));
        VBox primaryCard = Ui.card("Your address", primaryAddress, Ui.row(12, copy, details));

        detailsCard.getStyleClass().addAll("card", "address-details-card");
        detailsCard.setVisible(false);
        detailsCard.setManaged(false);

        VBox listCard = Ui.card("Receive addresses (CIP-1852)", addressList);

        VBox column = new VBox(16, title, primaryCard, detailsCard, listCard);
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
        String currentId = wallet == null ? null : wallet.walletId();
        if (!java.util.Objects.equals(walletId, currentId)) {
            walletId = currentId;
            hideDetails();
        }
        primaryAddress.setText(wallet == null ? "" : wallet.baseAddress());
        Ui.onFx(controller.addresses(ADDRESS_COUNT), addresses -> {
            if (!java.util.Objects.equals(walletId, currentId)) return;
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
                Button details = new Button("Details");
                details.getStyleClass().add("ghost-button-small");
                details.setAccessibleText("Public key details for address " + address.index());
                details.setOnAction(e -> showDetails(address.index()));
                value.setMinWidth(0);
                addressList.getChildren().add(Ui.row(12, index, value, Ui.spacer(), details, copy));
                addressList.getChildren().add(path);
            });
        }, error -> Ui.toast(overlay, "Addresses failed: " + error.getMessage(), true));
    }

    private void hideDetails() {
        detailsRequest++;
        selectedIndex = -1;
        detailsCard.getChildren().clear();
        detailsCard.setVisible(false);
        detailsCard.setManaged(false);
    }

    private void showDetails(int index) {
        if (selectedIndex == index && detailsCard.isVisible()) {
            hideDetails();
            return;
        }
        selectedIndex = index;
        int request = ++detailsRequest;
        Label heading = new Label("Address #" + index + " · Public keys");
        heading.getStyleClass().add("card-title");
        Button close = new Button("Close details");
        close.getStyleClass().add("ghost-button-small");
        close.setOnAction(e -> hideDetails());
        HBox header = Ui.row(12, heading, Ui.spacer(), close);
        detailsCard.getChildren().setAll(header, Ui.muted("Loading public keys…"));
        detailsCard.setVisible(true);
        detailsCard.setManaged(true);
        root.setVvalue(0);
        Ui.onFx(controller.addressDetails(index), details -> {
            if (!isCurrent(request)) return;
            Label address = new Label(details.address());
            address.getStyleClass().addAll("mono", "muted");
            address.setWrapText(true);
            Label explanation = Ui.muted("Public keys verify signatures; they cannot spend funds. Sharing them may link your activity.");
            explanation.setWrapText(true);
            VBox payment = keySection("Payment", details.paymentPath(), details.paymentPublicKey(), details.paymentKeyHash());
            TitledPane stake = new TitledPane("Stake key details", keySection("Stake", details.stakePath(),
                    details.stakePublicKey(), details.stakeKeyHash()));
            stake.setExpanded(false);
            stake.setAnimated(false);
            stake.getStyleClass().add("address-stake-details");
            detailsCard.getChildren().setAll(header, address, explanation, payment, stake);
        }, error -> {
            if (!isCurrent(request)) return;
            Label message = Ui.muted("Public keys could not be loaded. Unlock this wallet and try again.");
            message.setWrapText(true);
            Button retry = new Button("Try again");
            retry.getStyleClass().add("ghost-button-small");
            retry.setOnAction(e -> { selectedIndex = -1; showDetails(index); });
            detailsCard.getChildren().setAll(header, message, retry);
        });
    }

    private boolean isCurrent(int request) {
        var active = controller.activeWallet();
        return request == detailsRequest && active != null && java.util.Objects.equals(walletId, active.walletId());
    }

    private VBox keySection(String name, String path, String publicKey, String hash) {
        Label title = new Label(name + " verification key");
        title.getStyleClass().add("card-title");
        Label derivation = Ui.muted(path);
        derivation.getStyleClass().add("mono");
        return new VBox(8, title, derivation,
                copyableKey(name + " public key", "Public key · 32 bytes · hex", publicKey),
                copyableKey(name + " key hash", "Key hash · 28 bytes · hex", hash));
    }

    private VBox copyableKey(String name, String caption, String value) {
        TextField field = new TextField(value);
        field.setEditable(false);
        field.getStyleClass().add("mono");
        field.setAccessibleText(name + " in hexadecimal");
        HBox.setHgrow(field, Priority.ALWAYS);
        Button copy = new Button("Copy", Icons.icon(Icons.COPY, 12, "nav-icon"));
        copy.getStyleClass().add("ghost-button-small");
        copy.setMinWidth(Button.USE_PREF_SIZE);
        copy.setAccessibleText("Copy " + name.toLowerCase());
        copy.setOnAction(e -> {
            Ui.copyToClipboard(value);
            Ui.toast(overlay, name + " copied", false);
        });
        return new VBox(4, Ui.muted(caption), Ui.row(10, field, copy));
    }
}
