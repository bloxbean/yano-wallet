package com.bloxbean.cardano.yano.wallet.ui.screens;

import com.bloxbean.cardano.yano.wallet.ui.contract.WalletUiController.HistoryScanView;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class HistoryMessagesTest {
    @Test void anEmptyListSaysWhichKindOfEmptyItIs() {
        assertThat(HistoryScreen.emptyMessage(false)).isEqualTo("No transactions yet");
        // Local-only means the node indexes no transactions at all, so an empty
        // list is not "this wallet has done nothing" — it is "nothing can be read
        // from here", which is a different thing for the user to act on.
        assertThat(HistoryScreen.emptyMessage(true))
                .contains("History not found")
                .contains("no transaction index");
    }

    @Test void progressNamesWhereTheScanHasReached() {
        String message = HistoryScreen.scanMessage(new HistoryScanView(11_184_128L, 13_973_520L, 80));

        // Grouped digits: a raw 11184128 next to 13973520 is unreadable at a glance.
        assertThat(message).isEqualTo(
                "Scanning the chain for your transactions — block 11,184,128 of 13,973,520 · 80%");
    }

    @Test void idleIsNotAScan() {
        assertThat(HistoryScanView.idle().scanning()).isFalse();
        // Percent 0 is a scan that has just started, not the absence of one.
        assertThat(new HistoryScanView(0, 13_973_520L, 0).scanning()).isTrue();
        // A node that reported no tip cannot place the scan on a scale.
        assertThat(new HistoryScanView(500, 0, 50).scanning()).isFalse();
    }
}
