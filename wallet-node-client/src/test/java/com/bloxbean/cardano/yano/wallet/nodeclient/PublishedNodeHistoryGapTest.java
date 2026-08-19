package com.bloxbean.cardano.yano.wallet.nodeclient;

import com.bloxbean.cardano.yano.wallet.core.service.HistoryPort;
import com.bloxbean.cardano.yano.wallet.core.service.NodeStatusPort;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * ADR-043: what the wallet does about the history routes no published Yano
 * release serves.
 *
 * <p>Verified in the v0.1.0-pre13 <em>release artifact</em>, not just the source
 * tree — the class list of the release jar has no {@code AddressResource} and no
 * {@code HistoryResource}, and {@code TransactionResource} serves
 * {@code /txs/{hash}} and {@code /txs/{hash}/utxos} but no
 * {@code /txs/{hash}/status}. Checking the artifact is the point: an unreleased
 * build of the same version DOES carry all three, so the source tree alone
 * cannot tell you what a user's node will answer.
 *
 * <pre>unzip -l yano.jar | grep -E 'AddressResource|HistoryResource'</pre>
 *
 * <p>The status gap is the one that bit hardest, and silently: every
 * confirmation poll 404'd, so a transaction sitting in a block was still shown
 * as pending and then marked failed five minutes later.
 */
class PublishedNodeHistoryGapTest {

    private static final String STAKE = "stake_test1ur96ax323q0xq0lnn5mwnpa6e7u8a6vq28ez93078m7n2rq9emzd3";
    private static final String ADDRESS = "addr_test1qq_wallet";
    private static final String TX = "aa11";

    private StubYanoNode node;

    @BeforeEach
    void setUp() throws IOException {
        node = new StubYanoNode();
    }

    @AfterEach
    void tearDown() {
        node.close();
    }

    private YanoNodePorts ports() {
        return new YanoNodePorts(new YanoNodeClient(node.baseUrl(), false));
    }

    // ---- no transaction index ----------------------------------------------

    @Test
    void aNodeWithNoTransactionIndexSaysSoDistinctlyFromABrokenOne() {
        // Nothing registered → 404 on the account route, exactly like a real
        // published node. The distinct type is what lets the UI fall back to the
        // wallet's own record instead of showing an error.
        assertThatThrownBy(() -> ports().walletTransactions(STAKE, ADDRESS, 1, 10, true))
                .isInstanceOf(HistoryPort.HistoryNotSupportedException.class);
    }

    @Test
    void aNodeThatIsMerelyUnhealthyIsNotMistakenForOneWithoutAnIndex() {
        // 500, not 404. Falling back here would replace the user's real history
        // with a partial list and never tell them a thing was wrong.
        node.on("/api/v1/accounts/" + STAKE + "/transactions",
                req -> new StubYanoNode.Response(500, "application/json", "{\"error\":\"boom\"}"));

        assertThatThrownBy(() -> ports().walletTransactions(STAKE, ADDRESS, 1, 10, true))
                .isInstanceOf(HistoryPort.HistoryUnavailableException.class)
                .isNotInstanceOf(HistoryPort.HistoryNotSupportedException.class);
    }

    @Test
    void aHistoryDatasetTheOperatorSwitchedOffFallsBackRatherThanErroring() {
        // Verbatim from a post-pre13 Yano build, which serves these routes behind
        // per-dataset switches. "Off" is a permanent-until-reconfigured absence,
        // not a hiccup — and the wallet's own managed node does not pass the
        // enabling profile, so this is the shape wallet users will meet.
        node.on("/api/v1/accounts/" + STAKE + "/transactions",
                req -> new StubYanoNode.Response(503, "application/json",
                        "{\"error\":\"Address tx history disabled (enable yano.history.datasets…)\"}"));

        assertThatThrownBy(() -> ports().walletTransactions(STAKE, ADDRESS, 1, 10, true))
                .isInstanceOf(HistoryPort.HistoryNotSupportedException.class);
    }

    @Test
    void anEmptyIndexIsAnEmptyListNotAMissingIndex() {
        // A backend that DOES serve the route answers 200 [] for an account with
        // nothing in it. That must stay an ordinary empty history.
        node.on("/api/v1/accounts/" + STAKE + "/transactions", "[]");

        assertThat(ports().walletTransactions(STAKE, ADDRESS, 1, 10, true)).isEmpty();
    }

    // ---- tx status falls back to /txs/{hash} --------------------------------

    @Test
    void aTransactionInABlockIsRecognisedThroughTxs() {
        node.on("/api/v1/txs/" + TX, """
                {"hash":"aa11","block":"cc33","block_height":4242,"block_time":1699999000,
                 "slot":99000,"index":0,"fees":"170000"}
                """);

        NodeStatusPort.TxStatusView status = ports().txStatus(TX);

        assertThat(status.state()).isEqualTo(NodeStatusPort.TxState.IN_BLOCK);
        assertThat(status.blockHeight()).isEqualTo(4242);
        assertThat(status.slot()).isEqualTo(99000);
        assertThat(status.blockHash()).isEqualTo("cc33");
        assertThat(status.blockTime()).isEqualTo(1699999000L);
    }

    @Test
    void aTransactionNeitherRouteKnowsIsUnknownRatherThanAnError() {
        // Both 404: the submit may still be in the mempool. Reporting an error
        // here would let a healthy in-flight transaction look like a failure.
        NodeStatusPort.TxStatusView status = ports().txStatus(TX);

        assertThat(status.state()).isEqualTo(NodeStatusPort.TxState.UNKNOWN);
    }

    @Test
    void aTxStatus503MustNotBeReadAsNotOnChain() {
        // /txs/{hash} answers 503 when the UTxO index is switched off — the node
        // cannot look, which is NOT the same as looking and finding nothing.
        // Throwing lets WalletService#reconcile leave the record alone; mapping
        // it to UNKNOWN would mark a transaction that is in a block "failed".
        // Note the deliberate asymmetry with the history routes, where 503 DOES
        // mean "nothing to serve" (see the 503 test above).
        node.on("/api/v1/txs/" + TX,
                req -> new StubYanoNode.Response(503, "application/json",
                        "{\"error\":\"UTXO state disabled\"}"));

        assertThatThrownBy(() -> ports().txStatus(TX))
                .isInstanceOf(NodeClientException.class);
    }

    @Test
    void theDeprecatedStatusRouteIsNotConsultedAtAll() {
        // /txs/{hash}/status is deprecated and being removed, so it is not asked
        // even where it still answers. /txs/{hash} is Blockfrost standard and
        // present on Yano, yaci-store and Blockfrost alike, so one route covers
        // every backend and each poll costs one call, not two.
        node.on("/api/v1/txs/" + TX + "/status", """
                {"tx_hash":"aa11","status":"pending","block_height":-1,"slot":0,"confirmations":0}
                """);

        ports().txStatus(TX);

        assertThat(node.requests()).noneMatch(request -> request.path().endsWith("/status"));
    }

    @Test
    void aStatusRouteThatIsSwitchedOffCannotBreakConfirmationTracking() {
        // Observed verbatim on a live preprod node running a post-pre13 build:
        // the deprecated route exists and answers 503, not 404. An earlier
        // version of this client tried /status first and fell back only on 404,
        // which threw here and left every transaction stuck pending. Nothing
        // reads that route now, so its answer cannot matter — pinned because
        // "try the richer route first" is a tempting thing to reintroduce.
        node.on("/api/v1/txs/" + TX + "/status",
                req -> new StubYanoNode.Response(503, "application/json",
                        "{\"status\":\"incomplete\",\"detail\":\"transaction history is disabled or unavailable\"}"));
        node.on("/api/v1/txs/" + TX, """
                {"hash":"aa11","block":"cc33","block_height":4242,"block_time":1699999000,"slot":99000}
                """);

        assertThat(ports().txStatus(TX).state()).isEqualTo(NodeStatusPort.TxState.IN_BLOCK);
    }
}
