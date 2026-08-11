package com.bloxbean.cardano.yano.wallet.launcher;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The node reads genesis from {@code config/network/<network>/} relative to its
 * working directory, so a wrong working dir yields a node that starts and then
 * fails to bootstrap a real network — a slow, confusing failure. The two layouts
 * the wallet must support nest the jar at different depths below {@code config/},
 * so this is resolved by searching for the marker rather than by a fixed depth.
 */
class NodeLocatorWorkingDirTest {

    @TempDir
    Path root;

    private Path jarAt(Path dir) throws IOException {
        Files.createDirectories(dir);
        Path jar = dir.resolve("yano.jar");
        Files.writeString(jar, "not really a jar");
        return jar;
    }

    @Test
    void releaseLayout_usesTheDirectoryHoldingTheJar() throws IOException {
        // yano-1.2.3/{yano.jar, config/} — config is ONE level up from the jar.
        Path dist = root.resolve("yano-1.2.3");
        Path jar = jarAt(dist);
        Files.createDirectories(dist.resolve("config").resolve("network").resolve("preprod"));

        assertThat(NodeLocator.workingDirFor(jar)).isEqualTo(dist);
    }

    @Test
    void devLayout_walksUpPastBuildToTheConfigDir() throws IOException {
        // app/build/yano.jar with config in app/ — TWO levels up from the jar.
        Path appDir = root.resolve("app");
        Path jar = jarAt(appDir.resolve("build"));
        Files.createDirectories(appDir.resolve("config").resolve("network").resolve("devnet"));

        assertThat(NodeLocator.workingDirFor(jar)).isEqualTo(appDir);
    }

    @Test
    void bundledLayout_findsConfigBesideTheJarInsideTheAppImage() throws IOException {
        // jpackage stages the distribution at $APPDIR/yano-node/.
        Path bundled = root.resolve("Yano Wallet.app").resolve("Contents").resolve("app")
                .resolve("yano-node");
        Path jar = jarAt(bundled);
        Files.createDirectories(bundled.resolve("config"));

        assertThat(NodeLocator.workingDirFor(jar)).isEqualTo(bundled);
    }

    @Test
    void noConfigAnywhere_fallsBackToTheJarsOwnDirectory() throws IOException {
        // Better to let the node report the missing config than to guess a parent
        // and run it somewhere unrelated.
        Path loose = root.resolve("somewhere");
        Path jar = jarAt(loose);

        assertThat(NodeLocator.workingDirFor(jar)).isEqualTo(loose);
    }

    @Test
    void doesNotEscapeToAnUnrelatedAncestorConfig() throws IOException {
        // A config/ far above the jar (e.g. an unrelated parent checkout) must not
        // be adopted — the search depth is bounded.
        Files.createDirectories(root.resolve("config"));
        Path deep = root.resolve("a").resolve("b").resolve("c").resolve("d").resolve("e");
        Path jar = jarAt(deep);

        assertThat(NodeLocator.workingDirFor(jar)).isEqualTo(deep);
    }
}
