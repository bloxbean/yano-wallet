package com.bloxbean.cardano.yano.wallet.app;

import com.bloxbean.cardano.yano.wallet.core.service.NodeStatusPort;
import com.bloxbean.cardano.yano.wallet.core.wallet.WalletBalance;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class WalletBalanceViewTest {
    private final WalletBalance utxos = new WalletBalance(
            BigInteger.valueOf(10_123_456), 40, 2, List.of(), List.of());

    @Test
    void totalIncludesWithdrawableRewardsWhileSpendableBalanceRemainsUtxoOnly() {
        var view = DefaultWalletUiController.balanceView(utxos,
                () -> new NodeStatusPort.AccountView(true, BigInteger.valueOf(2_876_545), null, null, null));
        assertThat(view.totalAda()).isEqualTo("13.000001");
        assertThat(view.rewardsAda()).isEqualTo("2.876545");
        assertThat(view.ada()).isEqualTo("10.123456");
        assertThat(view.lovelace()).isEqualTo("10123456");
        assertThat(view.utxoCount()).isEqualTo(2);
    }

    @Test
    void unregisteredAccountAddsZeroRewards() {
        var view = DefaultWalletUiController.balanceView(utxos,
                () -> new NodeStatusPort.AccountView(false, BigInteger.ZERO, null, null, null));
        assertThat(view.totalAda()).isEqualTo(view.ada());
        assertThat(view.rewardsAda()).isEqualTo("0");
    }

    @Test
    void unavailableRewardsPreserveKnownUtxosAndScanWarningWithoutClaimingZeroRewards() {
        var partial = new WalletBalance(utxos.lovelace(), 400, 2, List.of(), List.of(), "Scan incomplete");
        var view = DefaultWalletUiController.balanceView(partial, () -> {
            throw new IllegalStateException("Node unavailable");
        });
        assertThat(view.totalAda()).isEqualTo("10.123456");
        assertThat(view.rewardsAda()).isNull();
        assertThat(view.scanWarning()).isEqualTo("Scan incomplete");
    }

    @Test
    void transactionTimeIncludesTheYear() {
        assertThat(DefaultWalletUiController.formatTransactionTime(1688169600L))
                .contains("2023");
    }
}
