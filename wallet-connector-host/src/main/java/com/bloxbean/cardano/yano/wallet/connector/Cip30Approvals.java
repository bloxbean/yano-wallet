package com.bloxbean.cardano.yano.wallet.connector;

/**
 * The user-consent gate for the CIP-30 bridge (ADR-035). Implemented by the wallet
 * app with a JavaFX dialog + a persisted per-origin allowlist. All methods are
 * called on a bridge worker thread and block until the user decides, so the
 * implementation must marshal to the UI thread and wait.
 */
public interface Cip30Approvals {

    /** True if {@code origin} is already on the allowlist (drives {@code isEnabled}). */
    boolean isConnected(String origin);

    /**
     * Prompts the user to connect {@code origin}; returns true if granted (and the
     * origin is added to the allowlist). Drives {@code enable}.
     */
    boolean confirmConnect(String origin);

    /**
     * Prompts the user to approve signing {@code txHex}.
     *
     * <p>The raw transaction is passed rather than a summary string because this
     * module is deliberately node-free "pure protocol" and cannot work out what a
     * transaction does. The implementation simulates it against the user's own
     * node and shows the result (ADR-042); the bridge's job is only to hand over
     * exactly what the dApp asked to have signed.
     */
    boolean confirmSign(String origin, String txHex, boolean partialSign);

    /**
     * Prompts the user to approve a CIP-8 data signature by the key for
     * {@code address}. Separate from {@link #confirmSign} because signing data
     * moves no value: there is nothing to simulate and nothing to diff.
     */
    boolean confirmSignData(String origin, String address);
}
