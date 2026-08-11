package com.bloxbean.cardano.yano.wallet.core.service;

import java.math.BigInteger;
import java.util.List;

/**
 * Transaction/reward history served by the node's ADR-033 M2 endpoints.
 * Implemented by wallet-node-client; a node without the address-tx index
 * throws {@link HistoryUnavailableException} and the UI degrades gracefully.
 */
public interface HistoryPort {
    List<TxRef> accountTransactions(String stakeAddress, int page, int count, boolean newestFirst);

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
}
