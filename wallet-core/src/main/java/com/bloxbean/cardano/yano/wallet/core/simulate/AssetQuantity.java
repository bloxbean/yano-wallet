package com.bloxbean.cardano.yano.wallet.core.simulate;

import java.math.BigInteger;
import java.util.Objects;

/**
 * A native-asset amount (ADR-042). ADA is never represented here — it is carried
 * as a separate lovelace field everywhere — so {@code policyId} is always a real
 * 28-byte policy hash.
 *
 * <p>{@code assetNameHex} stays hex all the way through the engine. Decoding it
 * for display is the view layer's job, because an asset name is arbitrary
 * attacker-chosen bytes and must be sanitised before it reaches a security
 * dialog.
 */
public record AssetQuantity(String policyId, String assetNameHex, BigInteger quantity) {

    public AssetQuantity {
        Objects.requireNonNull(policyId, "policyId is required");
        assetNameHex = assetNameHex == null ? "" : assetNameHex;
        quantity = quantity == null ? BigInteger.ZERO : quantity;
    }

    /** The canonical {@code policyId + assetNameHex} key used to aggregate the diff. */
    public String unit() {
        return policyId + assetNameHex;
    }
}
