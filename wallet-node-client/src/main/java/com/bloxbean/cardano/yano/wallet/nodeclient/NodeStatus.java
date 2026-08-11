package com.bloxbean.cardano.yano.wallet.nodeclient;

/**
 * Snapshot of the local Yano node's chain tip and UTXO indexer state,
 * from {@code GET /status}.
 */
public record NodeStatus(
        long slot,
        long blockNumber,
        String blockHash,
        boolean utxoIndexEnabled,
        long utxoLastAppliedBlock,
        long utxoLagBlocks) {

    /**
     * The wallet can trust balances once the UTXO index has caught up with the
     * tip. A freshly-started node reports tip 0 and lag 0 before anything is
     * indexed, so an unknown tip must not count as caught up.
     */
    public boolean utxoIndexCaughtUp() {
        return utxoIndexEnabled && blockNumber > 0 && utxoLagBlocks == 0;
    }
}
