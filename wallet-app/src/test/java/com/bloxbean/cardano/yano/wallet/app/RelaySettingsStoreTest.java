package com.bloxbean.cardano.yano.wallet.app;

import com.bloxbean.cardano.yano.wallet.core.config.UpstreamRelay;
import com.bloxbean.cardano.yano.wallet.core.config.WalletNetwork;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Per-network relay overrides (E18).
 *
 * <p>Every case here is really the same question: can a user, or a damaged file,
 * end up with a wallet that has no upstream at all? A node launched with nothing
 * to sync from is a worse outcome than any relay this store might have picked, so
 * the answer has to be no on every path.
 */
class RelaySettingsStoreTest {

    @TempDir
    Path dataDir;

    private static final UpstreamRelay MINE = new UpstreamRelay("mine.example.com", 3001);
    private static final UpstreamRelay ALSO_MINE = new UpstreamRelay("other.example.com", 4001);

    @Test
    void withNoOverrideTheNetworkDefaultsAreUsed() {
        RelaySettingsStore store = new RelaySettingsStore(dataDir);

        assertThat(store.relaysFor(WalletNetwork.PREPROD))
                .isEqualTo(WalletNetwork.PREPROD.defaultRelays());
        assertThat(store.hasOverride(WalletNetwork.PREPROD)).isFalse();
    }

    @Test
    void customRelaysComeFirstWithTheDefaultsBehindThem() {
        // A typo should cost a failover hop, not the ability to sync.
        RelaySettingsStore store = new RelaySettingsStore(dataDir);
        store.save(WalletNetwork.PREPROD, List.of(MINE), false);

        List<UpstreamRelay> resolved = store.relaysFor(WalletNetwork.PREPROD);

        assertThat(resolved).first().isEqualTo(MINE);
        assertThat(resolved).containsAll(WalletNetwork.PREPROD.defaultRelays());
    }

    @Test
    void onlyCustomKeepsThePublicRelaysOutEntirely() {
        // Someone running their own relay for privacy must not silently also dial
        // the shipped ones — that would leak the very thing they configured away.
        RelaySettingsStore store = new RelaySettingsStore(dataDir);
        store.save(WalletNetwork.PREPROD, List.of(MINE, ALSO_MINE), true);

        assertThat(store.relaysFor(WalletNetwork.PREPROD)).containsExactly(MINE, ALSO_MINE);
        assertThat(store.isOnlyCustom(WalletNetwork.PREPROD)).isTrue();
    }

    @Test
    void savingAnEmptyListRemovesTheOverrideAndRestoresTheDefaults() {
        RelaySettingsStore store = new RelaySettingsStore(dataDir);
        store.save(WalletNetwork.PREPROD, List.of(MINE), true);

        store.save(WalletNetwork.PREPROD, List.of(), true);

        assertThat(store.hasOverride(WalletNetwork.PREPROD)).isFalse();
        assertThat(store.relaysFor(WalletNetwork.PREPROD))
                .as("clearing must not leave onlyCustom stranded with nothing to use")
                .isEqualTo(WalletNetwork.PREPROD.defaultRelays());
    }

    @Test
    void overridesAreRememberedAcrossRestartsAndScopedPerNetwork() {
        new RelaySettingsStore(dataDir).save(WalletNetwork.PREPROD, List.of(MINE), true);

        RelaySettingsStore reopened = new RelaySettingsStore(dataDir);

        assertThat(reopened.relaysFor(WalletNetwork.PREPROD)).containsExactly(MINE);
        assertThat(reopened.relaysFor(WalletNetwork.MAINNET))
                .as("a preprod override must never reach mainnet")
                .isEqualTo(WalletNetwork.MAINNET.defaultRelays());
    }

    @Test
    void anUnreadableFileFallsBackToTheBuiltInRelays() throws IOException {
        Files.writeString(dataDir.resolve("relays.json"), "{ this is not json");

        RelaySettingsStore store = new RelaySettingsStore(dataDir);

        assertThat(store.relaysFor(WalletNetwork.MAINNET))
                .as("a corrupt settings file must never mean 'no upstream'")
                .isEqualTo(WalletNetwork.MAINNET.defaultRelays());
    }

    @Test
    void oneUnusableEntryIsDroppedWithoutLosingTheRest() throws IOException {
        // Hand-edited files happen. A bad line should cost that line.
        Files.writeString(dataDir.resolve("relays.json"), """
                {"preprod":{"relays":["not a relay","mine.example.com:3001"],"onlyCustom":true}}""");

        assertThat(new RelaySettingsStore(dataDir).relaysFor(WalletNetwork.PREPROD))
                .containsExactly(MINE);
    }

    @Test
    void anOverrideOfNothingButGarbageStillLeavesAWorkingWallet() throws IOException {
        // onlyCustom plus entries that all fail to parse is the one combination
        // that could produce an empty relay list. It must not.
        Files.writeString(dataDir.resolve("relays.json"), """
                {"preprod":{"relays":["nonsense","also nonsense"],"onlyCustom":true}}""");

        assertThat(new RelaySettingsStore(dataDir).relaysFor(WalletNetwork.PREPROD))
                .isEqualTo(WalletNetwork.PREPROD.defaultRelays());
    }

    @Test
    void aCustomRelayThatDuplicatesADefaultIsNotListedTwice() {
        RelaySettingsStore store = new RelaySettingsStore(dataDir);
        UpstreamRelay shipped = WalletNetwork.PREPROD.defaultRelays().get(1);
        store.save(WalletNetwork.PREPROD, List.of(shipped), false);

        List<UpstreamRelay> resolved = store.relaysFor(WalletNetwork.PREPROD);

        assertThat(resolved).doesNotHaveDuplicates();
        assertThat(resolved).first()
                .as("the user's ordering wins — that is what promoting a relay means")
                .isEqualTo(shipped);
    }

    @Test
    void aDevnetHasNoRelaysToFallBackToAndThatIsFine() {
        // Its upstream is whatever the user runs locally, decided by the node's own
        // profile; the launcher emits no relay flags at all for these.
        RelaySettingsStore store = new RelaySettingsStore(dataDir);

        assertThat(store.relaysFor(WalletNetwork.DEVNET)).isEmpty();
        assertThat(store.relaysFor(WalletNetwork.YACI_DEVKIT)).isEmpty();
    }
}
