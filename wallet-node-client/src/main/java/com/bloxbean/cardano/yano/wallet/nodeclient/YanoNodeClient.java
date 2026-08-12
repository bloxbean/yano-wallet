package com.bloxbean.cardano.yano.wallet.nodeclient;

import com.bloxbean.cardano.yano.wallet.core.config.WalletNetwork;
import com.bloxbean.cardano.yano.wallet.core.simulate.AssetQuantity;
import com.bloxbean.cardano.yano.wallet.core.simulate.ResolvedOutput;
import com.bloxbean.cardano.yano.wallet.core.simulate.ScriptEvaluation;
import com.bloxbean.cardano.yano.wallet.core.simulate.SimulationCapabilities;
import com.bloxbean.cardano.yano.wallet.core.simulate.TxSimulationException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.math.BigInteger;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Thin client for Yano-specific (non-Blockfrost) endpoints of a local node:
 * status and genesis. Blockfrost-compatible routes are consumed through
 * cardano-client-lib's backend service (see {@link YanoNodeBackend}).
 */
public class YanoNodeClient {
    private static final Logger log = LoggerFactory.getLogger(YanoNodeClient.class);
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(10);

    private final URI baseUri;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final Duration requestTimeout;
    /**
     * True when the backend is Blockfrost-shaped (yaci-store / Yaci DevKit)
     * rather than a Yano node (ADR-038). It changes which paths exist, not just
     * which are faster: yaci-store has no /governance/dreps/{id} and no
     * /accounts/{stake}/transactions, so those must be routed elsewhere.
     */
    private final boolean blockfrostFlavor;

    public YanoNodeClient(String baseUrl) {
        this(baseUrl, HttpClient.newBuilder().connectTimeout(DEFAULT_TIMEOUT).build(), DEFAULT_TIMEOUT);
    }

    public YanoNodeClient(String baseUrl, boolean blockfrostFlavor) {
        this(baseUrl, HttpClient.newBuilder().connectTimeout(DEFAULT_TIMEOUT).build(),
                DEFAULT_TIMEOUT, blockfrostFlavor);
    }

    public YanoNodeClient(String baseUrl, HttpClient httpClient, Duration requestTimeout) {
        this(baseUrl, httpClient, requestTimeout, false);
    }

    public YanoNodeClient(String baseUrl, HttpClient httpClient, Duration requestTimeout,
                          boolean blockfrostFlavor) {
        this.blockfrostFlavor = blockfrostFlavor;
        this.baseUri = URI.create(normalizeBaseUrl(baseUrl));
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient is required");
        this.objectMapper = new ObjectMapper();
        this.requestTimeout = Objects.requireNonNull(requestTimeout, "requestTimeout is required");
    }

    public String baseUrl() {
        return baseUri.toString();
    }

    /** True when this backend is yaci-store rather than a Yano node (ADR-038). */
    public boolean isBlockfrostFlavor() {
        return blockfrostFlavor;
    }

    public NodeStatus getStatus() {
        JsonNode root = getJsonOrNull("status");
        if (root == null) {
            // No /status: a Blockfrost-compatible backend such as yaci-store
            // (ADR-038). Its tip comes from /blocks/latest, and since there is no
            // separate index to fall behind, report no lag rather than guess.
            return blockfrostStatus();
        }
        JsonNode chain = root.path("chain");
        JsonNode utxo = root.path("utxo");
        return new NodeStatus(
                chain.path("slot").asLong(0),
                chain.path("blockNumber").asLong(0),
                chain.path("blockHash").asText(null),
                utxo.path("enabled").asBoolean(false),
                utxo.path("lastAppliedBlock").asLong(0),
                utxo.path("lagBlocks").asLong(0));
    }

    /** Tip of a Blockfrost-compatible backend (no sync/lag information available). */
    private NodeStatus blockfrostStatus() {
        JsonNode block = getJson("blocks/latest");
        long blockNumber = block.path("height").isMissingNode()
                ? block.path("number").asLong(0) : block.path("height").asLong(0);
        return new NodeStatus(
                block.path("slot").asLong(0),
                blockNumber,
                block.path("hash").asText(null),
                true,
                blockNumber,
                0L);
    }

    /**
     * The chain tip's block from {@code GET /blocks/latest} (Blockfrost-shaped),
     * for the live-chain visualization. Returns {@code null} if the endpoint is
     * absent, so a caller degrades gracefully rather than throwing.
     */
    public LatestBlock getLatestBlock() {
        JsonNode block = getJsonOrNull("blocks/latest");
        if (block == null) {
            return null;
        }
        long height = block.path("height").isMissingNode()
                ? block.path("number").asLong(0) : block.path("height").asLong(0);
        return new LatestBlock(
                height,
                block.path("slot").asLong(0),
                block.path("epoch").asLong(0),
                block.path("epoch_slot").asLong(0),
                block.path("tx_count").asInt(0),
                block.path("size").asLong(0),
                block.path("time").asLong(0),
                block.hasNonNull("hash") ? block.path("hash").asText() : null);
    }

    /**
     * Network/AdaPot totals from {@code GET /network} (Blockfrost-shaped) for the
     * live view's epoch panel. Returns {@code null} when unavailable — the endpoint
     * answers 503 unless the node runs with AdaPot tracking enabled.
     */
    public NetworkInfo getNetwork() {
        JsonNode root;
        try {
            root = getJsonOrNull("network");
        } catch (RuntimeException e) {
            return null; // 503 (AdaPot tracking off) or a transient node error
        }
        if (root == null || root.has("error") || !root.hasNonNull("supply")) {
            return null;
        }
        JsonNode supply = root.path("supply");
        JsonNode stake = root.path("stake");
        return new NetworkInfo(
                parseLovelace(supply.path("max").asText(null)),
                parseLovelace(supply.path("total").asText(null)),
                parseLovelace(supply.path("treasury").asText(null)),
                parseLovelace(supply.path("reserves").asText(null)),
                parseLovelace(stake.path("active").asText(null)));
    }

    private static long parseLovelace(String value) {
        if (value == null || value.isBlank()) {
            return 0;
        }
        try {
            return new java.math.BigInteger(value).longValueExact();
        } catch (RuntimeException e) {
            return 0;
        }
    }

    public GenesisInfo getGenesis() {
        JsonNode root = getJson("genesis");
        if (!root.hasNonNull("network_magic")) {
            throw new NodeClientException("Genesis response missing network_magic from " + baseUri);
        }
        return new GenesisInfo(
                root.path("network_magic").asLong(),
                root.path("system_start").asText(null),
                root.path("epoch_length").asLong(0),
                root.path("slot_length").asInt(0),
                root.path("security_param").asLong(0));
    }

    /**
     * Verifies the node at the base URL serves the expected network. A wallet
     * must never talk to a node on a different network than its stored wallets.
     *
     * <p>Blockfrost-compatible backends (yaci-store) expose no genesis and so
     * cannot be verified (ADR-038, yaci-store#1018). For those the network comes
     * from the user's explicit choice, which is safe only because it drives key
     * derivation: a non-mainnet choice derives {@code addr_test} keys, which
     * cannot address mainnet funds. Mainnet therefore still demands proof.
     */
    public void verifyNetwork(WalletNetwork expected) {
        if (expected.blockfrostFlavor()) {
            if (expected.production()) {
                throw new NodeClientException("Refusing to connect: " + expected.id()
                        + " cannot prove which network it serves, so it must not be used for mainnet.");
            }
            log.info("Skipping network verification for {} at {} — backend exposes no genesis;"
                    + " network taken from the user's explicit choice (magic {})",
                    expected.id(), baseUri, expected.protocolMagic());
            return;
        }
        long actualMagic = getGenesis().networkMagic();
        if (actualMagic != expected.protocolMagic()) {
            throw new NodeClientException("Node at " + baseUri + " serves protocol magic " + actualMagic
                    + " but wallet network is " + expected.id() + " (magic " + expected.protocolMagic() + ")");
        }
    }

    public boolean isReachable() {
        try {
            getStatus();
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /**
     * True once {@code GET /txs/{hash}} resolves. Prefer {@link #getTxStatus}
     * against ADR-033 M2 nodes, which distinguishes pending from unknown.
     */
    public boolean isTxOnChain(String txHash) {
        if (txHash == null || txHash.isBlank()) {
            throw new IllegalArgumentException("txHash is required");
        }
        URI uri = baseUri.resolve("txs/" + txHash);
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(requestTimeout)
                .header("Accept", "application/json")
                .GET()
                .build();
        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                return true;
            }
            if (response.statusCode() == 404) {
                return false;
            }
            throw new NodeClientException("GET " + uri + " failed with status " + response.statusCode());
        } catch (IOException e) {
            throw new NodeClientException("Unable to reach Yano node at " + uri, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new NodeClientException("Interrupted while calling Yano node at " + uri, e);
        }
    }

    /** Wallet-facing tx status from {@code GET /txs/{hash}/status} (ADR-033 M2). */
    public TxStatus getTxStatus(String txHash) {
        if (txHash == null || txHash.isBlank()) {
            throw new IllegalArgumentException("txHash is required");
        }
        JsonNode root = getJson("txs/" + txHash + "/status");
        return new TxStatus(
                root.path("tx_hash").asText(txHash),
                root.path("status").asText("unknown"),
                root.path("block_height").asLong(-1),
                root.path("slot").asLong(0),
                root.hasNonNull("block_hash") ? root.path("block_hash").asText() : null,
                root.path("confirmations").asLong(0),
                root.path("block_time").asLong(0));
    }

    /** Account-level tx history from {@code GET /accounts/{stake}/transactions}. */
    public java.util.List<AddressTx> getAccountTransactions(String stakeAddress, int page, int count, String order) {
        JsonNode root = getJson("accounts/" + stakeAddress + "/transactions?page=" + page
                + "&count=" + count + "&order=" + order);
        return parseAddressTxs(root);
    }

    /** Address tx history from {@code GET /addresses/{address}/transactions}. */
    public java.util.List<AddressTx> getAddressTransactions(String address, int page, int count, String order) {
        JsonNode root = getJson("addresses/" + address + "/transactions?page=" + page
                + "&count=" + count + "&order=" + order);
        return parseAddressTxs(root);
    }

    /**
     * Stake account state from {@code GET /accounts/{stake}}. A 404 (account
     * never seen) maps to an unregistered view rather than an error.
     */
    public com.bloxbean.cardano.yano.wallet.core.service.NodeStatusPort.AccountView getAccountInfo(
            String stakeAddress) {
        JsonNode root = getJsonOrNull("accounts/" + stakeAddress);
        if (root == null) { // never-seen account
            return new com.bloxbean.cardano.yano.wallet.core.service.NodeStatusPort.AccountView(
                    false, java.math.BigInteger.ZERO, null, null, null);
        }
        java.math.BigInteger withdrawable;
        try {
            withdrawable = new java.math.BigInteger(root.path("withdrawable_amount").asText("0"));
        } catch (NumberFormatException e) {
            withdrawable = java.math.BigInteger.ZERO;
        }
        return new com.bloxbean.cardano.yano.wallet.core.service.NodeStatusPort.AccountView(
                root.path("registered").asBoolean(root.path("active").asBoolean(false)),
                withdrawable,
                root.hasNonNull("pool_id") ? root.path("pool_id").asText() : null,
                root.hasNonNull("drep_id") ? root.path("drep_id").asText() : null,
                root.hasNonNull("drep_type") ? root.path("drep_type").asText() : null);
    }

    /**
     * DRep registration state from {@code GET /governance/dreps/{drepId}} (CIP-1694).
     * Returns {@code null} when the DRep is not registered (404); throws on a node
     * error so callers never mistake an outage for "not registered".
     */
    public DRepInfo getDRepInfo(String drepId) {
        // The two backends disagree on both the path and the payload (ADR-038 §4).
        // Yano serves /governance/dreps/{id} with explicit active/retired/expired
        // flags; yaci-store serves /governance-state/dreps/{id} with a single
        // `status` string and no registration epoch — it has no /governance/dreps
        // /{id} route at all, so asking it there 404s and the wallet would report
        // a registered DRep as "not registered".
        if (blockfrostFlavor) {
            JsonNode root = getJsonOrNull("governance-state/dreps/" + drepId);
            return root == null ? null : toDRepInfoFromGovernanceState(root);
        }
        JsonNode root = getJsonOrNull("governance/dreps/" + drepId);
        if (root == null) {
            return null;
        }
        return new DRepInfo(
                root.path("active").asBoolean(false),
                root.path("retired").asBoolean(false),
                root.path("expired").asBoolean(false),
                root.path("registered_epoch").asInt(0),
                depositOf(root));
    }

    /**
     * yaci-store's {@code DRepDetailsDto}: {@code status} in place of the flag
     * trio, and no registration epoch — {@link DRepInfo#registeredEpoch()} is 0,
     * which callers must read as "unknown" rather than "epoch zero".
     */
    private static DRepInfo toDRepInfoFromGovernanceState(JsonNode root) {
        String status = root.path("status").asText("").trim().toUpperCase(Locale.ROOT);
        boolean retired = status.contains("RETIRED") || status.contains("UNREGISTERED");
        boolean expired = status.contains("EXPIRED") || status.contains("INACTIVE");
        return new DRepInfo(!retired && !expired, retired, expired, 0, depositOf(root));
    }

    private static java.math.BigInteger depositOf(JsonNode root) {
        try {
            return new java.math.BigInteger(root.path("deposit").asText("0"));
        } catch (NumberFormatException e) {
            return java.math.BigInteger.ZERO;
        }
    }

    /** Reward history from {@code GET /accounts/{stake}/rewards}. */
    public java.util.List<Reward> getRewards(String stakeAddress, int page, int count) {
        JsonNode root = getJson("accounts/" + stakeAddress + "/rewards?page=" + page + "&count=" + count);
        java.util.List<Reward> rewards = new java.util.ArrayList<>();
        if (root.isArray()) {
            for (JsonNode node : root) {
                rewards.add(new Reward(
                        node.path("epoch").asInt(),
                        new java.math.BigInteger(node.path("amount").asText("0")),
                        node.path("pool_id").asText(null),
                        node.path("type").asText(null)));
            }
        }
        return rewards;
    }

    /** Active governance proposals from {@code GET /governance/proposals?status=active} (CIP-1694). */
    public List<GovernanceProposal> listActiveProposals() {
        JsonNode root = getJson("governance/proposals?status=active&count=100&order=asc");
        List<GovernanceProposal> proposals = new ArrayList<>();
        if (root != null && root.isArray()) {
            for (JsonNode p : root) {
                proposals.add(new GovernanceProposal(
                        p.path("id").asText(null),
                        p.path("tx_hash").asText(null),
                        p.path("cert_index").asInt(0),
                        p.path("governance_type").asText(null),
                        p.path("status").asText(null),
                        p.path("expires_after_epoch").asInt(0)));
            }
        }
        return proposals;
    }

    private static java.util.List<AddressTx> parseAddressTxs(JsonNode root) {
        java.util.List<AddressTx> txs = new java.util.ArrayList<>();
        if (root.isArray()) {
            for (JsonNode node : root) {
                txs.add(new AddressTx(
                        node.path("tx_hash").asText(),
                        node.path("block_height").asLong(0),
                        node.path("block_time").asLong(0),
                        node.path("slot").asLong(0)));
            }
        }
        return txs;
    }

    public record TxStatus(String txHash, String status, long blockHeight, long slot, String blockHash,
                           long confirmations, long blockTime) {
    }

    /** A governance action a wallet can vote on (subset of the node's proposal DTO). */
    public record GovernanceProposal(String id, String txHash, int certIndex,
                                     String governanceType, String status, int expiresAfterEpoch) {
    }

    /** DRep registration state (subset of the node's DRep DTO); {@code deposit} is the locked amount. */
    public record DRepInfo(boolean active, boolean retired, boolean expired, int registeredEpoch,
                           java.math.BigInteger deposit) {
    }

    public record AddressTx(String txHash, long blockHeight, long blockTime, long slot) {
    }

    /** Chain tip block for the live view (subset of {@code GET /blocks/latest}). */
    public record LatestBlock(long height, long slot, long epoch, long epochSlot,
                              int txCount, long sizeBytes, long timeSeconds, String hash) {
    }

    /** Network/AdaPot totals in lovelace (subset of {@code GET /network}); 0 = unknown. */
    public record NetworkInfo(long maxSupply, long total, long treasury, long reserves, long activeStake) {
    }

    public record Reward(int epoch, java.math.BigInteger amount, String poolId, String type) {
    }

    // ------------------------------------------------------------------
    // Transaction simulation (ADR-042). These run inside the signing path
    // under a hard deadline, so they use a tighter timeout than the rest of
    // the client and never retry.
    // ------------------------------------------------------------------

    /** An all-zero output reference, used to probe whether the route exists at all. */
    private static final String PROBE_TX_HASH =
            "0000000000000000000000000000000000000000000000000000000000000000";

    /**
     * Resolves an output reference via {@code GET /utxos/{txHash}/{index}}.
     *
     * <p>Returns {@code null} when the node positively reports the output is not
     * in its UTxO set — already spent, or not yet on-chain. That is an answer, so
     * it is not an exception. Anything else (node unreachable, 5xx, a node that
     * does not serve this route) throws {@link TxSimulationException}, because the
     * caller must not mistake "could not ask" for "not yours".
     *
     * <p>Note this is deliberately NOT cardano-client-lib's {@code
     * UtxoService.getTxOutput}: that resolves through {@code /txs/{hash}/utxos},
     * which answers a different question (historical outputs of an on-chain
     * transaction) than the unspent-only set this needs.
     */
    public ResolvedOutput getUtxo(String txHash, int index) {
        // The hash arrives from dApp-supplied CBOR. Anything but a real tx hash is
        // refused rather than interpolated into the path, where "aa/../status"
        // would silently resolve a different endpoint and feed its JSON to the
        // UTxO parser.
        if (!isTxHash(txHash)) {
            throw new TxSimulationException("Not a transaction hash: " + txHash);
        }
        if (index < 0) {
            throw new IllegalArgumentException("index must not be negative");
        }
        RawResponse response = getRaw("utxos/" + txHash + "/" + index, simulationTimeout());
        if (response.status() == 404) {
            // A route that exists answers a miss with an EMPTY body; a node that
            // does not serve this route answers with an error page. Distinguishing
            // them is what stops "your node is too old" being reported as "this
            // input is not yours".
            if (response.isEmptyBody()) {
                return null;
            }
            throw new TxSimulationException("This node does not serve /utxos/{txHash}/{index}");
        }
        if (response.status() != 200) {
            throw new TxSimulationException(
                    "Could not resolve " + txHash + "#" + index + " (node returned " + response.status() + ")");
        }
        try {
            return toResolvedOutput(objectMapper.readTree(response.body()));
        } catch (TxSimulationException e) {
            throw e;
        } catch (IOException | RuntimeException e) {
            // Backstop: every way this parse can go wrong must arrive as the one
            // exception the port contract declares, so the engine's "unresolved →
            // degrade" path catches it instead of taking an uncontracted crash.
            throw new TxSimulationException("Unreadable response for " + txHash + "#" + index, e);
        }
    }

    /**
     * Strict on purpose. Every lenient reading here — a skipped malformed asset, a
     * quantity coerced to zero, a missing address treated as unknown — removes
     * value from the "what leaves my wallet" total while still reporting the
     * summary as complete. That is precisely ADR-042's worst case: a confident,
     * smaller loss. A response we do not fully understand is not a resolved
     * output, so it throws and the input is reported unresolved.
     */
    private static ResolvedOutput toResolvedOutput(JsonNode utxo) {
        if (utxo == null || !utxo.isObject()) {
            throw new TxSimulationException("Node returned a non-object UTxO response");
        }
        String address = utxo.path("address").asText(null);
        if (!notBlank(address)) {
            throw new TxSimulationException("Node returned a UTxO with no address");
        }
        JsonNode amounts = utxo.path("amount");
        if (!amounts.isArray()) {
            throw new TxSimulationException("Node returned a UTxO with no amount list");
        }
        BigInteger lovelace = BigInteger.ZERO;
        List<AssetQuantity> assets = new ArrayList<>();
        for (JsonNode amount : amounts) {
            String unit = amount.path("unit").asText("");
            BigInteger quantity = parseQuantity(amount.path("quantity"));
            if ("lovelace".equals(unit)) {
                lovelace = lovelace.add(quantity);   // duplicates sum rather than overwrite
            } else if (unit.length() >= POLICY_ID_HEX_LENGTH) {
                assets.add(new AssetQuantity(
                        unit.substring(0, POLICY_ID_HEX_LENGTH),
                        unit.substring(POLICY_ID_HEX_LENGTH),
                        quantity));
            } else {
                throw new TxSimulationException("Node returned an unrecognised asset unit");
            }
        }
        String inlineDatum = utxo.hasNonNull("inline_datum") ? utxo.path("inline_datum").asText(null) : null;
        String dataHash = utxo.hasNonNull("data_hash") ? utxo.path("data_hash").asText(null) : null;
        String scriptRef = utxo.hasNonNull("script_ref") ? utxo.path("script_ref").asText(null) : null;
        String refScriptHash = utxo.hasNonNull("reference_script_hash")
                ? utxo.path("reference_script_hash").asText(null) : null;
        return new ResolvedOutput(
                address,
                lovelace,
                assets,
                notBlank(inlineDatum) || notBlank(dataHash),
                notBlank(scriptRef) || notBlank(refScriptHash));
    }

    private static final int POLICY_ID_HEX_LENGTH = 56;
    private static final int TX_HASH_HEX_LENGTH = 64;

    /** A 32-byte transaction hash, lower-case hex — nothing else reaches the URL path. */
    private static boolean isTxHash(String value) {
        if (value == null || value.length() != TX_HASH_HEX_LENGTH) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if ((c < '0' || c > '9') && (c < 'a' || c > 'f')) {
                return false;
            }
        }
        return true;
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }

    private static BigInteger parseQuantity(JsonNode quantity) {
        try {
            return new BigInteger(quantity.asText("0"));
        } catch (NumberFormatException e) {
            throw new TxSimulationException("Node returned an unreadable asset quantity");
        }
    }

    /**
     * Evaluates a transaction's Plutus scripts via {@code POST /utils/txs/evaluate}.
     *
     * <p>The endpoint is Ogmios-shaped and answers HTTP 200 even for a failed
     * evaluation, so the outcome is read from the body, never from the status
     * code. Never throws: a node that cannot evaluate is a renderable outcome
     * ({@link ScriptEvaluation.Outcome#UNAVAILABLE}), not an error, because the
     * user still needs a prompt.
     */
    public ScriptEvaluation evaluateTx(String txHex) {
        if (txHex == null || txHex.isBlank()) {
            throw new IllegalArgumentException("txHex is required");
        }
        RawResponse response;
        try {
            response = postRaw("utils/txs/evaluate", txHex, "text/plain", simulationTimeout());
        } catch (RuntimeException e) {
            return ScriptEvaluation.unavailable("Could not reach the node to evaluate scripts");
        }
        if (response.status() == 404) {
            return ScriptEvaluation.unavailable("This node does not support script evaluation");
        }
        if (response.status() != 200) {
            return ScriptEvaluation.unavailable("The node could not evaluate scripts just now");
        }
        return parseEvaluation(response.body());
    }

    private ScriptEvaluation parseEvaluation(String body) {
        JsonNode result;
        try {
            result = objectMapper.readTree(body).path("result");
        } catch (IOException e) {
            return ScriptEvaluation.unavailable("Unreadable evaluation response from the node");
        }
        JsonNode failure = result.path("EvaluationFailure");
        if (!failure.isMissingNode()) {
            String message = failure.path("message").asText("Script evaluation failed");
            // The node reports "not initialized" when tx-evaluation is off or
            // protocol parameters are missing. That is the node being unable to
            // check, NOT the transaction being bad — conflating them would tell
            // the user their transaction fails when nothing was ever run.
            return isEvaluatorUnavailable(message)
                    ? ScriptEvaluation.unavailable(message)
                    : ScriptEvaluation.failure(message);
        }
        JsonNode success = result.path("EvaluationResult");
        if (success.isMissingNode() || !success.isObject()) {
            return ScriptEvaluation.unavailable("Unrecognised evaluation response from the node");
        }
        List<ScriptEvaluation.RedeemerCost> costs = new ArrayList<>();
        java.util.Iterator<String> keys = success.fieldNames();
        while (keys.hasNext()) {
            String key = keys.next();                  // e.g. "spend:0"
            JsonNode exUnits = success.path(key);
            int separator = key.lastIndexOf(':');
            String tag = separator < 0 ? key : key.substring(0, separator);
            int index = 0;
            if (separator >= 0) {
                try {
                    index = Integer.parseInt(key.substring(separator + 1));
                } catch (NumberFormatException ignored) {
                    // Keep the redeemer with index 0 rather than dropping it: a
                    // redeemer we cannot label is still a redeemer that ran.
                }
            }
            costs.add(new ScriptEvaluation.RedeemerCost(
                    tag, index, exUnits.path("memory").asLong(0), exUnits.path("steps").asLong(0)));
        }
        return ScriptEvaluation.success(costs);
    }

    /**
     * Messages the node emits when its <em>evaluator</em> could not run — as
     * opposed to running and rejecting the transaction. Anchored prefixes of the
     * exact strings the node throws, not substrings: a substring test both misses
     * real infrastructure errors (
     * {@code "Cannot resolve SlotConfig zeroTime: no valid genesis timestamp is available"}
     * contains "is available") and, worse, can be triggered by an attacker — a
     * genuine script failure whose trace happens to contain "not available" would
     * be downgraded from "this WILL fail and burn your collateral" to a neutral
     * "could not verify".
     *
     * <p>Anything not on this list is treated as a real failure and rendered with
     * the node's own words. That is the fail-safe direction: wrongly reporting a
     * failure costs the user a transaction they could have sent, while wrongly
     * reporting "could not verify" costs them the fee and their collateral.
     *
     * <p>This is string matching against another codebase and is therefore
     * fragile by construction. The durable fix is structural — a machine-readable
     * code beside {@code message} in the node's {@code EvaluationFailure}.
     */
    private static final List<String> EVALUATOR_UNAVAILABLE_PREFIXES = List.of(
            "Script evaluation not initialized.",
            "Transaction evaluation is not available",
            "Failed to resolve current slot from runtime",
            "Cannot resolve SlotConfig zeroTime",
            "Protocol version not found or invalid in");

    private static boolean isEvaluatorUnavailable(String message) {
        if (message == null) {
            return false;
        }
        String trimmed = message.strip();
        for (String prefix : EVALUATOR_UNAVAILABLE_PREFIXES) {
            if (trimmed.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Probes what this node can do for simulation (ADR-042 SIM-M0), by calling the
     * endpoints rather than comparing version strings — a locally built node can
     * be newer than the pinned release while reporting an older version.
     *
     * <p>Never throws. A probe that cannot complete yields {@code UNKNOWN}, which
     * is reported honestly, rather than {@code UNAVAILABLE}, which would
     * permanently write off a capable node over one bad request.
     */
    public SimulationCapabilities probeSimulationCapabilities() {
        SimulationCapabilities.Support utxoLookup;
        String detail = null;
        try {
            RawResponse probe = getRaw("utxos/" + PROBE_TX_HASH + "/0", simulationTimeout());
            if (probe.status() == 200 || (probe.status() == 404 && probe.isEmptyBody())) {
                utxoLookup = SimulationCapabilities.Support.AVAILABLE;
            } else if (probe.status() == 404) {
                utxoLookup = SimulationCapabilities.Support.UNAVAILABLE;
            } else if (probe.status() == 503) {
                utxoLookup = SimulationCapabilities.Support.UNAVAILABLE;
                detail = "The node's UTxO index is switched off.";
            } else {
                utxoLookup = SimulationCapabilities.Support.UNKNOWN;
            }
        } catch (RuntimeException e) {
            log.debug("UTxO lookup probe failed at {}: {}", baseUri, e.getMessage());
            utxoLookup = SimulationCapabilities.Support.UNKNOWN;
        }

        SimulationCapabilities.Support scriptEvaluation;
        try {
            // One byte of CBOR: enough to reach the evaluator and be rejected by
            // it, with no side effects on the node. Sent raw rather than through
            // evaluateTx() so an unreachable node stays distinguishable from one
            // that answered "I cannot evaluate".
            RawResponse probe = postRaw("utils/txs/evaluate", "00", "text/plain", simulationTimeout());
            if (probe.status() == 404) {
                scriptEvaluation = SimulationCapabilities.Support.UNAVAILABLE;
            } else if (probe.status() != 200) {
                scriptEvaluation = SimulationCapabilities.Support.UNKNOWN;
            } else {
                ScriptEvaluation parsed = parseEvaluation(probe.body());
                // A deserialization complaint means the evaluator ran and rejected
                // our deliberate garbage — the capability is present.
                if (parsed.outcome() != ScriptEvaluation.Outcome.UNAVAILABLE) {
                    scriptEvaluation = SimulationCapabilities.Support.AVAILABLE;
                } else if (isEvaluatorUnavailable(parsed.message())) {
                    // The node said, in its own words, that its evaluator is off.
                    scriptEvaluation = SimulationCapabilities.Support.UNAVAILABLE;
                    if (detail == null) {
                        detail = parsed.message();
                    }
                } else {
                    // An answer we could not read is not the node saying "no".
                    scriptEvaluation = SimulationCapabilities.Support.UNKNOWN;
                }
            }
        } catch (RuntimeException e) {
            log.debug("Script evaluation probe failed at {}: {}", baseUri, e.getMessage());
            scriptEvaluation = SimulationCapabilities.Support.UNKNOWN;
        }

        return new SimulationCapabilities(utxoLookup, scriptEvaluation, nodeVersionOrNull(), detail);
    }

    /**
     * The node's self-reported build version from {@code GET /node/config}, or
     * {@code null}. For messages only — see {@link SimulationCapabilities}.
     */
    public String nodeVersionOrNull() {
        try {
            // Deliberately the simulation timeout, not the client's 10s default:
            // this runs inside the capability probe, which runs inside a signing
            // prompt. A decorative version string must never cost the user 10s.
            RawResponse response = getRaw("node/config", simulationTimeout());
            if (response.status() != 200) {
                return null;
            }
            JsonNode config = objectMapper.readTree(response.body());
            String version = config.path("version").asText(null);
            return notBlank(version) ? version : null;
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /** Simulation runs inside a signing prompt: bound it well below the general timeout. */
    private Duration simulationTimeout() {
        return requestTimeout.compareTo(SIMULATION_TIMEOUT) < 0 ? requestTimeout : SIMULATION_TIMEOUT;
    }

    private static final Duration SIMULATION_TIMEOUT = Duration.ofSeconds(2);

    /** Status + body, so callers can tell an empty-bodied 404 from an error page. */
    private record RawResponse(int status, String body) {
        boolean isEmptyBody() {
            return body == null || body.isBlank();
        }
    }

    private RawResponse getRaw(String path, Duration timeout) {
        URI uri = baseUri.resolve(path);
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(timeout)
                .header("Accept", "application/json")
                .GET()
                .build();
        return send(request, uri, timeout);
    }

    private RawResponse postRaw(String path, String body, String contentType, Duration timeout) {
        URI uri = baseUri.resolve(path);
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(timeout)
                .header("Content-Type", contentType)
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        return send(request, uri, timeout);
    }

    /**
     * Sends under a REAL deadline. {@code HttpRequest.timeout} only bounds the
     * wait for response <em>headers</em> — a node that returns headers and then
     * trickles the body holds the caller indefinitely, which on this path is the
     * CIP-30 bridge worker thread with a user staring at a signing prompt. Going
     * through {@code sendAsync} + {@code get(timeout)} + {@code cancel(true)}
     * bounds the whole exchange, body included, and cancelling aborts it rather
     * than leaking the connection.
     */
    private RawResponse send(HttpRequest request, URI uri, Duration timeout) {
        java.util.concurrent.CompletableFuture<HttpResponse<String>> exchange =
                httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString());
        try {
            HttpResponse<String> response = exchange.get(timeout.toMillis(),
                    java.util.concurrent.TimeUnit.MILLISECONDS);
            return new RawResponse(response.statusCode(), response.body());
        } catch (java.util.concurrent.TimeoutException e) {
            exchange.cancel(true);
            throw new TxSimulationException("Yano node at " + uri + " did not answer within "
                    + timeout.toMillis() + "ms", e);
        } catch (java.util.concurrent.ExecutionException e) {
            exchange.cancel(true);
            throw new TxSimulationException("Unable to reach Yano node at " + uri, e.getCause());
        } catch (InterruptedException e) {
            exchange.cancel(true);
            Thread.currentThread().interrupt();
            throw new TxSimulationException("Interrupted while calling Yano node at " + uri, e);
        }
    }

    /**
     * Like {@link #getJson} but returns {@code null} on a 404 (resource not found)
     * instead of throwing, while still throwing on every other non-200 (5xx,
     * timeout, …). Lets callers distinguish "absent" from "node error" without a
     * fragile status-string match.
     */
    private JsonNode getJsonOrNull(String path) {
        URI uri = baseUri.resolve(path);
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(requestTimeout)
                .header("Accept", "application/json")
                .GET()
                .build();
        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 404) {
                return null;
            }
            if (response.statusCode() != 200) {
                throw new NodeClientException("GET " + uri + " failed with status " + response.statusCode());
            }
            return objectMapper.readTree(response.body());
        } catch (IOException e) {
            throw new NodeClientException("Unable to reach Yano node at " + uri, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new NodeClientException("Interrupted while calling Yano node at " + uri, e);
        }
    }

    private JsonNode getJson(String path) {
        URI uri = baseUri.resolve(path);
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(requestTimeout)
                .header("Accept", "application/json")
                .GET()
                .build();
        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new NodeClientException("GET " + uri + " failed with status " + response.statusCode());
            }
            return objectMapper.readTree(response.body());
        } catch (IOException e) {
            throw new NodeClientException("Unable to reach Yano node at " + uri, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new NodeClientException("Interrupted while calling Yano node at " + uri, e);
        }
    }

    static String normalizeBaseUrl(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalArgumentException("baseUrl is required");
        }
        String normalized = baseUrl.trim();
        return normalized.endsWith("/") ? normalized : normalized + "/";
    }
}
