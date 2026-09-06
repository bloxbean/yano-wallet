package com.bloxbean.cardano.yano.wallet.core.wallet;

import com.bloxbean.cardano.client.address.Address;
import com.bloxbean.cardano.client.api.UtxoSupplier;
import com.bloxbean.cardano.client.api.common.OrderEnum;
import com.bloxbean.cardano.client.api.model.Amount;
import com.bloxbean.cardano.client.api.model.Utxo;
import com.bloxbean.cardano.client.common.model.Networks;
import com.bloxbean.cardano.hdwallet.Wallet;
import org.junit.jupiter.api.Test;
import java.math.BigInteger;
import java.util.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WalletAddressScannerTest {
    Wallet wallet = Wallet.createFromMnemonic(Networks.mainnet(),
        "drive useless envelope shine range ability time copper alarm museum near flee wrist live type device meadow allow churn purity wisdom praise drop code");
    static final BigInteger ADA = BigInteger.valueOf(1_000_000);

    static class Supplier implements UtxoSupplier {
        Map<String, List<Utxo>> funds = new HashMap<>();
        Set<String> history = new HashSet<>();
        Set<String> queried = new HashSet<>();
        boolean historyFails;
        void fund(String address) {
            funds.put(address, List.of(Utxo.builder().txHash(String.format("%064x", funds.size() + 1)).outputIndex(0)
                .amount(List.of(Amount.lovelace(ADA))).build()));
            history.add(address);
        }
        public List<Utxo> getPage(String address, Integer count, Integer page, OrderEnum order) {
            queried.add(address);
            return page == 0 ? funds.getOrDefault(address, List.of()) : List.of();
        }
        public Optional<Utxo> getTxOutput(String hash, int index) { return Optional.empty(); }
        public boolean isUsedAddress(Address address) {
            if (historyFails) throw new IllegalStateException("history unavailable");
            return history.contains(address.toBech32());
        }
    }

    @Test
    void findsInternalChangeUtxoEvenWhenReceiveChainIsEmpty() {
        Supplier s = new Supplier();
        String change = wallet.getAccountAtIndex(0).changeAddress();
        assertThat(change).isNotEqualTo(wallet.getBaseAddressString(0));
        s.fund(change);
        WalletBalance result = new WalletBalanceService().scan(wallet, s);
        assertThat(result.lovelace()).isEqualTo(ADA);
        assertThat(s.queried).contains(change);
    }

    @Test
    void continuesBeyondOneHundredAddressesWithHistory() {
        Supplier s = new Supplier();
        for (int i = 0; i < 100; i++) s.history.add(wallet.getBaseAddressString(i));
        s.fund(wallet.getBaseAddressString(100));
        WalletBalance result = new WalletBalanceService().scan(wallet, s);
        assertThat(result.addressCount()).isEqualTo(141);
        assertThat(result.lovelace()).isEqualTo(ADA);
        assertThat(new WalletBalanceService().scan(wallet, s, 20, 121).lovelace()).isEqualTo(ADA);
    }

    @Test
    void refusesPartialBalanceWhenHistoryFails() {
        Supplier s = new Supplier();
        for (int i = 0; i < 20; i++) s.history.add(wallet.getBaseAddressString(i));
        s.fund(wallet.getBaseAddressString(20));
        s.historyFails = true;
        assertThatThrownBy(() -> new WalletBalanceService().scan(wallet, s))
                .isInstanceOf(WalletAddressScanner.IncompleteScanException.class)
                .hasMessageContaining("history is unavailable");
        s.historyFails = false;
        assertThat(new WalletBalanceService().scan(wallet, s).lovelace()).isEqualTo(ADA);
    }
    @Test
    void refusesBalanceWhenSafetyBoundIsReachedBeforeGap() {
        Supplier s = new Supplier();
        s.fund(wallet.getBaseAddressString(0));
        assertThatThrownBy(() -> new WalletBalanceService().scan(wallet, s, 3, 3))
                .isInstanceOf(WalletAddressScanner.IncompleteScanException.class)
                .hasMessageContaining("receive");
    }

    @Test
    void scansChangeChainPastSpentAddressesWithItsOwnGap() {
        Supplier s = new Supplier();
        for (int i = 0; i < 24; i++) s.history.add(WalletAddresses.baseAddress(wallet, 1, i).toBech32());
        String change = WalletAddresses.baseAddress(wallet, 1, 24).toBech32();
        s.fund(change);
        WalletBalance result = new WalletBalanceService().scan(wallet, s);
        assertThat(result.lovelace()).isEqualTo(ADA);
        assertThat(result.addressCount()).isEqualTo(65);
        assertThat(result.utxos()).extracting(WalletUtxoView::address).containsExactly(change);
    }

    @Test
    void scansHardwareChangeAddressesUsingOnlyAccountPublicKey() {
        // Account xpub derived from the public test mnemonic, using the same export as hardware enrollment.
        var root = wallet.getRootKeyPair().orElseThrow();
        var generator = new com.bloxbean.cardano.client.crypto.bip32.HdKeyGenerator();
        var purposeKey = generator.getChildKeyPair(root, 1852, true);
        var coinKey = generator.getChildKeyPair(purposeKey, 1815, true);
        var accountKey = generator.getChildKeyPair(coinKey, 0, true);
        WatchOnlyWallet watch = new WatchOnlyWallet(Networks.mainnet(),
                accountKey.getPublicKey().getBytes(), 0);
        String change = WalletAddresses.baseAddress(wallet, 1, 7).toBech32();
        assertThat(watch.getChangeAddress(7).toBech32()).isEqualTo(change);
        Supplier s = new Supplier();
        s.fund(change);
        assertThat(new WalletBalanceService().scan(watch, s).lovelace()).isEqualTo(ADA);
    }

    @Test
    void aggregatesFundsAndAssetsAcrossBothChains() {
        Supplier supplier = new Supplier();
        String receive = wallet.getBaseAddressString(0);
        String change = WalletAddresses.baseAddress(wallet, 1, 0).toBech32();
        supplier.fund(receive);
        supplier.fund(change);
        String unit = "a".repeat(56) + "01";
        for (var list : supplier.funds.values()) {
            list.getFirst().setAmount(List.of(Amount.lovelace(ADA), Amount.asset(unit, BigInteger.TEN)));
        }
        WalletBalance balance = new WalletBalanceService().scan(wallet, supplier);
        assertThat(balance.lovelace()).isEqualTo(ADA.multiply(BigInteger.TWO));
        assertThat(balance.utxoCount()).isEqualTo(2);
        assertThat(balance.assets()).containsExactly(new WalletAssetBalance(unit, BigInteger.valueOf(20)));
    }

    @Test
    void refusesPartialBalanceWhenChangeChainReachesItsBound() {
        Supplier supplier = new Supplier();
        supplier.fund(wallet.getBaseAddressString(0));
        supplier.fund(WalletAddresses.baseAddress(wallet, 1, 1).toBech32());
        assertThatThrownBy(() -> new WalletBalanceService().scan(wallet, supplier, 2, 3))
                .isInstanceOf(WalletAddressScanner.IncompleteScanException.class)
                .hasMessageContaining("change");
    }

    @Test
    void acceptsGapEndingExactlyAtSafetyBound() {
        assertThat(new WalletBalanceService().scan(wallet, new Supplier(), 3, 3).addressCount())
                .isEqualTo(6);
    }

}
