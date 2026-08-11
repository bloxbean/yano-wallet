package com.bloxbean.cardano.yano.wallet.ui;

import com.bloxbean.cardano.yano.wallet.ui.contract.Cip30Prompt;
import com.bloxbean.cardano.yano.wallet.ui.contract.TxEffectView;
import javafx.application.Platform;
import javafx.event.Event;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextArea;
import javafx.scene.control.TitledPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * JavaFX modal for CIP-30 consent (ADR-035, ADR-042). Called from a bridge worker
 * thread, it marshals the dialog onto the FX thread and blocks the caller until
 * the user decides (or a timeout rejects).
 *
 * <p>The signing dialog shows what the transaction does to this wallet rather
 * than a free-text summary. Two rules govern the layout:
 *
 * <ul>
 *   <li>An unverified summary says so <em>first</em>, above any numbers. A number
 *       the wallet could not confirm must never be the most prominent thing on
 *       screen.</li>
 *   <li>Assets are never omitted. An unnamed token quietly leaving the wallet is
 *       precisely what an attacker wants invisible.</li>
 * </ul>
 */
public final class FxCip30Prompt implements Cip30Prompt {

    private final StackPane overlay;

    public FxCip30Prompt(StackPane overlay) {
        this.overlay = overlay;
    }

    @Override
    public boolean confirmConnect(String origin) {
        return ask(dialog -> {
            dialog.title("Connect dApp");
            dialog.body(origin + "\n\nwants to connect to your Yano wallet — it will be able to see your "
                    + "addresses, balance, and UTxOs. You approve each transaction separately.");
            dialog.okLabel("Connect");
        });
    }

    @Override
    public boolean confirmSignData(String origin, String address) {
        return ask(dialog -> {
            dialog.title("Approve signature");
            dialog.body(origin + "\n\nasks you to sign a message with the key for "
                    + shorten(address) + ".\n\nThis moves no funds and creates no transaction.");
            dialog.okLabel("Sign message");
        });
    }

    @Override
    public boolean confirmSign(String origin, TxEffectView effect) {
        return ask(dialog -> {
            dialog.title("Approve transaction");
            dialog.body(origin + " asks you to sign a transaction.");
            dialog.okLabel("Approve");
            dialog.effect(effect);
        });
    }

    // ---- rendering ---------------------------------------------------------

    /** Mutable builder handed to each caller so the three dialogs share one shell. */
    private static final class DialogSpec {
        private String title = "";
        private String body = "";
        private String okLabel = "Approve";
        private TxEffectView effect;

        void title(String value) {
            this.title = value;
        }

        void body(String value) {
            this.body = value;
        }

        void okLabel(String value) {
            this.okLabel = value;
        }

        void effect(TxEffectView value) {
            this.effect = value;
        }
    }

    private boolean ask(java.util.function.Consumer<DialogSpec> configure) {
        DialogSpec spec = new DialogSpec();
        configure.accept(spec);
        CompletableFuture<Boolean> result = new CompletableFuture<>();
        Platform.runLater(() -> show(spec, result));
        try {
            return result.get(5, TimeUnit.MINUTES);
        } catch (Exception e) {
            return false; // timeout / interruption → treat as rejection
        }
    }

    private void show(DialogSpec spec, CompletableFuture<Boolean> result) {
        StackPane scrim = new StackPane();
        scrim.getStyleClass().add("modal-scrim");
        scrim.setOnMouseClicked(Event::consume);

        Label titleLabel = new Label(spec.title);
        titleLabel.getStyleClass().add("card-title");
        Label bodyLabel = new Label(spec.body);
        bodyLabel.setWrapText(true);

        VBox content = new VBox(12, titleLabel, bodyLabel);
        if (spec.effect != null) {
            content.getChildren().addAll(effectNodes(spec.effect));
        }

        Button ok = new Button(spec.okLabel);
        ok.getStyleClass().add("primary-button");
        Button reject = new Button("Reject");
        reject.getStyleClass().add("ghost-button");
        HBox actions = new HBox(10, ok, reject);
        actions.setAlignment(Pos.CENTER_LEFT);
        content.getChildren().add(actions);

        ScrollPane scroller = new ScrollPane(content);
        scroller.setFitToWidth(true);
        scroller.setPrefViewportHeight(420);
        scroller.getStyleClass().add("modal-scroll");

        VBox card = new VBox(scroller);
        card.getStyleClass().addAll("card", "approval-card", "modal-card");
        card.setMaxWidth(520);
        card.setMaxHeight(Region.USE_PREF_SIZE);
        StackPane.setAlignment(card, Pos.CENTER);
        scrim.getChildren().add(card);
        overlay.getChildren().add(scrim);

        Runnable close = () -> overlay.getChildren().remove(scrim);
        ok.setOnAction(e -> {
            close.run();
            result.complete(true);
        });
        reject.setOnAction(e -> {
            close.run();
            result.complete(false);
        });
    }

    private static List<javafx.scene.Node> effectNodes(TxEffectView effect) {
        List<javafx.scene.Node> nodes = new ArrayList<>();

        // 1. Whether this was verified at all — before any number.
        if (effect.completeness() != TxEffectView.Completeness.COMPLETE) {
            Label warning = new Label(effect.completeness() == TxEffectView.Completeness.UNDECODABLE
                    ? "⚠  This transaction could not be read."
                    : "⚠  Not fully verified — the real effect may be larger than shown.");
            warning.getStyleClass().add("approval-warning");
            warning.setWrapText(true);
            nodes.add(warning);
            if (effect.limitation() != null && !effect.limitation().isBlank()) {
                Label why = new Label(effect.limitation());
                why.setWrapText(true);
                why.getStyleClass().add("muted");
                nodes.add(why);
            }
        }

        if (effect.completeness() == TxEffectView.Completeness.UNDECODABLE) {
            nodes.add(rawCborPane(effect));
            return nodes;
        }

        // 2. What leaves / arrives.
        Label ada = new Label(effect.netLovelace() < 0
                ? "Leaves your wallet:  ₳ " + ada(magnitude(effect.netLovelace()))
                : "Arrives in your wallet:  ₳ " + ada(BigInteger.valueOf(effect.netLovelace())));
        ada.getStyleClass().add("card-title");
        nodes.add(ada);
        nodes.add(muted("Network fee: ₳ " + ada(effect.feeLovelace()) + "  ·  included in the amount above"));

        for (TxEffectView.AssetChange asset : effect.assetChanges()) {
            Label line = new Label((asset.outgoing() ? "−  " : "+  ")
                    + stripSign(asset.quantity()) + "  " + asset.displayName());
            line.setWrapText(true);
            line.getStyleClass().add(asset.outgoing() ? "approval-warning" : "muted");
            nodes.add(line);
            nodes.add(muted("    policy " + asset.policyId()));
        }

        // 3. Everything else that happens.
        for (TxEffectView.AssetChange mint : effect.mint()) {
            nodes.add(muted((mint.outgoing() ? "Burns " : "Mints ")
                    + stripSign(mint.quantity()) + " " + mint.displayName()));
        }
        for (String certificate : effect.certificates()) {
            nodes.add(muted("Certificate: " + certificate));
        }
        for (TxEffectView.WithdrawalItem withdrawal : effect.withdrawals()) {
            nodes.add(muted("Withdraws ₳ " + ada(withdrawal.lovelace())
                    + (withdrawal.mine() ? " from your rewards" : " from another reward account")));
        }
        if (effect.collateralLovelace() > 0) {
            nodes.add(muted("Collateral at risk: ₳ " + ada(effect.collateralLovelace())
                    + " — taken only if the transaction's scripts fail"));
        }

        switch (effect.scriptOutcome()) {
            case FAILED -> {
                Label failed = new Label("⚠  This transaction's scripts fail. It will not succeed on-chain,"
                        + " and the fee and collateral would be lost.");
                failed.setWrapText(true);
                failed.getStyleClass().add("approval-warning");
                nodes.add(failed);
                if (effect.scriptMessage() != null && !effect.scriptMessage().isBlank()) {
                    nodes.add(muted(effect.scriptMessage()));
                }
            }
            case COULD_NOT_VERIFY -> {
                nodes.add(muted("Scripts could not be checked — this says nothing"
                        + " about whether they would pass."));
                if (effect.scriptMessage() != null && !effect.scriptMessage().isBlank()) {
                    nodes.add(muted(effect.scriptMessage()));
                }
            }
            case SUCCESS -> nodes.add(muted("Scripts checked: they succeed"
                    + (effect.redeemerCount() > 0
                    ? " (" + effect.redeemerCount() + " script"
                    + (effect.redeemerCount() == 1 ? "" : "s") + ", "
                    + effect.scriptMemory() + " memory / " + effect.scriptSteps() + " steps)"
                    : "") + "."));
            case NO_SCRIPTS -> {
                // Nothing to say; most transactions have no scripts.
            }
        }

        for (TxEffectView.RiskItem risk : effect.risks()) {
            Label item = new Label("•  " + risk.title() + " — " + risk.reason());
            item.setWrapText(true);
            item.getStyleClass().add(risk.severity() == TxEffectView.Severity.INFO
                    ? "muted" : "approval-warning");
            nodes.add(item);
        }

        nodes.add(rawCborPane(effect));
        return nodes;
    }

    private static TitledPane rawCborPane(TxEffectView effect) {
        TextArea raw = new TextArea(effect.rawCborHex());
        raw.setEditable(false);
        raw.setWrapText(true);
        raw.setPrefRowCount(6);
        TitledPane pane = new TitledPane("Raw transaction", raw);
        pane.setExpanded(false);   // summarising must never hide anything
        return pane;
    }

    private static Label muted(String text) {
        Label label = new Label(text);
        label.setWrapText(true);
        label.getStyleClass().add("muted");
        return label;
    }

    /** Quantities arrive as signed decimal strings; the sign is shown by the prefix. */
    private static String stripSign(String quantity) {
        return quantity != null && quantity.startsWith("-") ? quantity.substring(1) : quantity;
    }

    private static String ada(long lovelace) {
        return ada(BigInteger.valueOf(lovelace));
    }

    private static String ada(BigInteger lovelace) {
        return new BigDecimal(lovelace).movePointLeft(6).toPlainString();
    }

    /**
     * Absolute value via BigInteger: a saturated {@code Long.MIN_VALUE} negates
     * to itself, which would print a stray minus sign in front of the amount the
     * user is being asked to approve.
     */
    private static BigInteger magnitude(long lovelace) {
        return BigInteger.valueOf(lovelace).abs();
    }

    private static String shorten(String hex) {
        return hex == null ? "" : (hex.length() > 16 ? hex.substring(0, 16) + "…" : hex);
    }
}
