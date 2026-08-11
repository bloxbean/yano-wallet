package com.bloxbean.cardano.yano.wallet.hardware.ledger;

import java.util.List;

/**
 * A native-asset policy group within an output (ADR-034): the 28-byte policy id
 * and its tokens. The host must present groups and tokens in the same canonical
 * order the transaction body uses, so the device rebuilds identical CBOR.
 */
public record LedgerAssetGroup(byte[] policyId, List<LedgerToken> tokens) {

    public LedgerAssetGroup {
        if (policyId == null || policyId.length != 28) {
            throw new IllegalArgumentException("policyId must be 28 bytes");
        }
        tokens = tokens == null ? List.of() : List.copyOf(tokens);
    }
}
