package com.bloxbean.cardano.yano.wallet.nodeclient;

import com.bloxbean.cardano.yano.wallet.core.config.BackendFlavor;
import com.bloxbean.cardano.yano.wallet.core.config.BlockfrostEndpoints;
import com.bloxbean.cardano.yano.wallet.core.config.WalletNetwork;
import com.bloxbean.cardano.yano.wallet.core.simulate.SimulationCapabilities;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Exercises the hosted-Blockfrost flavor against the real service (ADR-043
 * BFH-M5).
 *
 * <p>Skipped unless {@code BLOCKFROST_PREPROD_PROJECT_ID} is set, so no
 * credential lives in this repository and CI stays offline by default. Run it
 * with:
 *
 * <pre>
 * BLOCKFROST_PREPROD_PROJECT_ID=preprod... ./gradlew :wallet-node-client:test \
 *     --tests '*HostedBlockfrostLiveTest*'
 * </pre>
 *
 * <p>Worth having as a real network test rather than a recorded one: what it
 * checks is whether an external service still behaves the way ADR-043 assumed,
 * and a fixture would answer that question with our own assumptions.
 */
@EnabledIfEnvironmentVariable(named = "BLOCKFROST_PREPROD_PROJECT_ID", matches = ".+")
class HostedBlockfrostLiveTest {

    private static String key() {
        return System.getenv("BLOCKFROST_PREPROD_PROJECT_ID");
    }

    private static YanoNodeClient preprodClient() {
        return new YanoNodeClient(BlockfrostEndpoints.baseUrlFor(WalletNetwork.PREPROD),
                BackendFlavor.BLOCKFROST_HOSTED, key());
    }

    @Test
    void provesItsNetworkSoTheMagicGateRunsUnchanged() {
        // The whole basis of ADR-043 §5: hosted Blockfrost serves /genesis, which
        // ADR-038 assumed it could not.
        preprodClient().verifyNetwork(WalletNetwork.PREPROD);

        assertThat(preprodClient().getGenesis().networkMagic())
                .isEqualTo(WalletNetwork.PREPROD.protocolMagic());
    }

    @Test
    void refusesAWalletOnTheWrongNetwork() {
        assertThatThrownBy(() -> preprodClient().verifyNetwork(WalletNetwork.MAINNET))
                .isInstanceOf(NodeClientException.class)
                .hasMessageContaining("protocol magic");
    }

    @Test
    void aPreprodKeyCannotReachMainnetAtAll() {
        // Enforced by the server, not by us — the guarantee that lets §5 admit
        // hosted Blockfrost to mainnet at all.
        YanoNodeClient mainnet = new YanoNodeClient(
                BlockfrostEndpoints.baseUrlFor(WalletNetwork.MAINNET),
                BackendFlavor.BLOCKFROST_HOSTED, key());

        assertThatThrownBy(() -> mainnet.getGenesis())
                .isInstanceOf(NodeClientException.class)
                .hasMessageContaining("rejected the API key");
    }

    @Test
    void withoutAKeyEveryCallIsRejectedLegibly() {
        YanoNodeClient anonymous = new YanoNodeClient(
                BlockfrostEndpoints.baseUrlFor(WalletNetwork.PREPROD),
                BackendFlavor.BLOCKFROST_HOSTED, null);

        assertThatThrownBy(() -> anonymous.getGenesis())
                .isInstanceOf(NodeClientException.class)
                .hasMessageContaining("rejected the API key");
    }

    @Test
    void reportsATipWithoutAYanoStatusEndpoint() {
        // /status is a 400 here, not a 404 — the case ADR-038's probe got wrong.
        YanoNodeClient client = preprodClient();

        assertThat(client.getStatus().blockNumber()).isPositive();
        assertThat(client.getLatestBlock().height()).isPositive();
    }

    @Test
    void simulationCapabilitiesProbeCleanOnAHostedBackend() {
        // ADR-043 §6: the flagship feature is NOT degraded here, and the probe
        // discovers that by asking rather than by assuming from the flavor.
        SimulationCapabilities capabilities = preprodClient().probeSimulationCapabilities();

        // Asserted separately: the two halves fail for unrelated reasons, and
        // "canSimulateFully is false" does not say which endpoint to go and look at.
        assertThat(capabilities.utxoLookup())
                .as("input resolution via /txs/{hash}/utxos")
                .isEqualTo(SimulationCapabilities.Support.AVAILABLE);
        assertThat(capabilities.scriptEvaluation())
                .as("script evaluation via /utils/txs/evaluate")
                .isEqualTo(SimulationCapabilities.Support.AVAILABLE);
        assertThat(capabilities.canSimulateFully()).isTrue();
    }

    @Test
    void theMoneyPathAuthenticatesToo() {
        // The half NOT covered by YanoNodeClient: UTxOs, protocol params and
        // submission go through CCL's BFBackendService, which takes the project
        // id as a constructor argument and sets the header itself. If only the
        // wallet's own client were wired up, everything would look connected
        // until the first balance read.
        YanoNodeBackend backend = YanoNodeBackend.connectVerified(
                WalletNetwork.PREPROD, BlockfrostEndpoints.baseUrlFor(WalletNetwork.PREPROD),
                BackendFlavor.BLOCKFROST_HOSTED, key());

        assertThat(backend.protocolParamsSupplier().getProtocolParams().getMaxTxSize())
                .isPositive();
        // A real, funded preprod address: an empty list here would mean the
        // request was rejected, not that the address is empty.
        assertThat(backend.utxoSupplier().getAll(
                "addr_test1qqjp6ahm37pkpw5fka7wtrwflelj98lfap7fu9yx8ymwrdg28kx5ass8hdt7tcgks"
                        + "qjfzz6e8a294gnwnxz3a96cq29sp5vxd9"))
                .isNotEmpty();
    }

    @Test
    void anUnauthenticatedMoneyPathFailsRatherThanReadingEmpty() {
        // The failure mode that would be worst: a rejected request presented as
        // "you have no funds". Assert it throws instead.
        YanoNodeBackend backend = YanoNodeBackend.connect(
                WalletNetwork.PREPROD, BlockfrostEndpoints.baseUrlFor(WalletNetwork.PREPROD),
                BackendFlavor.BLOCKFROST_HOSTED, null);

        assertThatThrownBy(() -> backend.utxoSupplier().getAll(
                "addr_test1qqjp6ahm37pkpw5fka7wtrwflelj98lfap7fu9yx8ymwrdg28kx5ass8hdt7tcgks"
                        + "qjfzz6e8a294gnwnxz3a96cq29sp5vxd9"))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    void readsAccountStateForAnUnknownStakeAddress() {
        // A real, well-formed stake address that has never registered: it must
        // read as unregistered rather than throw, because that is every fresh
        // wallet's first balance call. (An INVENTED address is a 400 "malformed"
        // instead, which tests the bech32 validator, not this path.)
        var account = preprodClient().getAccountInfo(
                "stake_test1uq9rmr2wcgrmk4l9uytgqfy3pdvn74z65fhfnpg7javq9zcasmx8w");

        assertThat(account).isNotNull();
        assertThat(account.registered()).isFalse();
    }
}
