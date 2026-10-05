package com.bloxbean.cardano.yano.wallet.launcher;

import com.bloxbean.cardano.yano.wallet.core.config.WalletNetwork;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NodeLaunchSpecTest {

    private NodeLaunchSpec spec(WalletNetwork network, int httpPort) {
        return new NodeLaunchSpec(network, Path.of("app/build/yano.jar"), false,
                Path.of("app"), httpPort, 13400, Path.of("cs"), Path.of("node.log"), "java",
                List.of());
    }

    @Test
    void baseUrlUsesTheConfiguredPort() {
        assertThat(spec(WalletNetwork.PREPROD, 8123).baseUrl())
                .isEqualTo("http://localhost:8123/api/v1/");
    }

    @Test
    void managedProfileAppendsWalletSoRealNetworksGetTheWalletApis() {
        // Only %devnet enables the wallet APIs on its own; preprod/mainnet need %wallet.
        // %medium sizes the node's caches for a desktop it shares with the wallet.
        assertThat(spec(WalletNetwork.PREPROD, 8090).quarkusProfile()).isEqualTo("preprod,medium,wallet");
        assertThat(spec(WalletNetwork.MAINNET, 8090).quarkusProfile()).isEqualTo("mainnet,medium,wallet");
        assertThat(spec(WalletNetwork.DEVNET, 8090).quarkusProfile()).isEqualTo("devnet,medium,wallet");
    }

    @Test
    void walletProfileStaysLastSoSizingCannotSwitchItOff() {
        // Quarkus lets a later profile win, and the wallet's scan/first-seen
        // switches are the reason the managed node can serve history at all.
        String profile = spec(WalletNetwork.MAINNET, 8090).quarkusProfile();
        assertThat(profile).endsWith(",wallet");
        assertThat(profile.indexOf("medium")).isLessThan(profile.indexOf("wallet"));
    }

    @Test
    void rejectsNonPositivePorts() {
        assertThatThrownBy(() -> spec(WalletNetwork.PREPROD, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void freePortReturnsBindableDistinctPorts() throws Exception {
        int a = FreePort.find();
        int b = FreePort.find();
        assertThat(a).isBetween(1, 65535);
        assertThat(b).isBetween(1, 65535);
        // Each returned port must be immediately bindable.
        try (var socket = new java.net.ServerSocket(a)) {
            assertThat(socket.getLocalPort()).isEqualTo(a);
        }
    }
}
