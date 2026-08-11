package com.bloxbean.cardano.yano.wallet.ui.screens;

import com.bloxbean.cardano.yano.wallet.ui.Shell;
import com.bloxbean.cardano.yano.wallet.ui.contract.WalletUiController;
import com.bloxbean.cardano.yano.wallet.ui.util.Ui;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

/**
 * CIP-1694 governance identity + delegation: shows whether this wallet is a
 * registered DRep and where it delegates its voting power, lets you delegate
 * voting power to a DRep, and register / unregister as a DRep. Voting on
 * proposals lives on the separate {@link ProposalsScreen}.
 */
public class GovernanceScreen implements Shell.Screen {
    private final WalletUiController controller;
    private final StackPane overlay;

    private final Label drepStatus = new Label();
    private final Label voteDelegation = new Label();
    private final Button unregisterButton = new Button("Unregister DRep (reclaim deposit)");

    private final TextField drepField = new TextField();
    private final Button voteDelegateButton = new Button("Delegate voting power");
    private final Button abstainButton = new Button("Always abstain");
    private final Button noConfidenceButton = new Button("No confidence");

    private final TextField regAnchorUrl = new TextField();
    private final TextField regAnchorHash = new TextField();
    private final Button registerButton = new Button("Register as DRep (₳500 deposit)");

    private final ScrollPane root;

    public GovernanceScreen(WalletUiController controller, StackPane overlay) {
        this.controller = controller;
        this.overlay = overlay;
        this.root = build();
    }

    private ScrollPane build() {
        Label title = new Label("Governance");
        title.getStyleClass().add("screen-title");

        // Current status.
        drepStatus.setWrapText(true);
        voteDelegation.getStyleClass().add("muted");
        unregisterButton.getStyleClass().add("ghost-button");
        unregisterButton.setOnAction(e -> unregister());
        unregisterButton.setVisible(false);
        unregisterButton.setManaged(false);
        VBox statusCard = Ui.card("Your DRep status", drepStatus, voteDelegation, unregisterButton);

        // Vote delegation.
        drepField.setPromptText("DRep id (drep1…)");
        voteDelegateButton.getStyleClass().add("primary-button");
        voteDelegateButton.setOnAction(e -> delegateVote(
                drepField.getText() == null ? "" : drepField.getText().trim()));
        abstainButton.getStyleClass().add("ghost-button");
        abstainButton.setOnAction(e -> delegateVote(WalletUiController.VOTE_ABSTAIN));
        noConfidenceButton.getStyleClass().add("ghost-button");
        noConfidenceButton.setOnAction(e -> delegateVote(WalletUiController.VOTE_NO_CONFIDENCE));
        VBox delegationCard = Ui.card("Vote delegation",
                Ui.muted("Delegate your voting power to a DRep, or pick a standing option."),
                Ui.row(10, drepField, voteDelegateButton),
                Ui.row(10, abstainButton, noConfidenceButton));

        // DRep registration.
        regAnchorUrl.setPromptText("Rationale URL (optional, CIP-100)");
        regAnchorHash.setPromptText("Rationale hash (hex, required with URL)");
        registerButton.getStyleClass().add("primary-button");
        registerButton.setOnAction(e -> registerDRep());
        VBox drepCard = Ui.card("Become a DRep",
                Ui.muted("Register your account as a DRep so you can vote on governance actions. "
                        + "Locks a refundable ₳500 deposit."),
                regAnchorUrl, regAnchorHash, registerButton);

        VBox column = new VBox(16, title, statusCard, delegationCard, drepCard);
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
        drepStatus.setText("Loading status…");
        voteDelegation.setText("");
        Ui.onFx(controller.governanceStatus(), status -> {
            drepStatus.setText(status.drepStatusText());
            voteDelegation.setText("Voting power: " + status.voteDelegationText());
            unregisterButton.setVisible(status.drepRegistered());
            unregisterButton.setManaged(status.drepRegistered());
        }, error -> {
            drepStatus.setText("Status unavailable: " + error.getMessage());
            voteDelegation.setText("");
            unregisterButton.setVisible(false);
            unregisterButton.setManaged(false);
        });
    }

    private void delegateVote(String target) {
        setBusy(true);
        Ui.onFx(controller.draftVoteDelegation(target), this::review, error -> {
            setBusy(false);
            Ui.toast(overlay, "Vote delegation draft failed: " + error.getMessage(), true);
        });
    }

    private void registerDRep() {
        setBusy(true);
        String url = regAnchorUrl.getText() == null ? "" : regAnchorUrl.getText().trim();
        String hash = regAnchorHash.getText() == null ? "" : regAnchorHash.getText().trim();
        Ui.onFx(controller.draftDRepRegistration(url, hash), this::review, error -> {
            setBusy(false);
            Ui.toast(overlay, "DRep registration draft failed: " + error.getMessage(), true);
        });
    }

    private void unregister() {
        unregisterButton.setDisable(true);
        Ui.onFx(controller.draftDRepDeregistration(), this::review, error -> {
            unregisterButton.setDisable(false);
            Ui.toast(overlay, "Unregister draft failed: " + error.getMessage(), true);
        });
    }

    private void review(WalletUiController.DraftView draft) {
        setBusy(false);
        unregisterButton.setDisable(false);
        ApprovalOverlay.show(overlay, controller, draft, this::refresh);
    }

    private void setBusy(boolean busy) {
        voteDelegateButton.setDisable(busy);
        abstainButton.setDisable(busy);
        noConfidenceButton.setDisable(busy);
        registerButton.setDisable(busy);
    }
}
