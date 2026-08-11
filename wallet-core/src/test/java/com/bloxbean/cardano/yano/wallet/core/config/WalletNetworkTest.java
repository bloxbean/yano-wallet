package com.bloxbean.cardano.yano.wallet.core.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WalletNetworkTest {
    @Test
    void resolvesSupportedNetworkProfilesById() {
        assertThat(WalletNetwork.fromId("devnet")).isEqualTo(WalletNetwork.DEVNET);
        assertThat(WalletNetwork.fromId("preview")).isEqualTo(WalletNetwork.PREVIEW);
        assertThat(WalletNetwork.fromId("preprod")).isEqualTo(WalletNetwork.PREPROD);
        assertThat(WalletNetwork.fromId("mainnet")).isEqualTo(WalletNetwork.MAINNET);
    }

    @Test
    void marksOnlyMainnetAsProduction() {
        assertThat(WalletNetwork.MAINNET.production()).isTrue();
        assertThat(WalletNetwork.DEVNET.production()).isFalse();
        assertThat(WalletNetwork.PREVIEW.production()).isFalse();
        assertThat(WalletNetwork.PREPROD.production()).isFalse();
    }

    @Test
    void mapsNetworksToCorrectProtocolMagicAndNetworkId() {
        assertThat(WalletNetwork.DEVNET.protocolMagic()).isEqualTo(42);
        assertThat(WalletNetwork.PREVIEW.protocolMagic()).isEqualTo(2);
        assertThat(WalletNetwork.PREPROD.protocolMagic()).isEqualTo(1);
        assertThat(WalletNetwork.MAINNET.protocolMagic()).isEqualTo(764824073L);

        // Devnet must not inherit preprod's protocol magic (Yano devnet uses 42).
        assertThat(WalletNetwork.DEVNET.toCclNetwork().getProtocolMagic()).isEqualTo(42);
        assertThat(WalletNetwork.DEVNET.toCclNetwork().getNetworkId()).isEqualTo(0);
        assertThat(WalletNetwork.MAINNET.toCclNetwork().getNetworkId()).isEqualTo(1);
    }

    @Test
    void yaciDevkitIsANonProductionDevnetOnABlockfrostBackend() {
        WalletNetwork devkit = WalletNetwork.fromId("yaci-devkit");
        assertThat(devkit).isEqualTo(WalletNetwork.YACI_DEVKIT);
        assertThat(devkit.protocolMagic()).isEqualTo(42);          // devkit's default
        assertThat(devkit.toCclNetwork().getProtocolMagic()).isEqualTo(42);
        assertThat(devkit.toCclNetwork().getNetworkId()).isEqualTo(0);
        assertThat(devkit.production()).isFalse();
        assertThat(devkit.blockfrostFlavor()).isTrue();            // served by yaci-store
        assertThat(devkit.defaultBaseUrl()).isEqualTo("http://localhost:8080/api/v1");
    }

    @Test
    void onlyYaciDevkitIsBlockfrostFlavored() {
        // Yano-backed networks must keep strict genesis verification (ADR-038).
        assertThat(WalletNetwork.DEVNET.blockfrostFlavor()).isFalse();
        assertThat(WalletNetwork.PREPROD.blockfrostFlavor()).isFalse();
        assertThat(WalletNetwork.PREVIEW.blockfrostFlavor()).isFalse();
        assertThat(WalletNetwork.MAINNET.blockfrostFlavor()).isFalse();
        assertThat(WalletNetwork.DEVNET.defaultBaseUrl()).isNull();
    }

    @Test
    void noProductionNetworkMayBeBlockfrostFlavored() {
        // The ADR-038 safety rule, pinned as an invariant rather than a code path:
        // a backend that cannot prove its network must never serve mainnet.
        for (WalletNetwork network : WalletNetwork.values()) {
            assertThat(network.production() && network.blockfrostFlavor())
                    .as("%s must not be both production and unverifiable", network)
                    .isFalse();
        }
    }

    @Test
    void rejectsUnsupportedNetworkProfile() {
        assertThatThrownBy(() -> WalletNetwork.fromId("unknown"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unsupported wallet network");
    }
}
