package com.bloxbean.cardano.yano.wallet.core.hardware;

/**
 * A supported hardware-wallet family (ADR-034). Ledger is the first target;
 * the enum is the extension point for Trezor and airgapped devices, which
 * share the watch-only keystore model but differ in transport/protocol.
 */
public enum DeviceType {
    LEDGER;
}
