package com.bloxbean.cardano.yano.wallet.nodeclient;

import com.bloxbean.cardano.yano.wallet.core.simulate.ScriptEvaluation;
import com.bloxbean.cardano.yano.wallet.core.simulate.SimulationCapabilities;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Probes a REAL running Yano node (ADR-042 SIM-M0). Off by default — CI has no
 * synced node — and enabled by pointing at one:
 *
 * <pre>./gradlew :wallet-node-client:test -Dyano.live.node=http://127.0.0.1:7070/api/v1/</pre>
 *
 * <p>This exists because the capability probe rests on a distinction the stub
 * cannot prove: that a node serving {@code /txs/{hash}/utxos} answers an unknown
 * transaction with a 404 carrying a JSON body, while an absent route answers with
 * an HTML error page. That is a property of the real server, so it deserves a test
 * against one — and against a DevKit's yaci-store too, which says the same thing
 * in RFC 7807 and is equally a pass.
 */
class LiveNodeSimulationProbeTest {

    private static final String NODE_URL_PROPERTY = "yano.live.node";

    @Test
    @EnabledIfSystemProperty(named = NODE_URL_PROPERTY, matches = ".+")
    void aLiveNodeReportsBothSimulationCapabilities() {
        YanoNodeClient client = new YanoNodeClient(System.getProperty(NODE_URL_PROPERTY));

        SimulationCapabilities capabilities = client.probeSimulationCapabilities();

        assertThat(capabilities.utxoLookup())
                .as("live node should serve /txs/{txHash}/utxos")
                .isEqualTo(SimulationCapabilities.Support.AVAILABLE);
        assertThat(capabilities.scriptEvaluation())
                .as("live node should serve /utils/txs/evaluate with an initialised evaluator")
                .isEqualTo(SimulationCapabilities.Support.AVAILABLE);
        assertThat(capabilities.canSimulateFully()).isTrue();
    }

    @Test
    @EnabledIfSystemProperty(named = NODE_URL_PROPERTY, matches = ".+")
    void anUnknownOutputReferenceResolvesToNullRatherThanThrowing() {
        YanoNodeClient client = new YanoNodeClient(System.getProperty(NODE_URL_PROPERTY));

        // An all-zero tx hash is not in any UTxO set; the route exists, so this is
        // the "not in the UTxO set" answer, not a missing-route error.
        assertThat(client.getUtxo("0".repeat(64), 0)).isNull();
    }

    @Test
    @EnabledIfSystemProperty(named = NODE_URL_PROPERTY, matches = ".+")
    void garbageIsRejectedByTheEvaluatorItselfNotByAMissingRoute() {
        YanoNodeClient client = new YanoNodeClient(System.getProperty(NODE_URL_PROPERTY));

        ScriptEvaluation evaluation = client.evaluateTx("00");

        // Backends disagree on HOW they reject garbage — a Yano node answers 200
        // with an Ogmios EvaluationFailure, yaci-store answers 500 — so the
        // portable assertion is that the wallet never mistakes either for a
        // transaction whose scripts were proven to fail.
        assertThat(evaluation.outcome()).isIn(
                ScriptEvaluation.Outcome.FAILURE, ScriptEvaluation.Outcome.UNAVAILABLE);
        assertThat(client.probeSimulationCapabilities().scriptEvaluation())
                .as("the evaluate route exists on this node")
                .isEqualTo(SimulationCapabilities.Support.AVAILABLE);
    }
}
