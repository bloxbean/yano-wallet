package com.bloxbean.cardano.yano.wallet.core.wallet;

import com.bloxbean.cardano.client.account.Account;
import com.bloxbean.cardano.client.address.Address;
import com.bloxbean.cardano.client.api.model.WalletUtxo;
import com.bloxbean.cardano.client.transaction.spec.Transaction;
import com.bloxbean.cardano.hdwallet.DefaultWallet;
import com.bloxbean.cardano.hdwallet.Wallet;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.IntStream;

/**
 * A finite address iterator for QuickTx, using the balance scanner's funded addresses.
 * CCL's DefaultWallet signer drops the derivation role, so signing must preserve it here.
 * Only used for a single software payment draft; never persisted as a wallet profile.
 */
public final class DiscoveredSpendingWallet extends DefaultWallet {
    private final List<Address> addresses;

    public DiscoveredSpendingWallet(Wallet source, WalletAddressScanner.Scan scan) {
        super(source.getNetwork(), null, source.getRootPvtKey()
                .orElseThrow(() -> new IllegalStateException("Software payment requires a signing key")),
                null, source.getAccountNo());
        // QuickTx treats position zero as the default change address. Keep it stable,
        // even when all funds are elsewhere. A nonempty explicit index list also
        // prevents CCL from falling back to its own receive-only gap scan.
        Map<String, Address> unique = new LinkedHashMap<>();
        Address primary = WalletAddresses.baseAddress(source, 0, 0);
        unique.put(primary.toBech32(), primary);
        for (var funded : scan.fundedAddresses()) {
            unique.put(funded.address().toBech32(), funded.address());
        }
        addresses = List.copyOf(unique.values());
        setIndexesToScan(IntStream.range(0, addresses.size()).toArray());
    }

    @Override
    public Address getBaseAddress(int position) {
        return addresses.get(position);
    }

    @Override
    public Transaction sign(Transaction transaction, Set<WalletUtxo> utxos) {
        Map<String, Account> signers = new LinkedHashMap<>();
        for (WalletUtxo utxo : utxos) {
            var path = utxo.getDerivationPath();
            if (path == null || addresses.stream().noneMatch(address ->
                    address.getDerivationPath().filter(path::equals).isPresent())) {
                throw new IllegalArgumentException("Input has no discovered wallet derivation path");
            }
            Account account = Account.createFromRootKey(getNetwork(), getRootPvtKey().orElseThrow(), path);
            signers.putIfAbsent(account.baseAddress(), account);
        }
        if (signers.isEmpty()) throw new IllegalStateException("No wallet payment signers found");
        for (Account account : signers.values()) transaction = account.sign(transaction);
        return transaction;
    }
}
