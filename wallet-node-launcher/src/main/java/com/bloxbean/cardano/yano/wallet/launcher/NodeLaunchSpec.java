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
     * Quarkus profiles for the managed node: the network profile plus the
     * {@code wallet} profile so the wallet APIs (address/tx/reward history,
     * address-tx index) are enabled. The devnet profile already turns these on,
     * but preprod/mainnet/preview do not — without the wallet profile a managed
     * real-network node would serve balances but no history/rewards. Later
     * profiles win on conflict, so {@code wallet} is listed last.
     */
    public String quarkusProfile() {
        return network.id() + ",wallet";
    }
}
