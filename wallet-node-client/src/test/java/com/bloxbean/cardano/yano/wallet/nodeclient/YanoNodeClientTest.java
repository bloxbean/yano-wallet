package com.bloxbean.cardano.yano.wallet.nodeclient;

import com.bloxbean.cardano.yano.wallet.core.config.WalletNetwork;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class YanoNodeClientTest {
    private static final String STATUS_JSON = """
            {
              "chain": {"slot": 12345, "blockNumber": 678, "blockHash": "aabbcc"},
              "utxo": {"enabled": true, "lastAppliedBlock": 678, "lastAppliedSlot": 12345, "lagBlocks": 0},
              "cfEstimates": {}
            }
            """;
    private static final String GENESIS_JSON = """
            {
              "active_slots_coefficient": 0.05,
              "update_quorum": 5,
              "max_lovelace_supply": "45000000000000000",
              "network_magic": 42,
              "epoch_length": 500,
              "system_start": "2026-07-01T00:00:00Z",
              "slots_per_kes_period": 129600,
              "slot_length": 1,
              "max_kes_evolutions": 62,
              "security_param": 300
            }
            """;

    private static final String PROPOSALS_JSON = """
            [
              {"id": "gov_action1abc", "tx_hash": "aa11", "cert_index": 0,
               "governance_type": "info_action", "status": "active",
               "proposed_epoch": 500, "expires_after_epoch": 520},
              {"id": "gov_action1def", "tx_hash": "bb22", "cert_index": 3,
               "governance_type": "treasury_withdrawals", "status": "active",
               "proposed_epoch": 501, "expires_after_epoch": 521}
            ]
            """;

    private static final String ACCOUNT_JSON = """
            {
              "stake_address": "stake_test1xyz", "active": true, "registered": true,
              "withdrawable_amount": "1500000", "pool_id": "pool1abc",
              "drep_id": "drep1qqq", "drep_type": "key_hash"
            }
            """;
    private static final String DREP_JSON = """
            {
              "drep_id": "drep1qqq", "active": true, "retired": false, "expired": false,
              "registered_epoch": 480, "deposit": "500000000"
            }
            """;

    private StubYanoNode stub;

    @BeforeEach
    void setUp() throws IOException {
        stub = new StubYanoNode();
    }

    @AfterEach
    void tearDown() {
        stub.close();
    }

    @Test
    void parsesNodeStatus() {
        stub.on("/api/v1/status", STATUS_JSON);
        YanoNodeClient client = new YanoNodeClient(stub.baseUrl());

        NodeStatus status = client.getStatus();

        assertThat(status.slot()).isEqualTo(12345);
        assertThat(status.blockNumber()).isEqualTo(678);
        assertThat(status.blockHash()).isEqualTo("aabbcc");
        assertThat(status.utxoIndexEnabled()).isTrue();
        assertThat(status.utxoLagBlocks()).isZero();
        assertThat(status.utxoIndexCaughtUp()).isTrue();
    }

    @Test
    void parsesGenesisAndVerifiesMatchingNetwork() {
        stub.on("/api/v1/genesis", GENESIS_JSON);
        YanoNodeClient client = new YanoNodeClient(stub.baseUrl());

        GenesisInfo genesis = client.getGenesis();
        assertThat(genesis.networkMagic()).isEqualTo(42);
        assertThat(genesis.securityParam()).isEqualTo(300);

        client.verifyNetwork(WalletNetwork.DEVNET);
    }

    @Test
    void rejectsNodeServingDifferentNetwork() {
        stub.on("/api/v1/genesis", GENESIS_JSON);
        YanoNodeClient client = new YanoNodeClient(stub.baseUrl());

        assertThatThrownBy(() -> client.verifyNetwork(WalletNetwork.PREPROD))
                .isInstanceOf(NodeClientException.class)
                .hasMessageContaining("magic 42")
                .hasMessageContaining("preprod");
    }

    @Test
    void blockfrostBackendWithoutStatusFallsBackToLatestBlock() {
        // yaci-store has no /status (ADR-038): the tip comes from /blocks/latest.
        // Verified live against a Yaci DevKit devnet.
        stub.on("/api/v1/blocks/latest",
                "{\"height\":6321,\"slot\":6322,\"hash\":\"8957dc42\",\"epoch\":10}");
        YanoNodeClient client = new YanoNodeClient(stub.baseUrl());

        NodeStatus status = client.getStatus();

        assertThat(status.blockNumber()).isEqualTo(6321);
        assertThat(status.slot()).isEqualTo(6322);
        assertThat(status.blockHash()).isEqualTo("8957dc42");
        // No separate index to fall behind, so never claim a lag.
        assertThat(status.utxoLagBlocks()).isZero();
        assertThat(status.utxoIndexCaughtUp()).isTrue();
    }

    @Test
    void blockfrostFlavoredNetworkSkipsGenesisVerification() {
        // No /genesis stubbed: yaci-store doesn't serve one, and the network comes
        // from the user's explicit YACI_DEVKIT choice instead.
        YanoNodeClient client = new YanoNodeClient(stub.baseUrl());

        assertThatCode(() -> client.verifyNetwork(WalletNetwork.YACI_DEVKIT))
                .doesNotThrowAnyException();
    }

    @Test
    void reportsUnreachableNode() {
        stub.close();
        YanoNodeClient client = new YanoNodeClient(stub.baseUrl());

        assertThat(client.isReachable()).isFalse();
        assertThatThrownBy(client::getStatus).isInstanceOf(NodeClientException.class);
    }

    @Test
    void normalizesBaseUrl() {
        assertThat(YanoNodeClient.normalizeBaseUrl("http://localhost:7070/api/v1"))
                .isEqualTo("http://localhost:7070/api/v1/");
        assertThat(YanoNodeClient.normalizeBaseUrl("http://localhost:7070/api/v1/"))
                .isEqualTo("http://localhost:7070/api/v1/");
        assertThatThrownBy(() -> YanoNodeClient.normalizeBaseUrl(" "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void reportsTxOnChainOnceTxResolves() {
        String hash = "9f96bde9339c7cfe4a3a5d84e730f6b17c76bbe4d1e5eb0ef2f6ff5df4f0e888";
        stub.on("/api/v1/txs/" + hash, "{\"hash\": \"" + hash + "\"}");
        YanoNodeClient client = new YanoNodeClient(stub.baseUrl());

        assertThat(client.isTxOnChain(hash)).isTrue();
        assertThat(client.isTxOnChain("0000000000000000000000000000000000000000000000000000000000000000")).isFalse();
    }

    @Test
    void parsesActiveGovernanceProposals() {
        stub.on("/api/v1/governance/proposals", PROPOSALS_JSON);
        YanoNodeClient client = new YanoNodeClient(stub.baseUrl());

        var proposals = client.listActiveProposals();

        assertThat(proposals).hasSize(2);
        assertThat(proposals.get(0).id()).isEqualTo("gov_action1abc");
        assertThat(proposals.get(0).txHash()).isEqualTo("aa11");
        assertThat(proposals.get(0).certIndex()).isZero();
        assertThat(proposals.get(0).governanceType()).isEqualTo("info_action");
        assertThat(proposals.get(0).status()).isEqualTo("active");
        assertThat(proposals.get(0).expiresAfterEpoch()).isEqualTo(520);
        assertThat(proposals.get(1).txHash()).isEqualTo("bb22");
        assertThat(proposals.get(1).certIndex()).isEqualTo(3);
    }

    @Test
    void accountInfoReadsDRepDelegationTarget() {
        stub.on("/api/v1/accounts/stake_test1xyz", ACCOUNT_JSON);
        YanoNodeClient client = new YanoNodeClient(stub.baseUrl());

        var view = client.getAccountInfo("stake_test1xyz");

        assertThat(view.registered()).isTrue();
        assertThat(view.delegatedPoolId()).isEqualTo("pool1abc");
        assertThat(view.drepId()).isEqualTo("drep1qqq");
        assertThat(view.drepType()).isEqualTo("key_hash");
    }

    @Test
    void accountInfo404MapsToUnregisteredWithNoDRep() {
        YanoNodeClient client = new YanoNodeClient(stub.baseUrl());

        var view = client.getAccountInfo("stake_test1never");

        assertThat(view.registered()).isFalse();
        assertThat(view.drepId()).isNull();
        assertThat(view.drepType()).isNull();
    }

    @Test
    void getDRepInfoParsesRegisteredDRep() {
        stub.on("/api/v1/governance/dreps/drep1qqq", DREP_JSON);
        YanoNodeClient client = new YanoNodeClient(stub.baseUrl());

        var info = client.getDRepInfo("drep1qqq");

        assertThat(info).isNotNull();
        assertThat(info.active()).isTrue();
        assertThat(info.registeredEpoch()).isEqualTo(480);
        assertThat(info.deposit()).isEqualTo(new java.math.BigInteger("500000000"));
    }

    @Test
    void getDRepInfo404MeansNotRegistered() {
        YanoNodeClient client = new YanoNodeClient(stub.baseUrl());
        assertThat(client.getDRepInfo("drep1none")).isNull();
    }

    @Test
    void getDRepInfoPropagatesNodeError() {
        stub.on("/api/v1/governance/dreps/drep1boom",
                req -> new StubYanoNode.Response(500, "application/json", "{\"error\":\"boom\"}"));
        YanoNodeClient client = new YanoNodeClient(stub.baseUrl());

        assertThatThrownBy(() -> client.getDRepInfo("drep1boom"))
                .isInstanceOf(NodeClientException.class)
                .hasMessageContaining("500");
    }

    @Test
    void statusFailureSurfacesHttpStatus() {
        stub.on("/api/v1/status", req -> new StubYanoNode.Response(500, "application/json", "{\"error\":\"boom\"}"));
        YanoNodeClient client = new YanoNodeClient(stub.baseUrl());

        assertThatThrownBy(client::getStatus)
                .isInstanceOf(NodeClientException.class)
                .hasMessageContaining("500");
    }
}
