package com.bloxbean.cardano.yano.wallet.core.simulate;

import java.math.BigInteger;
import java.util.List;
import java.util.Objects;

/**
 * A transaction output resolved from an output reference (ADR-042) — one entry of
 * what the node's {@code GET /txs/{txHash}/utxos} answers. This is what makes the
 * value diff possible: a transaction's inputs name outputs, and only a node with
 * the chain can say whose they are and what they hold.
 */
public record ResolvedOutput(String address,
                             BigInteger lovelace,
                             List<AssetQuantity> assets,
                             boolean hasDatum,
                             boolean hasReferenceScript) {

    public ResolvedOutput {
        Objects.requireNonNull(address, "address is required");
        lovelace = lovelace == null ? BigInteger.ZERO : lovelace;
        assets = assets == null ? List.of() : List.copyOf(assets);
    }
}
