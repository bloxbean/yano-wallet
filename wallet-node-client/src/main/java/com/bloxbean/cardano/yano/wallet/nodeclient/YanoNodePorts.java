package com.bloxbean.cardano.yano.wallet.nodeclient;

import com.bloxbean.cardano.yano.wallet.core.service.HistoryPort;
import com.bloxbean.cardano.yano.wallet.core.service.NodeStatusPort;
import com.bloxbean.cardano.yano.wallet.core.simulate.ResolvedOutput;
import com.bloxbean.cardano.yano.wallet.core.simulate.ScriptEvaluation;
import com.bloxbean.cardano.yano.wallet.core.simulate.SimulationCapabilities;
import com.bloxbean.cardano.yano.wallet.core.simulate.TxSimulationPort;

import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * wallet-core port implementations over the Yano REST API (ADR-033 M2, ADR-042).
 */
public class YanoNodePorts implements NodeStatusPort, HistoryPort, TxSimulationPort {
    private final YanoNodeClient client;

    /**
     * Probed once and reused: the answer only changes when the node is replaced,
     * and replacing it rebuilds this object (see {@code WalletBackendManager}).
     * Volatile rather than synchronized so a signing prompt never queues behind
     * another thread's probe.
     */
    private volatile SimulationCapabilities capabilities;

    public YanoNodePorts(YanoNodeClient client) {
        this.client = Objects.requireNonNull(client, "client is required");
    }

    @Override
    public NodeView status() {
        NodeStatus status = client.getStatus();
        return new NodeView(status.slot(), status.blockNumber(), status.utxoIndexEnabled(),
                status.utxoLagBlocks(), status.utxoIndexCaughtUp());
    }

    @Override
    public TxStatusView txStatus(String txHash) {
        YanoNodeClient.TxStatus status = client.getTxStatus(txHash);
        TxState state = switch (status.status().toLowerCase(Locale.ROOT)) {
            case "in_block" -> TxState.IN_BLOCK;
            case "pending" -> TxState.PENDING;
            default -> TxState.UNKNOWN;
        };
        return new TxStatusView(status.txHash(), state, status.blockHeight(), status.slot(),
                status.blockHash(), status.confirmations(), status.blockTime());
    }

    @Override
    public AccountView accountInfo(String stakeAddress) {
        return client.getAccountInfo(stakeAddress);
    }

    @Override
    public List<TxRef> accountTransactions(String stakeAddress, int page, int count, boolean newestFirst) {
        try {
            return client.getAccountTransactions(stakeAddress, page, count, newestFirst ? "desc" : "asc")
                    .stream()
                    .map(tx -> new TxRef(tx.txHash(), tx.blockHeight(), tx.blockTime(), tx.slot()))
                    .toList();
        } catch (NodeClientException e) {
            throw new HistoryUnavailableException(e.getMessage());
        }
    }

    @Override
    public List<RewardView> rewards(String stakeAddress, int page, int count) {
        try {
            return client.getRewards(stakeAddress, page, count)
                    .stream()
                    .map(reward -> new RewardView(reward.epoch(), reward.amount(), reward.poolId(), reward.type()))
                    .toList();
        } catch (NodeClientException e) {
            throw new HistoryUnavailableException(e.getMessage());
        }
    }

    // ---- TxSimulationPort (ADR-042) ----------------------------------------

    @Override
    public SimulationCapabilities capabilities() {
        SimulationCapabilities cached = capabilities;
        if (cached != null) {
            return cached;
        }
        // A benign race just probes twice; both answers describe the same node.
        SimulationCapabilities probed = client.probeSimulationCapabilities();
        // Cache ONLY a fully capable node. Anything less re-probes on the next
        // request, which costs two bounded calls and buys correctness during node
        // startup: a node still initialising answers 503 for the UTxO index and
        // "evaluation not initialized" for scripts — both definitive-looking, both
        // temporary. Caching those would write off a healthy node for the whole
        // session and tell the user its index is "switched off" when it is not.
        if (probed.canSimulateFully()) {
            capabilities = probed;
        }
        return probed;
    }

    @Override
    public ResolvedOutput resolveOutput(String txHash, int outputIndex) {
        return client.getUtxo(txHash, outputIndex);
    }

    @Override
    public ScriptEvaluation evaluate(String txHex) {
        return client.evaluateTx(txHex);
    }
}
