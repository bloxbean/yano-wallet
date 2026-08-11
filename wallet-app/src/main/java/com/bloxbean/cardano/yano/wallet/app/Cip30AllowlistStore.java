package com.bloxbean.cardano.yano.wallet.app;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The persisted set of dApp origins the user has connected (ADR-035). One origin
 * per line in {@code <data-dir>/cip30-allowlist.json}. Small and append-mostly;
 * a plain text file avoids a serialization dependency.
 */
final class Cip30AllowlistStore {

    private static final Logger log = LoggerFactory.getLogger(Cip30AllowlistStore.class);

    private final Path file;
    private final Set<String> origins = ConcurrentHashMap.newKeySet();

    Cip30AllowlistStore(Path dataDir) {
        this.file = dataDir.resolve("cip30-allowlist.json");
        load();
    }

    boolean isAllowed(String origin) {
        return origin != null && origins.contains(origin);
    }

    void allow(String origin) {
        if (origin != null && !origin.isBlank() && origins.add(origin)) {
            save();
        }
    }

    void revoke(String origin) {
        if (origins.remove(origin)) {
            save();
        }
    }

    Set<String> all() {
        return Set.copyOf(origins);
    }

    private void load() {
        if (!Files.isRegularFile(file)) {
            return;
        }
        try {
            Files.readAllLines(file).stream()
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .forEach(origins::add);
        } catch (IOException e) {
            log.warn("Could not read CIP-30 allowlist {}: {}", file, e.getMessage());
        }
    }

    private void save() {
        try {
            Files.createDirectories(file.getParent());
            Files.write(file, origins);
        } catch (IOException e) {
            log.warn("Could not save CIP-30 allowlist {}: {}", file, e.getMessage());
        }
    }
}
