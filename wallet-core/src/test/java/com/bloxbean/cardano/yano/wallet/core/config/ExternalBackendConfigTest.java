package com.bloxbean.cardano.yano.wallet.core.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The offline half of ADR-043: what a pasted key implies, and what a connection
 * config guarantees. The live behaviour is covered by
 * {@code HostedBlockfrostLiveTest}, which needs a real credential.
 */
class ExternalBackendConfigTest {

    @Test
    void aProjectIdNamesItsOwnNetwork() {
        assertThat(BlockfrostEndpoints.networkOf("preprodABC123")).isEqualTo(WalletNetwork.PREPROD);
        assertThat(BlockfrostEndpoints.networkOf("previewABC123")).isEqualTo(WalletNetwork.PREVIEW);
        assertThat(BlockfrostEndpoints.networkOf("mainnetABC123")).isEqualTo(WalletNetwork.MAINNET);
    }

    @Test
    void anythingUnrecognisedIsNotGuessedAt() {
        // The one wrong answer that matters would be inferring "mainnet" from an
        // opaque token, so an unknown shape teaches the wallet nothing and it
        // leaves the user's own settings alone.
        assertThat(BlockfrostEndpoints.networkOf("some-gateway-token")).isNull();
        assertThat(BlockfrostEndpoints.networkOf("")).isNull();
        assertThat(BlockfrostEndpoints.networkOf(null)).isNull();
        assertThat(BlockfrostEndpoints.networkOf("pre")).isNull();
    }

    @Test
    void endpointsUseBlockfrostsOwnApiVersion() {
        // v0, not the v1 that Yano and yaci-store serve — a detail that turns
        // every request into a 404 if it is wrong.
        assertThat(BlockfrostEndpoints.baseUrlFor(WalletNetwork.PREPROD))
                .isEqualTo("https://cardano-preprod.blockfrost.io/api/v0");
        assertThat(BlockfrostEndpoints.baseUrlFor(WalletNetwork.MAINNET))
                .isEqualTo("https://cardano-mainnet.blockfrost.io/api/v0");
        // Blockfrost serves no devnet.
        assertThat(BlockfrostEndpoints.baseUrlFor(WalletNetwork.DEVNET)).isNull();
        assertThat(BlockfrostEndpoints.baseUrlFor(WalletNetwork.YACI_DEVKIT)).isNull();
    }

    @Test
    void aManagedConnectionIsAlwaysAYanoNodeWhateverIsPassed() {
        // The wallet launches it, so the flavor is not the caller's to get wrong.
        assertThat(WalletConnectionConfig.managed(WalletNetwork.PREPROD).flavor())
                .isEqualTo(BackendFlavor.YANO);
        assertThat(new WalletConnectionConfig(WalletConnectionConfig.Mode.MANAGED,
                WalletNetwork.PREPROD, null, null, BackendFlavor.BLOCKFROST_HOSTED, "key").flavor())
                .isEqualTo(BackendFlavor.YANO);
    }

    @Test
    void anExternalConnectionDefaultsToYanoExceptForTheDevkit() {
        assertThat(WalletConnectionConfig.external(WalletNetwork.PREPROD, "http://localhost:7070/api/v1")
                .flavor()).isEqualTo(BackendFlavor.YANO);
        assertThat(WalletConnectionConfig.external(WalletNetwork.YACI_DEVKIT, "http://localhost:8080/api/v1")
                .flavor()).isEqualTo(BackendFlavor.YACI_STORE);
    }

    @Test
    void blankCredentialsCollapseToNone() {
        assertThat(WalletConnectionConfig.external(WalletNetwork.PREPROD, "u", BackendFlavor.YANO, "  ")
                .hasApiKey()).isFalse();
        assertThat(WalletConnectionConfig.external(WalletNetwork.PREPROD, "u", BackendFlavor.YANO, null)
                .hasApiKey()).isFalse();
    }

    @Test
    void theCredentialNeverAppearsInToString() {
        // This record reaches log lines and exception messages; the generated
        // toString would print the key in full.
        String printed = WalletConnectionConfig.external(WalletNetwork.PREPROD,
                "https://cardano-preprod.blockfrost.io/api/v0",
                BackendFlavor.BLOCKFROST_HOSTED, "preprodSUPERSECRET").toString();

        assertThat(printed).doesNotContain("preprodSUPERSECRET").contains("<redacted>");
    }

    @Test
    void onlyYaciStoreFailsToProveItsNetwork() {
        // The invariant the mainnet rule rests on (ADR-038 §3 as amended by
        // ADR-043 §5), asserted on the flavor rather than on a code path.
        for (BackendFlavor flavor : BackendFlavor.values()) {
            assertThat(flavor.provesItsNetwork())
                    .as("%s", flavor)
                    .isEqualTo(flavor != BackendFlavor.YACI_STORE);
        }
    }
}
