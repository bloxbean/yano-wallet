package com.bloxbean.cardano.yano.wallet.core.service;

import java.math.BigInteger;
import java.util.List;

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

    record TxRef(String txHash, long blockHeight, long blockTime, long slot) {
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
