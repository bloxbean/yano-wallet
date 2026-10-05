package com.bloxbean.cardano.yano.wallet.launcher;

import com.bloxbean.cardano.yano.wallet.core.config.WalletNetwork;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The command line the managed node is started with. Ordering here is not
 * cosmetic: a JVM option after {@code -jar} is a program argument, so a
 * misplaced {@code -Xmx} is accepted silently and simply does nothing.
 */
class NodeLaunchCommandTest {
    @TempDir Path dir;

    private List<String> command(boolean nativeBinary) {
        Path nodeDir = dir.resolve("node");
        NodeLaunchSpec spec = new NodeLaunchSpec(WalletNetwork.PREPROD,
                dir.resolve(nativeBinary ? "yano" : "yano.jar"), nativeBinary, dir,
                8090, 13400, nodeDir.resolve("chainstate"), nodeDir.resolve("node.log"),
                "java", List.of());
        return new ManagedNode(spec).buildCommand();
    }

    @Test void theJarGetsItsHeapBeforeTheJarArgument() {
        List<String> command = command(false);

        assertThat(command).contains("-Xmx1536m");
        assertThat(command.indexOf("-Xmx1536m")).isLessThan(command.indexOf("-jar"));
        assertThat(command.getFirst()).isEqualTo("java");
    }

    @Test void theNativeBinaryGetsTheSameHeap() {
        // A native image reads -Xmx at runtime, which is how yano.sh sizes it.
        List<String> command = command(true);

        assertThat(command).contains("-Xmx1536m");
        assertThat(command.getFirst()).endsWith("yano");
        assertThat(command).doesNotContain("-jar");
    }

    @Test void theProfileAndHeapFollowTheUsersFile() throws Exception {
        Path nodeDir = Files.createDirectories(dir.resolve("node"));
        Files.writeString(nodeDir.resolve(NodeOptions.FILE_NAME), "sizingProfile=xsmall");

        List<String> command = command(false);

        assertThat(command).contains("-Xmx384m", "-Dquarkus.profile=preprod,xsmall,wallet");
        // Whatever the user picks for sizing, the wallet profile stays last: it
        // is what enables the scan index this wallet reads history from.
        assertThat(command.stream().filter(arg -> arg.startsWith("-Dquarkus.profile=")).findFirst())
                .get().asString().endsWith(",wallet");
    }
}
