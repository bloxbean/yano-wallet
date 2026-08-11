package com.bloxbean.cardano.yano.wallet.ui.screens;

import com.bloxbean.cardano.yano.wallet.ui.contract.WalletUiController.WalletItem;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The multi-account list groups accounts by seed (ADR-037). The rules that
 * matter to a user: accounts of one wallet appear together under one card, in
 * account order, and the card order doesn't shuffle between refreshes.
 */
class WalletGroupingTest {

    private static WalletItem account(String walletId, String seedId, String name, int index) {
        return new WalletItem(walletId, seedId, name, "preprod", index,
                "addr_test_" + walletId, "stake_test_" + walletId, false);
    }

    @Test
    void groupsAccountsBySeedInFirstSeenOrder() {
        // Interleaved and out of order, as a flat index listing can be.
        List<WalletItem> wallets = List.of(
                account("w1", "seedA", "Personal", 0),
                account("w2", "seedB", "Savings", 0),
                account("w3", "seedA", "Trading", 1));

        Map<String, List<WalletItem>> groups = OnboardingScreen.walletGroups(wallets);

        assertThat(groups.keySet()).containsExactly("seedA", "seedB"); // first-seen order
        assertThat(groups.get("seedA")).extracting(WalletItem::name)
                .containsExactly("Personal", "Trading");
        assertThat(groups.get("seedB")).extracting(WalletItem::name).containsExactly("Savings");
    }

    @Test
    void sortsAccountsByIndexWithinAGroup() {
        List<WalletItem> wallets = List.of(
                account("w3", "seedA", "Third", 2),
                account("w1", "seedA", "First", 0),
                account("w2", "seedA", "Second", 1));

        Map<String, List<WalletItem>> groups = OnboardingScreen.walletGroups(wallets);

        assertThat(groups.get("seedA")).extracting(WalletItem::accountIndex).containsExactly(0, 1, 2);
    }

    @Test
    void emptyListYieldsNoGroups() {
        assertThat(OnboardingScreen.walletGroups(List.of())).isEmpty();
    }

    @Test
    void accountLabelCombinesIndexAndName() {
        assertThat(account("w1", "s", "Trading", 1).accountLabel()).isEqualTo("Account 1 · Trading");
    }

    @Test
    void accountLabelOmitsRedundantOrEmptyNames() {
        // Default names are already "Account N" — don't render "Account 1 · Account 1".
        assertThat(account("w1", "s", "Account 1", 1).accountLabel()).isEqualTo("Account 1");
        assertThat(account("w1", "s", "  ", 0).accountLabel()).isEqualTo("Account 0");
        assertThat(account("w1", "s", null, 0).accountLabel()).isEqualTo("Account 0");
    }
}
