package com.bloxbean.cardano.yano.wallet.nodeclient;

import com.bloxbean.cardano.yano.wallet.core.service.HistoryPort.HistoryNotSupportedException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/** Streams bounded NDJSON records; disconnect/EOF is never equivalent to done. */
final class WalletScanTransport {
    private static final ScheduledExecutorService DEADLINES = Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().daemon().name("wallet-scan-deadline").factory());
    private static final int MAX_RECORD_CHARS = 2_000_000;

    static final class Reorg extends NodeClientException {
        Reorg(String message) { super(message); }
    }

    private WalletScanTransport() { }

    static void scan(HttpClient client, ObjectMapper mapper, URI uri, JsonNode payload, Consumer<JsonNode> sink) {
        try {
            HttpRequest request = HttpRequest.newBuilder(uri).timeout(Duration.ofMinutes(30))
                    .header("Content-Type", "application/json").header("Accept", "application/x-ndjson")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(mapper.writeValueAsBytes(payload))).build();
            HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream body = response.body()) {
                if (response.statusCode() == 404) throw new HistoryNotSupportedException("This node does not support wallet scans");
                if (response.statusCode() == 409) throw new Reorg("Saved scan cursor is no longer canonical");
                if (response.statusCode() != 200) throw new NodeClientException("Wallet scan unavailable (HTTP " + response.statusCode() + ")");
                var deadline = DEADLINES.schedule(() -> {
                    try { body.close(); } catch (IOException ignored) { }
                }, 30, TimeUnit.MINUTES);
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(body, StandardCharsets.UTF_8))) {
                    boolean done = false;
                    StringBuilder line = new StringBuilder();
                    int character;
                    while ((character = reader.read()) != -1) {
                        if (Thread.currentThread().isInterrupted()) throw new NodeClientException("Wallet scan cancelled");
                        if (character != '\n') {
                            if (line.length() >= MAX_RECORD_CHARS) throw new NodeClientException("Scan record exceeds size limit");
                            line.append((char) character);
                            continue;
                        }
                        if (line.isEmpty()) continue;
                        if (done) throw new NodeClientException("Scan data received after completion");
                        JsonNode record = mapper.readTree(line.toString());
                        line.setLength(0);
                        String type = record.path("type").asText();
                        if (type.equals("rollback")) throw new Reorg("Chain rolled back during wallet scan");
                        if (type.equals("error")) throw new NodeClientException("Wallet scan failed: " + record.path("error").asText());
                        sink.accept(record);
                        done = type.equals("done");
                    }
                    if (!line.isEmpty() || !done) throw new NodeClientException("Wallet scan ended without a complete done record");
                } finally {
                    deadline.cancel(false);
                }
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new NodeClientException("Wallet scan interrupted", interrupted);
        } catch (IOException failure) {
            throw new NodeClientException("Wallet scan connection failed", failure);
        }
    }
}
