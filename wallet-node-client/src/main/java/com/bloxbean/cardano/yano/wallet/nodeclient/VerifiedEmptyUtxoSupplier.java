package com.bloxbean.cardano.yano.wallet.nodeclient;

import com.bloxbean.cardano.client.address.Address;
import com.bloxbean.cardano.client.api.UtxoSupplier;
import com.bloxbean.cardano.client.api.common.OrderEnum;
import com.bloxbean.cardano.client.api.model.Utxo;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Makes "no UTxOs" mean it, on a backend that answers a rejected request with an
 * empty list.
 *
 * <p>Measured against hosted Blockfrost on 2026-08-14: with a missing or invalid
 * {@code project_id}, CCL's {@code DefaultUtxoSupplier.getAll} returns an
 * <em>empty list</em> rather than throwing. Presented as-is that reads "this
 * wallet has no funds" — the one wrong answer a wallet must never give, and the
 * same hazard {@code PendingNodeAccess} exists to avoid on the other side of the
 * connection.
 *
 * <p>Connect-time verification already rejects a bad credential, so this covers
 * what that cannot: a key that stops working <em>during</em> a session — revoked,
 * expired, or past a plan's quota. On an empty result it asks the backend one
 * cheap authenticated question; if that fails, the emptiness was a lie and the
 * failure is raised instead.
 *
 * <p>Only wraps flavors that authenticate. A local node's empty answer is
 * trustworthy and pays nothing for this.
 */
final class VerifiedEmptyUtxoSupplier implements UtxoSupplier {

    private final UtxoSupplier delegate;
    private final YanoNodeClient client;

    VerifiedEmptyUtxoSupplier(UtxoSupplier delegate, YanoNodeClient client) {
        this.delegate = Objects.requireNonNull(delegate, "delegate is required");
        this.client = Objects.requireNonNull(client, "client is required");
    }

    @Override
    public List<Utxo> getPage(String address, Integer nrOfItems, Integer page, OrderEnum order) {
        return verifyIfEmpty(delegate.getPage(address, nrOfItems, page, order));
    }

    @Override
    public List<Utxo> getAll(String address) {
        return verifyIfEmpty(delegate.getAll(address));
    }

    @Override
    public Optional<Utxo> getTxOutput(String txHash, int outputIndex) {
        return delegate.getTxOutput(txHash, outputIndex);
    }

    @Override
    public boolean isUsedAddress(Address address) {
        return delegate.isUsedAddress(address);
    }

    @Override
    public void setSearchByAddressVkh(boolean flag) {
        delegate.setSearchByAddressVkh(flag);
    }

    /**
     * Costs one extra request, and only on the empty path — rare for a funded
     * wallet, and the alternative is a silently wrong balance.
     */
    private List<Utxo> verifyIfEmpty(List<Utxo> utxos) {
        if (utxos != null && !utxos.isEmpty()) {
            return utxos;
        }
        // Throws with the credential message (see YanoNodeClient#describeFailure)
        // if the backend is refusing us; returns normally if it is simply true
        // that this address holds nothing.
        client.getLatestBlock();
        return utxos;
    }
}
