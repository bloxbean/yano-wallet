package com.bloxbean.cardano.yano.wallet.app;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Which transport the CIP-30 dApp connector uses (ADR-035), persisted in
 * {@code <dataDir>/connector.json}.
 *
 * <p>Native Messaging is the default and the only one the wallet recommends: the
 * browser launches the connector itself and vouches for the extension's pinned
 * id, so the wallet knows which extension it is talking to. The localhost
 * WebSocket cannot do that — any local process can open it and claim to be a
 * dApp, because the origin it reports is self-asserted.
 *
 * <p>It exists anyway because Native Messaging can fail to install for reasons a
 * user cannot fix from inside the wallet (browser packaging, managed devices,
 * a missing manifest directory), and a wallet that cannot talk to dApps at all
 * is worse than one talking over a weaker transport the user knowingly chose.
 * "Knowingly" is the load-bearing word — see {@link #isWeakTransport()}, which
 * drives a warning on every launch rather than a one-time prompt.
 */
final class ConnectorSettingsStore {

    private static final Logger log = LoggerFactory.getLogger(ConnectorSettingsStore.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    static final String NATIVE_MESSAGING = "NATIVE_MESSAGING";
    static final String WEBSOCKET = "WEBSOCKET";

    private final Path file;
    private volatile Settings settings;

    ConnectorSettingsStore(Path dataDir) {
        this.file = dataDir.resolve("connector.json");
        this.settings = read();
    }

    /** @param wsPort the localhost port, used only when the transport is WEBSOCKET */
    record Settings(String transport, int wsPort) {
        Settings {
            transport = WEBSOCKET.equalsIgnoreCase(transport) ? WEBSOCKET : NATIVE_MESSAGING;
            // A port of 0 would bind something arbitrary the extension cannot find.
            wsPort = wsPort > 0 && wsPort <= 65535
                    ? wsPort
                    : com.bloxbean.cardano.yano.wallet.connector.Cip30BridgeServer.DEFAULT_PORT;
        }
    }

    Settings settings() {
        return settings;
    }

    boolean isWebSocket() {
        return WEBSOCKET.equals(settings.transport());
    }

    /** True when the wallet is reachable over a transport that cannot prove who is calling. */
    boolean isWeakTransport() {
        return isWebSocket();
    }

    Settings save(String transport, int wsPort) {
        Settings updated = new Settings(transport, wsPort);
        try {
            Files.createDirectories(file.getParent());
            MAPPER.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), updated);
        } catch (IOException e) {
            // Failing to persist must not leave the running wallet inconsistent
            // with what the user just chose; it only means the choice is not
            // remembered next launch, which the caller surfaces.
            log.warn("Could not save connector settings to {}: {}", file, e.getMessage());
        }
        this.settings = updated;
        return updated;
    }

    private Settings read() {
        if (!Files.exists(file)) {
            return new Settings(NATIVE_MESSAGING, com.bloxbean.cardano.yano.wallet.connector
                    .Cip30BridgeServer.DEFAULT_PORT);
        }
        try {
            return MAPPER.readValue(file.toFile(), Settings.class);
        } catch (IOException e) {
            // An unreadable file falls back to the SAFE transport rather than the
            // last one guessed at: defaulting to WebSocket on corruption would
            // silently weaken the wallet.
            log.warn("Unreadable connector settings {} — falling back to Native Messaging: {}",
                    file, e.getMessage());
            return new Settings(NATIVE_MESSAGING, com.bloxbean.cardano.yano.wallet.connector
                    .Cip30BridgeServer.DEFAULT_PORT);
        }
    }
}
