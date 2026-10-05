package com.bloxbean.cardano.yano.wallet.nodeclient;

import com.bloxbean.cardano.yano.wallet.core.service.HistoryPort.ScanProgress;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.Optional;

/**
 * Reads a scan's position out of the records it streams, so the UI can report a
 * long first scan instead of showing an empty screen for half a minute.
 *
 * <p>Reporting only, and deliberately separate from the scan's own bookkeeping
 * in {@link WalletScanHistory}: the validation there decides whether a scan is
 * trustworthy, and a display value must never be able to influence it. A record
 * this tracker cannot read is skipped rather than rejected, for the same reason.
 */
final class ScanProgressTracker {
    private final long fromBlock;

    /** Null until the node reports how far it has indexed, and again once done. */
    private volatile ScanProgress snapshot;

    ScanProgressTracker(long fromBlock) {
        this.fromBlock = fromBlock;
    }

    /**
     * The node states its tip in the {@code ready} record that opens every scan,
     * so the interval is known before the first block arrives; each later record
     * carries the point reached. {@code done} clears the snapshot — a finished
     * scan is not progress, and the rows themselves are about to be shown.
     */
    void observe(JsonNode record) {
        JsonNode block = record.path("point").path("blockNumber");
        if (!block.isIntegralNumber()) {
            return;
        }
        switch (record.path("type").asText()) {
            case "ready" -> snapshot = new ScanProgress(fromBlock, fromBlock, block.longValue());
            case "done" -> snapshot = null;
            default -> {
                ScanProgress current = snapshot;
                if (current != null) {
                    snapshot = new ScanProgress(fromBlock, block.longValue(), current.tipBlock());
                }
            }
        }
    }

    Optional<ScanProgress> snapshot() {
        return Optional.ofNullable(snapshot);
    }
}
