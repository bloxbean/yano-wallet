package com.bloxbean.cardano.yano.wallet.ui.live;

import com.bloxbean.cardano.yano.wallet.ui.contract.WalletUiController.LiveChainView;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * Shared state behind the live-chain visualization (ambient background + the
 * dedicated Live page). Updated from the shell's data poller on the FX thread and
 * read by the animation frames on the same thread, so no synchronization is needed.
 *
 * <p>Tracks the latest tip snapshot, a bounded deque of recently-arrived blocks
 * (for the "block train" animation), and a smoothed block/second throughput.
 */
public final class LiveChainModel {

    /** A recently-arrived block; {@code bornNanos} drives its entry animation. */
    public record Block(long height, int txCount, long sizeBytes, long bornNanos) {
    }

    private static final int MAX_BLOCKS = 40;
    // A single poll can jump thousands of blocks during initial sync — cap how many
    // tiles we spawn per update so the train stays legible (throughput carries the rate).
    private static final int MAX_SPAWN_PER_UPDATE = 6;

    private final Deque<Block> recent = new ArrayDeque<>();
    private final List<Runnable> listeners = new ArrayList<>();

    private volatile LiveChainView latest = LiveChainView.unreachable();
    private long lastHeight = -1;
    private long lastUpdateNanos;
    private double blocksPerSec;

    /** Register a callback fired (on the caller's thread) after each {@link #update}. */
    public void addListener(Runnable listener) {
        listeners.add(listener);
    }

    public LiveChainView latest() {
        return latest;
    }

    public double blocksPerSec() {
        return blocksPerSec;
    }

    /** Snapshot of the recent blocks, oldest first. */
    public List<Block> recentBlocks() {
        return new ArrayList<>(recent);
    }

    /** Fold a fresh tip snapshot into the model and notify listeners. */
    public void update(LiveChainView view, long nowNanos) {
        this.latest = view;
        if (!view.reachable()) {
            blocksPerSec = 0;
            lastUpdateNanos = nowNanos;
            notifyListeners();
            return;
        }
        long height = view.blockHeight();
        if (lastHeight < 0) {
            // First sighting: seed one tile so something paints immediately, but
            // don't fabricate a burst (we can't know how many blocks are "new").
            if (height > 0) {
                addBlock(height, view, nowNanos);
            }
            lastHeight = Math.max(0, height);
            lastUpdateNanos = nowNanos;
            notifyListeners();
            return;
        }
        // Past the first-sight branch, lastUpdateNanos is always a real captured
        // time, so gate on elapsed seconds (System.nanoTime() may be 0 / negative).
        double seconds = (nowNanos - lastUpdateNanos) / 1e9;
        if (height > lastHeight) {
            long delta = height - lastHeight;
            if (seconds > 0) {
                double instant = delta / seconds;
                blocksPerSec = blocksPerSec == 0 ? instant : blocksPerSec * 0.6 + instant * 0.4;
            }
            long spawn = Math.min(delta, MAX_SPAWN_PER_UPDATE);
            for (long i = spawn - 1; i >= 0; i--) {
                addBlock(height - i, view, nowNanos);
            }
        } else if (seconds > 10) {
            // No new block for a while — let the rate decay toward zero.
            blocksPerSec *= 0.5;
        }
        lastHeight = height;
        lastUpdateNanos = nowNanos;
        notifyListeners();
    }

    private void addBlock(long height, LiveChainView view, long nowNanos) {
        recent.addLast(new Block(height, view.txCount(), view.blockSizeBytes(), nowNanos));
        while (recent.size() > MAX_BLOCKS) {
            recent.removeFirst();
        }
    }

    /** Clear all animation state (e.g. on reconnect / relock). */
    public void reset() {
        recent.clear();
        lastHeight = -1;
        lastUpdateNanos = 0;
        blocksPerSec = 0;
        latest = LiveChainView.unreachable();
    }

    private void notifyListeners() {
        for (Runnable listener : listeners) {
            listener.run();
        }
    }
}
