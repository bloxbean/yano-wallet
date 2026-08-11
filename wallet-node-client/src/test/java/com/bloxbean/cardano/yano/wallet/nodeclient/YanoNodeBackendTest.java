package com.bloxbean.cardano.yano.wallet.nodeclient;

import com.bloxbean.cardano.client.api.model.EvaluationResult;
import com.bloxbean.cardano.client.api.model.ProtocolParams;
import com.bloxbean.cardano.client.api.model.Result;
import com.bloxbean.cardano.client.api.model.Utxo;
import com.bloxbean.cardano.yano.wallet.core.config.WalletNetwork;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.math.BigInteger;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the cardano-client-lib backend wiring against a stub that serves
 * Yano's Blockfrost-shaped responses (field names match Yano's DTOs).
 */
class YanoNodeBackendTest {
    private static final String ADDRESS =
            "addr_test1qpn6ekz4jyqcrb2v2yyc9nq5g25zc9fnw3vmpfuk4fu5s7"
                    + "6u3q0efuxjqcqhjmenn7t97j49y8w9ge8ekz3nu55dj2qquyu2xl";

    private static final String UTXOS_PAGE_1 = """
            [
              {
                "tx_hash": "d5c8b1a54d7708e0d0da2a6ee5f2ff08fbf19a05c11f279d31fcbb15b0aaa1a1",
                "output_index": 0,
                "address": "%s",
                "amount": [
                  {"unit": "lovelace", "quantity": "5000000000"},
                  {"unit": "1f7a58a1aa1e6b047a42109ade331ce26c9c2cce027d043ff264fb1f6274632d746f6b656e",
                   "quantity": "42"}
                ],
                "data_hash": null,
                "inline_datum": null,
                "reference_script_hash": null,
                "block": "b1"
              },
              {
                "tx_hash": "e6d9c2b65e8819f1e1eb3b7ff6f300f90c02ab16d22f38ae42fdcc26c1bbb2b2",
                "output_index": 1,
                "address": "%s",
                "amount": [{"unit": "lovelace", "quantity": "1500000"}],
                "data_hash": null,
                "inline_datum": null,
                "reference_script_hash": null,
                "block": "b2"
              }
            ]
            """.formatted(ADDRESS, ADDRESS);

    private static final String PROTOCOL_PARAMS = """
            {
              "epoch": 12,
              "min_fee_a": 44,
              "min_fee_b": 155381,
              "max_block_size": 90112,
              "max_tx_size": 16384,
              "max_block_header_size": 1100,
              "key_deposit": "2000000",
              "pool_deposit": "500000000",
              "e_max": 18,
              "n_opt": 500,
              "a0": 0.3,
              "rho": 0.003,
              "tau": 0.2,
              "protocol_major_ver": 10,
              "protocol_minor_ver": 0,
              "min_pool_cost": "170000000",
              "coins_per_utxo_size": "4310",
              "coins_per_utxo_word": "4310",
              "price_mem": 0.0577,
              "price_step": 0.0000721,
              "max_tx_ex_mem": "14000000",
              "max_tx_ex_steps": "10000000000",
              "max_block_ex_mem": "62000000",
              "max_block_ex_steps": "20000000000",
              "max_val_size": "5000",
              "collateral_percent": 150,
              "max_collateral_inputs": 3,
              "cost_models": {}
            }
            """;

    private static final String EVALUATE_RESPONSE = """
            {
              "type": "jsonwsp/response",
              "version": "1.0",
              "servicename": "ogmios",
              "methodname": "EvaluateTx",
              "result": {
                "EvaluationResult": {
                  "spend:0": {"memory": 1700, "steps": 476468}
                }
              }
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
    void utxoSupplierPagesThroughYanoUtxos() {
        stub.on("/api/v1/addresses/" + ADDRESS + "/utxos", req ->
                req.path().contains("page=1")
                        ? StubYanoNode.Response.json(UTXOS_PAGE_1)
                        : StubYanoNode.Response.json("[]"));
        YanoNodeBackend backend = YanoNodeBackend.connect(WalletNetwork.DEVNET, stub.baseUrl());

        List<Utxo> utxos = backend.utxoSupplier().getAll(ADDRESS);

        assertThat(utxos).hasSize(2);
        assertThat(utxos.getFirst().getTxHash())
                .isEqualTo("d5c8b1a54d7708e0d0da2a6ee5f2ff08fbf19a05c11f279d31fcbb15b0aaa1a1");
        assertThat(utxos.getFirst().getAmount()).hasSize(2);
        assertThat(utxos.getFirst().getAmount().getFirst().getQuantity())
                .isEqualTo(new BigInteger("5000000000"));
    }

    @Test
    void protocolParamsSupplierReadsEpochParameters() {
        // CCL's BF backend resolves the latest epoch number first, then fetches its parameters.
        stub.on("/api/v1/epochs/latest", "{\"epoch\": 12}");
        stub.on("/api/v1/epochs/12/parameters", PROTOCOL_PARAMS);
        YanoNodeBackend backend = YanoNodeBackend.connect(WalletNetwork.DEVNET, stub.baseUrl());

        ProtocolParams params = backend.protocolParamsSupplier().getProtocolParams();

        assertThat(params.getMinFeeA()).isEqualTo(44);
        assertThat(params.getMinFeeB()).isEqualTo(155381);
        assertThat(params.getCoinsPerUtxoSize()).isEqualTo("4310");
        assertThat(params.getMaxTxSize()).isEqualTo(16384);
    }

    @Test
    void submitsRawCborToTxSubmit() throws Exception {
        String txHash = "9f96bde9339c7cfe4a3a5d84e730f6b17c76bbe4d1e5eb0ef2f6ff5df4f0e888";
        stub.on("/api/v1/tx/submit", req -> StubYanoNode.Response.json("\"" + txHash + "\""));
        YanoNodeBackend backend = YanoNodeBackend.connect(WalletNetwork.DEVNET, stub.baseUrl());

        Result<String> result = backend.transactionProcessor().submitTransaction(new byte[]{(byte) 0x84, 0x01, 0x02});

        assertThat(result.isSuccessful()).isTrue();
        assertThat(result.getValue()).isEqualTo(txHash);
        StubYanoNode.RecordedRequest submit = stub.requests().stream()
                .filter(r -> r.path().startsWith("/api/v1/tx/submit"))
                .findFirst().orElseThrow();
        assertThat(submit.method()).isEqualTo("POST");
        assertThat(submit.contentType()).contains("application/cbor");
        assertThat(submit.body()).containsExactly((byte) 0x84, 0x01, 0x02);
    }

    @Test
    void evaluateTxHitsOgmiosCompatibleEndpointAndParsesExUnits() throws Exception {
        stub.on("/api/v1/utils/txs/evaluate", req -> StubYanoNode.Response.json(EVALUATE_RESPONSE));
        YanoNodeBackend backend = YanoNodeBackend.connect(WalletNetwork.DEVNET, stub.baseUrl());

        Result<List<EvaluationResult>> result =
                backend.transactionProcessor().evaluateTx(new byte[]{(byte) 0x84, 0x01}, Set.of());

        assertThat(result.isSuccessful()).isTrue();
        assertThat(result.getValue()).hasSize(1);
        EvaluationResult evaluation = result.getValue().getFirst();
        assertThat(evaluation.getExUnits().getMem()).isEqualTo(BigInteger.valueOf(1700));
        assertThat(evaluation.getExUnits().getSteps()).isEqualTo(BigInteger.valueOf(476468));
        assertThat(stub.requests().stream()
                .anyMatch(r -> r.path().startsWith("/api/v1/utils/txs/evaluate"))).isTrue();
    }

    @Test
    void connectVerifiedChecksGenesisNetworkMagic() {
        stub.on("/api/v1/genesis", """
                {"network_magic": 42, "system_start": "2026-07-01T00:00:00Z",
                 "epoch_length": 500, "slot_length": 1, "security_param": 300}
                """);

        YanoNodeBackend backend = YanoNodeBackend.connectVerified(WalletNetwork.DEVNET, stub.baseUrl());

        assertThat(backend.network()).isEqualTo(WalletNetwork.DEVNET);
    }
}
