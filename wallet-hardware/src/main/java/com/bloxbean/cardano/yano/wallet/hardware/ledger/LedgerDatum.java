package com.bloxbean.cardano.yano.wallet.hardware.ledger;

/**
 * An output datum for Ledger signing (ADR-035 M4 / Plutus outputs): either a
 * 32-byte datum hash or the full inline datum CBOR. Inline datums stream to the
 * device in 240-byte chunks.
 */
public record LedgerDatum(int type, byte[] bytes) {

    public static final int TYPE_HASH = 0;
    public static final int TYPE_INLINE = 1;

    public LedgerDatum {
        if (type != TYPE_HASH && type != TYPE_INLINE) {
            throw new IllegalArgumentException("Unknown datum type: " + type);
        }
        if (bytes == null || bytes.length == 0) {
            throw new IllegalArgumentException("datum bytes are required");
        }
        if (type == TYPE_HASH && bytes.length != 32) {
            throw new IllegalArgumentException("datum hash must be 32 bytes");
        }
    }

    public static LedgerDatum hash(byte[] hash) {
        return new LedgerDatum(TYPE_HASH, hash);
    }

    public static LedgerDatum inline(byte[] datumCbor) {
        return new LedgerDatum(TYPE_INLINE, datumCbor);
    }
}
