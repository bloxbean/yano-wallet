package com.bloxbean.cardano.yano.wallet.launcher;

import com.bloxbean.cardano.yano.wallet.core.config.WalletNetwork;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
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
        return findNodeJar().map(jar -> new NodeLaunchSpec(network, jar, false, workingDirFor(jar),
                httpPort, n2nPort, chainstateDir, logFile, resolveJavaExecutable(),
                network.defaultRelays()));
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

    /** {@code <dir>/yano.jar}, or the same inside a single nested dist directory. */
    private static Optional<Path> firstJarIn(Path dir) {
        if (!Files.isDirectory(dir)) {
            return Optional.empty();
        }
        Path direct = dir.resolve("yano.jar");
        if (Files.isRegularFile(direct)) {
            return Optional.of(direct.toAbsolutePath().normalize());
        }
        try (var entries = Files.list(dir)) {
            return entries.filter(Files::isDirectory)
                    .map(child -> child.resolve("yano.jar"))
                    .filter(Files::isRegularFile)
                    .findFirst()
                    .map(path -> path.toAbsolutePath().normalize());
        } catch (java.io.IOException e) {
            return Optional.empty();
        }
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
