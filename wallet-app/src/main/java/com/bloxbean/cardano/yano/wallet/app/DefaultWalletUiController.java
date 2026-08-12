package com.bloxbean.cardano.yano.wallet.app;

import com.bloxbean.cardano.client.address.Address;
import com.bloxbean.cardano.client.crypto.cip1852.CIP1852;
import com.bloxbean.cardano.client.governance.GovId;
import com.bloxbean.cardano.client.governance.LegacyDRepId;
import com.bloxbean.cardano.client.transaction.spec.governance.DRep;
import com.bloxbean.cardano.client.transaction.spec.governance.DRepType;
import com.bloxbean.cardano.client.transaction.spec.governance.Vote;
import com.bloxbean.cardano.client.util.HexUtil;
import com.bloxbean.cardano.yano.wallet.core.config.WalletNetwork;
import com.bloxbean.cardano.yano.wallet.core.service.HistoryPort;
import com.bloxbean.cardano.yano.wallet.core.service.NodeStatusPort;
import com.bloxbean.cardano.yano.wallet.core.service.WalletService;
import com.bloxbean.cardano.yano.wallet.core.tx.PendingTransaction;
import com.bloxbean.cardano.yano.wallet.core.tx.QuickAdaTxDraft;
import com.bloxbean.cardano.yano.wallet.core.wallet.StoredWallet;
import com.bloxbean.cardano.yano.wallet.core.vault.VaultSecondFactor;
import com.bloxbean.cardano.yano.wallet.hardware.fido.Fido2HmacSecret;
import com.bloxbean.cardano.yano.wallet.hardware.ledger.LedgerHardwareWalletService;
import com.bloxbean.cardano.yano.wallet.hardware.yubikey.YubiKeyChallengeResponse;
import com.bloxbean.cardano.yano.wallet.ui.contract.WalletUiController;
import com.bloxbean.cardano.yano.wallet.nodeclient.YanoNodeBackend;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;

/**
 * Production controller: adapts the async UI contract onto the shared
 * {@link WalletService} money path. All backend work runs on one background
 * executor; the FX thread never blocks.
 */
public class DefaultWalletUiController implements WalletUiController {
    private static final DateTimeFormatter TIME_FORMAT =
            DateTimeFormatter.ofPattern("dd MMM HH:mm").withZone(ZoneId.systemDefault());
    /** Account-discovery bounds (ADR-037): each probe is a node call, so stay modest. */
    private static final int MAX_DISCOVERED_ACCOUNTS = 20;
    private static final int DISCOVERY_GAP_LIMIT = 20;

    private final WalletBackendManager backendManager;
    private final ExecutorService executor =
            Executors.newSingleThreadExecutor(r -> {
                Thread thread = new Thread(r, "wallet-backend");
                thread.setDaemon(true);
                return thread;
            });
    private final Map<String, QuickAdaTxDraft> drafts = new ConcurrentHashMap<>();
    private final HardwareSendService hardwareSend = new HardwareSendService();
    private final HardwareStakeService hardwareStake = new HardwareStakeService();
    private final Map<String, HardwareSendService.Draft> hardwareDrafts = new ConcurrentHashMap<>();
    private final Map<String, HardwareStakeService.Draft> hardwareStakeDrafts = new ConcurrentHashMap<>();

    private volatile WalletService.Session session;
    private volatile WalletItem activeWallet;
    private volatile com.bloxbean.cardano.yano.wallet.connector.Cip30BridgeServer connectorServer;
    private volatile com.bloxbean.cardano.yano.wallet.connector.Cip30LocalSocketServer connectorSocketServer;
    private volatile Cip30AllowlistStore cip30Allowlist;
    private volatile TxEffectSummariser txEffectSummariser;
    private boolean connectorStarted;
    // Native Messaging (ADR-035 M5) is the default transport. The legacy
    // localhost WebSocket is opt-in (--enable-ws-connector): its origin is
    // self-asserted, so leaving it off means a dApp can reach the wallet only
    // over the browser-brokered native host.
    private volatile boolean wsConnectorEnabled;
    // Epoch length is network-constant; cache it after the first genesis read for
    // the live view's epoch ring. Reset on reconnect (may be a different network).
    private volatile long cachedEpochLength;

    public DefaultWalletUiController(WalletBackendManager backendManager) {
        this.backendManager = backendManager;
        // A reconnect rebuilds the backend; the current session is bound to the
        // old suppliers, so lock it rather than let it query a dead node.
        backendManager.setOnReconnect(this::lock);
    }

    /** Enables the legacy localhost WebSocket transport (default off). */
    public void setWsConnectorEnabled(boolean enabled) {
        this.wsConnectorEnabled = enabled;
    }

    // --- connection ---

    @Override
    public List<String> availableNetworks() {
        return java.util.Arrays.stream(WalletNetwork.values()).map(WalletNetwork::id).toList();
    }

    @Override
    public String defaultBaseUrl(String networkId) {
        return WalletNetwork.fromId(networkId).defaultBaseUrl();
    }

    @Override
    public boolean supportsManagedNode(String networkId) {
        // The wallet can launch a Yano node, not a yaci-store (ADR-038).
        return !WalletNetwork.fromId(networkId).blockfrostFlavor();
    }

    @Override
    public ConnectionInfo savedConnection() {
        return backendManager.savedConfig().map(config -> new ConnectionInfo(
                config.mode().name(), config.network().id(),
                config.isManaged() ? "(local managed node)" : config.externalBaseUrl(),
                config.isManaged())).orElse(null);
    }

    @Override
    public boolean isConnected() {
        return backendManager.isConnected();
    }

    @Override
    public CompletableFuture<ConnectionInfo> connectManaged(String networkId) {
        return async(() -> {
            var conn = backendManager.connect(com.bloxbean.cardano.yano.wallet.core.config
                    .WalletConnectionConfig.managed(WalletNetwork.fromId(networkId)));
            return new ConnectionInfo("MANAGED", conn.network().id(),
                    conn.backend().nodeClient().baseUrl(), true);
        });
    }

    @Override
    public CompletableFuture<ConnectionInfo> connectExternal(String networkId, String baseUrl) {
        return async(() -> {
            var conn = backendManager.connect(com.bloxbean.cardano.yano.wallet.core.config
                    .WalletConnectionConfig.external(WalletNetwork.fromId(networkId), baseUrl));
            return new ConnectionInfo("EXTERNAL", conn.network().id(),
                    conn.backend().nodeClient().baseUrl(), false);
        });
    }

    @Override
    public CompletableFuture<ConnectionInfo> reconnectSaved() {
        return async(() -> {
            var config = backendManager.savedConfig()
                    .orElseThrow(() -> new IllegalStateException("No saved connection"));
            var conn = backendManager.connect(config);
            return new ConnectionInfo(config.mode().name(), conn.network().id(),
                    conn.backend().nodeClient().baseUrl(), config.isManaged());
        });
    }

    @Override
    public void cancelConnect() {
        // Must run off BOTH the FX thread (close() waits for the process to die)
        // and the backend executor (which the connect being cancelled occupies),
        // so it goes to the common pool like the other node-introspection reads.
        io(() -> {
            backendManager.abortConnect();
            return null;
        });
    }

    @Override
    public boolean hasLocalChainstate(String networkId) {
        return backendManager.hasLocalChainstate(WalletNetwork.fromId(networkId));
    }

    private WalletBackendManager.ActiveConnection connection() {
        WalletBackendManager.ActiveConnection conn = backendManager.active();
        if (conn == null) {
            throw new IllegalStateException("Not connected to a node");
        }
        return conn;
    }

    private WalletService service() {
        return connection().service();
    }

    private com.bloxbean.cardano.yano.wallet.nodeclient.YanoNodePorts ports() {
        return connection().backend().ports();
    }

    @Override
    public String networkId() {
        WalletBackendManager.ActiveConnection conn = backendManager.active();
        return conn != null ? conn.network().id() : "";
    }

    @Override
    public String nodeBaseUrl() {
        WalletBackendManager.ActiveConnection conn = backendManager.active();
        return conn != null ? conn.backend().nodeClient().baseUrl() : "";
    }

    @Override
    public CompletableFuture<NodeStatusView> nodeStatus() {
        return async(() -> {
            try {
                NodeStatusPort.NodeView status = service().nodeStatus();
                return new NodeStatusView(status.slot(), status.blockNumber(), status.utxoIndexEnabled(),
                        status.utxoLagBlocks(), status.caughtUp(), true);
            } catch (RuntimeException e) {
                return new NodeStatusView(0, 0, false, 0, false, false);
            }
        });
    }

    @Override
    public CompletableFuture<NodeStartupView> nodeStartupStatus() {
        // Lock-free read on the common pool — NOT the single-thread backend
        // executor, which a long connectManaged() occupies for the whole sync.
        return io(() -> {
            var snapshot = backendManager.managedNodeStatus().orElse(null);
            if (snapshot == null) {
                return new NodeStartupView("PREPARING", "Preparing to start the node…",
                        0, false, false, null);
            }
            String state = snapshot.state();
            if ("FAILED".equals(state)) {
                String reason = snapshot.failureReason() != null
                        ? snapshot.failureReason() : "The node failed to start.";
                return new NodeStartupView("FAILED", reason, 0, false, true, reason);
            }
            if ("RUNNING".equals(state)) {
                return new NodeStartupView("READY", "Node is up — finishing the connection…",
                        0, true, false, null);
            }
            // STARTING / STOPPED: the process is spawning or booting its API. On a
            // public network the first start also builds the wallet index.
            return new NodeStartupView("STARTING",
                    "Starting the node and waiting for its API. On a public network the first start "
                            + "also builds the wallet index, which can take several minutes.",
                    0, false, false, null);
        });
    }

    @Override
    public CompletableFuture<List<String>> nodeLogTail(int maxLines) {
        return io(() -> backendManager.nodeLogTail(maxLines));
    }

    @Override
    public boolean managedNode() {
        return backendManager.isActiveManaged();
    }

    @Override
    public String dataDir() {
        return backendManager.dataDir().toString();
    }

    @Override
    public CompletableFuture<LiveChainView> liveChain() {
        return async(() -> {
            var conn = backendManager.active();
            if (conn == null) {
                return LiveChainView.unreachable();
            }
            long slot;
            long block;
            boolean synced;
            try {
                NodeStatusPort.NodeView status = conn.service().nodeStatus();
                slot = status.slot();
                block = status.blockNumber();
                synced = status.caughtUp();
            } catch (RuntimeException e) {
                return LiveChainView.unreachable();
            }
            long epoch = 0;
            long slotInEpoch = 0;
            int txCount = 0;
            long sizeBytes = 0;
            long age = 0;
            String hash = "";
            try {
                var latest = conn.backend().nodeClient().getLatestBlock();
                if (latest != null) {
                    epoch = latest.epoch();
                    slotInEpoch = latest.epochSlot();
                    txCount = latest.txCount();
                    sizeBytes = latest.sizeBytes();
                    if (latest.timeSeconds() > 0) {
                        age = Math.max(0, Instant.now().getEpochSecond() - latest.timeSeconds());
                    }
                    if (latest.hash() != null) {
                        hash = latest.hash();
                    }
                    if (block == 0) {
                        block = latest.height();
                    }
                    if (slot == 0) {
                        slot = latest.slot();
                    }
                }
            } catch (RuntimeException ignored) {
                // Best effort — the ticker still shows tip/slot from the status call.
            }
            long epochLength = cachedEpochLength;
            if (epochLength == 0) {
                try {
                    epochLength = conn.backend().nodeClient().getGenesis().epochLength();
                    cachedEpochLength = epochLength;
                } catch (RuntimeException ignored) {
                    // Genesis unavailable (e.g. a yaci-store backend) — the ring stays empty.
                }
            }
            long treasury = 0;
            long reserves = 0;
            long circulating = 0;
            long activeStake = 0;
            try {
                var network = conn.backend().nodeClient().getNetwork();
                if (network != null) {
                    treasury = network.treasury();
                    reserves = network.reserves();
                    // Circulating ≈ everything outside reserves and the treasury.
                    circulating = Math.max(0, network.total() - network.treasury());
                    activeStake = network.activeStake();
                }
            } catch (RuntimeException ignored) {
                // AdaPot tracking off / endpoint absent — the epoch panel just hides.
            }
            String shortHash = hash.length() > 12
                    ? hash.substring(0, 8) + "…" + hash.substring(hash.length() - 4) : hash;
            return new LiveChainView(block, slot, epoch, slotInEpoch, epochLength,
                    txCount, sizeBytes, age, shortHash,
                    treasury, reserves, circulating, activeStake, synced, true);
        });
    }

    @Override
    public CompletableFuture<List<WalletItem>> listWallets() {
        return async(() -> service().listWallets().stream().map(DefaultWalletUiController::toItem).toList());
    }

    @Override
    public CompletableFuture<CreatedWallet> createWallet(String name, char[] passphrase) {
        return async(() -> {
            var creation = service().createWallet(name, passphrase);
            return new CreatedWallet(toItem(creation.wallet()), creation.mnemonic());
        });
    }

    @Override
    public CompletableFuture<WalletItem> restoreWallet(String name, String mnemonic, char[] passphrase) {
        return async(() -> toItem(service().restoreWallet(name, mnemonic, passphrase)));
    }

    @Override
    public CompletableFuture<WalletItem> createAccount(String seedId, String name, char[] passphrase) {
        return async(() -> toItem(service().createAccount(seedId, name, passphrase)));
    }

    @Override
    public CompletableFuture<WalletItem> createAccountAt(String seedId, String name, char[] passphrase,
                                                         int accountIndex) {
        return async(() -> toItem(service().createAccountAt(seedId, name, passphrase, accountIndex)));
    }

    @Override
    public CompletableFuture<List<DiscoveredAccountView>> discoverAccounts(String seedId, char[] passphrase) {
        return async(() -> {
            var utxoSupplier = connection().backend().utxoSupplier();
            return service().repository().discoverAccounts(seedId, passphrase,
                            address -> utxoSupplier.isUsedAddress(new Address(address)),
                            MAX_DISCOVERED_ACCOUNTS, DISCOVERY_GAP_LIMIT).stream()
                    .map(found -> new DiscoveredAccountView(found.accountIndex(), found.baseAddress()))
                    .toList();
        });
    }

    @Override
    public CompletableFuture<List<String>> listHardwareDevices() {
        return async(() -> {
            try {
                return new LedgerHardwareWalletService().enumerate().stream()
                        .map(com.bloxbean.cardano.yano.wallet.core.hardware.HardwareDevice::displayName)
                        .toList();
            } catch (RuntimeException e) {
                return List.<String>of();
            }
        });
    }

    @Override
    public CompletableFuture<WalletItem> importHardwareWallet(String name, int accountIndex) {
        return async(() -> {
            var hardware = new LedgerHardwareWalletService();
            var devices = hardware.enumerate();
            if (devices.isEmpty()) {
                throw new IllegalStateException(
                        "No Ledger connected. Unlock it and open the Cardano app, then try again.");
            }
            var keystore = hardware.importAccount(devices.get(0), accountIndex);
            StoredWallet profile = service().repository().addWatchOnlyWallet(
                    name, keystore.type().name(), accountIndex, keystore.accountXpubHex());
            return toItem(profile);
        });
    }

    @Override
    public CompletableFuture<WalletItem> importHardwareAccount(String seedId, String name, int accountIndex) {
        return async(() -> {
            var hardware = new LedgerHardwareWalletService();
            var devices = hardware.enumerate();
            if (devices.isEmpty()) {
                throw new IllegalStateException(
                        "No Ledger connected. Unlock it and open the Cardano app, then try again.");
            }
            var device = devices.get(0);

            // Confirm this is the group's device before binding an account to it:
            // re-derive the group's first account and compare public keys. Without
            // this, plugging in a different Ledger would nest its account under
            // this wallet, and it could never sign for it.
            StoredWallet known = service().repository().listAccounts(seedId).stream()
                    .min(java.util.Comparator.comparingInt(StoredWallet::accountIndex))
                    .orElseThrow(() -> new IllegalStateException("Wallet not found: " + seedId));
            var check = hardware.importAccount(device, known.accountIndex());
            if (!check.accountXpubHex().equalsIgnoreCase(known.accountXpubHex())) {
                throw new IllegalStateException("This is a different device than the one "
                        + known.name() + " was imported from. Connect that device and try again.");
            }

            var keystore = hardware.importAccount(device, accountIndex);
            StoredWallet profile = service().repository().addWatchOnlyAccount(
                    seedId, name, accountIndex, keystore.accountXpubHex());
            return toItem(profile);
        });
    }

    @Override
    public CompletableFuture<WalletItem> unlockHardware(String walletId) {
        return async(() -> {
            WalletService.Session unlocked = service().unlockWatchOnly(walletId);
            session = unlocked;
            WalletItem item = toItem(unlocked.profile());
            activeWallet = item;
            return item;
        });
    }

    @Override
    public CompletableFuture<WalletItem> unlock(String walletId, char[] passphrase) {
        return async(() -> {
            WalletService.Session unlocked = service().unlock(walletId, passphrase);
            session = unlocked;
            WalletItem item = toItem(unlocked.profile());
            activeWallet = item;
            return item;
        });
    }

    // --- security key (ADR-036) ---

    private static final String KIND_YUBIKEY = "yubikey";
    private static final String KIND_FIDO2 = "fido2";

    @Override
    public CompletableFuture<SecurityKeyView> securityKey(String walletId) {
        return async(() -> {
            List<VaultSecondFactor.FactorDescriptor> factors = service().repository().walletFactors(walletId);
            if (factors.isEmpty()) {
                return new SecurityKeyView(false, "Not protected by a security key", 0, false, false);
            }
            VaultSecondFactor.FactorDescriptor first = factors.get(0);
            String kindLabel = VaultSecondFactor.FactorDescriptor.YUBIKEY_HMAC_SHA1.equals(first.type())
                    ? "YubiKey" : "FIDO2 security key";
            boolean requiresPin = factors.stream().anyMatch(VaultSecondFactor.FactorDescriptor::requireUv);
            boolean passwordless = service().repository().walletPasswordless(walletId);
            int backups = factors.size() - 1;
            String label = "Protected by " + kindLabel
                    + (passwordless ? " (no passphrase)" : "")
                    + (backups > 0 ? " · +" + backups + " backup key" + (backups > 1 ? "s" : "") : "");
            return new SecurityKeyView(true, label, factors.size(), requiresPin, passwordless);
        });
    }

    @Override
    public CompletableFuture<WalletItem> unlockWithSecurityKey(String walletId, char[] passphrase,
            java.util.function.Supplier<char[]> pinProvider, Runnable onTouch) {
        return async(() -> {
            List<VaultSecondFactor.FactorDescriptor> factors = service().repository().walletFactors(walletId);
            if (factors.isEmpty()) {
                throw new IllegalStateException("This wallet is not protected by a security key");
            }
            VaultSecondFactor factor = buildFactor(kindOf(factors.get(0).type()), pinProvider, onTouch);
            WalletService.Session unlocked = service().unlock(walletId, passphrase, factor);
            session = unlocked;
            WalletItem item = toItem(unlocked.profile());
            activeWallet = item;
            return item;
        });
    }

    @Override
    public CompletableFuture<Void> enrollSecurityKey(String walletId, char[] passphrase, String kind,
            int yubikeySlot, boolean passwordless,
            java.util.function.Supplier<char[]> pinProvider, Runnable onTouch) {
        return async(() -> {
            VaultSecondFactor factor = buildFactor(kind, pinProvider, onTouch);
            VaultSecondFactor.FactorDescriptor descriptor = descriptorFor(kind, yubikeySlot, factor);
            service().repository().enrollFactor(walletId, passphrase, descriptor, factor, passwordless);
            return null;
        });
    }

    @Override
    public CompletableFuture<Void> addBackupSecurityKey(String walletId, char[] passphrase, String kind,
            int yubikeySlot, java.util.function.Supplier<char[]> pinProvider, Runnable onTouch) {
        return async(() -> {
            List<VaultSecondFactor.FactorDescriptor> factors = service().repository().walletFactors(walletId);
            if (factors.isEmpty()) {
                throw new IllegalStateException("Protect the wallet with a security key first");
            }
            VaultSecondFactor unlockFactor = buildFactor(kindOf(factors.get(0).type()), pinProvider, onTouch);
            VaultSecondFactor newFactor = buildFactor(kind, pinProvider, onTouch);
            VaultSecondFactor.FactorDescriptor newDescriptor = descriptorFor(kind, yubikeySlot, newFactor);
            service().repository().addFactor(walletId, passphrase, unlockFactor, newDescriptor, newFactor);
            return null;
        });
    }

    @Override
    public CompletableFuture<Void> removeSecurityKey(String walletId, char[] passphrase,
            java.util.function.Supplier<char[]> pinProvider, Runnable onTouch) {
        return async(() -> {
            List<VaultSecondFactor.FactorDescriptor> factors = service().repository().walletFactors(walletId);
            if (!factors.isEmpty()) {
                VaultSecondFactor factor = buildFactor(kindOf(factors.get(0).type()), pinProvider, onTouch);
                service().repository().removeFactor(walletId, passphrase, factor);
            }
            return null;
        });
    }

    @Override
    public CompletableFuture<Void> setFido2Pin(char[] newPin, Runnable onTouch) {
        return async(() -> {
            Fido2HmacSecret.setPin(newPin, onTouch);
            return null;
        });
    }

    private static VaultSecondFactor buildFactor(String kind, java.util.function.Supplier<char[]> pinProvider,
                                                 Runnable onTouch) {
        return switch (kind) {
            case KIND_YUBIKEY -> new YubiKeyChallengeResponse();
            case KIND_FIDO2 -> new Fido2HmacSecret(Fido2HmacSecret.DEFAULT_RP_ID, pinProvider, onTouch);
            default -> throw new IllegalArgumentException("Unknown security key kind: " + kind);
        };
    }

    /** For FIDO2 the descriptor comes from enrolling a credential; for YubiKey it's the slot. */
    private static VaultSecondFactor.FactorDescriptor descriptorFor(String kind, int yubikeySlot,
                                                                    VaultSecondFactor factor) {
        if (KIND_FIDO2.equals(kind)) {
            return ((Fido2HmacSecret) factor).enroll();
        }
        return VaultSecondFactor.FactorDescriptor.yubikey(yubikeySlot);
    }

    private static String kindOf(String factorType) {
        return VaultSecondFactor.FactorDescriptor.FIDO2_HMAC_SECRET.equals(factorType) ? KIND_FIDO2 : KIND_YUBIKEY;
    }

    @Override
    public void lock() {
        session = null;
        activeWallet = null;
        cachedEpochLength = 0;
        drafts.clear();
        hardwareDrafts.clear();
        hardwareStakeDrafts.clear();
    }

    @Override
    public WalletItem activeWallet() {
        return activeWallet;
    }

    @Override
    public synchronized void startDappConnector(
            com.bloxbean.cardano.yano.wallet.ui.contract.Cip30Prompt prompt) {
        if (connectorStarted) {
            return;
        }
        connectorStarted = true; // set before the try so a partial failure doesn't re-bind on retry
        try {
            cip30Allowlist = new Cip30AllowlistStore(backendManager.dataDir());
            var wallet = new WalletCip30Wallet(backendManager, () -> session);
            var approvals = new Cip30ApprovalGate(cip30Allowlist, prompt, summariser());
            // Default transport (ADR-035 M5): the browser-launched proxy relays
            // to this socket — no localhost port, and Chrome vouches for the
            // extension id. Best-effort; a bind failure never breaks the wallet.
            try {
                var socketServer = new com.bloxbean.cardano.yano.wallet.connector.Cip30LocalSocketServer(
                        new NativeMessagingInstaller().socketPath(), wallet, approvals);
                socketServer.start();
                connectorSocketServer = socketServer;
            } catch (Exception e) {
                System.err.println("CIP-30 native-messaging socket could not start: " + e.getMessage());
            }
            // Legacy localhost WebSocket — opt-in only (--enable-ws-connector),
            // because its origin is self-asserted (ADR-035 transport decision).
            if (wsConnectorEnabled) {
                var server = new com.bloxbean.cardano.yano.wallet.connector.Cip30BridgeServer(wallet, approvals);
                server.start();
                connectorServer = server;
                System.err.println("CIP-30 legacy WebSocket connector ENABLED on 127.0.0.1"
                        + " (--enable-ws-connector); Native Messaging is preferred.");
            }
        } catch (RuntimeException e) {
            // A bind failure (e.g. the port is taken) must never break the wallet.
            System.err.println("CIP-30 dApp connector could not start: " + e.getMessage());
        }
    }

    @Override
    public synchronized void stopDappConnector() {
        if (connectorServer != null) {
            connectorServer.stop();
            connectorServer = null;
        }
        if (connectorSocketServer != null) {
            connectorSocketServer.stop();
            connectorSocketServer = null;
        }
        if (txEffectSummariser != null) {
            txEffectSummariser.close();
            txEffectSummariser = null;
        }
        connectorStarted = false;
    }

    @Override
    public CompletableFuture<String> installNativeMessagingHost() {
        return async(() -> {
            try {
                return new NativeMessagingInstaller().install();
            } catch (java.io.IOException e) {
                throw new IllegalStateException(e.getMessage(), e);
            }
        });
    }

    @Override
    public CompletableFuture<List<String>> connectedDapps() {
        return async(() -> {
            Cip30AllowlistStore allowlist = cip30Allowlist;
            return allowlist == null ? List.<String>of()
                    : allowlist.all().stream().sorted().toList();
        });
    }

    @Override
    public CompletableFuture<Void> forgetDapp(String origin) {
        return async(() -> {
            Cip30AllowlistStore allowlist = cip30Allowlist;
            if (allowlist != null) {
                allowlist.revoke(origin);
            }
            return null;
        });
    }

    @Override
    public CompletableFuture<BalanceView> balance() {
        return async(() -> {
            var balance = requireSession().balance();
            return new BalanceView(
                    ada(balance.lovelace()),
                    balance.lovelace().toString(),
                    balance.utxoCount(),
                    balance.addressCount(),
                    balance.assets().stream()
                            .map(asset -> new AssetItem(asset.unit(), asset.quantity().toString()))
                            .toList());
        });
    }

    @Override
    public CompletableFuture<List<AddressItem>> addresses(int count) {
        return async(() -> requireSession().addresses(count).receiveAddresses().stream()
                .map(address -> new AddressItem(address.addressIndex(), address.baseAddress(),
                        address.derivationPath()))
                .toList());
    }

    @Override
    public CompletableFuture<List<TxItem>> history(int page, int count) {
        return async(() -> {
            WalletService.Session active = requireSession();
            StoredWallet profile = active.profile();
            var conn = backendManager.active();
            WalletNetwork network = conn != null ? conn.network() : null;

            // Node's confirmed history first — it is authoritative. A tx here
            // wins over a stale local pending record of the same hash.
            List<TxItem> nodeItems = new ArrayList<>();
            Set<String> nodeHashes = new LinkedHashSet<>();
            for (HistoryPort.TxRef tx : ports().walletTransactions(
                    profile.stakeAddress(), profile.baseAddress(), page, count, true)) {
                nodeHashes.add(tx.txHash());
                nodeItems.add(new TxItem(tx.txHash(), tx.blockHeight(),
                        TIME_FORMAT.format(Instant.ofEpochSecond(tx.blockTime())),
                        "confirmed", null, null, explorerUrl(network, tx.txHash())));
            }

            List<TxItem> items = new ArrayList<>();
            if (page == 1) {
                // Page 1: prepend local submissions the node's history can't see
                // yet. The node's account-history (nodeHashes) is the single source
                // of "confirmed", so a local record shows as pending until it lands
                // there — regardless of the confirmation tracker's own flag, which
                // can run ahead of history and would otherwise make a just-submitted
                // tx vanish until the block indexes. Once history has it, drop the
                // local record.
                for (PendingTransaction pending : service().pendingTransactions(
                        profile.id(), profile.networkId())) {
                    if (nodeHashes.contains(pending.txHash())) {
                        service().forgetPending(pending.txHash());
                        continue;
                    }
                    boolean failed = "FAILED".equals(pending.status().name());
                    items.add(new TxItem(pending.txHash(), 0,
                            TIME_FORMAT.format(Instant.ofEpochMilli(pending.createdAtEpochMillis())),
                            failed ? "failed" : "pending",
                            "₳ " + ada(pending.lovelace()), "sent",
                            explorerUrl(network, pending.txHash())));
                }
            }
            items.addAll(nodeItems);
            return items;
        });
    }

    private static String explorerUrl(WalletNetwork network, String txHash) {
        return network == null ? null : network.explorerTxUrl(txHash);
    }

    @Override
    public CompletableFuture<List<RewardItem>> rewards(int page, int count) {
        return async(() -> ports().rewards(requireSession().profile().stakeAddress(), page, count)
                .stream()
                .map(reward -> new RewardItem(reward.epoch(), ada(reward.amount()),
                        reward.poolId(), blockfrostType(reward.type())))
                .toList());
    }

    @Override
    public CompletableFuture<StakingView> staking() {
        return async(() -> {
            StoredWallet profile = requireSession().profile();
            NodeStatusPort.AccountView account = ports().accountInfo(profile.stakeAddress());
            return new StakingView(profile.stakeAddress(), account.registered(),
                    account.delegatedPoolId(), ada(account.withdrawable()));
        });
    }

    @Override
    public CompletableFuture<DraftView> draftSend(String toAddress, String unit, String amount, String memo) {
        return async(() -> {
            WalletItem active = activeWallet;
            if (active != null && active.hardware()) {
                return draftHardwarePayment(toAddress, unit, amount, memo);
            }
            String normalizedMemo = memo == null || memo.isBlank() ? null : memo.trim();
            boolean isAda = unit == null || unit.isBlank() || "lovelace".equalsIgnoreCase(unit) || "ada".equalsIgnoreCase(unit);
            QuickAdaTxDraft draft;
            String amountText;
            if (isAda) {
                BigInteger lovelace;
                try {
                    lovelace = new BigDecimal(amount.trim()).movePointRight(6).toBigIntegerExact();
                } catch (NumberFormatException | ArithmeticException e) {
                    throw new IllegalArgumentException("Invalid ADA amount (max 6 decimal places): " + amount);
                }
                if (lovelace.signum() <= 0) {
                    throw new IllegalArgumentException("Amount must be greater than zero");
                }
                draft = requireSession().draftPayment(toAddress.trim(), lovelace, List.of(), normalizedMemo);
                amountText = "₳ " + ada(lovelace);
            } else {
                // Native asset: integer quantity; the node/CCL attaches the
                // required min-ADA to the output automatically.
                BigInteger quantity;
                try {
                    quantity = new BigInteger(amount.trim());
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException("Invalid asset quantity (must be a whole number): " + amount);
                }
                if (quantity.signum() <= 0) {
                    throw new IllegalArgumentException("Quantity must be greater than zero");
                }
                var assetAmount = com.bloxbean.cardano.client.api.model.Amount.asset(unit, quantity);
                draft = requireSession().draftPayment(toAddress.trim(), BigInteger.ZERO,
                        List.of(assetAmount), normalizedMemo);
                amountText = quantity + " " + assetLabel(unit);
            }
            // The built tx's lovelace output covers the payment (or the
            // auto-attached min-ADA); total = that output + fee.
            String totalAda = ada(draft.lovelace().add(draft.fee()));
            return cacheDraft(draft, "Payment", draft.toAddress(), amountText, ada(draft.fee()), totalAda);
        });
    }

    private DraftView draftHardwarePayment(String toAddress, String unit, String amount, String memo) {
        boolean isAda = unit == null || unit.isBlank() || "lovelace".equalsIgnoreCase(unit) || "ada".equalsIgnoreCase(unit);
        StoredWallet profile = requireSession().profile();
        var connection = backendManager.active();
        if (!isAda) {
            BigInteger quantity;
            try {
                quantity = new BigInteger(amount.trim());
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("Invalid asset quantity (must be a whole number): " + amount);
            }
            if (quantity.signum() <= 0) {
                throw new IllegalArgumentException("Quantity must be greater than zero");
            }
            HardwareSendService.Draft tokenDraft = hardwareSend.buildTokenPayment(
                    connection.backend(), connection.network(), profile, toAddress.trim(), unit, quantity, memo);
            hardwareDrafts.put(tokenDraft.txHash(), tokenDraft);
            return new DraftView(tokenDraft.txHash(), "Payment", tokenDraft.toAddress(),
                    quantity + " " + assetLabel(unit), ada(tokenDraft.fee()),
                    ada(tokenDraft.fee().add(BigInteger.valueOf(1_500_000))));
        }
        BigInteger lovelace;
        try {
            lovelace = new BigDecimal(amount.trim()).movePointRight(6).toBigIntegerExact();
        } catch (NumberFormatException | ArithmeticException e) {
            throw new IllegalArgumentException("Invalid ADA amount (max 6 decimal places): " + amount);
        }
        if (lovelace.signum() <= 0) {
            throw new IllegalArgumentException("Amount must be greater than zero");
        }
        HardwareSendService.Draft draft = hardwareSend.buildPayment(
                connection.backend(), connection.network(), profile, toAddress.trim(), lovelace, memo);
        hardwareDrafts.put(draft.txHash(), draft);
        return new DraftView(draft.txHash(), "Payment", draft.toAddress(),
                "₳ " + ada(lovelace), ada(draft.fee()), ada(lovelace.add(draft.fee())));
    }

    private DraftView draftHardwareDelegation(String poolId) {
        StoredWallet profile = requireSession().profile();
        boolean registered = ports().accountInfo(profile.stakeAddress()).registered();
        var connection = backendManager.active();
        HardwareStakeService.Draft draft = hardwareStake.buildDelegation(
                connection.backend(), connection.network(), profile, poolId, registered);
        hardwareStakeDrafts.put(draft.txHash(), draft);
        String total = registered ? ada(draft.fee())
                : ada(draft.fee().add(BigInteger.valueOf(2_000_000)));
        return new DraftView(draft.txHash(), "Delegation", poolId, draft.summary(), ada(draft.fee()), total);
    }

    private DraftView draftHardwareWithdrawal() {
        StoredWallet profile = requireSession().profile();
        BigInteger withdrawable = ports().accountInfo(profile.stakeAddress()).withdrawable();
        if (withdrawable == null || withdrawable.signum() <= 0) {
            throw new IllegalStateException("No rewards available to withdraw");
        }
        var connection = backendManager.active();
        HardwareStakeService.Draft draft = hardwareStake.buildWithdrawal(
                connection.backend(), connection.network(), profile, withdrawable);
        hardwareStakeDrafts.put(draft.txHash(), draft);
        return new DraftView(draft.txHash(), "Withdrawal", "rewards",
                "₳ " + ada(withdrawable), ada(draft.fee()), ada(draft.fee()));
    }

    private static String assetLabel(String unit) {
        // policyId (56 hex) + assetName (hex) → show the decoded asset name when printable.
        if (unit.length() > 56) {
            String nameHex = unit.substring(56);
            try {
                byte[] bytes = new byte[nameHex.length() / 2];
                for (int i = 0; i < bytes.length; i++) {
                    bytes[i] = (byte) Integer.parseInt(nameHex.substring(i * 2, i * 2 + 2), 16);
                }
                String name = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
                if (name.chars().allMatch(c -> c >= 0x20 && c < 0x7f)) {
                    return name;
                }
            } catch (RuntimeException ignored) {
                // fall through to the short unit
            }
        }
        return unit.length() > 12 ? unit.substring(0, 12) + "…" : unit;
    }

    @Override
    public CompletableFuture<DraftView> draftDelegation(String poolId) {
        return async(() -> {
            WalletItem active = activeWallet;
            if (active != null && active.hardware()) {
                return draftHardwareDelegation(poolId.trim());
            }
            String stake = requireSession().profile().stakeAddress();
            boolean registered = ports().accountInfo(stake).registered();
            QuickAdaTxDraft draft = requireSession().draftDelegation(poolId.trim());
            // First delegation also registers the stake address, locking a
            // refundable ~2 ₳ deposit — surface it so the total isn't understated.
            String amountLabel = registered
                    ? "delegation only"
                    : "+ ₳2 stake deposit (refundable)";
            String total = registered ? ada(draft.fee()) : ada(draft.fee().add(BigInteger.valueOf(2_000_000)));
            return cacheDraft(draft, "Delegation", poolId.trim(), amountLabel, ada(draft.fee()), total);
        });
    }

    @Override
    public CompletableFuture<DraftView> draftWithdrawal() {
        return async(() -> {
            WalletItem active = activeWallet;
            if (active != null && active.hardware()) {
                return draftHardwareWithdrawal();
            }
            String stake = requireSession().profile().stakeAddress();
            BigInteger withdrawable = ports().accountInfo(stake).withdrawable();
            QuickAdaTxDraft draft = requireSession().draftWithdrawal();
            return cacheDraft(draft, "Withdrawal", "rewards → your wallet",
                    "₳ " + ada(withdrawable) + " rewards", ada(draft.fee()), ada(draft.fee()));
        });
    }

    @Override
    public CompletableFuture<DraftView> draftVoteDelegation(String target) {
        return async(() -> {
            DRep drep = parseDRep(target);
            String label = voteTargetLabel(target, drep);
            WalletItem active = activeWallet;
            if (active != null && active.hardware()) {
                return draftHardwareVoteDelegation(drep, label);
            }
            String stake = requireSession().profile().stakeAddress();
            boolean registered = ports().accountInfo(stake).registered();
            QuickAdaTxDraft draft = requireSession().draftVoteDelegation(drep, label);
            String amountLabel = registered
                    ? "vote delegation only"
                    : "+ ₳2 stake deposit (refundable)";
            String total = registered ? ada(draft.fee()) : ada(draft.fee().add(BigInteger.valueOf(2_000_000)));
            return cacheDraft(draft, "Vote delegation", label, amountLabel, ada(draft.fee()), total);
        });
    }

    private DraftView draftHardwareVoteDelegation(DRep drep, String label) {
        StoredWallet profile = requireSession().profile();
        boolean registered = ports().accountInfo(profile.stakeAddress()).registered();
        var connection = backendManager.active();
        HardwareStakeService.Draft draft = hardwareStake.buildVoteDelegation(
                connection.backend(), connection.network(), profile, drep, registered);
        hardwareStakeDrafts.put(draft.txHash(), draft);
        String total = registered ? ada(draft.fee())
                : ada(draft.fee().add(BigInteger.valueOf(2_000_000)));
        return new DraftView(draft.txHash(), "Vote delegation", label, draft.summary(), ada(draft.fee()), total);
    }

    /** Parses a UI vote target into a CCL DRep: sentinels, or a DRep id (CIP-129, falling back to legacy CIP-105). */
    static DRep parseDRep(String target) {
        String t = target == null ? "" : target.trim();
        if (WalletUiController.VOTE_ABSTAIN.equalsIgnoreCase(t)) {
            return DRep.abstain();
        }
        if (WalletUiController.VOTE_NO_CONFIDENCE.equalsIgnoreCase(t)) {
            return DRep.noConfidence();
        }
        if (t.isEmpty()) {
            throw new IllegalArgumentException("Enter a DRep id, or choose abstain / no confidence");
        }
        try {
            return GovId.toDrep(t); // CIP-129 (drep id with a credential-type header byte)
        } catch (RuntimeException cip129Failed) {
            try {
                return LegacyDRepId.toDrep(t, DRepType.ADDR_KEYHASH); // CIP-105 legacy (assume key-hash)
            } catch (RuntimeException legacyFailed) {
                throw new IllegalArgumentException("Not a valid DRep id: " + t);
            }
        }
    }

    private static String voteTargetLabel(String target, DRep drep) {
        return switch (drep.getType()) {
            case ABSTAIN -> "abstain";
            case NO_CONFIDENCE -> "no confidence";
            default -> target.trim();
        };
    }

    @Override
    public CompletableFuture<List<ProposalView>> listProposals() {
        return async(() -> {
            var backend = backendManager.active().backend();
            List<ProposalView> views = new ArrayList<>();
            for (var p : backend.nodeClient().listActiveProposals()) {
                views.add(new ProposalView(p.id(), p.txHash(), p.certIndex(),
                        p.governanceType(), p.status(), p.expiresAfterEpoch()));
            }
            return views;
        });
    }

    @Override
    public CompletableFuture<DraftView> draftDRepRegistration(String anchorUrl, String anchorHashHex) {
        return async(() -> {
            byte[] anchorHash = parseAnchorHash(anchorUrl, anchorHashHex);
            String url = (anchorUrl == null || anchorUrl.isBlank()) ? null : anchorUrl.trim();
            WalletItem active = activeWallet;
            if (active != null && active.hardware()) {
                StoredWallet profile = requireSession().profile();
                var connection = backendManager.active();
                HardwareStakeService.Draft draft = hardwareStake.buildDRepRegistration(
                        connection.backend(), connection.network(), profile, url, anchorHash);
                hardwareStakeDrafts.put(draft.txHash(), draft);
                String total = ada(draft.fee().add(BigInteger.valueOf(500_000_000)));
                return new DraftView(draft.txHash(), "DRep registration", "become a DRep",
                        draft.summary(), ada(draft.fee()), total);
            }
            QuickAdaTxDraft draft = requireSession().draftDRepRegistration(url, anchorHash);
            String total = ada(draft.fee().add(BigInteger.valueOf(500_000_000)));
            return cacheDraft(draft, "DRep registration", "become a DRep",
                    "+ ₳500 deposit (refundable)", ada(draft.fee()), total);
        });
    }

    @Override
    public CompletableFuture<DraftView> draftVote(String proposalTxHash, int certIndex, String choice,
                                                  String anchorUrl, String anchorHashHex) {
        return async(() -> {
            Vote vote = parseVote(choice);
            byte[] anchorHash = parseAnchorHash(anchorUrl, anchorHashHex);
            String url = (anchorUrl == null || anchorUrl.isBlank()) ? null : anchorUrl.trim();
            String label = vote + " on " + shortProposal(proposalTxHash, certIndex);
            WalletItem active = activeWallet;
            if (active != null && active.hardware()) {
                StoredWallet profile = requireSession().profile();
                var connection = backendManager.active();
                HardwareStakeService.Draft draft = hardwareStake.buildVote(
                        connection.backend(), connection.network(), profile,
                        proposalTxHash, certIndex, vote, url, anchorHash);
                hardwareStakeDrafts.put(draft.txHash(), draft);
                return new DraftView(draft.txHash(), "Vote", label, draft.summary(),
                        ada(draft.fee()), ada(draft.fee()));
            }
            QuickAdaTxDraft draft = requireSession().draftVote(proposalTxHash, certIndex, vote, url, anchorHash);
            return cacheDraft(draft, "Vote", label, "vote only", ada(draft.fee()), ada(draft.fee()));
        });
    }

    private static Vote parseVote(String choice) {
        return switch (choice) {
            case WalletUiController.VOTE_YES -> Vote.YES;
            case WalletUiController.VOTE_NO -> Vote.NO;
            case WalletUiController.VOTE_ABSTAIN_VOTE -> Vote.ABSTAIN;
            default -> throw new IllegalArgumentException("Unknown vote choice: " + choice);
        };
    }

    private static byte[] parseAnchorHash(String anchorUrl, String anchorHashHex) {
        if (anchorUrl == null || anchorUrl.isBlank()) {
            return null;
        }
        if (anchorHashHex == null || anchorHashHex.isBlank()) {
            throw new IllegalArgumentException("A rationale URL also needs its 32-byte hash (CIP-100 metadata hash)");
        }
        byte[] hash = HexUtil.decodeHexString(anchorHashHex.trim());
        if (hash.length != 32) {
            throw new IllegalArgumentException("Anchor hash must be 32 bytes (64 hex chars)");
        }
        return hash;
    }

    private static String shortProposal(String txHash, int certIndex) {
        String h = txHash != null && txHash.length() > 12 ? txHash.substring(0, 12) + "…" : txHash;
        return h + "#" + certIndex;
    }

    @Override
    public CompletableFuture<GovernanceStatusView> governanceStatus() {
        return async(() -> {
            StoredWallet profile = requireSession().profile();
            var backend = backendManager.active().backend();
            String myDrepId = ownDrepId(profile);
            var drepInfo = backend.nodeClient().getDRepInfo(myDrepId); // null → not registered
            NodeStatusPort.AccountView account = ports().accountInfo(profile.stakeAddress());

            boolean registered = drepInfo != null && !drepInfo.retired();
            String statusText;
            String depositAda = null;
            if (drepInfo == null) {
                statusText = "Not registered as a DRep";
            } else if (drepInfo.retired()) {
                statusText = "DRep retired";
            } else {
                depositAda = ada(drepInfo.deposit());
                statusText = "Registered DRep since epoch " + drepInfo.registeredEpoch()
                        + "  ·  ₳" + depositAda + " deposit locked"
                        + (drepInfo.expired() ? "  ·  inactive (vote to reactivate)" : "");
            }
            return new GovernanceStatusView(registered, myDrepId, statusText,
                    describeVoteDelegation(account), depositAda);
        });
    }

    @Override
    public CompletableFuture<DraftView> draftDRepDeregistration() {
        return async(() -> {
            StoredWallet profile = requireSession().profile();
            var connection = backendManager.active();
            var drepInfo = connection.backend().nodeClient().getDRepInfo(ownDrepId(profile));
            if (drepInfo == null || drepInfo.retired()) {
                throw new IllegalStateException("This wallet is not a registered DRep.");
            }
            BigInteger deposit = drepInfo.deposit();
            WalletItem active = activeWallet;
            if (active != null && active.hardware()) {
                HardwareStakeService.Draft draft = hardwareStake.buildDRepDeregistration(
                        connection.backend(), connection.network(), profile, deposit);
                hardwareStakeDrafts.put(draft.txHash(), draft);
                return new DraftView(draft.txHash(), "Unregister DRep", "reclaim ₳" + ada(deposit),
                        "deposit refunded", ada(draft.fee()), ada(draft.fee()));
            }
            QuickAdaTxDraft draft = requireSession().draftDRepDeregistration(deposit);
            return cacheDraft(draft, "Unregister DRep", "reclaim ₳" + ada(deposit),
                    "deposit refunded", ada(draft.fee()), ada(draft.fee()));
        });
    }

    /** This wallet's own CIP-129 DRep id: stored for software wallets, derived from the xpub for hardware. */
    private static String ownDrepId(StoredWallet profile) {
        if (profile.drepId() != null && !profile.drepId().isBlank()) {
            return profile.drepId(); // CCL Account.drepId() is CIP-129
        }
        byte[] xpub = HexUtil.decodeHexString(profile.accountXpubHex());
        byte[] drepKeyHash = new CIP1852().getPublicKeyFromAccountPubKey(xpub, 3, 0).getKeyHash();
        return GovId.drepFromKeyHash(drepKeyHash);
    }

    private static String describeVoteDelegation(NodeStatusPort.AccountView account) {
        String type = account.drepType();
        if (type == null) {
            return "Not delegated";
        }
        return switch (type) {
            case "abstain" -> "Always abstain";
            case "no_confidence" -> "No confidence";
            default -> {
                String id = account.drepId();
                yield "Delegated to " + (id != null && id.length() > 20 ? id.substring(0, 20) + "…" : id);
            }
        };
    }

    /**
     * The shared simulator, created on first use. The dApp connector and the
     * wallet's own confirmations both go through it, so a wallet that never
     * started the connector can still simulate its own transactions.
     */
    private synchronized TxEffectSummariser summariser() {
        if (txEffectSummariser == null) {
            txEffectSummariser = new TxEffectSummariser(backendManager, () -> session);
        }
        return txEffectSummariser;
    }

    @Override
    public CompletableFuture<com.bloxbean.cardano.yano.wallet.ui.contract.TxEffectView> simulateDraft(
            String draftId) {
        return async(() -> {
            String cborHex = draftCborHex(draftId);
            if (cborHex == null) {
                return TxEffectSummariser.degraded("",
                        "This transaction is no longer available to check.");
            }
            return summariser().summarise(cborHex);
        });
    }

    /**
     * The CBOR of a drafted transaction, whichever kind it is. Hardware drafts
     * hold a body rather than bytes, so it is re-serialised with an empty witness
     * set — the effect depends only on the body, and no signature exists yet.
     */
    private String draftCborHex(String draftId) {
        QuickAdaTxDraft draft = drafts.get(draftId);
        if (draft != null) {
            return draft.cborHex();
        }
        HardwareSendService.Draft send = hardwareDrafts.get(draftId);
        if (send != null) {
            return bodyHex(send.body());
        }
        HardwareStakeService.Draft stake = hardwareStakeDrafts.get(draftId);
        if (stake != null) {
            return bodyHex(stake.body());
        }
        return null;
    }

    private static String bodyHex(com.bloxbean.cardano.client.transaction.spec.TransactionBody body) {
        if (body == null) {
            return null;
        }
        try {
            var tx = com.bloxbean.cardano.client.transaction.spec.Transaction.builder()
                    .body(body)
                    .witnessSet(new com.bloxbean.cardano.client.transaction.spec.TransactionWitnessSet())
                    .build();
            return com.bloxbean.cardano.client.util.HexUtil.encodeHexString(tx.serialize());
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    public CompletableFuture<SubmitView> confirmDraft(String draftId) {
        return async(() -> {
            HardwareSendService.Draft hardwareDraft = hardwareDrafts.get(draftId);
            if (hardwareDraft != null) {
                var connection = backendManager.active();
                String hwTxHash = hardwareSend.signAndSubmit(
                        connection.backend(), connection.network(), hardwareDraft);
                hardwareDrafts.remove(draftId);
                service().recordSubmittedPayment(hardwareDraft.profile(), hwTxHash,
                        hardwareDraft.amount(), hardwareDraft.fee(), hardwareDraft.toAddress(),
                        hardwareDraft.ttl());
                service().trackConfirmation(hwTxHash, 120);
                return new SubmitView(hwTxHash, false);
            }
            HardwareStakeService.Draft stakeDraft = hardwareStakeDrafts.get(draftId);
            if (stakeDraft != null) {
                var connection = backendManager.active();
                String hwTxHash = hardwareStake.signAndSubmit(
                        connection.backend(), connection.network(), stakeDraft);
                hardwareStakeDrafts.remove(draftId);
                service().recordSubmittedPayment(stakeDraft.profile(), hwTxHash,
                        BigInteger.ZERO, stakeDraft.fee(), stakeDraft.summary(), stakeDraft.ttl());
                service().trackConfirmation(hwTxHash, 120);
                return new SubmitView(hwTxHash, false);
            }
            QuickAdaTxDraft draft = drafts.get(draftId);
            if (draft == null) {
                throw new IllegalStateException("Draft expired — please review again");
            }
            String txHash;
            try {
                txHash = requireSession().submit(draft);
            } catch (WalletService.RetryableSubmitException e) {
                throw e; // Transport failure: keep the signed draft so the user can retry.
            } catch (RuntimeException e) {
                drafts.remove(draftId); // Terminal rejection: the tx is invalid, don't retry it.
                throw e;
            }
            drafts.remove(draftId);
            // Confirmation polling on a dedicated thread (never the shared
            // backend executor) and holding no reference to the session/keys.
            service().trackConfirmation(txHash, 120);
            return new SubmitView(txHash, false);
        });
    }

    private DraftView cacheDraft(QuickAdaTxDraft draft, String kind, String toSummary,
                                 String amountAda, String feeAda, String totalAda) {
        drafts.put(draft.txHash(), draft);
        return new DraftView(draft.txHash(), kind, toSummary, amountAda, feeAda, totalAda);
    }

    private WalletService.Session requireSession() {
        WalletService.Session active = session;
        if (active == null) {
            throw new IllegalStateException("Wallet is locked");
        }
        return active;
    }

    private <T> CompletableFuture<T> async(Supplier<T> task) {
        return CompletableFuture.supplyAsync(task, executor);
    }

    /**
     * For short, lock-free node-introspection reads (startup status, log tail).
     * These MUST NOT run on {@link #executor}: a managed connectManaged() occupies
     * that single thread for the entire first sync, which would starve the
     * Connect screen's startup poller. The common pool keeps them responsive.
     */
    private static <T> CompletableFuture<T> io(Supplier<T> task) {
        return CompletableFuture.supplyAsync(task);
    }

    private static WalletItem toItem(StoredWallet wallet) {
        return new WalletItem(wallet.id(), wallet.seedId(), wallet.name(), wallet.networkId(),
                wallet.accountIndex(), wallet.baseAddress(), wallet.stakeAddress(), wallet.isHardware());
    }

    private static String ada(BigInteger lovelace) {
        if (lovelace == null) return "0";
        return new BigDecimal(lovelace).movePointLeft(6).stripTrailingZeros().toPlainString();
    }

    private static String blockfrostType(String type) {
        if (type == null) return "";
        return switch (type) {
            case "MEMBER" -> "member";
            case "LEADER" -> "leader";
            case "REFUND" -> "refund";
            default -> type.toLowerCase();
        };
    }
}
