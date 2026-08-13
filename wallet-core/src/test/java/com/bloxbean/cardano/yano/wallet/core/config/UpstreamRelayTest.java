package com.bloxbean.cardano.yano.wallet.core.config;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Relay validation (E18). These values become {@code -Dyano.upstream.peers[i].host}
 * arguments on the node's command line and are editable by the user, so the
 * question each case answers is: can a typed value change which property the node
 * reads, or leave it reading none?
 */
class UpstreamRelayTest {

    @Test
    void acceptsHostnamesAndAddresses() {
        assertThat(new UpstreamRelay("backbone.cardano.iog.io", 3001).host())
                .isEqualTo("backbone.cardano.iog.io");
        assertThat(new UpstreamRelay("192.0.2.10", 3001).host()).isEqualTo("192.0.2.10");
        assertThat(new UpstreamRelay("[2001:db8::1]", 3001).host()).isEqualTo("[2001:db8::1]");
        assertThat(new UpstreamRelay("  Relay.Example.COM  ", 3001).host())
                .as("trimmed and lower-cased so two spellings are one relay")
                .isEqualTo("relay.example.com");
    }

    @Test
    void rejectsAnythingThatCouldBeReadAsADifferentProperty() {
        // Each of these, passed through, would either be parsed as another key or
        // silently drop the peer — leaving a wallet that believes it has failover
        // and does not.
        List.of("", "   ", "relay .example.com", "relay=example.com",
                "relay\nyano.upstream.mode=static-multi", "relay\texample.com",
                "-relay.example.com", "relay.example.com-", ".relay.example.com",
                "relay.example.com.", "relay/../example", "häst.example.com")
                .forEach(host -> assertThatThrownBy(() -> new UpstreamRelay(host, 3001))
                        .as("host: %s", host)
                        .isInstanceOf(IllegalArgumentException.class));
    }

    @Test
    void rejectsPortsOutsideTheValidRange() {
        List.of(0, -1, 65536, 1_000_000).forEach(port ->
                assertThatThrownBy(() -> new UpstreamRelay("relay.example.com", port))
                        .as("port: %s", port)
                        .isInstanceOf(IllegalArgumentException.class));
    }

    @Test
    void parsesTheHostColonPortFormShownInSettings() {
        assertThat(UpstreamRelay.parse("relay.example.com:3001"))
                .isEqualTo(new UpstreamRelay("relay.example.com", 3001));
        assertThat(UpstreamRelay.parse("  relay.example.com:3001 "))
                .isEqualTo(new UpstreamRelay("relay.example.com", 3001));
        assertThat(UpstreamRelay.parse("[2001:db8::1]:3001").port()).isEqualTo(3001);
    }

    @Test
    void refusesMalformedInputRatherThanGuessingAPort() {
        // A relay with a guessed port is worse than a rejected one: it looks
        // configured and never connects.
        List.of("relay.example.com", "relay.example.com:", ":3001", "",
                "relay.example.com:notaport", "relay.example.com:0")
                .forEach(value -> assertThatThrownBy(() -> UpstreamRelay.parse(value))
                        .as("value: %s", value)
                        .isInstanceOf(IllegalArgumentException.class));
    }

    @Test
    void rendersAsHostColonPortForDisplayAndRoundTrips() {
        UpstreamRelay relay = new UpstreamRelay("relay.example.com", 3001);
        assertThat(relay).hasToString("relay.example.com:3001");
        assertThat(UpstreamRelay.parse(relay.toString())).isEqualTo(relay);
    }
}
