package com.bloxbean.cardano.yano.wallet.nodeclient;

import com.bloxbean.cardano.client.api.ProtocolParamsSupplier;
import com.bloxbean.cardano.client.api.TransactionProcessor;
import com.bloxbean.cardano.client.api.UtxoSupplier;
import com.bloxbean.cardano.client.backend.api.BackendService;
import com.bloxbean.cardano.client.backend.api.DefaultProtocolParamsSupplier;
import com.bloxbean.cardano.client.backend.api.DefaultTransactionProcessor;
import com.bloxbean.cardano.client.backend.api.DefaultUtxoSupplier;
import com.bloxbean.cardano.client.backend.blockfrost.service.BFBackendService;
import com.bloxbean.cardano.yano.wallet.core.config.BackendFlavor;
import com.bloxbean.cardano.yano.wallet.core.config.WalletNetwork;

import java.util.Objects;

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
    private final ProtocolParamsSupplier protocolParamsSupplier;
    private final TransactionProcessor transactionProcessor;
    private final YanoNodePorts ports;

    private YanoNodeBackend(WalletNetwork network, YanoNodeClient nodeClient, BackendService backendService) {
        this.network = network;
        this.nodeClient = nodeClient;
        this.backendService = backendService;
        UtxoSupplier defaultSupplier = new DefaultUtxoSupplier(backendService.getUtxoService());
        // A backend we authenticate to can answer a rejected request with an
        // empty list, which would render as "no funds" (see
        // VerifiedEmptyUtxoSupplier). A local node cannot, and pays nothing here.
        this.utxoSupplier = nodeClient.flavor().requiresApiKey()
                ? new VerifiedEmptyUtxoSupplier(defaultSupplier, nodeClient)
                : defaultSupplier;
        this.protocolParamsSupplier = new DefaultProtocolParamsSupplier(backendService.getEpochService());
        this.transactionProcessor = new DefaultTransactionProcessor(backendService.getTransactionService());
        this.ports = new YanoNodePorts(nodeClient);
    }

    public static YanoNodeBackend connect(WalletNetwork network, String baseUrl) {
        return connect(network, baseUrl, BackendFlavor.YANO, null);
    }

    /**
     * @param flavor which backend software answers here — it decides which paths
     *               exist, not merely which are preferred (ADR-038 §4), and
     *               whether the network can be verified at all (ADR-043 §5).
     *               Carried on the connection rather than derived from the
     *               network, because the same network can be reached either way.
     * @param apiKey credential, or null for a backend that needs none. Sent as
     *               {@code project_id} by both halves of the connection — CCL's
     *               client offers no other header (see {@link YanoNodeClient}).
     */
    public static YanoNodeBackend connect(WalletNetwork network, String baseUrl,
                                          BackendFlavor flavor, String apiKey) {
        Objects.requireNonNull(network, "network is required");
        String normalized = YanoNodeClient.normalizeBaseUrl(baseUrl);
        YanoNodeClient nodeClient = new YanoNodeClient(normalized, flavor, apiKey);
        // A backend that needs no credential still gets a placeholder: Yano
        // ignores the header, and CCL requires the argument.
        BackendService backendService = new BFBackendService(normalized,
                apiKey == null || apiKey.isBlank() ? "yano" : apiKey);
        return new YanoNodeBackend(network, nodeClient, backendService);
    }

    /** Connects and fails fast if the backend serves a different network. */
    public static YanoNodeBackend connectVerified(WalletNetwork network, String baseUrl) {
        return connectVerified(network, baseUrl, BackendFlavor.YANO, null);
    }

    public static YanoNodeBackend connectVerified(WalletNetwork network, String baseUrl,
                                                  BackendFlavor flavor, String apiKey) {
        YanoNodeBackend backend = connect(network, baseUrl, flavor, apiKey);
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
