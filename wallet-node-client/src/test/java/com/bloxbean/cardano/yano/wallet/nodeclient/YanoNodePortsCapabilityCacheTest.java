package com.bloxbean.cardano.yano.wallet.nodeclient;

import com.bloxbean.cardano.yano.wallet.core.simulate.SimulationCapabilities;
import com.bloxbean.cardano.yano.wallet.core.simulate.SimulationCapabilities.Support;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The capability cache's policy (ADR-042 SIM-M0): probe once for a healthy node,
 * but never write off a node that answered "no" while it was still starting up.
 * A node initialising its UTxO index answers 503, and its evaluator reports "not
 * initialized" — both look definitive, both are temporary.
 */
class YanoNodePortsCapabilityCacheTest {

    private StubYanoNode node;
    private YanoNodePorts ports;

    private static final String PROBE_UTXO_PATH = "/api/v1/utxos/" + "0".repeat(64) + "/0";
    private static final String EVALUATE_PATH = "/api/v1/utils/txs/evaluate";

    private static String evalFailure(String message) {
        return """
                {"result":{"EvaluationFailure":{"message":"%s"}}}
                """.formatted(message);
    }

    @BeforeEach
    void setUp() throws IOException {
        node = new StubYanoNode();
        ports = new YanoNodePorts(new YanoNodeClient(node.baseUrl()));
    }

    @AfterEach
    void tearDown() {
        node.close();
    }

    private void serveCapableNode() {
        node.on(PROBE_UTXO_PATH, req -> new StubYanoNode.Response(404, "application/json", ""));
        node.on(EVALUATE_PATH, evalFailure("CBOR deserialization failed"));
    }

    private long probeRequestCount() {
        return node.requests().stream()
                .filter(r -> r.path().startsWith("/api/v1/utxos/") || r.path().equals(EVALUATE_PATH))
                .count();
    }

    @Test
    void aFullyCapableNodeIsProbedOnce() {
        serveCapableNode();

        assertThat(ports.capabilities().canSimulateFully()).isTrue();
        long afterFirst = probeRequestCount();
        assertThat(ports.capabilities().canSimulateFully()).isTrue();

        assertThat(probeRequestCount())
                .as("second call must be served from the cache")
                .isEqualTo(afterFirst);
    }

    @Test
    void aStartingNodeIsNotWrittenOffForTheSession() {
        // Still initialising: index not up, evaluator not up. Both definitive-looking.
        node.on(PROBE_UTXO_PATH, req -> new StubYanoNode.Response(503, "application/json",
                "{\"error\":\"UTXO state disabled\"}"));
        node.on(EVALUATE_PATH, evalFailure("Script evaluation not initialized. Ensure tx-evaluation is enabled"));

        SimulationCapabilities starting = ports.capabilities();
        assertThat(starting.canSimulateFully()).isFalse();

        // The node finishes starting up.
        serveCapableNode();

        assertThat(ports.capabilities().canSimulateFully())
                .as("a node that finished starting must not stay written off")
                .isTrue();
    }

    @Test
    void anInconclusiveProbeIsRetried() {
        node.close(); // unreachable → UNKNOWN

        assertThat(ports.capabilities().utxoLookup()).isEqualTo(Support.UNKNOWN);
        assertThat(ports.capabilities().utxoLookup()).isEqualTo(Support.UNKNOWN);
    }

    @Test
    void oneBackendHandsOutOneCachingPortsInstance() {
        // The cache only means anything if callers share an instance; app code
        // reaches this as backend.ports() at many call sites.
        YanoNodeBackend backend = YanoNodeBackend.connect(
                com.bloxbean.cardano.yano.wallet.core.config.WalletNetwork.PREPROD, node.baseUrl());

        assertThat(backend.ports()).isSameAs(backend.ports());
    }
}
