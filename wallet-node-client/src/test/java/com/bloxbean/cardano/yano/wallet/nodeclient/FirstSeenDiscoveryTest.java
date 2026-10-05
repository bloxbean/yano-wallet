package com.bloxbean.cardano.yano.wallet.nodeclient;

import com.bloxbean.cardano.yano.wallet.core.service.HistoryPort.HistoryNotSupportedException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FirstSeenDiscoveryTest {
    @Test void slotZeroCountsAsUsedAndCompleteNullAsUnused() throws Exception {
        try (StubYanoNode node = new StubYanoNode()) {
            YanoNodeClient client = new YanoNodeClient(node.baseUrl(), false);
            node.on("/api/v1/addresses/test/first-seen", response("0", true, 3, 3));
            assertThat(client.isAddressUsed("test")).isTrue();
            node.on("/api/v1/addresses/test/first-seen", response("null", true, 3, 3));
            assertThat(client.isAddressUsed("test")).isFalse();
            assertThat(node.requests()).allMatch(r -> r.path().endsWith("/first-seen"));
        }
    }

    @Test void LaggingNullAndIncompletePositiveNeverDriveGapCounter() throws Exception {
        try (StubYanoNode node = new StubYanoNode()) {
            YanoNodeClient client = new YanoNodeClient(node.baseUrl(), false);
            node.on("/api/v1/addresses/test/first-seen", response("null", true, 2, 3));
            assertThatThrownBy(() -> client.isAddressUsed("test")).isInstanceOf(NodeClientException.class)
                    .hasMessageContaining("catching up");
            node.on("/api/v1/addresses/test/first-seen", response("10", false, 3, 3));
            assertThatThrownBy(() -> client.isAddressUsed("test")).isInstanceOf(NodeClientException.class)
                    .hasMessageContaining("complete coverage");
        }
    }

    @Test void DisabledIndexDoesNotFallBackToPossiblyIncompleteHistory() throws Exception {
        try (StubYanoNode node = new StubYanoNode()) {
            node.on("/api/v1/addresses/test/first-seen", r -> new StubYanoNode.Response(503, "application/json", "{}"));
            assertThatThrownBy(() -> new YanoNodeClient(node.baseUrl(), false).isAddressUsed("test"))
                    .isInstanceOf(HistoryNotSupportedException.class);
            assertThat(node.requests()).hasSize(1);
        }
    }

    @Test void OlderNodeUsesExistingHistoryEndpoint() throws Exception {
        try (StubYanoNode node = new StubYanoNode()) {
            node.on("/api/v1/addresses/test/transactions", "[{\"tx_hash\":\"abc\"}]");
            assertThat(new YanoNodeClient(node.baseUrl(), false).isAddressUsed("test")).isTrue();
            assertThat(node.requests()).hasSize(2);
        }
    }

    private String response(String slot, boolean complete, int indexed, int tip) {
        return """
                {"firstSeenSlot":%s,"coverage":{"enabled":true,"completeFromOrigin":%s,
                "indexedThrough":{"blockNumber":%d}},"liveTip":{"blockNumber":%d}}
                """.formatted(slot, complete, indexed, tip);
    }
}
