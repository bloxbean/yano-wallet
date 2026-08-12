package com.bloxbean.cardano.yano.wallet.launcher;

import com.bloxbean.cardano.yano.wallet.core.config.UpstreamRelay;
import com.bloxbean.cardano.yano.wallet.core.config.WalletNetwork;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The launch flags that give the managed node somewhere to fail over to (E18).
 *
 * <p>These property names are a contract with the node's config keys, written as
 * strings and read through MicroProfile Config — which ignores a key it does not
 * recognise. A typo would therefore produce a node that starts perfectly well on
 * a single upstream, with the failover silently absent. That is exactly the state
 * the wallet was in on 2026-08-13 when a mainnet sync stalled with nowhere to go,
 * so the spelling is worth pinning rather than trusting.
 */
class UpstreamRelayLaunchFlagsTest {

    private static NodeLaunchSpec spec(WalletNetwork network, List<UpstreamRelay> relays) {
        return new NodeLaunchSpec(network, Path.of("app/build/yano.jar"), false, Path.of("app"),
                8090, 13400, Path.of("cs"), Path.of("node.log"), "java", relays);
    }

    private static List<String> flagsFor(WalletNetwork network, List<UpstreamRelay> relays) {
        List<String> command = new ArrayList<>();
        new ManagedNode(spec(network, relays)).addUpstreamRelays(command);
        return command;
    }

    @Test
    void everyPublicNetworkShipsMoreThanOneRelay() {
        // One relay is not failover. If this ever drops to a single entry the
        // feature is gone while still appearing configured.
        for (WalletNetwork network : List.of(WalletNetwork.MAINNET, WalletNetwork.PREPROD,
                WalletNetwork.PREVIEW)) {
            assertThat(network.defaultRelays())
                    .as("%s relays", network.id())
                    .hasSizeGreaterThan(1);
            assertThat(network.defaultRelays().stream().map(UpstreamRelay::host).distinct().count())
                    .as("%s relays must be distinct hosts — two names for one machine is not redundancy",
                            network.id())
                    .isEqualTo(network.defaultRelays().size());
        }
    }

    @Test
    void theTwoDevnetsGetNoRelayFlagsAtAll() {
        // A Yano devnet syncs from whatever the user runs locally and a Yaci DevKit
        // has no managed node; a public relay would be wrong for both.
        assertThat(flagsFor(WalletNetwork.DEVNET, List.of())).isEmpty();
        assertThat(flagsFor(WalletNetwork.YACI_DEVKIT, List.of())).isEmpty();
    }

    @Test
    void failoverModeIsRequestedWithBulkSyncStillOnOnePeer() {
        List<String> flags = flagsFor(WalletNetwork.PREPROD, List.of());

        assertThat(flags).contains("-Dyano.upstream.mode=trusted-failover");
        assertThat(flags)
                .as("resilience, not parallel download — bulk stays single-sourced")
                .contains("-Dyano.upstream.sync.bulk-source=single-trusted");
        assertThat(flags)
                .as("discovery is a separate, opt-in behaviour change and must not ride along")
                .noneMatch(flag -> flag.contains("discovery"));
    }

    @Test
    void relaysAreEmittedInOrderWithAscendingPriority() {
        List<String> flags = flagsFor(WalletNetwork.MAINNET, List.of(
                new UpstreamRelay("mine.example.com", 3001),
                new UpstreamRelay("fallback.example.com", 4001)));

        assertThat(flags).contains(
                "-Dyano.upstream.peers[0].host=mine.example.com",
                "-Dyano.upstream.peers[0].port=3001",
                "-Dyano.upstream.peers[0].priority=0",
                "-Dyano.upstream.peers[0].trust=trusted",
                "-Dyano.upstream.peers[1].host=fallback.example.com",
                "-Dyano.upstream.peers[1].port=4001",
                "-Dyano.upstream.peers[1].priority=1");
    }

    @Test
    void anEmptyRelayListFallsBackToTheNetworkDefaults() {
        // "No preference" must never become "no upstream" — a node launched with
        // zero relays cannot sync at all, which is worse than any relay we might
        // have chosen for the user.
        assertThat(spec(WalletNetwork.MAINNET, List.of()).relays())
                .isEqualTo(WalletNetwork.MAINNET.defaultRelays());
        assertThat(spec(WalletNetwork.MAINNET, null).relays())
                .isEqualTo(WalletNetwork.MAINNET.defaultRelays());
        assertThat(flagsFor(WalletNetwork.MAINNET, List.of()))
                .contains("-Dyano.upstream.peers[0].host=backbone.cardano.iog.io");
    }

    @Test
    void anExplicitListIsUsedVerbatimRatherThanAppendedToTheDefaults() {
        // The caller decides. Appending defaults behind a user's own relay is a
        // policy for the settings layer to apply deliberately, not something the
        // launcher should do behind its back — someone running their own relay for
        // privacy must not silently also dial public ones.
        List<UpstreamRelay> only = List.of(new UpstreamRelay("mine.example.com", 3001));

        assertThat(spec(WalletNetwork.MAINNET, only).relays()).isEqualTo(only);
        assertThat(flagsFor(WalletNetwork.MAINNET, only))
                .noneMatch(flag -> flag.contains("backbone.cardano.iog.io"));
    }

    @Test
    void theNetworkMagicIsNeverAmongTheFlags() {
        // Host and port are user-editable; the chain is not. A relay for the wrong
        // network must fail the node's handshake, never sync a foreign chain into
        // this network's chainstate.
        assertThat(flagsFor(WalletNetwork.MAINNET, List.of()))
                .noneMatch(flag -> flag.contains("protocol-magic") || flag.contains("protocolMagic"));
    }
}
