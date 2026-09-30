package com.bloxbean.cardano.yano.wallet.nodeclient;

import com.bloxbean.cardano.client.address.Address;
import com.bloxbean.cardano.yano.wallet.core.service.HistoryPort.HistoryNotSupportedException;
import com.bloxbean.cardano.yano.wallet.core.service.HistoryPort.TxRef;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HexFormat;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WalletScanHistoryTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private static final String STAKE = new Address(HexFormat.of().parseHex("e0" + "01".repeat(28))).toBech32();
    private static final String ADDRESS = new Address(HexFormat.of().parseHex("00" + "02".repeat(28) + "01".repeat(28))).toBech32();
    @TempDir Path directory;

    @Test void restartSuppliesPersistedAssetsAndFindsOutgoingOnlyHistory() throws Exception {
        try (StubYanoNode node = new StubYanoNode()) {
            node.on("/api/v1/scan", stream(1, transaction(1, false)));
            assertThat(history(node).transactions(STAKE, 1, 10, false)).extracting(TxRef::txHash).containsExactly(hash(1));
            node.on("/api/v1/scan", stream(2, transaction(2, true)));
            assertThat(history(node).transactions(STAKE, 1, 10, false)).extracting(TxRef::txHash).containsExactly(hash(1), hash(2));
            JsonNode request = mapper.readTree(node.requests().getLast().body());
            assertThat(request.path("after")).isEqualTo(point(1));
            assertThat(request.path("knownOutputs").size()).isEqualTo(1);
            assertThat(request.path("knownOutputs").get(0).path("assets").get(0).path("quantity").asInt()).isEqualTo(7);
            assertThat(saved().path("current").path("outputs").size()).isZero();
        }
    }

    @Test void incompleteStreamDoesNotReplaceSavedState() throws Exception {
        try (StubYanoNode node = new StubYanoNode()) {
            node.on("/api/v1/scan", stream(1, transaction(1, false)));
            history(node).transactions(STAKE, 1, 10, false);
            JsonNode before = saved();
            node.on("/api/v1/scan", ready(2) + transaction(2, true));
            assertThatThrownBy(() -> history(node).transactions(STAKE, 1, 10, false))
                    .isInstanceOf(NodeClientException.class).hasMessageContaining("without a complete done");
            assertThat(saved()).isEqualTo(before);
        }
    }

    @Test void reorgRestoresPreviousBoundaryIncludingUnspentOutput() throws Exception {
        try (StubYanoNode node = new StubYanoNode()) {
            node.on("/api/v1/scan", stream(1, transaction(1, false)));
            history(node).transactions(STAKE, 1, 10, false);
            node.on("/api/v1/scan", stream(2, transaction(2, true)));
            history(node).transactions(STAKE, 1, 10, false);
            AtomicInteger attempt = new AtomicInteger();
            node.on("/api/v1/scan", request -> attempt.getAndIncrement() == 0
                    ? new StubYanoNode.Response(409, "application/json", "{}")
                    : StubYanoNode.Response.json(stream(3, "")));
            assertThat(history(node).transactions(STAKE, 1, 10, false)).extracting(TxRef::txHash).containsExactly(hash(1));
            JsonNode resumed = mapper.readTree(node.requests().getLast().body());
            assertThat(resumed.path("after")).isEqualTo(point(1));
            assertThat(resumed.path("knownOutputs").size()).isEqualTo(1);
            assertThat(saved().path("current").path("outputs").size()).isEqualTo(1);
        }
    }

    @Test void deeperReorgRestartsAtOriginWithEmptyState() throws Exception {
        try (StubYanoNode node = new StubYanoNode()) {
            node.on("/api/v1/scan", stream(1, transaction(1, false)));
            history(node).transactions(STAKE, 1, 10, false);
            node.on("/api/v1/scan", stream(2, transaction(2, true)));
            history(node).transactions(STAKE, 1, 10, false);
            AtomicInteger attempt = new AtomicInteger();
            node.on("/api/v1/scan", request -> attempt.getAndIncrement() < 2
                    ? new StubYanoNode.Response(409, "application/json", "{}")
                    : StubYanoNode.Response.json(stream(3, "")));
            assertThat(history(node).transactions(STAKE, 1, 10, false)).isEmpty();
            JsonNode resumed = mapper.readTree(node.requests().getLast().body());
            assertThat(resumed.path("after").path("blockNumber").asInt()).isEqualTo(-1);
            assertThat(resumed.path("knownOutputs").size()).isZero();
        }
    }

    @Test void mismatchedDoneOrPostDoneDataCannotCommitHistory() throws Exception {
        try (StubYanoNode node = new StubYanoNode()) {
            node.on("/api/v1/scan", ready(2) + event("done", 1));
            assertThatThrownBy(() -> history(node).transactions(STAKE, 1, 10, false))
                    .isInstanceOf(NodeClientException.class).hasMessageContaining("completion point mismatch");
            node.on("/api/v1/scan", stream(1, "") + transaction(1, false));
            assertThatThrownBy(() -> history(node).transactions(STAKE, 1, 10, false))
                    .isInstanceOf(NodeClientException.class).hasMessageContaining("after completion");
            try (var files = Files.list(directory)) { assertThat(files).isEmpty(); }
        }
    }

    @Test void malformedOrOutOfRangeTransactionCannotCommitHistory() throws Exception {
        try (StubYanoNode node = new StubYanoNode()) {
            node.on("/api/v1/scan", stream(1, transaction(2, false)));
            assertThatThrownBy(() -> history(node).transactions(STAKE, 1, 10, false))
                    .isInstanceOf(NodeClientException.class).hasMessageContaining("scan point");
            ObjectNode malformed = (ObjectNode) mapper.readTree(transaction(1, false));
            ((ObjectNode) malformed.path("point")).remove("slot");
            node.on("/api/v1/scan", stream(1, malformed + "\n"));
            assertThatThrownBy(() -> history(node).transactions(STAKE, 1, 10, false))
                    .isInstanceOf(NodeClientException.class).hasMessageContaining("outside scan interval");
            try (var files = Files.list(directory)) { assertThat(files).isEmpty(); }
        }
    }

    @Test void matchingTransactionMayAlsoPayAnUnrelatedByronAddress() throws Exception {
        try (StubYanoNode node = new StubYanoNode()) {
            ObjectNode record = (ObjectNode) mapper.readTree(transaction(1, false));
            record.withArray("outputs").addObject()
                    .put("address", "Ae2tdPwUPEZ3DdaWu8jn553npu6jwEPAJiahruj3xQjPXxgoxfYDWusJz7x")
                    .put("lovelace", 1000000).putObject("outpoint").put("txHash", hash(1)).put("index", 1);
            node.on("/api/v1/scan", stream(1, record + "\n"));
            assertThat(history(node).transactions(STAKE, 1, 10, false)).extracting(TxRef::txHash).containsExactly(hash(1));
            assertThat(saved().path("current").path("outputs").size()).isEqualTo(1);
        }
    }

    @Test void aNodeThatCannotServeScansIsTreatedAsHavingNoIndex() throws Exception {
        // 503, not 404: pre16 carries the scan route but answers 503 when the
        // index or UTxO state is off, or the index needs a fresh sync. Reported
        // as an error it would fill the History screen with a failure on every
        // refresh instead of falling back (ADR-043). A chainstate synced before
        // the index existed answers 400 instead — see the origin-scan tests.
        try (StubYanoNode node = new StubYanoNode()) {
            node.on("/api/v1/scan", request -> new StubYanoNode.Response(503, "application/json",
                    "{\"error\":\"Wallet scan requires UTxO state\"}"));
            assertThatThrownBy(() -> history(node).transactions(STAKE, 1, 10, false))
                    .isInstanceOf(HistoryNotSupportedException.class)
                    .hasMessageContaining("UTxO state");

            node.on("/api/v1/scan", request -> new StubYanoNode.Response(503, "application/json",
                    "{\"error\":\"Index disabled\",\"coverage\":{\"enabled\":false,\"completeFromOrigin\":false}}"));
            assertThatThrownBy(() -> history(node).transactions(STAKE, 1, 10, false))
                    .isInstanceOf(HistoryNotSupportedException.class);

            // Enabled but never initialised: the node's own remedy is a fresh sync.
            node.on("/api/v1/scan", request -> new StubYanoNode.Response(503, "application/json",
                    "{\"error\":\"Index missing; enable before a fresh sync\","
                            + "\"coverage\":{\"enabled\":true,\"completeFromOrigin\":false}}"));
            assertThatThrownBy(() -> history(node).transactions(STAKE, 1, 10, false))
                    .isInstanceOf(HistoryNotSupportedException.class)
                    .hasMessageContaining("fresh sync");
        }
    }

    @Test void aBusyNodeStaysAnErrorRatherThanLosingItsHistory() throws Exception {
        // Each of these passes. Answering one with "this node keeps no history"
        // would swap the node's real history for the local list — hiding
        // transactions behind a permanent-sounding answer to a temporary state.
        // The last two carry a coverage object and name the index, which is
        // exactly why neither can decide the question on its own.
        for (String body : new String[] {
                "{\"error\":\"Scan concurrency limit reached\"}",
                "{\"error\":\"Index is not at the applied canonical point\","
                        + "\"coverage\":{\"enabled\":true,\"completeFromOrigin\":true}}",
                "{\"error\":\"Contributor storage is closed or being replaced\","
                        + "\"coverage\":{\"enabled\":true,\"completeFromOrigin\":false}}"}) {
            try (StubYanoNode node = new StubYanoNode()) {
                node.on("/api/v1/scan", request -> new StubYanoNode.Response(503, "application/json", body));
                assertThatThrownBy(() -> history(node).transactions(STAKE, 1, 10, false))
                        .as(body)
                        .isInstanceOf(NodeClientException.class)
                        .isNotInstanceOf(HistoryNotSupportedException.class)
                        .hasMessageContaining("HTTP 503");
            }
        }
    }

    @Test void anIndexStartedAfterGenesisFallsBackForAScanFromOrigin() throws Exception {
        // What every chainstate upgraded to an index-carrying node gets: the index
        // starts at the block after it was switched on, so a scan from origin is
        // refused with 400 on every refresh. There is no history to read — it
        // falls back to the local list (ADR-043) instead of erroring forever.
        try (StubYanoNode node = new StubYanoNode()) {
            node.on("/api/v1/scan", request -> new StubYanoNode.Response(400, "application/json",
                    "{\"error\":\"Requested range is outside complete filter coverage\"}"));
            assertThatThrownBy(() -> history(node).transactions(STAKE, 1, 10, false))
                    .isInstanceOf(HistoryNotSupportedException.class)
                    .hasMessageContaining("start of the chain");
            assertThat(mapper.readTree(node.requests().getLast().body()).path("after").path("blockNumber").asInt())
                    .isEqualTo(-1);
        }
    }

    @Test void anIndexBehindASavedCursorStaysAnError() throws Exception {
        // Same 400, but from a cursor this node itself handed out: an index
        // trailing it says the same thing and catches up. Falling back would
        // replace the scanned history with the local list in the meantime.
        try (StubYanoNode node = new StubYanoNode()) {
            node.on("/api/v1/scan", stream(1, transaction(1, false)));
            history(node).transactions(STAKE, 1, 10, false);
            JsonNode before = saved();
            node.on("/api/v1/scan", request -> new StubYanoNode.Response(400, "application/json",
                    "{\"error\":\"Requested range is outside complete filter coverage\"}"));
            assertThatThrownBy(() -> history(node).transactions(STAKE, 1, 10, false))
                    .isInstanceOf(NodeClientException.class)
                    .isNotInstanceOf(HistoryNotSupportedException.class)
                    .hasMessageContaining("outside complete filter coverage");
            assertThat(saved()).isEqualTo(before);
        }
    }

    @Test void anyOtherRefusalOfAScanFromOriginStaysAnError() throws Exception {
        // A malformed request is the wallet's bug, not the node's missing index.
        try (StubYanoNode node = new StubYanoNode()) {
            node.on("/api/v1/scan", request -> new StubYanoNode.Response(400, "application/json",
                    "{\"error\":\"Unsupported scan version\"}"));
            assertThatThrownBy(() -> history(node).transactions(STAKE, 1, 10, false))
                    .isInstanceOf(NodeClientException.class)
                    .isNotInstanceOf(HistoryNotSupportedException.class)
                    .hasMessageContaining("Unsupported scan version");
        }
    }

    @Test void progressIsReportedOnlyWhileAScanIsRunning() throws Exception {
        try (StubYanoNode node = new StubYanoNode()) {
            WalletScanHistory history = history(node);
            assertThat(history.progress()).isEmpty();

            node.on("/api/v1/scan", stream(1, transaction(1, false)));
            history.transactions(STAKE, 1, 10, false);
            // A finished scan is not progress: its rows are about to be shown.
            assertThat(history.progress()).isEmpty();

            // And a scan that fails must not leave a percentage frozen on screen
            // claiming work is still going on.
            node.on("/api/v1/scan", ready(2) + transaction(2, true));
            assertThatThrownBy(() -> history.transactions(STAKE, 1, 10, false))
                    .isInstanceOf(NodeClientException.class);
            assertThat(history.progress()).isEmpty();
        }
    }

    private WalletScanHistory history(StubYanoNode node) {
        return new WalletScanHistory(new YanoNodeClient(node.baseUrl(), false), directory);
    }

    private JsonNode saved() throws Exception {
        try (var files = Files.list(directory)) {
            return mapper.readTree(Files.readAllBytes(files.filter(p -> p.toString().endsWith(".json")).findFirst().orElseThrow()));
        }
    }

    private ObjectNode point(int block) {
        return mapper.createObjectNode().put("blockNumber", block).put("slot", block * 10).put("blockHash", hash(block));
    }

    private String ready(int block) {
        ObjectNode record = mapper.createObjectNode().put("type", "ready");
        record.set("point", point(block));
        record.putObject("coverage").put("identity", "test-genesis");
        return record + "\n";
    }

    private String event(String type, int block) {
        ObjectNode record = mapper.createObjectNode().put("type", type);
        record.set("point", point(block));
        return record + "\n";
    }

    private String stream(int block, String data) { return ready(block) + data + event("done", block); }

    private String transaction(int block, boolean spend) {
        ObjectNode record = mapper.createObjectNode().put("type", "transaction").put("txHash", hash(block)).put("blockTime", block * 10);
        record.set("point", point(block));
        if (spend) record.putArray("inputs").addObject().put("txHash", hash(1)).put("index", 0);
        else {
            ObjectNode output = record.putArray("outputs").addObject().put("address", ADDRESS).put("lovelace", 1000000);
            output.putObject("outpoint").put("txHash", hash(block)).put("index", 0);
            output.putArray("assets").addObject().put("unit", "ab".repeat(28)).put("quantity", 7);
        }
        return record + "\n";
    }

    private static String hash(int value) { return "%02x".formatted(value).repeat(32); }
}
