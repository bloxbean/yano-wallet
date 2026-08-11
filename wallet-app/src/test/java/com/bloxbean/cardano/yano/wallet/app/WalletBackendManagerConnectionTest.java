package com.bloxbean.cardano.yano.wallet.app;

import com.bloxbean.cardano.yano.wallet.core.config.WalletNetwork;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Connect-screen support surface: the chainstate probe that sets expectations
 * before a (possibly very long) managed start, and the abort that lets a user
 * back out of one.
 */
class WalletBackendManagerConnectionTest {

    @TempDir
    Path dataDir;

    private Path chainstateDir(WalletNetwork network) {
        return dataDir.resolve(network.id()).resolve("node").resolve("chainstate");
    }

    @Test
    void noChainstate_whenTheNetworkHasNeverBeenStarted() {
        WalletBackendManager manager = new WalletBackendManager(dataDir);
        assertThat(manager.hasLocalChainstate(WalletNetwork.PREPROD)).isFalse();
    }

    @Test
    void noChainstate_whenTheDirectoryExistsButIsEmpty() throws IOException {
        // A node that was launched and died before writing anything must not be
        // reported as resumable — that would promise a fast start it can't keep.
        Files.createDirectories(chainstateDir(WalletNetwork.PREPROD));
        WalletBackendManager manager = new WalletBackendManager(dataDir);
        assertThat(manager.hasLocalChainstate(WalletNetwork.PREPROD)).isFalse();
    }

    @Test
    void hasChainstate_onceTheNetworkHasDataOnDisk() throws IOException {
        Path chainstate = chainstateDir(WalletNetwork.PREPROD);
        Files.createDirectories(chainstate);
        Files.writeString(chainstate.resolve("CURRENT"), "MANIFEST-000001\n");

        WalletBackendManager manager = new WalletBackendManager(dataDir);
        assertThat(manager.hasLocalChainstate(WalletNetwork.PREPROD)).isTrue();
        // Chainstate is per network, so one network's data says nothing about another's.
        assertThat(manager.hasLocalChainstate(WalletNetwork.PREVIEW)).isFalse();
    }

    @Test
    void abortConnect_isANoOpWhenNothingIsStarting() {
        // The Connect screen calls this on Cancel without knowing whether a
        // managed node was ever spawned; it must never throw.
        WalletBackendManager manager = new WalletBackendManager(dataDir);
        assertThatCode(manager::abortConnect).doesNotThrowAnyException();
    }
}
