package com.bloxbean.cardano.yano.wallet.hardware.ledger;

import java.math.BigInteger;

/**
 * A native token within an asset group (ADR-034): the asset name bytes and an
 * amount.
 *
 * <p>The sign rule depends on where the token is used, so it is enforced by the
 * serializer rather than here: <b>output</b> amounts are unsigned (uint64), while
 * <b>mint</b> amounts are signed (int64) — a burn is negative.
 */
public record LedgerToken(byte[] assetName, BigInteger amount) {

    public LedgerToken {
        if (assetName == null) {
            assetName = new byte[0];
        }
        if (amount == null) {
            throw new IllegalArgumentException("token amount is required");
        }
    }
}
