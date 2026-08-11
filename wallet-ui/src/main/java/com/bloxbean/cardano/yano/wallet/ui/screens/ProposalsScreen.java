package com.bloxbean.cardano.yano.wallet.ui.screens;

import com.bloxbean.cardano.yano.wallet.ui.Shell;
import com.bloxbean.cardano.yano.wallet.ui.contract.WalletUiController;
import com.bloxbean.cardano.yano.wallet.ui.contract.WalletUiController.ProposalView;
import com.bloxbean.cardano.yano.wallet.ui.util.Ui;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

/**
 * Active governance actions (CIP-1694) fetched from the node, with Yes/No/Abstain
 * voting. A banner reflects whether this wallet is a registered DRep — votes only
 * count if it is. Kept separate from {@link GovernanceScreen} because proposal
 * lists can be long.
 */
public class ProposalsScreen implements Shell.Screen {
    private final WalletUiController controller;
    private final StackPane overlay;

    private final Label banner = new Label();
    private final Button refreshButton = new Button("Refresh");
    private final VBox proposalsBox = new VBox(10);
    private final ScrollPane root;

    public ProposalsScreen(WalletUiController controller, StackPane overlay) {
        this.controller = controller;
        this.overlay = overlay;
        this.root = build();
    }

    private ScrollPane build() {
        Label title = new Label("Proposals");
        title.getStyleClass().add("screen-title");

        banner.setWrapText(true);
        banner.getStyleClass().add("muted");
        refreshButton.getStyleClass().add("ghost-button");
        refreshButton.setOnAction(e -> loadProposals());

        VBox headerCard = Ui.card("Governance actions",
                Ui.row(10, banner, Ui.spacer(), refreshButton));

        VBox column = new VBox(16, title, headerCard, proposalsBox);
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
        banner.setText("");
        Ui.onFx(controller.governanceStatus(), status -> banner.setText(status.drepRegistered()
                ? "You're a registered DRep — your votes count."
                : "Register as a DRep (Governance tab) so your votes count."),
                error -> banner.setText("DRep status unavailable: " + error.getMessage()));
        loadProposals();
    }

    private void loadProposals() {
        refreshButton.setDisable(true);
        proposalsBox.getChildren().setAll(Ui.muted("Loading proposals…"));
        Ui.onFx(controller.listProposals(), proposals -> {
            refreshButton.setDisable(false);
            proposalsBox.getChildren().clear();
            if (proposals.isEmpty()) {
                proposalsBox.getChildren().add(Ui.muted("No active governance actions right now."));
                return;
            }
            proposals.forEach(p -> proposalsBox.getChildren().add(proposalRow(p)));
        }, error -> {
            refreshButton.setDisable(false);
            proposalsBox.getChildren().setAll(Ui.muted("Couldn't load proposals: " + error.getMessage()));
        });
    }

    private Node proposalRow(ProposalView p) {
        Label type = new Label(p.type() != null ? p.type() : "governance action");
        type.getStyleClass().add("card-title");
        String id = p.id() != null ? p.id() : Ui.middleEllipsis(p.txHash(), 12) + "#" + p.certIndex();
        Label meta = Ui.muted(id + "   ·   expires epoch " + p.expiresAfterEpoch());
        meta.setWrapText(true);

        Button yes = voteButton("Yes", WalletUiController.VOTE_YES, p);
        Button no = voteButton("No", WalletUiController.VOTE_NO, p);
        Button abstain = voteButton("Abstain", WalletUiController.VOTE_ABSTAIN_VOTE, p);
        yes.getStyleClass().add("primary-button");
        no.getStyleClass().add("ghost-button");
        abstain.getStyleClass().add("ghost-button");

        VBox card = Ui.card(null, type, meta, Ui.row(10, yes, no, abstain));
        card.getStyleClass().add("card");
        return card;
    }

    private Button voteButton(String text, String choice, ProposalView p) {
        Button b = new Button(text);
        b.setOnAction(e -> vote(p.txHash(), p.certIndex(), choice, b));
        return b;
    }

    private void vote(String txHash, int certIndex, String choice, Button source) {
        source.setDisable(true);
        Ui.onFx(controller.draftVote(txHash, certIndex, choice, "", ""), draft -> {
            source.setDisable(false);
            ApprovalOverlay.show(overlay, controller, draft, this::refresh);
        }, error -> {
            source.setDisable(false);
            Ui.toast(overlay, "Vote draft failed: " + error.getMessage(), true);
        });
    }
}
