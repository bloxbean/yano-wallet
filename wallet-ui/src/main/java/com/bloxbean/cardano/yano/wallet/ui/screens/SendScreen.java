package com.bloxbean.cardano.yano.wallet.ui.screens;

import com.bloxbean.cardano.yano.wallet.ui.Shell;
import com.bloxbean.cardano.yano.wallet.ui.contract.WalletUiController;
import com.bloxbean.cardano.yano.wallet.ui.util.Ui;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Send flow: form → signed draft with fee preview → explicit approval →
 * submit. The draft/approve split is the same contract the CIP-30 bridge
 * will reuse (every signature passes an approval step).
 */
public class SendScreen implements Shell.Screen {
    private final WalletUiController controller;
    private final StackPane overlay;

    /** An option in the asset picker: ADA or a native asset with its balance. */
    private record AssetOption(String unit, String label, String available) {
        @Override
        public String toString() {
            return available == null ? label : label + "  ·  " + available;
        }
    }

    private final TextField toField = new TextField();
    private final ComboBox<AssetOption> assetPicker = new ComboBox<>();
    private final TextField amountField = new TextField();
    private final TextField memoField = new TextField();
    private final Button reviewButton = new Button("Review & sign");
    private final ScrollPane root;

    public SendScreen(WalletUiController controller, StackPane overlay) {
        this.controller = controller;
        this.overlay = overlay;
        this.root = build();
    }

    private ScrollPane build() {
        Label title = new Label("Send");
        title.getStyleClass().add("screen-title");

        toField.setPromptText("Receiver address (addr…)");
        amountField.setPromptText("Amount");
        memoField.setPromptText("Optional message (CIP-20, stored on-chain)");
        assetPicker.setMaxWidth(Double.MAX_VALUE);
        assetPicker.setOnAction(e -> {
            AssetOption selected = assetPicker.getValue();
            boolean ada = selected == null || "lovelace".equals(selected.unit());
            amountField.setPromptText(ada ? "Amount in ADA, e.g. 12.5" : "Quantity (whole number)");
        });

        GridPane form = new GridPane();
        form.setHgap(12);
        form.setVgap(12);
        form.add(Ui.muted("To"), 0, 0);
        form.add(toField, 1, 0);
        form.add(Ui.muted("Asset"), 0, 1);
        form.add(assetPicker, 1, 1);
        form.add(Ui.muted("Amount"), 0, 2);
        form.add(amountField, 1, 2);
        form.add(Ui.muted("Memo"), 0, 3);
        form.add(memoField, 1, 3);
        GridPane.setHgrow(toField, javafx.scene.layout.Priority.ALWAYS);
        GridPane.setHgrow(assetPicker, javafx.scene.layout.Priority.ALWAYS);

        reviewButton.getStyleClass().add("primary-button");
        reviewButton.setOnAction(e -> review());

        VBox formCard = Ui.card(null, form, reviewButton);
        VBox column = new VBox(16, title, formCard);
        column.setPadding(new Insets(24));
        column.setMaxWidth(860);

        ScrollPane scroll = new ScrollPane(column);
        scroll.setFitToWidth(true);
        scroll.getStyleClass().add("screen-scroll");
        return scroll;
    }

    private void review() {
        String to = toField.getText() == null ? "" : toField.getText().trim();
        String amount = amountField.getText() == null ? "" : amountField.getText().trim();
        if (to.isEmpty() || amount.isEmpty()) {
            Ui.toast(overlay, "Receiver address and amount are required", true);
            return;
        }
        AssetOption selected = assetPicker.getValue();
        String unit = selected == null ? "lovelace" : selected.unit();
        reviewButton.setDisable(true);
        Ui.onFx(controller.draftSend(to, unit, amount, memoField.getText()), draft -> {
            reviewButton.setDisable(false);
            ApprovalOverlay.show(overlay, controller, draft, () -> {
                toField.clear();
                amountField.clear();
                memoField.clear();
            });
        }, error -> {
            reviewButton.setDisable(false);
            Ui.toast(overlay, "Draft failed: " + error.getMessage(), true);
        });
    }

    @Override
    public Node root() {
        return root;
    }

    @Override
    public void refresh() {
        // Populate the asset picker from the wallet's current balance.
        Ui.onFx(controller.balance(), balance -> {
            AssetOption previous = assetPicker.getValue();
            List<AssetOption> options = new ArrayList<>();
            options.add(new AssetOption("lovelace", "ADA", "₳ " + balance.ada()));
            for (WalletUiController.AssetItem asset : balance.assets()) {
                options.add(new AssetOption(asset.unit(), assetLabel(asset.unit()), asset.quantity()));
            }
            assetPicker.setItems(FXCollections.observableArrayList(options));
            assetPicker.setValue(previous != null
                    ? options.stream().filter(o -> o.unit().equals(previous.unit())).findFirst().orElse(options.get(0))
                    : options.get(0));
        }, error -> {
            assetPicker.setItems(FXCollections.observableArrayList(
                    new AssetOption("lovelace", "ADA", null)));
            assetPicker.getSelectionModel().selectFirst();
        });
    }

    /** policyId+assetName hex → decoded asset name when printable, else a short unit. */
    private static String assetLabel(String unit) {
        if (unit.length() > 56) {
            String nameHex = unit.substring(56);
            try {
                byte[] bytes = new byte[nameHex.length() / 2];
                for (int i = 0; i < bytes.length; i++) {
                    bytes[i] = (byte) Integer.parseInt(nameHex.substring(i * 2, i * 2 + 2), 16);
                }
                String name = new String(bytes, StandardCharsets.UTF_8);
                if (!name.isEmpty() && name.chars().allMatch(c -> c >= 0x20 && c < 0x7f)) {
                    return name;
                }
            } catch (RuntimeException ignored) {
                // fall through
            }
        }
        return unit.length() > 14 ? unit.substring(0, 14) + "…" : unit;
    }
}

