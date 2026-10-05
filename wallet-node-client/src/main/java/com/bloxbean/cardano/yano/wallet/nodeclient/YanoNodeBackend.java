package com.bloxbean.cardano.yano.wallet.nodeclient;

import com.bloxbean.cardano.client.api.ProtocolParamsSupplier;
import com.bloxbean.cardano.client.api.TransactionProcessor;
import com.bloxbean.cardano.client.api.UtxoSupplier;
import com.bloxbean.cardano.client.backend.api.BackendService;
import com.bloxbean.cardano.client.backend.api.DefaultProtocolParamsSupplier;
import com.bloxbean.cardano.client.backend.api.DefaultTransactionProcessor;
import com.bloxbean.cardano.client.backend.blockfrost.service.BFBackendService;
import com.bloxbean.cardano.yano.wallet.core.config.WalletNetwork;

import java.util.Objects;
import java.nio.file.Path;

/**
 * Wallet-side view of a local Yano node: cardano-client-lib suppliers backed
 * by the node's Blockfrost-compatible REST API (default prefix
 * {@code http://localhost:7070/api/v1/}), plus the Yano-specific status client.
 *
 * <p>Transaction submission runs through the node's mempool with local Scalus
 * ledger-rule validation; {@code evaluateTx} hits the node's Ogmios-compatible
 * {@code POST /utils/txs/evaluate}.
 */
public class YanoNodeBackend {
    public static final String DEFAULT_LOCAL_BASE_URL = "http://localhost:7070/api/v1/";

    private final WalletNetwork network;
    private final YanoNodeClient nodeClient;
    private final BackendService backendService;
    private final UtxoSupplier utxoSupplier;
    private final UtxoSupplier selectionUtxoSupplier;
    private final ProtocolParamsSupplier protocolParamsSupplier;
    private final TransactionProcessor transactionProcessor;
    private final YanoNodePorts ports;
    private final PendingInputs pendingInputs;

    private YanoNodeBackend(WalletNetwork network, YanoNodeClient nodeClient, BackendService backendService) {
        this.network = network;
        this.nodeClient = nodeClient;
        this.backendService = backendService;
        this.utxoSupplier = new YanoUtxoSupplier(backendService.getUtxoService(), nodeClient);
        this.pendingInputs = new PendingInputs();
        this.selectionUtxoSupplier = nodeClient.isBlockfrostFlavor() ? utxoSupplier
                : new YanoUtxoSupplier(backendService.getUtxoService(), nodeClient, pendingInputs);
        this.protocolParamsSupplier = new DefaultProtocolParamsSupplier(backendService.getEpochService());
        this.transactionProcessor = nodeClient.isBlockfrostFlavor()
                ? new DefaultTransactionProcessor(backendService.getTransactionService())
                : new YanoTransactionProcessor(backendService.getTransactionService(), pendingInputs, nodeClient);
        this.ports = new YanoNodePorts(nodeClient);
    }

    public static YanoNodeBackend connect(WalletNetwork network, String baseUrl) {
        Objects.requireNonNull(network, "network is required");
        String normalized = YanoNodeClient.normalizeBaseUrl(baseUrl);
        // The flavor decides which paths exist, not merely which are preferred
        // (ADR-038 §4) — so it is fixed at connect time from the user's network
        // choice, which is the only thing that can identify a yaci-store.
        YanoNodeClient nodeClient = new YanoNodeClient(normalized, network.blockfrostFlavor());
        // Yano ignores the Blockfrost project_id header; pass a placeholder.
        BackendService backendService = new BFBackendService(normalized, "yano");
        return new YanoNodeBackend(network, nodeClient, backendService);
    }

    /** Connects and fails fast if the node serves a different network. */
    public static YanoNodeBackend connectVerified(WalletNetwork network, String baseUrl) {
        YanoNodeBackend backend = connect(network, baseUrl);
        backend.nodeClient.verifyNetwork(network);
        return backend;
    }

    public WalletNetwork network() {
        return network;
    }

    public YanoNodeClient nodeClient() {
        return nodeClient;
    }

    public BackendService backendService() {
        return backendService;
    }

    public UtxoSupplier utxoSupplier() {
        return utxoSupplier;
    }

    /** Transaction-building view; confirmed balances must use {@link #utxoSupplier()}. */
    public UtxoSupplier selectionUtxoSupplier() {
        return selectionUtxoSupplier;
    }

    /** Call before exposing the connection to transaction builders/submission. */
    public void persistPendingInputs(Path file) {
        if (!nodeClient.isBlockfrostFlavor()) pendingInputs.persistAt(file);
    }

    public ProtocolParamsSupplier protocolParamsSupplier() {
        return protocolParamsSupplier;
    }

    public TransactionProcessor transactionProcessor() {
        return transactionProcessor;
    }

    /**
     * wallet-core port implementations (status/tx-status/history/rewards, and
     * transaction simulation per ADR-042).
     *
     * <p>Deliberately ONE instance for the lifetime of this backend, not a fresh
     * one per call: it caches the node's probed simulation capabilities, and
     * callers reach it as {@code backend.ports()} at many sites. Handing out new
     * instances would silently re-probe the node on every signing request.
     * Replacing the node rebuilds the backend, which is what expires the cache.
     */
    public YanoNodePorts ports() {
        return ports;
    }
}
