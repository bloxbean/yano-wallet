package com.bloxbean.cardano.yano.wallet.core.config;

import java.util.Locale;

/**
 * Reads a Blockfrost project id and says what it is for (ADR-043 §4).
 *
 * <p>A project id carries its network as a prefix — {@code preprod…},
 * {@code preview…}, {@code mainnet…} — so pasting one is enough to fill in the
 * endpoint and select the network, and enough to refuse a key that does not
 * match the network the user picked.
 *
 * <p>The refusal is a convenience, not the safety property. Blockfrost enforces
 * the same scoping itself: a preprod key sent to the mainnet host is rejected
 * with "Network token mismatch" (verified live, 2026-08-13). So a mismatch this
 * class fails to spot still cannot read the wrong chain — it just fails later
 * and less clearly.
 */
public final class BlockfrostEndpoints {

    public static final String PROVIDER = "Blockfrost";

    private BlockfrostEndpoints() {
    }

    /**
     * The network a Blockfrost project id is scoped to, or null if this does not
     * look like one.
     *
     * <p>Deliberately conservative: an unrecognised string returns null so the
     * caller leaves the user's own settings alone. Guessing "probably mainnet"
     * from an opaque token would be the one wrong answer that matters.
     */
    public static WalletNetwork networkOf(String projectId) {
        if (projectId == null) {
            return null;
        }
        String key = projectId.strip().toLowerCase(Locale.ROOT);
        // Ordered longest-prefix-first: "preprod" and "preview" share no prefix
        // with each other, but both would be missed by a bare startsWith("pre").
        if (key.startsWith("mainnet")) {
            return WalletNetwork.MAINNET;
        }
        if (key.startsWith("preprod")) {
            return WalletNetwork.PREPROD;
        }
        if (key.startsWith("preview")) {
            return WalletNetwork.PREVIEW;
        }
        return null;
    }

    /**
     * The public endpoint for a network, or null for networks Blockfrost does not
     * serve (the devnets).
     *
     * <p>Note {@code /api/v0} — Blockfrost's version, not the {@code /api/v1}
     * that Yano and yaci-store use.
     */
    public static String baseUrlFor(WalletNetwork network) {
        if (network == null) {
            return null;
        }
        return switch (network) {
            case MAINNET, PREPROD, PREVIEW ->
                    "https://cardano-" + network.id() + ".blockfrost.io/api/v0";
            case DEVNET, YACI_DEVKIT -> null;
        };
    }

    /** True when the URL points at the hosted service rather than a local backend. */
    public static boolean isHostedUrl(String baseUrl) {
        return baseUrl != null && baseUrl.toLowerCase(Locale.ROOT).contains("blockfrost.io");
    }
}
