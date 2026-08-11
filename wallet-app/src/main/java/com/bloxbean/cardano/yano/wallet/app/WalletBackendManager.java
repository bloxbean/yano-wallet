package com.bloxbean.cardano.yano.wallet.app;

import com.bloxbean.cardano.yano.wallet.core.config.WalletConnectionConfig;
import com.bloxbean.cardano.yano.wallet.core.config.WalletNetwork;
import com.bloxbean.cardano.yano.wallet.core.service.WalletService;
import com.bloxbean.cardano.yano.wallet.core.tx.FilePendingTransactionStore;
import com.bloxbean.cardano.yano.wallet.core.wallet.FileStoredWalletRepository;
import com.bloxbean.cardano.yano.wallet.launcher.FreePort;
import com.bloxbean.cardano.yano.wallet.launcher.ManagedNode;
import com.bloxbean.cardano.yano.wallet.launcher.NodeLaunchSpec;
import com.bloxbean.cardano.yano.wallet.launcher.NodeLocator;
import com.bloxbean.cardano.yano.wallet.nodeclient.YanoNodeBackend;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Resolves a {@link WalletConnectionConfig} into a live wallet backend: for a
 * managed connection it launches and supervises a local node (ADR-033 A3); for
 * an external connection it verifies the given URL. Owns the per-connection
 * repository, backend, and {@link WalletService}, rebuilding them on reconnect.
 */
public class WalletBackendManager implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(WalletBackendManager.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    // Devnet starts in seconds; a real network resuming a chainstate that lacks
    // the wallet index rebuilds it on first start, which can take minutes. A
    // generous ceiling avoids a spurious timeout on that one-time backfill.
    private static final Duration DEVNET_START_TIMEOUT = Duration.ofSeconds(90);
    private static final Duration REAL_NETWORK_START_TIMEOUT = Duration.ofMinutes(45);

    private final Path dataDirRoot;
    private final Path connectionFile;

    // Volatile so the UI's startup poller can snapshot the node's lifecycle and
    // tail its log WITHOUT the connect() monitor — connect() can hold that lock
    // for the whole (up to 45-minute) first sync, so those reads must stay lock-free.
    private volatile ManagedNode managedNode;
    // Read off the connect path (FX thread, backend executor) → volatile for
    // a happens-before with connect() publishing it.
    private volatile ActiveConnection active;
    private volatile Runnable onReconnect;

    public WalletBackendManager(Path dataDirRoot) {
        this.dataDirRoot = dataDirRoot.toAbsolutePath().normalize();
        this.connectionFile = this.dataDirRoot.resolve("connection.json");
        warnIfTemporary(this.dataDirRoot);
    }

    /**
     * A data dir under /tmp is a time bomb: macOS periodically deletes /tmp
     * files not accessed for a few days, which silently corrupts the node's
     * RocksDB (observed in the field — old SSTs vanish from under a valid
     * manifest). Warn loudly up front rather than diagnose after the fact.
     */
    private static void warnIfTemporary(Path dataDir) {
        String path = dataDir.toString();
        if (path.startsWith("/tmp/") || path.startsWith("/private/tmp/") || path.startsWith("/var/folders/")) {
            log.warn("Data dir {} is in a TEMPORARY location — the OS periodically deletes files here,"
                    + " which will corrupt the node database. Use a persistent --data-dir"
                    + " (default: ~/.yano-wallet).", dataDir);
        }
    }

    /** The live connection, or null before {@link #connect} succeeds. */
    public ActiveConnection active() {
        return active;
    }

    /** Root data directory (holds connection.json, per-network node data, etc.). */
    public Path dataDir() {
        return dataDirRoot;
    }

    public boolean isConnected() {
        return active != null;
    }

    /** Invoked when a NEW connection replaces an existing one (e.g. to lock the wallet). */
    public void setOnReconnect(Runnable callback) {
        this.onReconnect = callback;
    }

    /** Reads the persisted connection config, or empty on first run. */
    public Optional<WalletConnectionConfig> savedConfig() {
        if (!Files.exists(connectionFile)) {
            return Optional.empty();
        }
        try {
            Persisted p = MAPPER.readValue(connectionFile.toFile(), Persisted.class);
            WalletNetwork network = WalletNetwork.fromId(p.network());
            return Optional.of("EXTERNAL".equals(p.mode())
                    ? WalletConnectionConfig.external(network, p.baseUrl())
                    : p.managedHttpPort() != null
                            ? WalletConnectionConfig.managed(network, p.managedHttpPort())
                            : WalletConnectionConfig.managed(network));
        } catch (IOException e) {
            log.warn("Unable to read connection config {}: {}", connectionFile, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Establishes the connection described by the config, launching a managed
     * node if needed, and persists the choice. Rebuilds the backend/service.
     */
    public synchronized ActiveConnection connect(WalletConnectionConfig config) {
        WalletNetwork network = config.network();
        String baseUrl;
        if (config.isManaged()) {
            baseUrl = ensureManagedNode(config);
        } else {
            baseUrl = config.externalBaseUrl();
            if (baseUrl == null || baseUrl.isBlank()) {
                throw new IllegalArgumentException("External node URL is required");
            }
        }

        YanoNodeBackend backend = YanoNodeBackend.connectVerified(network, baseUrl);
        Path networkDir = dataDirRoot.resolve(network.id());
        FileStoredWalletRepository repository = new FileStoredWalletRepository(networkDir, network);
        WalletService service = new WalletService(
                repository,
                backend.utxoSupplier(),
                backend.protocolParamsSupplier(),
                backend.transactionProcessor(),
                new FilePendingTransactionStore(networkDir.resolve("pending-transactions.json")),
                backend.ports());

        boolean isReconnect = this.active != null;
        this.active = new ActiveConnection(config, network, backend, service);
        persist(config);
        // A reconnect replaces the backend/suppliers; any unlocked session is
        // now bound to the OLD backend, so ask the controller to lock.
        if (isReconnect && onReconnect != null) {
            onReconnect.run();
        }
        log.info("Connected ({}) to {} on {}", config.mode(), network.id(), baseUrl);
        return active;
    }

    private String ensureManagedNode(WalletConnectionConfig config) {
        WalletNetwork network = config.network();

        // Reuse a running managed node for the same network.
        if (managedNode != null && managedNode.spec().network() == network && managedNode.isReachable()) {
            return managedNode.baseUrl();
        }
        if (managedNode != null) {
            managedNode.close();
        }

        // Auto-pick free ports (a configured port is honored) so the managed
        // node never collides with a default Yano or unrelated software.
        int httpPort = config.managedHttpPort() != null ? config.managedHttpPort() : FreePort.find();
        int n2nPort = FreePort.find();
        while (n2nPort == httpPort) { // two independent finds can return the same number
            n2nPort = FreePort.find();
        }

        Path nodeData = dataDirRoot.resolve(network.id()).resolve("node");
        Path chainstate = nodeData.resolve("chainstate");
        Path logFile = nodeData.resolve("node.log");
        NodeLaunchSpec spec = NodeLocator.autoDetectDevJar(network, chainstate, logFile, httpPort, n2nPort)
                .orElseThrow(() -> new IllegalStateException(
                        "Could not find a Yano node to run. Fetch the pinned release with "
                                + "'./gradlew fetchYanoNode', or point at an existing one with "
                                + "YANO_NODE_JAR=/path/to/yano.jar. You can also switch to "
                                + "\"Connect to my node\" and use a node you run yourself."));

        Duration startTimeout = "devnet".equals(network.id())
                ? DEVNET_START_TIMEOUT : REAL_NETWORK_START_TIMEOUT;
        managedNode = new ManagedNode(spec);
        if (!managedNode.startAndAwaitReady(startTimeout)) {
            String reason = managedNode.failureReason();
            throw new IllegalStateException("Managed node failed to start: "
                    + (reason != null ? reason : "unknown") + " (log: " + logFile + ")");
        }
        return managedNode.baseUrl();
    }

    /**
     * Aborts an in-flight {@link #connect} by stopping the managed node it is
     * waiting on, so the user can back out of a slow start and pick a different
     * network.
     *
     * <p>Deliberately NOT synchronized: {@code connect()} holds this object's
     * monitor for the whole (up to 45-minute) start, so acquiring it here would
     * deadlock instead of cancelling. It reads the volatile field and relies on
     * {@link ManagedNode#close()} setting its own {@code closing} flag, which the
     * readiness poll checks without the node's monitor — so the in-flight
     * {@code startAndAwaitReady} returns false and {@code connect()} throws.
     *
     * <p>May block for as long as the node takes to die: call it off the FX thread.
     */
    public void abortConnect() {
        ManagedNode node = managedNode;
        if (node != null) {
            log.info("Aborting in-flight connect — stopping the managed node");
            node.close();
        }
    }

    /**
     * True when a managed node for this network already has chainstate on disk,
     * i.e. starting it resumes rather than syncing from genesis. Lets the Connect
     * screen set the right expectation before a potentially very long first sync.
     */
    public boolean hasLocalChainstate(WalletNetwork network) {
        Path chainstate = dataDirRoot.resolve(network.id()).resolve("node").resolve("chainstate");
        if (!Files.isDirectory(chainstate)) {
            return false;
        }
        try (var entries = Files.list(chainstate)) {
            return entries.findAny().isPresent();
        } catch (IOException e) {
            return false;
        }
    }

    /** Persist a connection choice WITHOUT connecting (so the UI can connect async). */
    public void saveConfig(WalletConnectionConfig config) {
        persist(config);
    }

    private void persist(WalletConnectionConfig config) {
        try {
            Files.createDirectories(dataDirRoot);
            MAPPER.writerWithDefaultPrettyPrinter().writeValue(connectionFile.toFile(),
                    new Persisted(config.mode().name(), config.network().id(),
                            config.externalBaseUrl(), config.managedHttpPort()));
        } catch (IOException e) {
            throw new UncheckedIOException("Unable to persist connection config", e);
        }
    }

    @Override
    public synchronized void close() {
        if (managedNode != null) {
            managedNode.close();
            managedNode = null;
        }
    }

    /**
     * Lock-free snapshot of the managed node's lifecycle for the startup UI, or
     * empty if no managed node has been created. Never synchronizes, so it stays
     * responsive while {@link #connect} is blocked on a long first sync.
     */
    public Optional<ManagedNodeStatus> managedNodeStatus() {
        ManagedNode node = this.managedNode;
        return node == null
                ? Optional.empty()
                : Optional.of(new ManagedNodeStatus(node.state().name(), node.failureReason()));
    }

    /** True when the ACTIVE connection is a managed local node (so a local log exists). */
    public boolean isActiveManaged() {
        ActiveConnection conn = this.active;
        return conn != null && conn.config().isManaged();
    }

    /**
     * The last {@code maxLines} of the managed node's log, or an empty list for an
     * external connection / when no log exists yet. Lock-free (see {@link #managedNodeStatus}).
     */
    public List<String> nodeLogTail(int maxLines) {
        ManagedNode node = this.managedNode;
        Path logFile = node != null ? node.logFile() : null;
        if (logFile == null) {
            ActiveConnection conn = this.active;
            if (conn != null && conn.config().isManaged()) {
                logFile = dataDirRoot.resolve(conn.network().id()).resolve("node").resolve("node.log");
            }
        }
        return logFile == null ? List.of() : ManagedNode.tailLines(logFile, maxLines);
    }

    /** Snapshot of the managed node's lifecycle state (see {@link #managedNodeStatus}). */
    public record ManagedNodeStatus(String state, String failureReason) {
    }

    /** A resolved, live connection. */
    public record ActiveConnection(WalletConnectionConfig config, WalletNetwork network,
                                   YanoNodeBackend backend, WalletService service) {
        public ActiveConnection {
            Objects.requireNonNull(service, "service is required");
        }
    }

    private record Persisted(String mode, String network, String baseUrl, Integer managedHttpPort) {
    }
}
