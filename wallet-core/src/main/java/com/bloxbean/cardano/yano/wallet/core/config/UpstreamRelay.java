package com.bloxbean.cardano.yano.wallet.core.config;

import java.util.Locale;
import java.util.Objects;

/**
 * A Cardano node-to-node relay the managed node may sync from (ADR-033 A3).
 *
 * <p>A wallet runs with more than one so that a relay which stops delivering is
 * not the end of the sync: Yano already asks for a new peer on {@code NO_PROGRESS}
 * and {@code BODY_FETCH_STUCK}, but with a single upstream configured there is
 * nowhere for it to go. That is not hypothetical — on 2026-08-13 a mainnet sync
 * sat at 0% CPU for tens of seconds at a time waiting on its only relay, then ran
 * at 5,165 blocks/s against the same host later the same hour.
 *
 * <p>Deliberately carries no protocol magic. The network's magic is fixed by
 * {@link WalletNetwork} and never editable, so a relay for the wrong chain fails
 * the node's handshake rather than syncing a foreign chain into this network's
 * chainstate.
 */
public record UpstreamRelay(String host, int port) {

    private static final int MAX_HOST_LENGTH = 253;

    public UpstreamRelay {
        Objects.requireNonNull(host, "host is required");
        host = host.strip().toLowerCase(Locale.ROOT);
        if (host.isEmpty()) {
            throw new IllegalArgumentException("host must not be blank");
        }
        if (host.length() > MAX_HOST_LENGTH) {
            throw new IllegalArgumentException("host is too long: " + host.length() + " characters");
        }
        // These reach the node as -Dyano.upstream.peers[i].host=… arguments. There
        // is no shell in between (ProcessBuilder passes argv directly), so this is
        // not about quoting — it is that a value containing '=', whitespace or a
        // newline would be read back as a different property, or as none at all.
        // Hosts are user-editable in settings, so reject rather than sanitise.
        if (!isPlausibleHost(host)) {
            throw new IllegalArgumentException("not a hostname or IP address: " + host);
        }
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("port out of range: " + port);
        }
    }

    /**
     * Hostname, IPv4, or bracketed IPv6. Intentionally narrow: this decides what
     * may be handed to the node as a launch argument, so anything not obviously a
     * host is refused.
     */
    private static boolean isPlausibleHost(String host) {
        if (host.startsWith("[")) {
            return host.endsWith("]") && host.length() > 2
                    && host.substring(1, host.length() - 1).chars()
                    .allMatch(c -> isHexDigit(c) || c == ':' || c == '.');
        }
        boolean sawNonDigit = false;
        for (int i = 0; i < host.length(); i++) {
            char c = host.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '.' || c == '-';
            if (!ok) {
                return false;
            }
            if (c != '.' && !(c >= '0' && c <= '9')) {
                sawNonDigit = true;
            }
        }
        // A leading or trailing dot/hyphen is not a host; neither is "1.2.3.4.5".
        char first = host.charAt(0);
        char last = host.charAt(host.length() - 1);
        if (first == '.' || first == '-' || last == '.' || last == '-') {
            return false;
        }
        return sawNonDigit || host.chars().filter(c -> c == '.').count() == 3;
    }

    private static boolean isHexDigit(int c) {
        return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f');
    }

    @Override
    public String toString() {
        return host + ":" + port;
    }

    /**
     * Parses {@code host:port}, the form shown and edited in settings.
     *
     * @throws IllegalArgumentException if it is not a well-formed relay
     */
    public static UpstreamRelay parse(String value) {
        String trimmed = value == null ? "" : value.strip();
        int colon = trimmed.lastIndexOf(':');
        if (colon <= 0 || colon == trimmed.length() - 1) {
            throw new IllegalArgumentException("expected host:port, got: " + value);
        }
        String port = trimmed.substring(colon + 1);
        try {
            return new UpstreamRelay(trimmed.substring(0, colon), Integer.parseInt(port));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("not a port number: " + port);
        }
    }
}
