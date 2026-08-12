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
    void publicNetworksLinkToTheirCardanoscan() {
        String hash = "aa11";
        assertThat(WalletNetwork.MAINNET.explorerTxUrl(hash)).isEqualTo("https://cardanoscan.io/transaction/aa11");
        assertThat(WalletNetwork.PREPROD.explorerTxUrl(hash)).contains("preprod.cardanoscan.io");
        assertThat(WalletNetwork.PREVIEW.explorerTxUrl(hash)).contains("preview.cardanoscan.io");
    }

    @Test
    void yaciDevkitLinksToTheLocalYaciViewer() {
        // Verified against a running DevKit: /transactions/{hash} serves the
        // transaction, /tx/{hash} does not exist.
        assertThat(WalletNetwork.YACI_DEVKIT.explorerTxUrl("aa11"))
                .isEqualTo("http://localhost:5173/transactions/aa11");
    }

    @Test
    void aYanoDevnetHasNoExplorerSoItIsNotLinked() {
        // No viewer ships with a hand-run devnet, so a link would go nowhere —
        // worse than showing the hash as plain text.
        assertThat(WalletNetwork.DEVNET.explorerTxUrl("aa11")).isNull();
    }

    @Test
    void noNetworkLinksAnEmptyHash() {
        for (WalletNetwork network : WalletNetwork.values()) {
            assertThat(network.explorerTxUrl(null)).as(network.id()).isNull();
            assertThat(network.explorerTxUrl("  ")).as(network.id()).isNull();
        }
    }

    @Test
    void displayNamesDisambiguateTheTwoDevnets() {
        assertThat(WalletNetwork.DEVNET.displayName()).isEqualTo("Yano Devnet");
        assertThat(WalletNetwork.YACI_DEVKIT.displayName()).isEqualTo("Yaci DevKit");
    }

    @Test
    void idsStayStableWhileDisplayNamesChange() {
        // Ids key storage directories and round-trip through fromId, so a display
        // name must never be mistaken for one.
        for (WalletNetwork network : WalletNetwork.values()) {
            assertThat(WalletNetwork.fromId(network.id())).isEqualTo(network);
            assertThat(network.id()).as(network.name()).isLowerCase();
            assertThat(network.displayName()).as(network.name()).isNotBlank();
        }
        assertThat(WalletNetwork.DEVNET.id()).isEqualTo("devnet");
        assertThat(WalletNetwork.YACI_DEVKIT.id()).isEqualTo("yaci-devkit");
    }

    @Test
    void rejectsUnsupportedNetworkProfile() {
        assertThatThrownBy(() -> WalletNetwork.fromId("unknown"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unsupported wallet network");
    }
}
