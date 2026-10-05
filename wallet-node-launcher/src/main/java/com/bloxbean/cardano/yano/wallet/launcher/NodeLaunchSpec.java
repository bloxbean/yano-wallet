package com.bloxbean.cardano.yano.wallet.launcher;

import com.bloxbean.cardano.yano.wallet.core.config.UpstreamRelay;
import com.bloxbean.cardano.yano.wallet.core.config.WalletNetwork;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/**
 * How to launch a managed Yano node as a child process (ADR-033 A3). The node
 * runs its REST API on {@link #httpPort} with an isolated chainstate, so a
 * managed node never collides with a default Yano on 7070.
 */
public record NodeLaunchSpec(
        WalletNetwork network,
        Path nodeJar,        // path to app/build/yano.jar (or a native binary)
        boolean nativeBinary,// nodeJar is a native executable rather than a jar
        Path workingDir,     // must contain config/network/... (i.e. the app/ dir)
        int httpPort,        // REST API port (default 8090; NOT 7070)
        int n2nPort,         // node-to-node port (default 13400; NOT 13337)
        Path chainstateDir,  // isolated chainstate for this managed node
        Path logFile,        // node stdout/stderr capture
        String javaExecutable, // "java" or an absolute path; ignored for native
        List<UpstreamRelay> relays // upstream relays, best first; empty → the network's defaults
) {
    /** Managed-node defaults: REST 8090, N2N 13400 — clear of a default Yano's 7070/13337. */
    public static final int DEFAULT_HTTP_PORT = 8090;
    public static final int DEFAULT_N2N_PORT = 13400;

    /**
     * Sizing the user can change by hand in {@code node.properties} beside
     * {@code node.log}; see {@link NodeOptions}. Read once per spec so a running
     * node keeps the values it started with, and an edit takes effect on the
     * next start rather than halfway through a sync.
     */
    private NodeOptions options() {
        return NodeOptions.load(chainstateDir.getParent());
    }

    public NodeLaunchSpec {
        Objects.requireNonNull(network, "network is required");
        Objects.requireNonNull(nodeJar, "nodeJar is required");
        Objects.requireNonNull(workingDir, "workingDir is required");
        Objects.requireNonNull(chainstateDir, "chainstateDir is required");
        Objects.requireNonNull(logFile, "logFile is required");
        if (httpPort <= 0 || n2nPort <= 0) {
            throw new IllegalArgumentException("ports must be positive");
        }
        javaExecutable = javaExecutable == null || javaExecutable.isBlank() ? "java" : javaExecutable;
        // An empty list means "no preference", not "no upstream". Launching a node
        // with zero relays configured would leave it unable to sync at all, which
        // is a worse failure than any relay we could have picked — so fall back to
        // the network's defaults rather than honouring the emptiness.
        relays = relays == null || relays.isEmpty()
                ? network.defaultRelays()
                : List.copyOf(relays);
    }

    public String baseUrl() {
        return "http://localhost:" + httpPort + "/api/v1/";
    }

    /**
     * Quarkus profiles for the managed node: the network profile, then
     * {@code medium}, then {@code wallet}.
     *
     * <p>{@code wallet} enables what this wallet reads — the scan index,
     * address-first-seen and the UTxO state. The devnet profile turns those on
     * by itself, but preprod/mainnet/preview do not, so without it a managed
     * real-network node serves balances and no history.
     *
     * <p>The sizing profile — {@code medium} unless {@code node.properties} says
     * otherwise — sizes the node for the desktop it is sharing: RocksDB caches,
     * open files and the decoded-block queue budget. Note it carries no heap of
     * its own; {@code yano.sh} pairs each profile with an {@code -Xmx} and this
     * launcher spawns the node directly, so {@link #maxHeap()} supplies it.
     *
     * <p>Later profiles win on conflict, so {@code wallet} stays last: its
     * switches must not be overridden by a sizing profile, whoever chose it.
     */
    public String quarkusProfile() {
        return network.id() + "," + options().sizingProfile() + ",wallet";
    }

    /**
     * The {@code -Xmx} to launch with. Accepted by both the jar and the native
     * binary — a native image reads {@code -Xmx}, {@code -Xms} and {@code -Xss}
     * at runtime, which is how {@code yano.sh} sizes it too.
     */
    public String maxHeap() {
        return options().maxHeap();
    }
}
