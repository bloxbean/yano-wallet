package com.bloxbean.cardano.yano.wallet.hardware.ledger;

/**
 * A witness the device returned for one signing path (ADR-034): the BIP32 path
 * and the 64-byte Ed25519 signature. A staking transaction needs two — a
 * payment-key witness for the inputs and a stake-key witness for the
 * certificate/withdrawal.
 */
public record LedgerWitness(long[] path, byte[] signature) {
}
