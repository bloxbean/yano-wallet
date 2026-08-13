package com.bloxbean.cardano.yano.wallet.core.service;

import com.bloxbean.cardano.client.address.Address;
import com.bloxbean.cardano.client.api.ProtocolParamsSupplier;
import com.bloxbean.cardano.client.api.TransactionProcessor;
import com.bloxbean.cardano.client.api.UtxoSupplier;
import com.bloxbean.cardano.client.api.common.OrderEnum;
import com.bloxbean.cardano.client.api.model.EvaluationResult;
import com.bloxbean.cardano.client.api.model.ProtocolParams;
import com.bloxbean.cardano.client.api.model.Result;
import com.bloxbean.cardano.client.api.model.Utxo;

import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Stands in for the node-backed half of a {@link WalletService} while a managed
 * node is still starting: every method throws {@link NodeNotReadyException}.
 *
 * <p>{@code WalletService} requires all of its suppliers, and rightly so — a
 * null one would surface as a NullPointerException from somewhere deep in a
 * transaction build. This makes "the node is not up yet" an explicit, named
 * state with a message a user can act on, and keeps the local half (the wallet
 * repository) fully usable meanwhile.
 */
final class PendingNodeAccess
        implements UtxoSupplier, ProtocolParamsSupplier, TransactionProcessor, NodeStatusPort {

    private final String message;

    PendingNodeAccess(String message) {
        this.message = message;
    }

    private NodeNotReadyException fail() {
        return new NodeNotReadyException(message);
    }

    // --- UtxoSupplier ---

    @Override
    public List<Utxo> getPage(String address, Integer nrOfItems, Integer page, OrderEnum order) {
        throw fail();
    }

    @Override
    public Optional<Utxo> getTxOutput(String txHash, int outputIndex) {
        throw fail();
    }

    @Override
    public List<Utxo> getAll(String address) {
        throw fail();
    }

    @Override
    public boolean isUsedAddress(Address address) {
        throw fail();
    }

    // --- ProtocolParamsSupplier ---

    @Override
    public ProtocolParams getProtocolParams() {
        throw fail();
    }

    // --- TransactionProcessor ---

    @Override
    public Result<String> submitTransaction(byte[] cborData) {
        throw fail();
    }

    @Override
    public Result<List<EvaluationResult>> evaluateTx(byte[] cbor, Set<Utxo> inputUtxos) {
        throw fail();
    }

    // --- NodeStatusPort ---

    @Override
    public NodeView status() {
        throw fail();
    }

    @Override
    public TxStatusView txStatus(String txHash) {
        throw fail();
    }

    @Override
    public AccountView accountInfo(String stakeAddress) {
        throw fail();
    }
}
