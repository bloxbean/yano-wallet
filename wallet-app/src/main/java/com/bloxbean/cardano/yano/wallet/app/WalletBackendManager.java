package com.bloxbean.cardano.yano.wallet.app;

import com.bloxbean.cardano.yano.wallet.core.config.WalletConnectionConfig;
import com.bloxbean.cardano.yano.wallet.core.config.WalletNetwork;
import com.bloxbean.cardano.yano.wallet.core.service.NodeNotReadyException;
import com.bloxbean.cardano.yano.wallet.core.service.WalletService;
import com.bloxbean.cardano.yano.wallet.core.tx.FilePendingTransactionStore;
import com.bloxbean.cardano.yano.wallet.core.wallet.FileStoredWalletRepository;
import com.bloxbean.cardano.yano.wallet.launcher.FreePort;
import com.bloxbean.cardano.yano.wallet.launcher.ManagedNode;
import com.bloxbean.cardano.yano.wallet.launcher.NodeLaunchSpec;
import com.bloxbean.cardano.yano.wallet.launcher.NodeStartupProgress;
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
    // How long the node may write NOTHING to its log before we call the start
    // dead. Not a ceiling on the start itself — see ManagedNode#startAndAwaitReady;
    // rebuilding the account-history index legitimately takes over an hour, and
    // capping the total start is what used to kill it halfway.
    //
    // Left generous (rather than tightened now that progress extends it) because
    // the two failure modes are not symmetric: too short kills a working node and
    // throws away its work, while too long only delays the message for a node
    // that is already hung — and the user can leave at any point, since the
    // wallet's local screens are usable throughout the wait.
    private static final Duration DEVNET_NO_PROGRESS_TIMEOUT = Duration.ofSeconds(90);
    private static final Duration REAL_NETWORK_NO_PROGRESS_TIMEOUT = Duration.ofMinutes(45);

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

    private final RelaySettingsStore relaySettings;

    public WalletBackendManager(Path dataDirRoot) {
        this.dataDirRoot = dataDirRoot.toAbsolutePath().normalize();
        this.connectionFile = this.dataDirRoot.resolve("connection.json");
        this.relaySettings = new RelaySettingsStore(this.dataDirRoot);
        warnIfTemporary(this.dataDirRoot);
    }

    /**
     * The upstream relay overrides (E18). Exposed so settings can show and edit
     * them; changes take effect the next time a node is launched.
     */
    RelaySettingsStore relaySettings() {
        return relaySettings;
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

    /** True once a node-backed connection is live (not merely warming up). */
    public boolean isConnected() {
        ActiveConnection conn = active;
        return conn != null && conn.nodeReady();
    }

    /**
     * True when a connection exists at all, including one whose managed node is
     * still starting. Local work (listing, creating, restoring wallets) is
     * available in that state; anything chain-backed is not.
     */
    public boolean hasConnection() {
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

        // Upgrading our own warming-up connection is not a reconnect: nothing was
        // ever bound to the node half of it, so there is nothing to lock.
        boolean isReconnect = this.active != null && this.active.nodeReady();
        this.active = new ActiveConnection(config, network, baseUrl, backend, service);
        persist(config);
        // A reconnect replaces the backend/suppliers; any unlocked session is
        // now bound to the OLD backend, so ask the controller to lock.
        if (isReconnect && onReconnect != null) {
            onReconnect.run();
        }
        log.info("Connected ({}) to {} on {}", config.mode(), network.id(), baseUrl);
        return active;
    }

    /**
     * Launches the managed node and publishes a connection that serves the local
     * half immediately — wallet list, create, restore — without waiting for the
     * node's REST API. Call {@link #completeManagedConnect} to finish.
     *
     * <p>Two phases rather than one because the wait is long enough to matter: a
     * first start on a public network rebuilds the account-history index before
     * binding its HTTP port, which measured ~86 minutes on preview. Making the
     * user watch a spinner for that, when everything they actually need to do
     * first (create or restore a wallet, write down a mnemonic) is local, is time
     * spent for nothing.
     *
     * <p>Fast and synchronized: it spawns a process and returns. The waiting
     * happens in {@link #completeManagedConnect}, deliberately without this
     * object's monitor.
     */
    public synchronized ActiveConnection beginManagedConnect(WalletConnectionConfig config) {
        if (!config.isManaged()) {
            throw new IllegalArgumentException("beginManagedConnect is for managed connections");
        }
        WalletNetwork network = config.network();
        ManagedNode node = spawnManagedNode(config);
        if (node.isReachable()) {
            // Nothing to warm up — this network's node is already answering (a
            // reconnect, or a second attempt after the first one finished while
            // the user was elsewhere). Publishing a warming-up connection here
            // would replace a working one with a crippled one.
            return connect(config);
        }
        persist(config);

        // Replacing a live connection: whatever is unlocked belongs to the old
        // backend, so the same lock-on-reconnect rule applies as in connect().
        boolean replacingLive = this.active != null && this.active.nodeReady();
        this.active = new ActiveConnection(config, network, node.baseUrl(), null,
                localService(network));
        if (replacingLive && onReconnect != null) {
            onReconnect.run();
        }
        log.info("Managed node starting for {} at {} — local wallet operations available now",
                network.id(), node.baseUrl());
        return this.active;
    }

    /**
     * Waits for the node started by {@link #beginManagedConnect} and upgrades the
     * warming-up connection to a full one.
     *
     * <p>Deliberately NOT synchronized: this blocks for as long as the node takes
     * (over an hour on a first start), and holding the monitor for that would
     * stall every other caller — which is the shape of the original bug. It takes
     * the monitor only at the end, via {@link #connect}, by which point the node
     * is reachable and that call is quick.
     *
     * @throws IllegalStateException if the node never became ready
     */
    public ActiveConnection completeManagedConnect(WalletConnectionConfig config) {
        ManagedNode node = this.managedNode; // volatile read, no lock
        if (node == null) {
            throw new IllegalStateException("No managed node has been started");
        }
        if (!node.awaitReady(noProgressTimeout(config.network()))) {
            String reason = node.failureReason();
            throw new IllegalStateException("Managed node failed to start: "
                    + (reason != null ? reason : "unknown") + " (log: " + node.logFile() + ")");
        }
        return connect(config);
    }

    /**
     * A repository-backed service for a network whose node is not up yet. Every
     * chain-backed call throws {@link NodeNotReadyException} with this message.
     */
    private WalletService localService(WalletNetwork network) {
        Path networkDir = dataDirRoot.resolve(network.id());
        return WalletService.localOnly(
                new FileStoredWalletRepository(networkDir, network),
                new FilePendingTransactionStore(networkDir.resolve("pending-transactions.json")),
                "The local " + network.id() + " node is still starting, so the wallet cannot read the "
                        + "chain yet. Creating and restoring wallets works now; balances, history and "
                        + "sending become available when the node finishes starting.");
    }

    private static Duration noProgressTimeout(WalletNetwork network) {
        return "devnet".equals(network.id())
                ? DEVNET_NO_PROGRESS_TIMEOUT : REAL_NETWORK_NO_PROGRESS_TIMEOUT;
    }

    /**
     * Reuse-or-spawn, without waiting. A node for the same network that is
     * already running OR still starting is reused — a second click during a
     * 90-minute start must not kill the node and restart the whole rebuild.
     */
    private synchronized ManagedNode spawnManagedNode(WalletConnectionConfig config) {
        WalletNetwork network = config.network();
        ManagedNode existing = managedNode;
        if (existing != null && existing.spec().network() == network
                && (existing.isReachable() || existing.state() == ManagedNode.State.STARTING)) {
            return existing;
        }
        if (existing != null) {
            existing.close();
        }
        ManagedNode node = new ManagedNode(launchSpec(config));
        managedNode = node;
        if (!node.start()) {
            String reason = node.failureReason();
            throw new IllegalStateException("Managed node failed to start: "
                    + (reason != null ? reason : "unknown"));
        }
        return node;
    }

    private String ensureManagedNode(WalletConnectionConfig config) {
        ManagedNode node = spawnManagedNode(config);
        if (!node.awaitReady(noProgressTimeout(config.network()))) {
            String reason = node.failureReason();
            throw new IllegalStateException("Managed node failed to start: "
                    + (reason != null ? reason : "unknown") + " (log: " + node.logFile() + ")");
        }
        return node.baseUrl();
    }

    private NodeLaunchSpec launchSpec(WalletConnectionConfig config) {
        WalletNetwork network = config.network();

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
        return NodeLocator.autoDetectDevJar(network, chainstate, logFile, httpPort, n2nPort,
                        relaySettings.relaysFor(network))
                .orElseThrow(() -> new IllegalStateException(
                        "Could not find a Yano node to run. Fetch the pinned release with "
                                + "'./gradlew fetchYanoNode', or point at an existing one with "
                                + "YANO_NODE_JAR=/path/to/yano.jar. You can also switch to "
                                + "\"Connect to my node\" and use a node you run yourself."));
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
        // Drop a warming-up connection: its node is now dead, so leaving it in
        // place would offer local screens backed by a network the user just left.
        // A fully connected one is untouched — aborting a connect must not tear
        // down a connection that already works.
        ActiveConnection conn = this.active;
        if (conn != null && !conn.nodeReady()) {
            this.active = null;
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
        if (node == null) {
            return Optional.empty();
        }
        NodeStartupProgress progress = node.progress();
        return Optional.of(new ManagedNodeStatus(node.state().name(), node.failureReason(),
                progress.phase().name(), progress.detail(), progress.current(), progress.total()));
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

    /**
     * Snapshot of the managed node's lifecycle state and, while it is starting,
     * where it has got to (see {@link #managedNodeStatus}). {@code current}/
     * {@code total} are 0 when the current phase reports no position.
     */
    public record ManagedNodeStatus(String state, String failureReason, String phase, String detail,
                                    long current, long total) {
    }

    /**
     * A resolved connection. {@code nodeBackend} is null while a managed node is
     * still starting — the local half (the wallet repository, via
     * {@link #service()}) works throughout, the chain half does not.
     */
    public record ActiveConnection(WalletConnectionConfig config, WalletNetwork network,
                                   String baseUrl, YanoNodeBackend nodeBackend, WalletService service) {
        public ActiveConnection {
            Objects.requireNonNull(service, "service is required");
        }

        /**
         * The node backend, or {@link NodeNotReadyException} if the node is still
         * starting.
         *
         * <p>Written out rather than left as the record's accessor so that every
         * one of its many call sites fails with something a user can read,
         * instead of a NullPointerException from wherever it was dereferenced.
         */
        public YanoNodeBackend backend() {
            if (nodeBackend == null) {
                throw new NodeNotReadyException("The local " + network.id() + " node is still starting,"
                        + " so this needs to wait for it. Wallet creation and restore work now.");
            }
            return nodeBackend;
        }

        /** True once the node's API is up and {@link #backend()} is usable. */
        public boolean nodeReady() {
            return nodeBackend != null;
        }
    }

    private record Persisted(String mode, String network, String baseUrl, Integer managedHttpPort) {
    }
}
