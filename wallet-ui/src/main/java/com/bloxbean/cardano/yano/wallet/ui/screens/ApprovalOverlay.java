package com.bloxbean.cardano.yano.wallet.ui.screens;

import com.bloxbean.cardano.yano.wallet.ui.contract.TxEffectView;
import com.bloxbean.cardano.yano.wallet.ui.contract.WalletUiController;
import com.bloxbean.cardano.yano.wallet.ui.util.Ui;
import javafx.animation.FadeTransition;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.util.Duration;

/**
 * The shared "review &amp; submit" step for every drafted transaction. Renders as a
 * modal card over a dimmed scrim in the screen's overlay {@link StackPane} — so it
 * can't be missed the way the old inline card could — confirms via
 * {@link WalletUiController#confirmDraft}, toasts the result, and removes itself.
 */
public final class ApprovalOverlay {
    private ApprovalOverlay() {
    }

    /**
     * @param onSubmitted runs on the FX thread after a successful submit (e.g. to
     *                    refresh the screen); may be {@code null}.
     */
    public static void show(StackPane overlay, WalletUiController controller,
                            WalletUiController.DraftView draft, Runnable onSubmitted) {
        StackPane scrim = new StackPane();
        scrim.getStyleClass().add("modal-scrim");
        scrim.setOnMouseClicked(javafx.event.Event::consume); // swallow background clicks

        Label title = new Label("Review");
        title.getStyleClass().add("card-title");
        Label summary = new Label(draft.kind() + " — " + draft.toSummary());
        summary.setWrapText(true);
        Label detail = Ui.muted(draft.amountAda());
        Label fee = Ui.muted("Network fee: ₳ " + draft.feeAda()
                + "   ·   Total ADA out: ₳ " + draft.totalAda());

        // ADR-042 SIM-M4: the same simulation that backs dApp prompts, so our own
        // transactions are held to the same standard. It starts as "checking"
        // and never gates the buttons — a slow node must not stop the user
        // submitting a transaction they built themselves.
        Label verification = Ui.muted("Checking this transaction against your node…");
        verification.setWrapText(true);

        Button confirm = new Button("Confirm & submit");
        confirm.getStyleClass().add("primary-button");
        Button cancel = new Button("Cancel");
        cancel.getStyleClass().add("ghost-button");

        VBox card = new VBox(12, title, summary, detail, fee, verification, Ui.row(10, confirm, cancel));
        card.getStyleClass().addAll("card", "approval-card", "modal-card");
        card.setMaxWidth(460);
        card.setMaxHeight(Region.USE_PREF_SIZE);
        StackPane.setAlignment(card, Pos.CENTER);
        scrim.getChildren().add(card);
        overlay.getChildren().add(scrim);

        FadeTransition in = new FadeTransition(Duration.millis(120), scrim);
        in.setFromValue(0);
        in.setToValue(1);
        in.play();

        Ui.onFx(controller.simulateDraft(draft.draftId()),
                effect -> describeVerification(verification, effect),
                error -> verification.setText("This transaction could not be checked against your node."));

        Runnable close = () -> overlay.getChildren().remove(scrim);
        cancel.setOnAction(e -> close.run());
        confirm.setOnAction(e -> {
            confirm.setDisable(true);
            cancel.setDisable(true);
            Ui.onFx(controller.confirmDraft(draft.draftId()), submit -> {
                close.run();
                Ui.toast(overlay, "Submitted " + Ui.middleEllipsis(submit.txHash(), 10), false);
                if (onSubmitted != null) {
                    onSubmitted.run();
                }
            }, error -> {
                confirm.setDisable(false);
                cancel.setDisable(false);
                Ui.toast(overlay, "Submit failed: " + error.getMessage(), true);
            });
        });
    }

    /**
     * Turns the simulation into one line the user can act on. Deliberately terse
     * — this is a transaction the user just built themselves, so the interesting
     * cases are the ones that contradict what they expected.
     */
    private static void describeVerification(Label label, TxEffectView effect) {
        if (effect == null) {
            label.setText("This transaction could not be checked against your node.");
            return;
        }
        if (effect.scriptOutcome() == TxEffectView.ScriptOutcome.FAILED) {
            label.setText("⚠  Your node says this transaction's scripts fail — submitting it would"
                    + " waste the fee and forfeit any collateral.");
            label.getStyleClass().add("approval-warning");
            return;
        }
        if (effect.completeness() != TxEffectView.Completeness.COMPLETE) {
            label.setText("⚠  Not fully verified. "
                    + (effect.limitation() == null ? "" : effect.limitation()));
            label.getStyleClass().add("approval-warning");
            return;
        }
        if (effect.scriptOutcome() == TxEffectView.ScriptOutcome.COULD_NOT_VERIFY) {
            // The amounts are sound, but the contract half is unknown — and that
            // is where the collateral is lost. Reporting only the amounts here
            // would read as a clean bill of health.
            label.setText("⚠  Amounts check out, but this transaction's smart-contract code could not"
                    + " be evaluated. "
                    + (effect.scriptMessage() == null ? "" : effect.scriptMessage()));
            label.getStyleClass().add("approval-warning");
            return;
        }
        StringBuilder verified = new StringBuilder("Checked against your node: ");
        verified.append(effect.netLovelace() < 0
                ? "₳ " + ada(-effect.netLovelace()) + " leaves your wallet"
                : "₳ " + ada(effect.netLovelace()) + " arrives");
        for (TxEffectView.AssetChange asset : effect.assetChanges()) {
            verified.append(asset.outgoing() ? ", −" : ", +")
                    .append(asset.quantity().startsWith("-") ? asset.quantity().substring(1) : asset.quantity())
                    .append(' ').append(asset.displayName());
        }
        verified.append('.');
        for (TxEffectView.RiskItem risk : effect.risks()) {
            if (risk.severity() != TxEffectView.Severity.INFO) {
                verified.append("  ⚠ ").append(risk.title()).append('.');
            }
        }
        label.setText(verified.toString());
    }

    private static String ada(long lovelace) {
        return new java.math.BigDecimal(java.math.BigInteger.valueOf(lovelace))
                .movePointLeft(6).toPlainString();
    }
}
