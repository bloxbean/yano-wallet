package com.bloxbean.cardano.yano.wallet.ui.live;

import com.bloxbean.cardano.yano.wallet.ui.contract.WalletUiController.LiveChainView;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class LiveChainModelTest {

    private static LiveChainView tip(long height, int txCount) {
        return new LiveChainView(height, height, 42, 100, 432000, txCount, 1024, 5, "abcd…ef",
                0, 0, 0, 0, true, true);
    }

    private static long secs(double s) {
        return (long) (s * 1_000_000_000L);
    }

    @Test
    void seedsOneBlockOnFirstSightAndNotifies() {
        LiveChainModel model = new LiveChainModel();
        AtomicInteger notified = new AtomicInteger();
        model.addListener(notified::incrementAndGet);

        model.update(tip(100, 3), secs(0));

        assertThat(model.recentBlocks()).extracting(LiveChainModel.Block::height).containsExactly(100L);
        assertThat(notified.get()).isEqualTo(1);
    }

    @Test
    void appendsNewBlocksAsTheTipAdvances() {
        LiveChainModel model = new LiveChainModel();
        model.update(tip(100, 3), secs(0));
        model.update(tip(101, 7), secs(20));
        model.update(tip(102, 0), secs(40));

        assertThat(model.recentBlocks()).extracting(LiveChainModel.Block::height)
                .containsExactly(100L, 101L, 102L);
    }

    @Test
    void capsTilesSpawnedForAHugeSyncJump() {
        LiveChainModel model = new LiveChainModel();
        model.update(tip(0, 0), secs(0));       // fresh node, nothing seeded (height 0)
        model.update(tip(10_000, 2), secs(2));  // jumped 10k blocks in one poll

        // At most MAX_SPAWN_PER_UPDATE (6) tiles, ending at the real tip height.
        assertThat(model.recentBlocks()).hasSize(6);
        assertThat(model.recentBlocks().get(5).height()).isEqualTo(10_000L);
        assertThat(model.blocksPerSec()).isGreaterThan(0);
    }

    @Test
    void neverExceedsTheBlockCap() {
        LiveChainModel model = new LiveChainModel();
        for (int h = 1; h <= 200; h++) {
            model.update(tip(h, 1), secs(h));
        }
        assertThat(model.recentBlocks().size()).isLessThanOrEqualTo(40);
        // The newest tile is always the current tip.
        assertThat(model.recentBlocks().get(model.recentBlocks().size() - 1).height()).isEqualTo(200L);
    }

    @Test
    void unreachableClearsThroughputButKeepsLastView() {
        LiveChainModel model = new LiveChainModel();
        model.update(tip(100, 3), secs(0));
        model.update(tip(101, 3), secs(1));
        assertThat(model.blocksPerSec()).isGreaterThan(0);

        model.update(LiveChainView.unreachable(), secs(2));
        assertThat(model.blocksPerSec()).isZero();
        assertThat(model.latest().reachable()).isFalse();
    }
}
