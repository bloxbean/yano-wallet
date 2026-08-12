package com.bloxbean.cardano.yano.wallet.app;

import com.bloxbean.cardano.yano.wallet.ui.contract.WalletUiController.UpstreamRelaysView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The relay settings as the UI sees them (E18) — the controller layer above
 * {@link RelaySettingsStore}, which is where a typed list becomes relays.
 */
class UpstreamRelaySettingsTest {

    @TempDir
    Path dataDir;

    private DefaultWalletUiController controller;

    @BeforeEach
    void setUp() {
        controller = new DefaultWalletUiController(new WalletBackendManager(dataDir));
    }

    @Test
    void aFreshWalletShowsTheShippedRelaysAndNoOverride() {
        UpstreamRelaysView view = controller.upstreamRelays("preprod");

        assertThat(view.editable()).isTrue();
        assertThat(view.custom()).isEmpty();
        assertThat(view.shipped()).hasSizeGreaterThan(1);
        assertThat(view.configured())
                .as("what the node will actually launch with")
                .isEqualTo(view.shipped());
    }

    @Test
    void savedRelaysLeadTheShippedOnes() {
        controller.saveUpstreamRelays("preprod", List.of("mine.example.com:3001"), false);

        UpstreamRelaysView view = controller.upstreamRelays("preprod");

        assertThat(view.custom()).containsExactly("mine.example.com:3001");
        assertThat(view.configured()).first().isEqualTo("mine.example.com:3001");
        assertThat(view.configured()).containsAll(view.shipped());
    }

    @Test
    void oneBadEntrySavesNothingAtAll() {
        // The load-bearing case. Saving the parseable lines and dropping the rest
        // would leave the node launching from a list the user never approved — and
        // they would have no way to tell, since the box would redisplay as saved.
        controller.saveUpstreamRelays("preprod", List.of("good.example.com:3001"), false);

        assertThatThrownBy(() -> controller.saveUpstreamRelays("preprod",
                List.of("better.example.com:3001", "this is not a relay"), false))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(controller.upstreamRelays("preprod").custom())
                .as("the previous configuration must survive a rejected edit")
                .containsExactly("good.example.com:3001");
    }

    @Test
    void blankLinesAreIgnoredRatherThanRejected() {
        // People type trailing newlines into a text area; that is not an error.
        controller.saveUpstreamRelays("preprod",
                List.of("mine.example.com:3001", "   ", ""), false);

        assertThat(controller.upstreamRelays("preprod").custom())
                .containsExactly("mine.example.com:3001");
    }

    @Test
    void savingAnEmptyListRestoresTheShippedRelays() {
        controller.saveUpstreamRelays("preprod", List.of("mine.example.com:3001"), true);

        String message = controller.saveUpstreamRelays("preprod", List.of(), false);

        assertThat(message).contains("built-in");
        UpstreamRelaysView view = controller.upstreamRelays("preprod");
        assertThat(view.custom()).isEmpty();
        assertThat(view.configured()).isEqualTo(view.shipped());
    }

    @Test
    void onlyCustomExcludesTheShippedRelaysEntirely() {
        controller.saveUpstreamRelays("preprod", List.of("mine.example.com:3001"), true);

        UpstreamRelaysView view = controller.upstreamRelays("preprod");

        assertThat(view.onlyCustom()).isTrue();
        assertThat(view.configured()).containsExactly("mine.example.com:3001");
    }

    @Test
    void everySaveSaysItNeedsARestart() {
        // These are launch arguments of a running child process. A settings screen
        // that looks like it applied immediately would have the user watching a
        // sync that is still using the old relay.
        assertThat(controller.saveUpstreamRelays("preprod", List.of("mine.example.com:3001"), false))
                .containsIgnoringCase("restart");
        assertThat(controller.saveUpstreamRelays("preprod", List.of(), false))
                .containsIgnoringCase("restart");
    }

    @Test
    void networksWithNoManagedNodeOfferNoRelayEditor() {
        // A control that cannot affect anything is worse than no control.
        assertThat(controller.upstreamRelays("yaci-devkit").editable()).isFalse();
        assertThat(controller.upstreamRelays("devnet").editable()).isFalse();
    }

    @Test
    void overridesDoNotLeakBetweenNetworks() {
        controller.saveUpstreamRelays("preprod", List.of("mine.example.com:3001"), true);

        assertThat(controller.upstreamRelays("mainnet").custom()).isEmpty();
        assertThat(controller.upstreamRelays("mainnet").configured())
                .isEqualTo(controller.upstreamRelays("mainnet").shipped());
    }
}
