package com.bloxbean.cardano.yano.wallet.nodeclient;

import com.bloxbean.cardano.yano.wallet.core.simulate.ResolvedOutput;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Resolves a REAL output reference against a live node (ADR-038 BF-M3). Point it
 * at either backend — a Yano node or a DevKit's yaci-store — and the assertion is
 * the same, which is the whole claim behind resolving through Blockfrost's
 * {@code /txs/{hash}/utxos} rather than a per-backend route:
 *
 * <pre>./gradlew :wallet-node-client:test -Dyano.live.node=http://localhost:8080/api/v1/ \
 *     -Dyano.live.outref=&lt;txHash&gt;#&lt;index&gt;</pre>
 */
class LiveResolveTest {

    @Test
    @EnabledIfSystemProperty(named = "yano.live.outref", matches = ".+")
    void resolvesARealOutputReferenceOnWhicheverBackendItIsPointedAt() {
        String[] ref = System.getProperty("yano.live.outref").split("#");
        YanoNodeClient client = new YanoNodeClient(System.getProperty("yano.live.node"));

        ResolvedOutput output = client.getUtxo(ref[0], Integer.parseInt(ref[1]));

        assertThat(output).isNotNull();
        assertThat(output.address()).startsWith("addr");
        assertThat(output.lovelace()).isPositive();
        System.out.println("RESOLVED " + output.address().substring(0, 24) + "… lovelace="
                + output.lovelace() + " assets=" + output.assets().size());
    }
}
