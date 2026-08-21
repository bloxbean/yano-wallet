package com.bloxbean.cardano.yano.wallet.launcher;

import com.bloxbean.cardano.yano.wallet.core.config.UpstreamRelay;
import com.bloxbean.cardano.yano.wallet.core.config.WalletNetwork;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Optional;

/**
 * Finds the Yano node artifact to launch and the working directory that holds
 * its {@code config/network/...} files.
 *
 * <p>Search order: {@code yano.node.jar} system property, {@code YANO_NODE_JAR}
 * env, then a released distribution unpacked under {@code .yano-node/}, then
 * {@code app/build/yano.jar} walking up from the current directory (the layout
 * when this lived in the Yano repo, kept for a side-by-side checkout).
 */
public final class NodeLocator {
    private NodeLocator() {
    }

    /** How far above the artifact to look for the {@code config/} directory. */
    private static final int CONFIG_SEARCH_DEPTH = 3;

    /** Locates the node artifact and derives the working directory beside it. */
    public static Optional<NodeLaunchSpec> autoDetectDevJar(WalletNetwork network, Path chainstateDir,
                                                            Path logFile, int httpPort, int n2nPort) {
        return autoDetectDevJar(network, chainstateDir, logFile, httpPort, n2nPort, List.of());
    }

    /**
     * As above, with the upstream relays to sync from (E18). An empty list means
     * the network's shipped defaults; callers that hold a user override pass the
     * resolved list, custom entries first.
     */
    public static Optional<NodeLaunchSpec> autoDetectDevJar(WalletNetwork network, Path chainstateDir,
                                                            Path logFile, int httpPort, int n2nPort,
                                                            List<UpstreamRelay> relays) {
        return findNodeJar().map(jar -> new NodeLaunchSpec(network, jar, isNativeBinary(jar),
                workingDirFor(jar), httpPort, n2nPort, chainstateDir, logFile,
                resolveJavaExecutable(), relays));
    }

    /**
     * The directory to run the node from: the nearest ancestor of the artifact
     * that actually contains {@code config/}. The node reads genesis from
     * {@code config/network/<network>/} at runtime, so getting this wrong gives a
     * node that starts and then fails to bootstrap a real network.
     *
     * <p>Two layouts must both work, and they nest differently:
     * <ul>
     *   <li>dev/side-by-side — {@code app/build/yano.jar}, config in {@code app/} (two up)</li>
     *   <li>release or bundled — {@code yano-1.2.3/yano.jar}, config in
     *       {@code yano-1.2.3/} (one up)</li>
     * </ul>
     * Searching for the marker handles both; a hardcoded depth silently breaks one.
     */
    static Path workingDirFor(Path jar) {
        Path dir = jar.toAbsolutePath().getParent();
        for (int depth = 0; depth <= CONFIG_SEARCH_DEPTH && dir != null; depth++) {
            if (Files.isDirectory(dir.resolve("config"))) {
                return dir;
            }
            dir = dir.getParent();
        }
        // No marker: run from the artifact's own directory and let the node report
        // the missing config, which is clearer than guessing a parent.
        return jar.toAbsolutePath().getParent();
    }

    static Optional<Path> findNodeJar() {
        String override = System.getProperty("yano.node.jar",
                System.getenv().getOrDefault("YANO_NODE_JAR", ""));
        if (!override.isBlank()) {
            Path path = Paths.get(override);
            return Files.isRegularFile(path) ? Optional.of(path.toAbsolutePath().normalize()) : Optional.empty();
        }
        // Beside the running executable first. A distributed native build ships
        // `yano-node/` next to the binary, and the user may launch it directly
        // rather than through yano-wallet.sh — in which case yano.node.jar is
        // unset and a cwd-relative search finds nothing, which is exactly the
        // "Could not find a Yano node to run" failure.
        Optional<Path> beside = besideExecutable();
        if (beside.isPresent()) {
            return beside;
        }

        Path dir = Paths.get("").toAbsolutePath();
        for (int depth = 0; depth < 6 && dir != null; depth++) {
            // A distribution fetched by the build (gradle/yano-node.gradle links
            // the resolved dist here), then the in-repo dev layout.
            Optional<Path> dist = firstJarIn(dir.resolve(".yano-node"));
            if (dist.isPresent()) {
                return dist;
            }
            Path candidate = dir.resolve("app").resolve("build").resolve("yano.jar");
            if (Files.isRegularFile(candidate)) {
                return Optional.of(candidate.normalize());
            }
            dir = dir.getParent();
        }
        return Optional.empty();
    }

    /**
     * A node distribution shipped alongside the running executable — {@code
     * yano-node/} (the layout of the native zip) or {@code .yano-node/}.
     *
     * <p>Resolved from the process's own command rather than the working
     * directory, so it holds however the binary was launched: double-clicked,
     * from another directory, or through a symlink on PATH.
     */
    private static Optional<Path> besideExecutable() {
        return ProcessHandle.current().info().command()
                .map(Paths::get)
                .map(Path::toAbsolutePath)
                .map(Path::getParent)
                .flatMap(dir -> {
                    Optional<Path> shipped = firstJarIn(dir.resolve("yano-node"));
                    return shipped.isPresent() ? shipped : firstJarIn(dir.resolve(".yano-node"));
                });
    }

    /**
     * True when the resolved artifact is a native executable rather than a jar.
     *
     * <p>Decided by name, because that is the one thing both distributions agree
     * on: the JVM distribution ships {@code yano.jar}, the native one a bare
     * {@code yano} (or {@code yano.exe}). {@code ManagedNode} needs this to choose
     * between running the file directly and handing it to {@code java -jar}.
     */
    static boolean isNativeBinary(Path artifact) {
        return !artifact.getFileName().toString().endsWith(".jar");
    }

    /** Node artifacts to look for, in order: the jar first, then the native binary. */
    private static final List<String> NODE_ARTIFACTS = List.of("yano.jar", "yano", "yano.exe");

    /**
     * A node artifact directly in {@code dir}, or inside a single nested dist
     * directory (the shape both release zips unpack to). The jar is tried first,
     * so a directory holding both keeps the behaviour it had before native
     * support existed.
     */
    private static Optional<Path> firstJarIn(Path dir) {
        if (!Files.isDirectory(dir)) {
            return Optional.empty();
        }
        for (String name : NODE_ARTIFACTS) {
            Path direct = dir.resolve(name);
            if (Files.isRegularFile(direct)) {
                return Optional.of(direct.toAbsolutePath().normalize());
            }
        }
        try (var stream = Files.list(dir)) {
            for (Path child : stream.filter(Files::isDirectory).toList()) {
                for (String name : NODE_ARTIFACTS) {
                    Path candidate = child.resolve(name);
                    if (Files.isRegularFile(candidate)) {
                        return Optional.of(candidate.toAbsolutePath().normalize());
                    }
                }
            }
        } catch (java.io.IOException e) {
            return Optional.empty();
        }
        return Optional.empty();
    }

    static String resolveJavaExecutable() {
        String javaHome = System.getProperty("java.home");
        if (javaHome != null && !javaHome.isBlank()) {
            Path java = Paths.get(javaHome, "bin", "java");
            if (Files.isExecutable(java)) {
                return java.toString();
            }
        }
        return "java";
    }
}
