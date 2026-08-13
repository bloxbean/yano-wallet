package com.bloxbean.cardano.yano.wallet.launcher;

import com.bloxbean.cardano.yano.wallet.core.config.WalletNetwork;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
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
        return buildSleeperJar(false);
    }

    /**
     * @param chatty print a line every 200ms, so the redirected log keeps growing.
     *               That is what the launcher reads as "still making progress".
     */
    private Path buildSleeperJar(boolean chatty) throws Exception {
        Path src = tempDir.resolve("Sleeper.java");
        Files.writeString(src, chatty
                ? "public class Sleeper { public static void main(String[] a) throws Exception {"
                        + " for (int i = 0; ; i++) { System.out.println(\"Account history reconcile"
                        + " progress: block \" + (i * 1000) + \"/4567221\"); Thread.sleep(200); } } }"
                : "public class Sleeper { public static void main(String[] a) throws Exception {"
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
                tempDir.resolve("node.log"), NodeLocator.resolveJavaExecutable(), List.of());
        ManagedNode node = new ManagedNode(spec);
        try {
            // The sleeper never serves REST and never logs, so the silence window
            // must fire (and not hang).
            assertThat(node.startAndAwaitReady(Duration.ofSeconds(3))).isFalse();
            assertThat(node.failureReason()).contains("wrote nothing to its log");
        } finally {
            node.close();
        }
        assertThat(node.state()).isEqualTo(ManagedNode.State.STOPPED);
    }

    /**
     * The timeout is a silence window, not a ceiling: a node still writing
     * progress must survive well past it. Rebuilding the account-history index
     * takes ~86 minutes on preview with no HTTP port bound, and the old fixed
     * 45-minute cap killed exactly that — a working node, halfway through.
     */
    @Test
    void aNodeStillWritingProgressOutlivesTheSilenceWindow() throws Exception {
        Path jar = buildSleeperJar(true);
        NodeLaunchSpec spec = new NodeLaunchSpec(WalletNetwork.DEVNET, jar, false, tempDir,
                FreePort.find(), FreePort.find(), tempDir.resolve("cs"),
                tempDir.resolve("node.log"), NodeLocator.resolveJavaExecutable(), List.of());
        ManagedNode node = new ManagedNode(spec);
        Thread starter = new Thread(() -> node.startAndAwaitReady(Duration.ofSeconds(2)));
        starter.setDaemon(true);
        starter.start();
        try {
            // Three silence windows' worth of wall clock; the log grows throughout.
            Thread.sleep(6_000);
            assertThat(node.state()).isEqualTo(ManagedNode.State.STARTING);
            assertThat(node.failureReason()).isNull();
            // And the progress it is writing is legible to the UI.
            assertThat(node.progress().phase())
                    .isEqualTo(NodeStartupProgress.Phase.RECONCILING_ACCOUNT_HISTORY);
            assertThat(node.progress().current()).isPositive();
        } finally {
            node.close();
        }
        starter.join(10_000);
        assertThat(node.state()).isEqualTo(ManagedNode.State.STOPPED);
    }

    @Test
    void closeDuringStartAbortsPromptly() throws Exception {
        Path jar = buildSleeperJar();
        NodeLaunchSpec spec = new NodeLaunchSpec(WalletNetwork.DEVNET, jar, false, tempDir,
                FreePort.find(), FreePort.find(), tempDir.resolve("cs"),
                tempDir.resolve("node.log"), NodeLocator.resolveJavaExecutable(), List.of());
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
