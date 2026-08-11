package com.bloxbean.cardano.yano.wallet.launcher;

import com.bloxbean.cardano.yano.wallet.core.config.WalletNetwork;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exercises the process lifecycle with a stand-in "node" jar that never opens
 * a REST port, so we can assert the launcher's failure/close paths without a
 * real Yano node.
 */
class ManagedNodeLifecycleTest {

    @TempDir
    Path tempDir;

    /** A tiny runnable jar whose main just sleeps — a node that never becomes ready. */
    private Path buildSleeperJar() throws Exception {
        Path src = tempDir.resolve("Sleeper.java");
        Files.writeString(src, "public class Sleeper { public static void main(String[] a) throws Exception {"
                + " Thread.sleep(600000); } }");
        Path classesDir = tempDir.resolve("classes");
        Files.createDirectories(classesDir);
        run(javaBin("javac"), src.toString(), "-d", classesDir.toString());
        Path manifest = tempDir.resolve("MANIFEST.MF");
        Files.writeString(manifest, "Main-Class: Sleeper\n");
        Path jar = tempDir.resolve("sleeper.jar");
        run(javaBin("jar"), "cfm", jar.toString(), manifest.toString(), "-C", classesDir.toString(), ".");
        return jar;
    }

    @Test
    void startTimesOutThenCloseKillsTheProcess() throws Exception {
        Path jar = buildSleeperJar();
        NodeLaunchSpec spec = new NodeLaunchSpec(WalletNetwork.DEVNET, jar, false, tempDir,
                FreePort.find(), FreePort.find(), tempDir.resolve("cs"),
                tempDir.resolve("node.log"), NodeLocator.resolveJavaExecutable());
        ManagedNode node = new ManagedNode(spec);
        try {
            // The sleeper never serves REST, so a short await must time out (not hang).
            assertThat(node.startAndAwaitReady(Duration.ofSeconds(3))).isFalse();
            assertThat(node.failureReason()).contains("did not become ready");
        } finally {
            node.close();
        }
        assertThat(node.state()).isEqualTo(ManagedNode.State.STOPPED);
    }

    @Test
    void closeDuringStartAbortsPromptly() throws Exception {
        Path jar = buildSleeperJar();
        NodeLaunchSpec spec = new NodeLaunchSpec(WalletNetwork.DEVNET, jar, false, tempDir,
                FreePort.find(), FreePort.find(), tempDir.resolve("cs"),
                tempDir.resolve("node.log"), NodeLocator.resolveJavaExecutable());
        ManagedNode node = new ManagedNode(spec);
        Thread starter = new Thread(() -> node.startAndAwaitReady(Duration.ofSeconds(60)));
        starter.start();
        Thread.sleep(1500); // let it spawn the sleeper
        long t0 = System.nanoTime();
        node.close(); // must not block for the full 60s await
        long elapsedMs = (System.nanoTime() - t0) / 1_000_000;
        starter.join(5000);
        assertThat(elapsedMs).isLessThan(25_000);
        assertThat(node.state()).isEqualTo(ManagedNode.State.STOPPED);
    }

    private static String javaBin(String tool) {
        Path p = Path.of(System.getProperty("java.home"), "bin", tool);
        return Files.isExecutable(p) ? p.toString() : tool;
    }

    private static void run(String... command) throws Exception {
        Process process = new ProcessBuilder(command).inheritIO().start();
        if (!process.waitFor(60, java.util.concurrent.TimeUnit.SECONDS) || process.exitValue() != 0) {
            throw new IllegalStateException("command failed: " + String.join(" ", command));
        }
    }
}
