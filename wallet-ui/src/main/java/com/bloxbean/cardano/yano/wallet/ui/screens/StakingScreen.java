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

/** Delegation status, delegate/withdraw actions, and reward history. */
public class StakingScreen implements Shell.Screen {
    private final WalletUiController controller;
    private final StackPane overlay;

    private final Label stakeAddress = new Label();
    private final Label delegationState = new Label();
    private final Label withdrawable = new Label();
    private final TextField poolField = new TextField();
    private final Button delegateButton = new Button("Delegate");
    private final Button withdrawButton = new Button("Withdraw rewards");
    private final VBox rewardsBox = new VBox(8);
    private final ScrollPane root;

    public StakingScreen(WalletUiController controller, StackPane overlay) {
        this.controller = controller;
        this.overlay = overlay;
        this.root = build();
    }

    private ScrollPane build() {
        Label title = new Label("Staking");
        title.getStyleClass().add("screen-title");

        stakeAddress.getStyleClass().add("mono");
        stakeAddress.setWrapText(true);
        delegationState.getStyleClass().add("muted");
        withdrawable.getStyleClass().add("balance-sub");

        poolField.setPromptText("Pool id (pool1…)");
        delegateButton.getStyleClass().add("primary-button");
        delegateButton.setOnAction(e -> delegate());
        withdrawButton.getStyleClass().add("ghost-button");
        withdrawButton.setOnAction(e -> withdraw());

        VBox statusCard = Ui.card("Delegation", stakeAddress, delegationState,
                Ui.row(10, poolField, delegateButton));
        VBox rewardsCard = Ui.card("Rewards", withdrawable, withdrawButton, rewardsBox);

        VBox column = new VBox(16, title, statusCard, rewardsCard);
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
        Ui.onFx(controller.staking(), staking -> {
            stakeAddress.setText(staking.stakeAddress());
            if (staking.delegatedPoolId() != null) {
                delegationState.setText("Delegated to " + staking.delegatedPoolId());
            } else if (staking.registered()) {
                delegationState.setText("Stake address registered, not delegated");
            } else {
                delegationState.setText("Not registered — delegating will register the stake address");
            }
            withdrawable.setText("₳ " + staking.withdrawableAda() + " withdrawable");
        }, error -> Ui.toast(overlay, "Staking info failed: " + error.getMessage(), true));

        Ui.onFx(controller.rewards(1, 20), rewards -> {
            rewardsBox.getChildren().clear();
            if (rewards.isEmpty()) {
                rewardsBox.getChildren().add(Ui.muted("No rewards yet"));
            } else {
                rewards.forEach(reward -> rewardsBox.getChildren().add(Ui.row(12,
                        Ui.chip("epoch " + reward.epoch(), "chip-neutral"),
                        new Label("₳ " + reward.amountAda()),
                        Ui.chip(reward.type(), "chip-ok"),
                        Ui.spacer(),
                        Ui.muted(reward.poolId() != null ? Ui.middleEllipsis(reward.poolId(), 12) : ""))));
            }
        }, error -> rewardsBox.getChildren().setAll(
                Ui.muted("Reward history unavailable: " + error.getMessage())));
    }

    private void delegate() {
        String poolId = poolField.getText() == null ? "" : poolField.getText().trim();
        if (poolId.isEmpty()) {
            Ui.toast(overlay, "Pool id is required", true);
            return;
        }
        delegateButton.setDisable(true);
        Ui.onFx(controller.draftDelegation(poolId), draft -> {
            delegateButton.setDisable(false);
            ApprovalOverlay.show(overlay, controller, draft, this::refresh);
        }, error -> {
            delegateButton.setDisable(false);
            Ui.toast(overlay, "Delegation draft failed: " + error.getMessage(), true);
        });
    }

    private void withdraw() {
        withdrawButton.setDisable(true);
        Ui.onFx(controller.draftWithdrawal(), draft -> {
            withdrawButton.setDisable(false);
            ApprovalOverlay.show(overlay, controller, draft, this::refresh);
        }, error -> {
            withdrawButton.setDisable(false);
            Ui.toast(overlay, "Withdrawal draft failed: " + error.getMessage(), true);
        });
    }
}
