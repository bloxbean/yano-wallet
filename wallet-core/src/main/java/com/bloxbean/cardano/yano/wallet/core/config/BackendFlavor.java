package com.bloxbean.cardano.yano.wallet.core.config;

/**
 * Which backend software serves a connection (ADR-043 §2).
 *
 * <p>This used to be a boolean derived from the network — {@code network ==
 * YACI_DEVKIT} — which worked only because yaci-store owned a network entry of
 * its own. Hosted Blockfrost breaks that identity: preprod may now be reached
 * through a managed Yano node <em>or</em> through Blockfrost, so the same
 * network can have either flavor and the flavor has to travel with the
 * connection instead.
 *
 * <p>It is not a spectrum from "full" to "degraded". Probed against a live
 * service on 2026-08-13, hosted Blockfrost sits on <em>Yano's</em> side of the
 * DRep split (same path, same payload) while lacking {@code /status} like
 * yaci-store — so no single ordering of these three is correct, and each
 * capability below is answered per flavor rather than inferred from a rank.
 */
public enum BackendFlavor {

    /** A Yano node — the wallet's own, managed or external. */
    YANO,

    /** yaci-store, as shipped inside Yaci DevKit. */
    YACI_STORE,

    /** The hosted Blockfrost service, reached with a {@code project_id}. */
    BLOCKFROST_HOSTED;

    /**
     * Whether this backend can state which chain it serves.
     *
     * <p>The mainnet rule rests on exactly this: a wallet may only connect to
     * mainnet over a backend that proves its network (ADR-038 §3, amended by
     * ADR-043 §5). Yano and hosted Blockfrost both serve {@code /genesis} with
     * the protocol magic; yaci-store exposes it nowhere, which is why a DevKit
     * connection takes the user's explicit network choice on trust and is
     * refused mainnet outright.
     */
    public boolean provesItsNetwork() {
        return this != YACI_STORE;
    }

    /** True when the wallet must send an API key; hosted Blockfrost requires one. */
    public boolean requiresApiKey() {
        return this == BLOCKFROST_HOSTED;
    }

    /**
     * True where {@code GET /status} exists — Yano's own endpoint, carrying the
     * chain tip and the UTxO index lag. The others answer an error for it
     * (yaci-store 404, hosted Blockfrost <em>400</em>) and the sync pill falls
     * back to {@code /blocks/latest}, which has no lag figure.
     */
    public boolean hasYanoStatus() {
        return this == YANO;
    }

    /**
     * True where DRep info lives at {@code /governance/dreps/{id}} with explicit
     * active/retired/expired flags. Hosted Blockfrost agrees with Yano here;
     * yaci-store serves {@code /governance-state/dreps/{id}} with a single status
     * string and no registration epoch.
     */
    public boolean hasYanoDRepPath() {
        return this != YACI_STORE;
    }

    /**
     * True where wallet history can be read for a whole stake account at
     * {@code /accounts/{stake}/transactions}, rather than one address at a time.
     *
     * <p>Account-level is strictly better — it covers every address of the seed,
     * where per-address history misses funds received on a change address. Yano
     * and hosted Blockfrost both serve it (the latter verified live on
     * 2026-08-13, returning {@code tx_hash}/{@code block_height}/{@code
     * block_time}, though no {@code slot}). yaci-store has no such route at all,
     * so a DevKit connection falls back to per-address.
     */
    public boolean hasAccountTransactions() {
        return this != YACI_STORE;
    }
}
