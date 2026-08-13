package com.bloxbean.cardano.yano.wallet.core.config;

/**
 * How the wallet reaches a chain (ADR-033 A3). Either a managed local node the
 * wallet launches and supervises, or an external backend URL the user runs or
 * subscribes to. Persisted per install so the choice survives restarts.
 *
 * <p>The backend {@link BackendFlavor} lives here rather than on
 * {@link WalletNetwork} (ADR-043 §2): the same network can be reached through a
 * Yano node or through hosted Blockfrost, so flavor is a property of the
 * connection, not of the chain.
 *
 * @param apiKey credential for backends that need one — Blockfrost's
 *               {@code project_id}, or whatever an auth gateway in front of a
 *               Yano node expects. Null or blank means send no header, which is
 *               every local node and every DevKit. <strong>Never log this
 *               field</strong>; {@link #toString()} is overridden to keep it out
 *               of accidental interpolation.
 */
public record WalletConnectionConfig(
        Mode mode,
        WalletNetwork network,
        String externalBaseUrl,   // used only when mode == EXTERNAL
        Integer managedHttpPort,  // used only when mode == MANAGED (null → default)
        BackendFlavor flavor,
        String apiKey
) {
    public enum Mode {MANAGED, EXTERNAL}

    public WalletConnectionConfig {
        // A managed connection is a Yano node by construction — the wallet
        // launches it — so the flavor is not the user's to get wrong.
        if (mode == Mode.MANAGED) {
            flavor = BackendFlavor.YANO;
        }
        if (flavor == null) {
            flavor = BackendFlavor.YANO;
        }
        if (apiKey != null && apiKey.isBlank()) {
            apiKey = null; // one representation of "no credential"
        }
    }

    public static WalletConnectionConfig managed(WalletNetwork network) {
        return new WalletConnectionConfig(Mode.MANAGED, network, null, null, BackendFlavor.YANO, null);
    }

    public static WalletConnectionConfig managed(WalletNetwork network, int httpPort) {
        return new WalletConnectionConfig(Mode.MANAGED, network, null, httpPort, BackendFlavor.YANO, null);
    }

    /** An external backend with no credential — a Yano node, or a DevKit. */
    public static WalletConnectionConfig external(WalletNetwork network, String baseUrl) {
        return external(network, baseUrl, defaultFlavorFor(network), null);
    }

    public static WalletConnectionConfig external(WalletNetwork network, String baseUrl,
                                                  BackendFlavor flavor, String apiKey) {
        return new WalletConnectionConfig(Mode.EXTERNAL, network, baseUrl, null, flavor, apiKey);
    }

    /**
     * What an external URL is assumed to be when nothing says otherwise: a Yano
     * node, except for the DevKit entry, whose whole purpose is to declare
     * yaci-store. Hosted Blockfrost is never inferred — it is chosen by
     * supplying a key, because guessing it would silently relax the mainnet rule.
     */
    private static BackendFlavor defaultFlavorFor(WalletNetwork network) {
        return network == WalletNetwork.YACI_DEVKIT ? BackendFlavor.YACI_STORE : BackendFlavor.YANO;
    }

    public boolean isManaged() {
        return mode == Mode.MANAGED;
    }

    public boolean hasApiKey() {
        return apiKey != null;
    }

    /**
     * Redacted: this record reaches log lines and exception messages, and the
     * default record {@code toString} would print the credential in full.
     */
    @Override
    public String toString() {
        return "WalletConnectionConfig[mode=" + mode + ", network=" + network
                + ", externalBaseUrl=" + externalBaseUrl + ", managedHttpPort=" + managedHttpPort
                + ", flavor=" + flavor + ", apiKey=" + (apiKey == null ? "none" : "<redacted>") + "]";
    }
}
