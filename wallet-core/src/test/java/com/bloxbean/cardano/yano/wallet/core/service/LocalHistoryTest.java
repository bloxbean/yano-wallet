package com.bloxbean.cardano.yano.wallet.core.service;

import com.bloxbean.cardano.yano.wallet.core.config.WalletNetwork;
import com.bloxbean.cardano.yano.wallet.core.tx.FilePendingTransactionStore;
import com.bloxbean.cardano.yano.wallet.core.tx.PendingTransaction;
import com.bloxbean.cardano.yano.wallet.core.tx.PendingTransactionStatus;
import com.bloxbean.cardano.yano.wallet.core.tx.PendingTransactionStore;
import com.bloxbean.cardano.yano.wallet.core.wallet.FileStoredWalletRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigInteger;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ADR-043: the wallet's own record of what it sent, standing in for chain
 * history on a node that indexes no transactions — which is every published
 * Yano release.
 *
 * <p>What these pin is the settling of a record, because that is where the
 * fallback is easy to get quietly wrong. The list itself is just the store.
 */
class LocalHistoryTest {

    private static final String WALLET = "wlt_1";
    private static final String NETWORK = "preprod";
    private static final long NOW = 1_700_000_000_000L;
    private static final long LONG_AGO = NOW - WalletService.PENDING_TIMEOUT_MILLIS - 1;

    @TempDir
    Path tempDir;

    private PendingTransactionStore store;

    /** Counts what the node was actually asked, so "did not poll" is testable. */
    private static class StubNode implements NodeStatusPort {
        private final NodeStatusPort.TxStatusView answer;
        private final RuntimeException failure;
        private final long lagBlocks;
        final List<String> asked = new ArrayList<>();

        StubNode(NodeStatusPort.TxStatusView answer) {
            this(answer, null, 0L);
        }

        StubNode(NodeStatusPort.TxStatusView answer, RuntimeException failure) {
            this(answer, failure, 0L);
        }

        StubNode(NodeStatusPort.TxStatusView answer, RuntimeException failure, long lagBlocks) {
            this.answer = answer;
            this.failure = failure;
            this.lagBlocks = lagBlocks;
        }

        @Override
        public NodeView status() {
            return new NodeView(1_000L, 5_000_000L, true, lagBlocks, lagBlocks == 0);
        }

        @Override
        public TxStatusView txStatus(String txHash) {
            asked.add(txHash);
            if (failure != null) {
                throw failure;
            }
            return new TxStatusView(txHash, answer.state(), answer.blockHeight(), answer.slot(),
                    answer.blockHash(), answer.confirmations(), answer.blockTime());
        }

        @Override
        public AccountView accountInfo(String stakeAddress) {
            throw new UnsupportedOperationException();
        }
    }

    private static NodeStatusPort.TxStatusView inBlock(long height, long slot, long blockTime) {
        return new NodeStatusPort.TxStatusView("", NodeStatusPort.TxState.IN_BLOCK, height, slot,
                "b".repeat(64), 3, blockTime);
    }

    private static final NodeStatusPort.TxStatusView UNKNOWN =
            new NodeStatusPort.TxStatusView("", NodeStatusPort.TxState.UNKNOWN, -1, 0, null, 0, 0);

    private WalletService serviceWith(NodeStatusPort node) {
        PendingNodeAccess pending = new PendingNodeAccess("no node in this test");
        store = new FilePendingTransactionStore(tempDir.resolve("pending-transactions.json"));
        return new WalletService(new FileStoredWalletRepository(tempDir, WalletNetwork.PREPROD),
                pending, pending, pending, store, node);
    }

    private PendingTransaction save(String txHash, PendingTransactionStatus status, long createdAt) {
        return store.save(new PendingTransaction(txHash, null, createdAt, createdAt, status,
                WALLET, NETWORK, BigInteger.valueOf(1_500_000), BigInteger.valueOf(170_000),
                "addr_from", "addr_to", null, null, null, null, null, null));
    }

    @Test
    void aTransactionTheNodeHasInABlockIsConfirmedAndKeepsItsBlockTime() {
        StubNode node = new StubNode(inBlock(4_242L, 99_000L, 1_699_999_000L));
        WalletService service = serviceWith(node);
        save("aa11", PendingTransactionStatus.PENDING, NOW - 10_000L);

        List<PendingTransaction> history = service.localHistory(WALLET, NETWORK, NOW);

        assertThat(history).singleElement().satisfies(tx -> {
            assertThat(tx.status()).isEqualTo(PendingTransactionStatus.CONFIRMED);
            assertThat(tx.confirmedBlock()).isEqualTo(4_242L);
            // The block time is the whole reason this field exists: without it a
            // confirmed row has nothing to sort or display but its submit time.
            assertThat(tx.confirmedBlockTimeEpochSeconds()).isEqualTo(1_699_999_000L);
        });
        // Persisted, not just returned — the next refresh must not re-poll it.
        assertThat(store.find("aa11")).get()
                .satisfies(tx -> assertThat(tx.status()).isEqualTo(PendingTransactionStatus.CONFIRMED));
    }

    @Test
    void anAlreadyConfirmedRecordIsNeverPolledAgain() {
        StubNode node = new StubNode(UNKNOWN);
        WalletService service = serviceWith(node);
        save("aa11", PendingTransactionStatus.PENDING, NOW - 10_000L);
        store.save(store.find("aa11").orElseThrow().markConfirmed(1L, 2L, "c".repeat(64), 1_699_000_000L));

        service.localHistory(WALLET, NETWORK, NOW);

        assertThat(node.asked).isEmpty();
    }

    @Test
    void anUnreachableNodeLeavesAStaleRecordAloneRatherThanCallingItFailed() {
        // The one outcome worth going out of the way to avoid: a transaction that
        // is on chain shown as failed because a localhost call happened to fail.
        StubNode node = new StubNode(UNKNOWN, new RuntimeException("connection refused"));
        WalletService service = serviceWith(node);
        save("aa11", PendingTransactionStatus.PENDING, LONG_AGO);

        List<PendingTransaction> history = service.localHistory(WALLET, NETWORK, NOW);

        assertThat(history).singleElement()
                .satisfies(tx -> assertThat(tx.status()).isEqualTo(PendingTransactionStatus.PENDING));
        assertThat(store.find("aa11")).get()
                .satisfies(tx -> assertThat(tx.status()).isEqualTo(PendingTransactionStatus.PENDING));
    }

    @Test
    void aRecordTheNodeAnswersOnAndHasNotSeenPastTheTimeoutIsMarkedFailed() {
        StubNode node = new StubNode(UNKNOWN);
        WalletService service = serviceWith(node);
        save("aa11", PendingTransactionStatus.PENDING, LONG_AGO);

        List<PendingTransaction> history = service.localHistory(WALLET, NETWORK, NOW);

        assertThat(history).singleElement().satisfies(tx -> {
            assertThat(tx.status()).isEqualTo(PendingTransactionStatus.FAILED);
            assertThat(tx.lastError()).contains("5 minutes");
        });
    }

    @Test
    void aNodeStillCatchingUpNeverExpiresAnything() {
        // The five-minute timeout assumes the node can see the tip. One that is
        // 6,000 blocks behind cannot: the transaction may already be in a block
        // the index has not reached, so /txs/{hash} truthfully says "not found".
        // Expiring on that marks a good transaction failed — and it is exactly
        // the state a wallet is in just after a first sync.
        StubNode node = new StubNode(UNKNOWN, null, 6_373L);
        WalletService service = serviceWith(node);
        save("aa11", PendingTransactionStatus.PENDING, LONG_AGO);

        List<PendingTransaction> history = service.localHistory(WALLET, NETWORK, NOW);

        assertThat(history).singleElement()
                .satisfies(tx -> assertThat(tx.status()).isEqualTo(PendingTransactionStatus.PENDING));
        assertThat(store.find("aa11")).get()
                .satisfies(tx -> assertThat(tx.status()).isEqualTo(PendingTransactionStatus.PENDING));
    }

    @Test
    void aCatchingUpNodeStillConfirmsWhatItCanSee() {
        // Not expiring must not mean not reconciling: once the index reaches the
        // block, the record settles even though the node is still behind the tip.
        StubNode node = new StubNode(inBlock(4_242L, 99_000L, 1_699_999_000L), null, 6_373L);
        WalletService service = serviceWith(node);
        save("aa11", PendingTransactionStatus.PENDING, LONG_AGO);

        assertThat(service.localHistory(WALLET, NETWORK, NOW)).singleElement()
                .satisfies(tx -> assertThat(tx.status()).isEqualTo(PendingTransactionStatus.CONFIRMED));
    }

    @Test
    void aRecordWronglyTimedOutRecoversOnceTheNodeCanSeeIt() {
        // "failed" from a timeout is a guess, not a fact — and awaitsConfirmation()
        // is false once failed, so without this nothing ever looks again and the
        // wrong verdict outlives the sync that would have overturned it.
        StubNode node = new StubNode(inBlock(4_242L, 99_000L, 1_699_999_000L));
        WalletService service = serviceWith(node);
        save("aa11", PendingTransactionStatus.PENDING, LONG_AGO);
        store.save(store.find("aa11").orElseThrow()
                .markFailed("Not seen on chain within 5 minutes"));

        assertThat(service.localHistory(WALLET, NETWORK, NOW)).singleElement()
                .satisfies(tx -> assertThat(tx.status()).isEqualTo(PendingTransactionStatus.CONFIRMED));
    }

    @Test
    void aTransactionTheNodeRejectedIsNotSecondGuessed() {
        // Its message is the real reason and more useful than a generic retry.
        StubNode node = new StubNode(UNKNOWN);
        WalletService service = serviceWith(node);
        save("aa11", PendingTransactionStatus.PENDING, LONG_AGO);
        store.save(store.find("aa11").orElseThrow().markFailed("ValueNotConservedUTxO"));

        List<PendingTransaction> history = service.localHistory(WALLET, NETWORK, NOW);

        assertThat(history).singleElement()
                .satisfies(tx -> assertThat(tx.lastError()).isEqualTo("ValueNotConservedUTxO"));
        assertThat(node.asked).isEmpty();
    }

    @Test
    void inFlightRecordsGetTheLookupBudgetBeforeFailedOnes() {
        // A devnet-reset wallet full of dead records must not starve the
        // transaction the user just sent.
        StubNode node = new StubNode(UNKNOWN);
        WalletService service = serviceWith(node);
        for (int i = 0; i < 25; i++) {
            String hash = String.format("%064x", i);
            save(hash, PendingTransactionStatus.PENDING, LONG_AGO);
            store.save(store.find(hash).orElseThrow().markFailed("Not seen on chain within 5 minutes"));
        }
        save("aa11", PendingTransactionStatus.PENDING, NOW - 1_000L);

        service.localHistory(WALLET, NETWORK, NOW);

        assertThat(node.asked).hasSize(20).contains("aa11");
    }

    @Test
    void aRecentUnseenRecordStaysPending() {
        StubNode node = new StubNode(UNKNOWN);
        WalletService service = serviceWith(node);
        save("aa11", PendingTransactionStatus.PENDING, NOW - 1_000L);

        assertThat(service.localHistory(WALLET, NETWORK, NOW)).singleElement()
                .satisfies(tx -> assertThat(tx.status()).isEqualTo(PendingTransactionStatus.PENDING));
    }

    @Test
    void anotherWalletsTransactionsAreNotListed() {
        WalletService service = serviceWith(new StubNode(UNKNOWN));
        save("aa11", PendingTransactionStatus.PENDING, NOW - 1_000L);
        store.save(new PendingTransaction("bb22", null, NOW, NOW, PendingTransactionStatus.PENDING,
                "wlt_other", NETWORK, BigInteger.ONE, BigInteger.ONE, "f", "t",
                null, null, null, null, null, null));

        assertThat(service.localHistory(WALLET, NETWORK, NOW))
                .extracting(PendingTransaction::txHash).containsExactly("aa11");
    }

    @Test
    void theNumberOfNodeLookupsPerRefreshIsBounded() {
        // History refreshes on every dashboard tick. A reset devnet can leave
        // dozens of records stuck unconfirmed, and without a bound each tick
        // would fire one node call per record, forever.
        StubNode node = new StubNode(UNKNOWN);
        WalletService service = serviceWith(node);
        for (int i = 0; i < 30; i++) {
            save(String.format("%064x", i), PendingTransactionStatus.PENDING, NOW - 1_000L);
        }

        List<PendingTransaction> history = service.localHistory(WALLET, NETWORK, NOW);

        assertThat(history).hasSize(30);
        assertThat(node.asked).hasSize(20);
    }
}
