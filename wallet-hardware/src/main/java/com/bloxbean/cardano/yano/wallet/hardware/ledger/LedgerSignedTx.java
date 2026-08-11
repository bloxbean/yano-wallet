package com.bloxbean.cardano.yano.wallet.hardware.ledger;

import java.util.List;

/**
 * Result of a Ledger signing (ADR-034): the transaction id the device computed
 * (which must equal the host's blake2b-256 of the tx body) and one Ed25519
 * witness per requested signing path.
 *
 * @param txHashHex 32-byte transaction id, hex
 * @param witnesses one per signing path (payment for inputs, stake for certs/withdrawals)
 */
public record LedgerSignedTx(String txHashHex, List<LedgerWitness> witnesses) {

    public LedgerSignedTx {
        witnesses = witnesses == null ? List.of() : List.copyOf(witnesses);
    }
}
