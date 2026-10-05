package com.bloxbean.cardano.yano.wallet.core.service;

import java.math.BigInteger;
import java.util.List;
import java.util.Optional;

/**
 * Transaction/reward history served by the node's ADR-033 M2 endpoints.
 * Implemented by wallet-node-client; a node without the address-tx index
 * throws {@link HistoryUnavailableException} and the UI degrades gracefully.
 */
public interface HistoryPort {
    /**
     * This wallet's transaction history.
     *
     * <p>Both addresses are supplied because backends key history differently and
     * only one of them exists on each: a Yano node serves it per stake account
     * ({@code /accounts/{stake}/transactions}), while yaci-store has no such route
     * and serves it per address ({@code /addresses/{address}/transactions}).
     */
    List<TxRef> walletTransactions(String stakeAddress, String paymentAddress,
                                   int page, int count, boolean newestFirst);

    List<RewardView> rewards(String stakeAddress, int page, int count);

    /**
     * How far a scan running right now has walked the chain, or empty when none
     * is. A first scan from origin walks the whole chain before it can answer,
     * which is long enough that a screen showing nothing reads as a failure —
     * this is what lets the UI say what is happening instead.
     *
     * <p>Read from whatever thread asks, including while the scan itself holds
     * the backend executor.
     */
    default Optional<ScanProgress> scanProgress() {
        return Optional.empty();
    }

    record TxRef(String txHash, long blockHeight, long blockTime, long slot) {
    }

    /**
     * Absolute chain heights: {@code fromBlock} is the cursor the scan resumed
     * from (-1 for origin) and {@code tipBlock} the point the node reported it
     * had indexed through when the scan started.
     */
    record ScanProgress(long fromBlock, long currentBlock, long tipBlock) {
        /** Share of the interval walked, 0..1. A scan with nothing to walk is done. */
        public double fraction() {
            long span = tipBlock - fromBlock;
            if (span <= 0) {
                return 1.0;
            }
            return Math.clamp((double) (currentBlock - fromBlock) / span, 0.0, 1.0);
        }
    }

    record RewardView(int epoch, BigInteger amount, String poolId, String type) {
    }

    class HistoryUnavailableException extends RuntimeException {
        public HistoryUnavailableException(String message) {
            super(message);
        }
    }

    /**
     * The backend serves no transaction index at all — not "the call failed",
     * but "there is nothing here to call". No published Yano release exposes an
     * account- or address-level transaction route, so this is the normal answer
     * against a managed node.
     *
     * <p>Kept separate from its parent on purpose. A caller must be able to tell
     * a permanent absence, which it can work around by falling back to the
     * wallet's own record of what it submitted (ADR-043), from a node that was
     * merely unreachable or unhealthy for a moment — where showing a partial
     * local list instead of the real history would quietly hide transactions.
     */
    class HistoryNotSupportedException extends HistoryUnavailableException {
        public HistoryNotSupportedException(String message) {
            super(message);
        }
    }
}
