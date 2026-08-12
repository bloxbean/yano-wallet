package com.bloxbean.cardano.yano.wallet.app;

import com.bloxbean.cardano.yano.wallet.core.config.UpstreamRelay;
import com.bloxbean.cardano.yano.wallet.core.config.WalletNetwork;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Per-network upstream relay overrides (E18), persisted in
 * {@code <dataDir>/relays.json}.
 *
 * <p>This is the wallet's answer to two problems the shipped defaults cannot
 * solve. The first is rot: no hostname compiled into a desktop application lives
 * forever, and when the last default dies a user must be able to point the wallet
 * somewhere that works without waiting for a release. The second is a relay that
 * is alive but slow — Yano's supervisor judges liveness, not throughput, so its
 * failover will not move off a peer that answers keep-alives while delivering
 * almost nothing (measured 2026-08-13: ~48s stalls against a 5-minute stuck
 * threshold). Switching relays by hand is the remedy for that, by design.
 *
 * <p>Custom relays come first and the shipped defaults are appended behind them,
 * so a typo costs a failover hop rather than a wallet that cannot sync — unless
 * {@code onlyCustom} is set, which exists because someone running their own relay
 * for privacy must not silently also dial public ones.
 */
final class RelaySettingsStore {

    private static final Logger log = LoggerFactory.getLogger(RelaySettingsStore.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Path file;
    private volatile Map<String, NetworkRelays> byNetwork;

    RelaySettingsStore(Path dataDir) {
        this.file = dataDir.resolve("relays.json");
        this.byNetwork = read();
    }

    /** A network's override: the relays the user typed, and whether to use only those. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record NetworkRelays(List<String> relays, boolean onlyCustom) {
        NetworkRelays {
            relays = relays == null ? List.of() : List.copyOf(relays);
        }
    }

    /**
     * The relays to launch this network's node with: the user's, then the shipped
     * defaults as fallback. Never empty for a network that has defaults — a node
     * with no upstream cannot sync at all, which is worse than any relay we could
     * have chosen.
     *
     * <p>Entries that no longer parse are dropped with a warning rather than
     * failing the launch. A settings file edited by hand should cost the bad line,
     * not the ability to start.
     */
    List<UpstreamRelay> relaysFor(WalletNetwork network) {
        NetworkRelays override = byNetwork.get(network.id());
        List<UpstreamRelay> custom = parse(override, network);
        if (custom.isEmpty()) {
            return network.defaultRelays();
        }
        if (override.onlyCustom()) {
            return custom;
        }
        List<UpstreamRelay> combined = new ArrayList<>(custom);
        for (UpstreamRelay fallback : network.defaultRelays()) {
            if (!combined.contains(fallback)) {
                combined.add(fallback);
            }
        }
        return List.copyOf(combined);
    }

    /** What the user typed for this network, empty when they have not overridden it. */
    List<UpstreamRelay> customRelaysFor(WalletNetwork network) {
        return parse(byNetwork.get(network.id()), network);
    }

    boolean isOnlyCustom(WalletNetwork network) {
        NetworkRelays override = byNetwork.get(network.id());
        return override != null && override.onlyCustom();
    }

    boolean hasOverride(WalletNetwork network) {
        return !customRelaysFor(network).isEmpty();
    }

    /**
     * Records an override. An empty list clears it, which is how the user gets the
     * shipped defaults back — deliberately the same operation as "remove", so there
     * is no way to end up with an override that is present but empty.
     */
    void save(WalletNetwork network, List<UpstreamRelay> relays, boolean onlyCustom) {
        Map<String, NetworkRelays> updated = new LinkedHashMap<>(byNetwork);
        if (relays == null || relays.isEmpty()) {
            updated.remove(network.id());
        } else {
            updated.put(network.id(), new NetworkRelays(
                    relays.stream().map(UpstreamRelay::toString).toList(), onlyCustom));
        }
        write(updated);
        this.byNetwork = Map.copyOf(updated);
    }

    void clear(WalletNetwork network) {
        save(network, List.of(), false);
    }

    private static List<UpstreamRelay> parse(NetworkRelays override, WalletNetwork network) {
        if (override == null) {
            return List.of();
        }
        List<UpstreamRelay> parsed = new ArrayList<>();
        for (String entry : override.relays()) {
            try {
                parsed.add(UpstreamRelay.parse(entry));
            } catch (IllegalArgumentException e) {
                log.warn("Ignoring unusable relay '{}' for {}: {}", entry, network.id(), e.getMessage());
            }
        }
        return List.copyOf(parsed);
    }

    private void write(Map<String, NetworkRelays> updated) {
        try {
            Files.createDirectories(file.getParent());
            MAPPER.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), updated);
        } catch (IOException e) {
            // Not persisting is survivable — the choice is simply forgotten next
            // launch — but the user must not be told it was saved when it was not.
            log.warn("Could not save relay settings to {}: {}", file, e.getMessage());
        }
    }

    private Map<String, NetworkRelays> read() {
        if (!Files.exists(file)) {
            return Map.of();
        }
        try {
            Map<String, NetworkRelays> loaded =
                    MAPPER.readValue(file.toFile(), new TypeReference<>() {
                    });
            return loaded == null ? Map.of() : Map.copyOf(loaded);
        } catch (IOException | RuntimeException e) {
            // An unreadable file falls back to the shipped defaults rather than to
            // nothing: the failure mode of a corrupt settings file must be a wallet
            // that syncs from the standard relays, never one that cannot sync.
            log.warn("Unreadable relay settings {} — using the built-in relays: {}",
                    file, e.getMessage());
            return Map.of();
        }
    }
}
