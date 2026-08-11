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
     * True when this network is served by a Blockfrost-compatible backend rather
     * than a Yano node (ADR-038): no {@code /status}, no {@code /genesis}, so the
     * network cannot be verified and is taken from this explicit choice.
     */
    public boolean blockfrostFlavor() {
        return this == YACI_DEVKIT;
    }

    /** Default backend URL for networks that have a conventional one. */
    public String defaultBaseUrl() {
        return this == YACI_DEVKIT ? "http://localhost:8080/api/v1" : null;
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
            case DEVNET, YACI_DEVKIT -> null;
        };
    }

    public static WalletNetwork fromId(String id) {
        return Arrays.stream(values())
                .filter(network -> network.id.equalsIgnoreCase(id))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unsupported wallet network: " + id));
    }
}
