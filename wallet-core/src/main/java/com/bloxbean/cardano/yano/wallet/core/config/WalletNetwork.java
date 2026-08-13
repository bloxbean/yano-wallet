package com.bloxbean.cardano.yano.wallet.core.config;

import com.bloxbean.cardano.client.common.model.Network;
import com.bloxbean.cardano.client.common.model.Networks;

import java.util.Arrays;

public enum WalletNetwork {
    DEVNET("devnet", false, 42),
    /**
     * A Yaci DevKit devnet, served by yaci-store (ADR-038). Same chain shape as
     * {@link #DEVNET} (magic 42, devkit's default) but a distinct entry because
     * choosing it also declares the <em>backend flavor</em> — yaci-store cannot
     * report its own network, so the user asserts it. Its own storage id keeps
     * throwaway devkit wallets apart from a hand-run devnet's.
     */
    YACI_DEVKIT("yaci-devkit", false, 42),
    PREVIEW("preview", false, 2),
    PREPROD("preprod", false, 1),
    MAINNET("mainnet", true, 764824073);

    private final String id;
    private final boolean production;
    private final long protocolMagic;

    WalletNetwork(String id, boolean production, long protocolMagic) {
        this.id = id;
        this.production = production;
        this.protocolMagic = protocolMagic;
    }

    public String id() {
        return id;
    }

    public boolean production() {
        return production;
    }

    public long protocolMagic() {
        return protocolMagic;
    }

    /** The address network id nibble: 1 for mainnet, 0 for every test network. */
    public int networkId() {
        return production ? 1 : 0;
    }

    public Network toCclNetwork() {
        return switch (this) {
            case DEVNET, YACI_DEVKIT -> new Network(0, protocolMagic);
            case PREPROD -> Networks.preprod();
            case PREVIEW -> Networks.preview();
            case MAINNET -> Networks.mainnet();
        };
    }

    /**
     * True when this network can only be served by a Blockfrost-compatible
     * backend the wallet cannot launch — the DevKit entry, which exists to
     * declare yaci-store (ADR-038).
     *
     * <p>Not a flavor accessor. Flavor moved to {@link BackendFlavor} on the
     * connection (ADR-043 §2) once the same network could be reached through a
     * Yano node or through hosted Blockfrost; asking a <em>network</em> which
     * backend serves it stopped having an answer. This asks the one question
     * that is still about the network: whether the wallet can run a node for it.
     */
    public boolean requiresExternalBackend() {
        return this == YACI_DEVKIT;
    }

    /** Default backend URL for networks that have a conventional one. */
    public String defaultBaseUrl() {
        return this == YACI_DEVKIT ? "http://localhost:8080/api/v1" : null;
    }

    /**
     * Relays a managed node syncs from, best first. More than one per public
     * network on purpose — see {@link UpstreamRelay} for why a single upstream is
     * a stall waiting to happen.
     *
     * <p>Each pair resolves to independent hosts, which is the point: two names
     * for one machine would look like redundancy and provide none. All six were
     * confirmed to resolve and accept TCP on 2026-08-13, and they are official
     * IOG / Cardano Foundation infrastructure rather than third-party pool relays,
     * which makes them likelier to outlive this release.
     *
     * <p>Empty for the two devnets: a Yano devnet syncs from whatever local node
     * the user runs (its own Quarkus profile decides), and a Yaci DevKit has no
     * managed node at all. Hardcoded public relays would be wrong for both.
     *
     * <p>These will rot eventually — no hostname shipped in a desktop app lives
     * forever. That is what the per-network override in settings is for, and why
     * node readiness is deliberately "the REST API answers" rather than "the chain
     * is advancing": a user whose relays have all died can still unlock, reach
     * settings, and point the wallet somewhere that works.
     */
    public java.util.List<UpstreamRelay> defaultRelays() {
        return switch (this) {
            case MAINNET -> java.util.List.of(
                    new UpstreamRelay("backbone.cardano.iog.io", 3001),
                    new UpstreamRelay("backbone.mainnet.cardanofoundation.org", 3001));
            case PREPROD -> java.util.List.of(
                    new UpstreamRelay("preprod1-node.play.dev.cardano.org", 3001),
                    new UpstreamRelay("preprod2-node.play.dev.cardano.org", 3001));
            case PREVIEW -> java.util.List.of(
                    new UpstreamRelay("preview1-node.play.dev.cardano.org", 3001),
                    new UpstreamRelay("preview2-node.play.dev.cardano.org", 3001));
            case DEVNET, YACI_DEVKIT -> java.util.List.of();
        };
    }

    /**
     * A public Cardanoscan URL for a transaction, or {@code null} for networks
     * with no public explorer (devnet, Yaci DevKit). Lets the UI link a tx hash
     * to its details.
     */
    public String explorerTxUrl(String txHash) {
        if (txHash == null || txHash.isBlank()) {
            return null;
        }
        return switch (this) {
            case MAINNET -> "https://cardanoscan.io/transaction/" + txHash;
            case PREPROD -> "https://preprod.cardanoscan.io/transaction/" + txHash;
            case PREVIEW -> "https://preview.cardanoscan.io/transaction/" + txHash;
            // Yaci DevKit ships Yaci Viewer, a local explorer, and serves a
            // transaction at /transactions/{hash} (verified against a running
            // DevKit: that path returns the transaction, /tx/{hash} does not
            // exist). It is optional and its port is configurable, so this link
            // can be dead — but a devnet transaction is otherwise unviewable,
            // and the viewer is part of the standard DevKit setup.
            case YACI_DEVKIT -> YACI_VIEWER_BASE_URL + "/transactions/" + txHash;
            // A hand-run Yano devnet has no explorer at all, so a link would go
            // nowhere. The hash is shown as plain text instead.
            case DEVNET -> null;
        };
    }

    /** Yaci DevKit's bundled explorer, on its default port. */
    public static final String YACI_VIEWER_BASE_URL = "http://localhost:5173";

    /**
     * The name to show a user. The id is a storage key and an API value — it names
     * directories on disk and round-trips through {@link #fromId} — so it stays
     * lowercase and stable while this can say what the network actually is.
     * "devnet" in particular is ambiguous now that two of them exist.
     */
    public String displayName() {
        return switch (this) {
            case DEVNET -> "Yano Devnet";
            case YACI_DEVKIT -> "Yaci DevKit";
            case PREVIEW -> "Preview";
            case PREPROD -> "Preprod";
            case MAINNET -> "Mainnet";
        };
    }

    public static WalletNetwork fromId(String id) {
        return Arrays.stream(values())
                .filter(network -> network.id.equalsIgnoreCase(id))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unsupported wallet network: " + id));
    }
}
