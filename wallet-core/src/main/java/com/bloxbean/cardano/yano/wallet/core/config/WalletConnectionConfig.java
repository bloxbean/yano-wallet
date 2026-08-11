package com.bloxbean.cardano.yano.wallet.core.config;

/**
 * How the wallet reaches a Yano node (ADR-033 A3). Either a managed local node
 * the wallet launches and supervises, or an external node URL the user runs.
 * Persisted per install so the choice survives restarts.
 */
public record WalletConnectionConfig(
        Mode mode,
        WalletNetwork network,
        String externalBaseUrl,   // used only when mode == EXTERNAL
        Integer managedHttpPort   // used only when mode == MANAGED (null → default)
) {
    public enum Mode {MANAGED, EXTERNAL}

    public static WalletConnectionConfig managed(WalletNetwork network) {
        return new WalletConnectionConfig(Mode.MANAGED, network, null, null);
    }

    public static WalletConnectionConfig managed(WalletNetwork network, int httpPort) {
        return new WalletConnectionConfig(Mode.MANAGED, network, null, httpPort);
    }

    public static WalletConnectionConfig external(WalletNetwork network, String baseUrl) {
        return new WalletConnectionConfig(Mode.EXTERNAL, network, baseUrl, null);
    }

    public boolean isManaged() {
        return mode == Mode.MANAGED;
    }
}
