package com.bloxbean.cardano.yano.wallet.launcher;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * The progress line is the only thing the wallet can show during a long node
 * start, so the parse must survive the real log's shape — and, more importantly,
 * must degrade to "booting" rather than throwing when it does not recognise a
 * line. The samples here are copied from an actual
 * {@code ~/.yano-wallet/preview/node/node.log}.
 */
class NodeStartupProgressTest {

    private static final String RECONCILE_LINE =
            "2026-08-13 20:44:15,643 INFO  [com.blo.car.yan.run.int.RuntimeNode] (main) "
                    + "Account history reconcile progress: block 252000/4567221";

    @Test
    void readsAccountHistoryPosition() {
        NodeStartupProgress progress = NodeStartupProgress.parse(List.of(RECONCILE_LINE));

        assertThat(progress.phase()).isEqualTo(NodeStartupProgress.Phase.RECONCILING_ACCOUNT_HISTORY);
        assertThat(progress.current()).isEqualTo(252_000);
        assertThat(progress.total()).isEqualTo(4_567_221);
        assertThat(progress.determinate()).isTrue();
        assertThat(progress.fraction()).isCloseTo(0.0551, within(0.001));
    }

    @Test
    void newestLineWins() {
        NodeStartupProgress progress = NodeStartupProgress.parse(List.of(
                RECONCILE_LINE,
                RECONCILE_LINE.replace("252000", "453000")));

        assertThat(progress.current()).isEqualTo(453_000);
    }

    @Test
    void listeningBeatsAnEarlierReconcile() {
        // Ordering matters: once HTTP is bound the reconcile lines above it are
        // history, and showing 10% next to a node that is about to answer would
        // be a lie.
        NodeStartupProgress progress = NodeStartupProgress.parse(List.of(
                RECONCILE_LINE,
                "2026-08-13 20:48:02,001 INFO  [io.quarkus] (main) Listening on: http://0.0.0.0:50859"));

        assertThat(progress.phase()).isEqualTo(NodeStartupProgress.Phase.LISTENING);
        assertThat(progress.determinate()).isFalse();
    }

    @Test
    void unrecognisedOrEmptyLogIsBootingNotAFailure() {
        assertThat(NodeStartupProgress.parse(List.of()).phase())
                .isEqualTo(NodeStartupProgress.Phase.BOOTING);
        assertThat(NodeStartupProgress.parse(null).phase())
                .isEqualTo(NodeStartupProgress.Phase.BOOTING);
        assertThat(NodeStartupProgress.parse(List.of("SLF4J: Class path contains multiple providers")).phase())
                .isEqualTo(NodeStartupProgress.Phase.BOOTING);
        // The node's UTxO reconcile only logs on completion, so it stays BOOTING
        // rather than becoming a phase the log cannot actually support.
        assertThat(NodeStartupProgress.parse(List.of(
                "2026-08-13 20:39:01,120 INFO  (main) UTXO reconciliation complete at startup")).phase())
                .isEqualTo(NodeStartupProgress.Phase.BOOTING);
    }

    @Test
    void halfWrittenLineDoesNotThrow() {
        // tailLines reads a log the node is actively writing, so the last line is
        // routinely truncated mid-number.
        NodeStartupProgress progress = NodeStartupProgress.parse(List.of(
                "2026-08-13 20:44:15,643 INFO  (main) Account history reconcile progress: block 2520"));

        assertThat(progress.phase()).isEqualTo(NodeStartupProgress.Phase.RECONCILING_ACCOUNT_HISTORY);
        assertThat(progress.determinate()).isFalse();
    }

    @Test
    void fractionIsClampedAndNeverExceedsOne() {
        NodeStartupProgress overshoot = new NodeStartupProgress(
                NodeStartupProgress.Phase.RECONCILING_ACCOUNT_HISTORY, "x", 5_000_000, 4_567_221);

        assertThat(overshoot.fraction()).isEqualTo(1.0);
    }
}
