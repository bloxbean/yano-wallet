package com.bloxbean.cardano.yano.wallet.nodeclient;

import com.bloxbean.cardano.yano.wallet.core.service.HistoryPort.ScanProgress;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class ScanProgressTrackerTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test void reportsNothingUntilTheNodeStatesHowFarItReaches() {
        ScanProgressTracker tracker = new ScanProgressTracker(-1);

        // A progress record before "ready" leaves the interval unknown, and a
        // percentage computed against an unknown tip would be invented.
        tracker.observe(record("progress", 500));
        assertThat(tracker.snapshot()).isEmpty();

        tracker.observe(record("ready", 1_000));
        assertThat(tracker.snapshot()).get().isEqualTo(new ScanProgress(-1, -1, 1_000));
    }

    @Test void walksFromTheResumeCursorToTheTip() {
        ScanProgressTracker tracker = new ScanProgressTracker(400);
        tracker.observe(record("ready", 800));

        tracker.observe(record("progress", 600));
        assertThat(tracker.snapshot()).get().isEqualTo(new ScanProgress(400, 600, 800));
        // Fraction covers the interval actually being walked, not the whole
        // chain: an incremental scan is half done at its own midpoint.
        assertThat(tracker.snapshot().orElseThrow().fraction()).isEqualTo(0.5, within(1e-9));

        // Transactions carry a point too, so history-dense stretches still advance.
        tracker.observe(record("transaction", 700));
        assertThat(tracker.snapshot()).get().isEqualTo(new ScanProgress(400, 700, 800));
    }

    @Test void completionIsNotProgress() {
        ScanProgressTracker tracker = new ScanProgressTracker(-1);
        tracker.observe(record("ready", 900));
        tracker.observe(record("progress", 450));

        tracker.observe(record("done", 900));

        assertThat(tracker.snapshot()).isEmpty();
    }

    @Test void skipsRecordsItCannotRead() {
        ScanProgressTracker tracker = new ScanProgressTracker(0);
        tracker.observe(record("ready", 100));
        ScanProgress before = tracker.snapshot().orElseThrow();

        tracker.observe(mapper.createObjectNode().put("type", "progress"));
        tracker.observe(mapper.createObjectNode().put("type", "transaction")
                .set("point", mapper.createObjectNode().put("blockNumber", "not-a-number")));

        assertThat(tracker.snapshot()).get().isEqualTo(before);
    }

    @Test void aScanWithNothingToWalkIsComplete() {
        // Tip equal to the cursor: the wallet is already at the node's tip, so
        // the interval is empty rather than a division by zero.
        assertThat(new ScanProgress(900, 900, 900).fraction()).isEqualTo(1.0);
    }

    @Test void fractionStaysWithinBounds() {
        // The node may index past the tip it announced while the scan runs.
        assertThat(new ScanProgress(0, 1_200, 1_000).fraction()).isEqualTo(1.0);
        assertThat(new ScanProgress(500, 100, 1_000).fraction()).isEqualTo(0.0);
    }

    private com.fasterxml.jackson.databind.JsonNode record(String type, long block) {
        var node = mapper.createObjectNode().put("type", type);
        node.putObject("point").put("blockNumber", block);
        return node;
    }
}
