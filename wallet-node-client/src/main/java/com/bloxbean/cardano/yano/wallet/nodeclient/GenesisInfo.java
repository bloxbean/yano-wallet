package com.bloxbean.cardano.yano.wallet.nodeclient;

/** Subset of {@code GET /genesis} needed by the wallet, Blockfrost-shaped. */
public record GenesisInfo(
        long networkMagic,
        String systemStart,
        long epochLength,
        int slotLength,
        long securityParam) {
}
