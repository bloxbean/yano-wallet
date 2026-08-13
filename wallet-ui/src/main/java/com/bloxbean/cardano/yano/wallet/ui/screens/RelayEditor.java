package com.bloxbean.cardano.yano.wallet.ui.screens;

import com.bloxbean.cardano.yano.wallet.ui.contract.WalletUiController;
import com.bloxbean.cardano.yano.wallet.ui.util.Ui;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.layout.VBox;

import java.util.Arrays;
import java.util.List;

/**
 * Editor for a network's upstream relays (E18), shared by Settings and the
 * connect screen.
 *
 * <p>It appears in both places for a reason. The wallet ships two relays per
 * public network and the node fails over between them, so this is not needed in
 * normal use — it is the recovery path for relay hostnames that stop resolving
 * years after a release, and for a relay that stays alive while delivering almost
 * nothing (the node's failover reacts to liveness, not throughput). Both of those
 * can leave a wallet unable to sync, and a user in that state must be able to fix
 * it <em>before</em> committing to a long, doomed start — not only from inside a
 * wallet they may be waiting to reach.
 */
final class RelayEditor {

    private RelayEditor() {
    }

    /**
     * @param onSaved notified after a successful save, so a host screen can react
     *                (re-render a hint, say); may be null
     */
    static Node build(WalletUiController controller, String networkId, Runnable onSaved) {
        WalletUiController.UpstreamRelaysView view = controller.upstreamRelays(networkId);

        // Whether this network has relays at all is a different question from
        // whether they apply right now. A Yaci DevKit and a Yano devnet have none
        // — there is nothing to edit and the reason is the whole content. But a
        // public network reached through someone else's node still has relays
        // worth setting: they apply the next time the wallet launches a node.
        // Replacing the editor there would hide a setting that does work.
        if (view.shipped().isEmpty()) {
            Label why = Ui.muted(view.unavailableReason());
            why.setWrapText(true);
            return new VBox(8, why);
        }

        VBox box = new VBox(8);
        if (!view.editable()) {
            Label note = Ui.muted(view.unavailableReason()
                    + " These relays apply when the wallet next starts a node itself.");
            note.setWrapText(true);
            box.getChildren().add(note);
        }

        Label current = Ui.muted("Currently syncing from: " + String.join(", ", view.configured()));
        current.setWrapText(true);

        TextArea editor = new TextArea(String.join("\n", view.custom()));
        editor.setPromptText("one host:port per line, e.g. relay.example.com:3001");
        editor.setPrefRowCount(3);
        editor.getStyleClass().add("mono");

        CheckBox onlyCustom = new CheckBox("Use only these relays (do not fall back to the built-in ones)");
        onlyCustom.setSelected(view.onlyCustom());
        onlyCustom.setWrapText(true);

        Label status = new Label();
        status.setWrapText(true);
        status.getStyleClass().add("muted");

        Button save = new Button("Save relays");
        save.getStyleClass().add("primary-button");
        Button reset = new Button("Use built-in relays");
        reset.getStyleClass().add("ghost-button");

        Runnable refresh = () -> {
            WalletUiController.UpstreamRelaysView latest = controller.upstreamRelays(networkId);
            editor.setText(String.join("\n", latest.custom()));
            onlyCustom.setSelected(latest.onlyCustom());
            current.setText("Currently syncing from: " + String.join(", ", latest.configured()));
        };

        save.setOnAction(e -> {
            List<String> entries = Arrays.stream(editor.getText().split("\\R"))
                    .map(String::strip)
                    .filter(line -> !line.isEmpty())
                    .toList();
            try {
                String message = controller.saveUpstreamRelays(networkId, entries, onlyCustom.isSelected());
                refresh.run();
                status.getStyleClass().setAll("muted");
                status.setText(message);
                if (onSaved != null) {
                    onSaved.run();
                }
            } catch (RuntimeException ex) {
                // The whole save is rejected rather than the good lines kept: a
                // partially-applied list is one the user never approved, and the
                // box would redisplay as though it had been accepted.
                status.getStyleClass().setAll("approval-warning");
                status.setText("Not saved — " + ex.getMessage());
            }
        });

        reset.setOnAction(e -> {
            String message = controller.saveUpstreamRelays(networkId, List.of(), false);
            refresh.run();
            status.getStyleClass().setAll("muted");
            status.setText(message);
            if (onSaved != null) {
                onSaved.run();
            }
        });

        box.getChildren().addAll(
                current,
                Ui.muted("Built-in: " + String.join(", ", view.shipped())),
                Ui.muted("Leave this empty to use the built-in relays. Entries you add are tried "
                        + "first, with the built-in ones behind them unless you tick the box below."),
                editor,
                onlyCustom,
                Ui.row(8, save, reset),
                Ui.muted("Takes effect the next time the node starts — relays are set when the "
                        + "node process is launched."),
                status);
        return box;
    }
}
