package com.bloxbean.cardano.yano.wallet.core.service;

import com.bloxbean.cardano.yano.wallet.core.tx.PendingTransaction;
import com.bloxbean.cardano.yano.wallet.core.tx.PendingTransactionStatus;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A submitted transaction that never reaches the chain used to have no way out
 * of "pending": the only exit is the node's history containing its hash, so a
 * transaction rejected after submission — or one belonging to a devnet that has
 * since been reset — sat at the top of Recent Activity forever, claiming to be
 * in flight.
 *
 * <p>The rule lives on the record because it is a property of the record, not of
 * the money path: {@code WalletService.expirePendingIfStale} is only the store
 * plumbing around it.
 */
class PendingExpiryTest {

    private static final long NOW = 1_700_000_000_000L;
    private static final long TIMEOUT = WalletService.PENDING_TIMEOUT_MILLIS;

    private static PendingTransaction submittedAt(long createdAtMillis) {
        return new PendingTransaction("aa11", null, createdAtMillis, createdAtMillis,
                PendingTransactionStatus.PENDING, "wlt_1", "yaci-devkit",
                BigInteger.valueOf(1_000_000), BigInteger.valueOf(170_000),
                "addr_test1_from", "addr_test1_to", null, null, null, null, null);
    }

    @Test
    void theTimeoutIsFiveMinutes() {
        assertThat(TIMEOUT).isEqualTo(5 * 60 * 1000L);
    }

    @Test
    void aTransactionUnseenBeyondTheTimeoutIsStale() {
        assertThat(submittedAt(NOW - TIMEOUT - 1).isStale(NOW, TIMEOUT)).isTrue();
    }

    @Test
    void aRecentTransactionIsLeftAlone() {
        // The dangerous direction: calling a transaction failed while it is still
        // plausibly on its way. The record is the user's only evidence it was
        // sent, so it must outlive a slow block.
        assertThat(submittedAt(NOW - TIMEOUT + 1000).isStale(NOW, TIMEOUT)).isFalse();
        assertThat(submittedAt(NOW).isStale(NOW, TIMEOUT)).isFalse();
    }

    @Test
    void theBoundaryItselfIsNotYetStale() {
        assertThat(submittedAt(NOW - TIMEOUT).isStale(NOW, TIMEOUT)).isFalse();
    }

    @Test
    void anAlreadyFailedRecordKeepsTheRealReason() {
        // Re-marking on every history reload would overwrite "the node rejected
        // this, and why" with a generic timeout message.
        PendingTransaction rejected = submittedAt(NOW - TIMEOUT - 1)
                .markFailed("Node rejected: ValueNotConservedUTxO");

        assertThat(rejected.isStale(NOW, TIMEOUT)).isFalse();
        assertThat(rejected.lastError()).contains("ValueNotConservedUTxO");
    }

    @Test
    void expiringMarksTheRecordAndKeepsItReadable() {
        // Marked, not deleted: a transaction the user submitted that did not
        // arrive is exactly what they should still be able to see.
        PendingTransaction expired = submittedAt(NOW - TIMEOUT - 1)
                .markFailed("Not seen on chain within 5 minutes");

        assertThat(expired.status()).isEqualTo(PendingTransactionStatus.FAILED);
        assertThat(expired.txHash()).isEqualTo("aa11");
        assertThat(expired.lovelace()).isEqualTo(BigInteger.valueOf(1_000_000));
        assertThat(expired.createdAtEpochMillis())
                .as("the submission time is what History sorts it by")
                .isEqualTo(NOW - TIMEOUT - 1);
    }
}
