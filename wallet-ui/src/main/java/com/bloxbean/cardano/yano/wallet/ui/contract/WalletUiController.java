package com.bloxbean.cardano.yano.wallet.ui.contract;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Async boundary between the JavaFX views and the wallet backend. Views and
 * view-models depend ONLY on this contract and its records — no CCL, node, or
 * HTTP types cross it (ADR-033: the MVP's best structural idea, carried
 * forward). Implementations execute on background threads; every future
 * completes off the FX thread and callers hop back via {@code Platform.runLater}.
 */
public interface WalletUiController {

    // --- connection (managed local node vs external node) ---
    /** The networks the wallet can connect to. */
    List<String> availableNetworks();

    /** The persisted connection, or null on first run. */
    ConnectionInfo savedConnection();

    /** True once a node connection is active (backend built + reachable). */
    boolean isConnected();

    /**
     * Launches (or reuses) a managed local node for the network and connects
     * to it. Completes when the node's REST API is reachable.
     */
    CompletableFuture<ConnectionInfo> connectManaged(String networkId);

    /** Verifies and connects to an external node URL for the network. */
    CompletableFuture<ConnectionInfo> connectExternal(String networkId, String baseUrl);

    /** Connects using the persisted connection config exactly (honors managed port). */
    CompletableFuture<ConnectionInfo> reconnectSaved();

    /**
     * Aborts an in-flight connect attempt, stopping a managed node that is still
     * starting. The pending connect future then completes exceptionally. Safe to
     * call when nothing is connecting; never blocks the caller.
     */
    void cancelConnect();

    /**
     * True when a managed node for this network already has chainstate on disk —
     * starting it resumes instead of syncing from genesis.
     */
    boolean hasLocalChainstate(String networkId);

    // --- environment (valid once connected) ---
    String networkId();

    String nodeBaseUrl();

    CompletableFuture<NodeStatusView> nodeStatus();

    /**
     * Best-effort managed-node startup snapshot for the Connect screen, polled
     * while a managed connection is being established. Never throws; safe to call
     * before the node is reachable (and while {@link #connectManaged} is running).
     */
    CompletableFuture<NodeStartupView> nodeStartupStatus();

    /**
     * The last {@code maxLines} of the managed node's log (empty for an external
     * node). Backs the Connect screen's log peek and the Settings Advanced card.
     */
    CompletableFuture<List<String>> nodeLogTail(int maxLines);

    /** True when the active connection is a managed local node (so a local log exists). */
    boolean managedNode();

    /** Absolute path of the wallet/node data directory, for display in Settings. */
    String dataDir();

    /**
     * Live chain-tip snapshot for the animated "Live" view / ambient background.
     * Best-effort: returns {@link LiveChainView#unreachable()} rather than throwing
     * when the node is unavailable, so the visualization degrades quietly.
     */
    CompletableFuture<LiveChainView> liveChain();

    /** Conventional backend URL for a network, or null if it has none. */
    String defaultBaseUrl(String networkId);

    /**
     * False when the wallet cannot run this network's backend itself — e.g. a
     * Yaci DevKit devnet, which is served by yaci-store (ADR-038). Such networks
     * are external-only.
     */
    boolean supportsManagedNode(String networkId);

    // --- wallet lifecycle ---
    CompletableFuture<List<WalletItem>> listWallets();

    CompletableFuture<CreatedWallet> createWallet(String name, char[] passphrase);

    CompletableFuture<WalletItem> restoreWallet(String name, String mnemonic, char[] passphrase);

    /**
     * Derives the next CIP-1852 account from an existing software wallet's seed
     * (ADR-037). Does not unlock it — call {@link #unlock} next.
     *
     * @param seedId the seed group to extend ({@link WalletItem#seedId()})
     */
    CompletableFuture<WalletItem> createAccount(String seedId, String name, char[] passphrase);

    /**
     * Finds accounts of a software wallet's seed that have on-chain history but
     * aren't stored yet (ADR-037) — what a restored seed needs, since restore
     * creates only account 0. Nothing is added: call {@link #createAccount} for
     * the ones the user confirms. Requires a reachable node.
     */
    CompletableFuture<List<DiscoveredAccountView>> discoverAccounts(String seedId, char[] passphrase);

    /**
     * Adds a specific account of a software wallet's seed — for accounts returned
     * by {@link #discoverAccounts}, whose index must be preserved (the user may
     * add only some of them, so "next free index" would derive the wrong account).
     */
    CompletableFuture<WalletItem> createAccountAt(String seedId, String name, char[] passphrase,
                                                  int accountIndex);

    /** An account found on-chain that isn't stored yet. */
    record DiscoveredAccountView(int accountIndex, String baseAddress) {
    }

    // --- hardware wallet (ADR-034) ---
    /** Display names of currently connected hardware devices (empty if none). */
    CompletableFuture<List<String>> listHardwareDevices();

    /**
     * Imports the first connected device's account as a watch-only wallet
     * (reads its account public key; the device may prompt to confirm). Does not
     * unlock it — call {@link #unlockHardware} next.
     */
    CompletableFuture<WalletItem> importHardwareWallet(String name, int accountIndex);

    /**
     * Imports another account of an already-imported hardware wallet (ADR-037),
     * keeping it in the same {@code seedId} group. Fails if the connected device
     * is not the one that group was imported from (verified by re-deriving the
     * group's first account on the device), so the account can always be signed
     * for. The device may prompt twice: once to verify, once for the new account.
     */
    CompletableFuture<WalletItem> importHardwareAccount(String seedId, String name, int accountIndex);

    /** Opens a watch-only hardware wallet as the active session (no passphrase). */
    CompletableFuture<WalletItem> unlockHardware(String walletId);

    /** Unlocks and makes the wallet the active session. */
    CompletableFuture<WalletItem> unlock(String walletId, char[] passphrase);

    // --- security key (ADR-036), all opt-in ---

    /** The security-key protection status of a wallet's vault. */
    CompletableFuture<SecurityKeyView> securityKey(String walletId);

    /**
     * Unlocks a wallet protected by a security key. {@code pinProvider} supplies
     * a FIDO2 PIN when the vault needs one; {@code onTouch} hints the device is
     * waiting for a tap.
     */
    CompletableFuture<WalletItem> unlockWithSecurityKey(String walletId, char[] passphrase,
            java.util.function.Supplier<char[]> pinProvider, Runnable onTouch);

    /**
     * Seals a wallet with its first security key (kind = "yubikey" or "fido2").
     * When {@code passwordless} (ADR-040), the vault opens with the key + PIN alone.
     */
    CompletableFuture<Void> enrollSecurityKey(String walletId, char[] passphrase, String kind,
            int yubikeySlot, boolean passwordless,
            java.util.function.Supplier<char[]> pinProvider, Runnable onTouch);

    /** Adds a backup security key (touch the current key, then the new one). */
    CompletableFuture<Void> addBackupSecurityKey(String walletId, char[] passphrase, String kind,
            int yubikeySlot, java.util.function.Supplier<char[]> pinProvider, Runnable onTouch);

    /** Removes the security key protecting the wallet (the last one → passphrase-only). */
    CompletableFuture<Void> removeSecurityKey(String walletId, char[] passphrase,
            java.util.function.Supplier<char[]> pinProvider, Runnable onTouch);

    /** Sets a FIDO2 PIN on the connected key (key-global; the UI must warn first). */
    CompletableFuture<Void> setFido2Pin(char[] newPin, Runnable onTouch);

    /** Security-key protection status of a wallet's vault (ADR-036). */
    record SecurityKeyView(boolean protectedByKey, String label, int keyCount, boolean requiresPin,
                           boolean passwordless) {
    }

    /** Discards the active session (locks the wallet). */
    void lock();

    /** The active session's wallet, or null when locked. */
    WalletItem activeWallet();

    /**
     * Starts the CIP-30 dApp connector bridge (ADR-035), using {@code prompt} for
     * connect/sign consent. Idempotent; called once after the UI is up. The bridge
     * reflects the current lock state, so it can start regardless of session.
     */
    void startDappConnector(Cip30Prompt prompt);

    /** Stops the CIP-30 dApp connector bridge. */
    void stopDappConnector();

    /** The origins of dApps the user has connected (CIP-30 allowlist). */
    CompletableFuture<List<String>> connectedDapps();

    /** Revokes a connected dApp origin; it must approve again to reconnect. */
    CompletableFuture<Void> forgetDapp(String origin);

    /**
     * Installs the browser Native Messaging host (ADR-035 M5): the relay the
     * browser launches itself so dApp traffic stops using a localhost port.
     * Returns a human-readable summary; the browser needs a restart after.
     */
    CompletableFuture<String> installNativeMessagingHost();

    /**
     * How the browser reaches the wallet (ADR-035).
     *
     * <p>{@code NATIVE_MESSAGING} is the default and the only transport that can
     * identify its caller: the browser launches the connector and vouches for the
     * extension's pinned id. Over the localhost WebSocket the origin is
     * self-asserted, so any local process can claim to be a dApp — which is why
     * {@link ConnectorSettingsView#weak()} exists and why the UI warns about it
     * on every launch rather than once.
     */
    ConnectorSettingsView connectorSettings();

    /**
     * Switches transport and restarts the connector. Returns a message describing
     * what is now listening, for the UI to show.
     */
    CompletableFuture<String> setConnectorTransport(String transport, int wsPort);

    /**
     * @param weak true when the active transport cannot prove which extension is
     *             calling — the UI must keep saying so, not merely record it
     */
    record ConnectorSettingsView(String transport, int wsPort, boolean weak) {
    }

    // --- active-session queries ---
    CompletableFuture<BalanceView> balance();

    CompletableFuture<List<AddressItem>> addresses(int count);

    CompletableFuture<List<TxItem>> history(int page, int count);

    CompletableFuture<List<RewardItem>> rewards(int page, int count);

    CompletableFuture<StakingView> staking();

    // --- active-session actions (draft → review → confirm) ---
    /**
     * Drafts a payment of {@code amount} units of {@code unit} ("lovelace" for
     * ADA, else policyId+assetName). ADA amounts are decimal; native-asset
     * amounts are integer quantities.
     */
    CompletableFuture<DraftView> draftSend(String toAddress, String unit, String amount, String memo);

    CompletableFuture<DraftView> draftDelegation(String poolId);

    CompletableFuture<DraftView> draftWithdrawal();

    /**
     * Delegates the wallet's voting power (CIP-1694). {@code target} is either a
     * DRep id ({@code drep1…}) or one of the sentinels {@link #VOTE_ABSTAIN} /
     * {@link #VOTE_NO_CONFIDENCE}.
     */
    CompletableFuture<DraftView> draftVoteDelegation(String target);

    String VOTE_ABSTAIN = "abstain";
    String VOTE_NO_CONFIDENCE = "no-confidence";

    /** Active governance actions (CIP-1694) the wallet can vote on, from the node. */
    CompletableFuture<List<ProposalView>> listProposals();

    /** This wallet's DRep registration state + current vote-delegation target. */
    CompletableFuture<GovernanceStatusView> governanceStatus();

    /** Unregisters this wallet's DRep, reclaiming the locked deposit. */
    CompletableFuture<DraftView> draftDRepDeregistration();

    /**
     * Registers this wallet's account as a DRep (CIP-1694). {@code anchorUrl} /
     * {@code anchorHashHex} are an optional rationale (both, or neither).
     */
    CompletableFuture<DraftView> draftDRepRegistration(String anchorUrl, String anchorHashHex);

    /**
     * Casts a vote as a DRep on a governance action. {@code choice} is
     * {@link #VOTE_YES} / {@link #VOTE_NO} / {@link #VOTE_ABSTAIN_VOTE}.
     */
    CompletableFuture<DraftView> draftVote(String proposalTxHash, int certIndex, String choice,
                                           String anchorUrl, String anchorHashHex);

    String VOTE_YES = "yes";
    String VOTE_NO = "no";
    String VOTE_ABSTAIN_VOTE = "abstain-vote";

    /** Submits the given draft (from the draft* methods) and returns the tx hash. */
    CompletableFuture<SubmitView> confirmDraft(String draftId);

    // --- records ---
    record ConnectionInfo(String mode, String networkId, String baseUrl, boolean managed) {
    }

    /**
     * One CIP-1852 account (ADR-037). Accounts derived from the same seed share a
     * {@code seedId} — the UI groups by it to show one card per wallet.
     */
    record WalletItem(String walletId, String seedId, String name, String networkId, int accountIndex,
                      String baseAddress, String stakeAddress, boolean hardware) {

        /** Display label for this account within its group, e.g. "Account 1 · Trading". */
        public String accountLabel() {
            String account = "Account " + accountIndex;
            return name == null || name.isBlank() || name.equals(account) ? account : account + " · " + name;
        }
    }

    record CreatedWallet(WalletItem wallet, String mnemonic) {
    }

    /**
     * Managed-node startup phase for the Connect screen. {@code phase} is one of
     * {@code PREPARING}/{@code STARTING}/{@code READY}/{@code FAILED} (display
     * detail in {@code detail}); {@code blockNumber} is 0 until the node reports a
     * tip. {@code failed} + {@code failureReason} carry a startup failure.
     */
    record NodeStartupView(String phase, String detail, long blockNumber,
                           boolean reachable, boolean failed, String failureReason) {
    }

    /**
     * Chain-tip snapshot for the live visualization. {@code slotInEpoch}/
     * {@code epochLength} drive the epoch-progress ring; {@code ageSeconds} is how
     * long ago the tip block arrived. The {@code *Lovelace} totals come from the
     * node's AdaPot ({@code GET /network}); all are 0 when unknown (e.g. AdaPot
     * tracking is off, or the field is genuinely unavailable).
     */
    record LiveChainView(long blockHeight, long slot, long epoch, long slotInEpoch, long epochLength,
                         int txCount, long blockSizeBytes, long ageSeconds, String blockHashShort,
                         long treasuryLovelace, long reservesLovelace,
                         long circulatingLovelace, long activeStakeLovelace,
                         boolean synced, boolean reachable) {
        public static LiveChainView unreachable() {
            return new LiveChainView(0, 0, 0, 0, 0, 0, 0, 0, "", 0, 0, 0, 0, false, false);
        }

        /** Fraction of the current epoch elapsed, in [0, 1]; 0 when unknown. */
        public double epochProgress() {
            return epochLength > 0 ? Math.min(1.0, (double) slotInEpoch / epochLength) : 0;
        }

        /** True when the AdaPot totals are populated (node exposes /network). */
        public boolean hasSupply() {
            return treasuryLovelace > 0 || reservesLovelace > 0;
        }
    }

    record NodeStatusView(long slot, long blockNumber, boolean utxoIndexEnabled,
                          long utxoLagBlocks, boolean caughtUp, boolean reachable) {
    }

    record AssetItem(String unit, String quantity) {
    }

    record BalanceView(String ada, String lovelace, int utxoCount, int addressesScanned,
                       List<AssetItem> assets) {
    }

    record AddressItem(int index, String address, String derivationPath) {
    }

    record TxItem(String txHash, long blockHeight, String timeText, String status,
                  String amountText, String direction, String explorerUrl) {
    }

    record RewardItem(int epoch, String amountAda, String poolId, String type) {
    }

    record StakingView(String stakeAddress, boolean registered, String delegatedPoolId,
                       String withdrawableAda) {
    }

    record ProposalView(String id, String txHash, int certIndex, String type, String status,
                        int expiresAfterEpoch) {
    }

    /**
     * Governance status for the current wallet. {@code drepRegistered} gates the
     * Unregister action; the text fields are display-ready. {@code depositAda} is
     * the locked deposit (null when not registered).
     */
    record GovernanceStatusView(boolean drepRegistered, String drepId, String drepStatusText,
                                String voteDelegationText, String depositAda) {
    }

    record DraftView(String draftId, String kind, String toSummary, String amountAda,
                     String feeAda, String totalAda) {
    }

    /**
     * Simulates a drafted transaction against the user's own node (ADR-042
     * SIM-M4), so the wallet's own Send / Staking / Governance confirmations
     * answer "what will this do" with the same machinery that backs dApp
     * requests. One implementation serving both keeps them honest with each
     * other — a bug that under-reports a dApp drain would under-report ours too,
     * where it is far more likely to be noticed.
     *
     * <p>Best-effort and bounded: a node that cannot answer yields a degraded
     * view rather than blocking the confirmation.
     */
    CompletableFuture<TxEffectView> simulateDraft(String draftId);

    record SubmitView(String txHash, boolean confirmed) {
    }
}
