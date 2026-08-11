package com.bloxbean.cardano.yano.wallet.hardware.ledger;

/**
 * A transaction input for Ledger signing (ADR-034): the UTXO's transaction id
 * and output index. Serialized to the device as {@code txHash(32) || index(4 BE)}.
 *
 * @param txHashHex 64-hex-character transaction id
 * @param index     output index within that transaction
 */
public record LedgerTxInput(String txHashHex, int index) {

    public LedgerTxInput {
        if (txHashHex == null || !txHashHex.matches("[0-9a-fA-F]{64}")) {
            throw new IllegalArgumentException("txHashHex must be 64 hex chars");
        }
        if (index < 0) {
            throw new IllegalArgumentException("index must be >= 0");
        }
    }
}
