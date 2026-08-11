package com.bloxbean.cardano.yano.wallet.core.service;

/**
 * Node-side data the wallet needs beyond CCL's supplier interfaces.
 * Implemented by wallet-node-client against the Yano REST API (ADR-033 M2).
 */
public interface NodeStatusPort {
    /** Chain tip + UTXO indexer state. */
    NodeView status();

    /** Wallet-facing status of a transaction. */
    TxStatusView txStatus(String txHash);

    /** Stake account state; {@code registered=false} for never-seen accounts. */
    AccountView accountInfo(String stakeAddress);

    /**
     * Stake account state. {@code drepId}/{@code drepType} are the account's
     * current vote-delegation target (CIP-1694); both null when the account
     * delegates its voting power to nobody. {@code drepType} is one of
     * {@code key_hash}/{@code script_hash}/{@code abstain}/{@code no_confidence}.
     */
    record AccountView(boolean registered, java.math.BigInteger withdrawable, String delegatedPoolId,
                       String drepId, String drepType) {
    }

    record NodeView(long slot, long blockNumber, boolean utxoIndexEnabled,
                    long utxoLagBlocks, boolean caughtUp) {
    }

    enum TxState {PENDING, IN_BLOCK, UNKNOWN}

    record TxStatusView(String txHash, TxState state, long blockHeight, long slot, String blockHash,
                        long confirmations, long blockTime) {
    }
}
