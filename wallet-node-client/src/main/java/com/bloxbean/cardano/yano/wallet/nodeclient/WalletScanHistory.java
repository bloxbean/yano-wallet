package com.bloxbean.cardano.yano.wallet.nodeclient;

import com.bloxbean.cardano.client.address.Address;
import com.bloxbean.cardano.client.address.util.AddressUtil;
import com.bloxbean.cardano.yano.wallet.core.service.HistoryPort.ScanProgress;
import com.bloxbean.cardano.yano.wallet.core.service.HistoryPort.TxRef;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

/** Atomic wallet-local cursor, matching outputs and transaction history. */
final class WalletScanHistory {
    private static final int MAX_TRANSACTIONS = 100_000;
    private final YanoNodeClient client;
    private final Path directory;
    private final ObjectMapper mapper = new ObjectMapper();

    /** Set while a scan is streaming; read by the UI from another thread. */
    private volatile ScanProgressTracker tracker;

    WalletScanHistory(YanoNodeClient client, Path directory) {
        this.client = client;
        this.directory = directory;
    }

    /**
     * Deliberately not synchronized: {@link #transactions} holds this instance's
     * lock for the whole scan, so a progress read that waited for it could only
     * ever report a scan that had already finished.
     */
    Optional<ScanProgress> progress() {
        ScanProgressTracker current = tracker;
        return current == null ? Optional.empty() : current.snapshot();
    }

    synchronized List<TxRef> transactions(String stakeAddress, int page, int count, boolean newestFirst) {
        if (page < 1 || count < 1 || count > 100) throw new IllegalArgumentException("Invalid history page/count");
        try {
            byte[] stake = new Address(stakeAddress).getBytes();
            if (stake.length != 29 || ((stake[0] & 255) >>> 4) < 14) throw new IllegalArgumentException("Stake address required for account scan");
            String hash = HexFormat.of().formatHex(stake, 1, 29);
            Path file = directory.resolve(digest(client.baseUrl() + '|' + stakeAddress) + ".json");
            ObjectNode saved = read(file);
            List<ObjectNode> candidates = new ArrayList<>();
            if (saved != null) {
                candidates.add((ObjectNode) saved.path("current"));
                if (saved.path("previous").isObject()) candidates.add((ObjectNode) saved.path("previous"));
            }
            candidates.add(empty());
            ObjectNode next = null;
            ObjectNode previous = null;
            for (ObjectNode candidate : candidates) {
                try {
                    next = scan(candidate, hash, (stake[0] & 0x10) != 0);
                    previous = candidate;
                    break;
                } catch (WalletScanTransport.Reorg reorg) {
                    // Retry a persisted earlier boundary with its matching outpoint state.
                    // If both retained boundaries were orphaned, origin is always the safe fallback.
                }
            }
            if (next == null) throw new NodeClientException("Chain kept changing during wallet history recovery; retry");
            ObjectNode durable = mapper.createObjectNode().put("version", 1);
            durable.set("current", next);
            durable.set("previous", previous);
            if (saved == null || !next.equals(saved.path("current"))) persist(file, durable);
            List<TxRef> rows = new ArrayList<>();
            for (JsonNode row : next.path("transactions")) {
                rows.add(new TxRef(row.path("txHash").asText(), row.path("blockNumber").longValue(),
                        row.path("blockTime").longValue(), row.path("slot").longValue()));
            }
            Comparator<TxRef> order = Comparator.comparingLong(TxRef::blockHeight).thenComparing(TxRef::txHash);
            rows.sort(newestFirst ? order.reversed() : order);
            return rows.stream().skip(Math.multiplyExact((long) page - 1, count)).limit(count).toList();
        } catch (IOException failure) {
            throw new NodeClientException("Wallet scan history could not be read or saved", failure);
        }
    }

    private ObjectNode scan(ObjectNode previous, String stakeHash, boolean script) {
        ObjectNode next = previous.deepCopy();
        ObjectNode outputs = (ObjectNode) next.path("outputs");
        ObjectNode transactions = (ObjectNode) next.path("transactions");
        ObjectNode request = mapper.createObjectNode().put("version", 1);
        request.putArray("credentials").addObject().put("role", "stake").put("type", script ? "script" : "key").put("hash", stakeHash);
        request.set("after", previous.path("cursor"));
        ArrayNode known = request.putArray("knownOutputs");
        outputs.forEach(known::add);
        boolean[] ready = {false};
        JsonNode[] end = {null};
        JsonNode[] last = {previous.path("cursor")};
        boolean[] genesis = {false};
        ScanProgressTracker progress = new ScanProgressTracker(previous.path("cursor").path("blockNumber").longValue());
        tracker = progress;
        try {
            scanRecords(request, progress, previous, next, outputs, transactions, stakeHash, script,
                    ready, end, last, genesis);
        } finally {
            // Cleared on every exit, so a failed or abandoned scan never leaves a
            // stalled percentage on screen claiming work is still happening.
            tracker = null;
        }
        return next;
    }

    private void scanRecords(ObjectNode request, ScanProgressTracker progress, ObjectNode previous,
                             ObjectNode next, ObjectNode outputs, ObjectNode transactions,
                             String stakeHash, boolean script, boolean[] ready, JsonNode[] end,
                             JsonNode[] last, boolean[] genesis) {
        client.scanWallet(request, record -> {
            progress.observe(record);
            String type = record.path("type").asText();
            switch (type) {
                case "ready" -> {
                    if (ready[0]) throw new NodeClientException("Duplicate scan readiness record");
                    JsonNode identity = record.path("coverage").path("identity");
                    if (!identity.isTextual() || !validPoint(record.path("point"))) throw new NodeClientException("Missing scan identity or point");
                    if (previous.hasNonNull("identity") && !previous.path("identity").equals(identity)) {
                        throw new WalletScanTransport.Reorg("Node chain identity changed");
                    }
                    checkForward(previous.path("cursor"), record.path("point"));
                    next.set("identity", identity);
                    end[0] = record.path("point");
                    ready[0] = true;
                }
                case "genesis", "transaction" -> {
                    if (!ready[0]) throw new NodeClientException("Scan data preceded coverage record");
                    JsonNode point = record.path("point");
                    if (type.equals("genesis")) {
                        if (genesis[0] || previous.path("cursor").path("blockNumber").longValue() != -1
                                || !point.equals(previous.path("cursor")) || !last[0].equals(previous.path("cursor"))) {
                            throw new NodeClientException("Unexpected genesis record");
                        }
                        genesis[0] = true;
                    } else {
                        if (!validPoint(point) || point.path("blockNumber").longValue()
                                <= previous.path("cursor").path("blockNumber").longValue()) {
                            throw new NodeClientException("Transaction outside scan interval");
                        }
                        checkForward(last[0], point);
                        checkForward(point, end[0]);
                        last[0] = point;
                    }
                    for (JsonNode input : record.path("inputs")) outputs.remove(outpoint(input));
                    for (JsonNode output : record.path("outputs")) {
                        if (matchesStake(output.path("address").asText(), stakeHash, script)) {
                            outputs.set(outpoint(output.path("outpoint")), output);
                        }
                    }
                    if (outputs.size() > 10_000) throw new NodeClientException("Wallet scan output limit exceeded");
                    if (type.equals("transaction")) {
                        if (!validHash(record.path("txHash")) || !record.path("blockTime").isIntegralNumber()) {
                            throw new NodeClientException("Malformed scan transaction");
                        }
                        ObjectNode row = mapper.createObjectNode().put("txHash", record.path("txHash").asText())
                                .put("blockNumber", point.path("blockNumber").longValue())
                                .put("slot", point.path("slot").longValue()).put("blockTime", record.path("blockTime").longValue());
                        row.set("blockHash", point.path("blockHash"));
                        transactions.set(row.path("txHash").asText(), row);
                        if (transactions.size() > MAX_TRANSACTIONS) throw new NodeClientException("Wallet history limit exceeded");
                    }
                }
                case "progress" -> {
                    if (!ready[0]) throw new NodeClientException("Scan progress preceded coverage record");
                    checkForward(last[0], record.path("point"));
                    checkForward(record.path("point"), end[0]);
                    last[0] = record.path("point");
                }
                case "done" -> {
                    if (!ready[0] || !record.path("point").equals(end[0])) throw new NodeClientException("Scan completion point mismatch");
                    next.set("cursor", record.path("point"));
                }
                default -> throw new NodeClientException("Unknown scan record type: " + type);
            }
        });
    }

    private ObjectNode empty() {
        ObjectNode state = mapper.createObjectNode();
        state.putObject("cursor").put("blockNumber", -1).put("slot", 0).put("blockHash", "00".repeat(32));
        state.putObject("outputs");
        state.putObject("transactions");
        return state;
    }

    private ObjectNode read(Path file) throws IOException {
        if (!Files.exists(file)) return null;
        if (Files.size(file) > 64_000_000) throw new IOException("Wallet history file exceeds size limit");
        JsonNode root = mapper.readTree(Files.readAllBytes(file));
        if (!root.isObject() || root.path("version").asInt() != 1 || !validState(root.path("current"))
                || root.has("previous") && !validState(root.path("previous"))) {
            throw new IOException("Invalid wallet scan history state");
        }
        return (ObjectNode) root;
    }

    private static boolean validState(JsonNode state) {
        return state.isObject() && validPoint(state.path("cursor"))
                && state.path("outputs").isObject() && state.path("transactions").isObject();
    }

    private static boolean validHash(JsonNode hash) {
        return hash.isTextual() && hash.textValue().matches("[0-9a-f]{64}");
    }

    private static boolean validPoint(JsonNode point) {
        JsonNode block = point.path("blockNumber");
        JsonNode slot = point.path("slot");
        if (!block.isIntegralNumber() || !block.canConvertToLong() || block.longValue() < -1
                || !slot.isIntegralNumber() || !slot.canConvertToLong() || slot.longValue() < 0
                || !validHash(point.path("blockHash"))) return false;
        return block.longValue() != -1 || slot.longValue() == 0
                && point.path("blockHash").textValue().equals("00".repeat(32));
    }

    private static void checkForward(JsonNode from, JsonNode to) {
        if (!validPoint(from) || !validPoint(to)
                || to.path("blockNumber").longValue() < from.path("blockNumber").longValue()
                || to.path("slot").longValue() < from.path("slot").longValue()
                || to.path("blockNumber").longValue() == from.path("blockNumber").longValue() && !to.equals(from)) {
            throw new NodeClientException("Invalid or inconsistent scan point");
        }
    }

    private void persist(Path file, ObjectNode state) throws IOException {
        byte[] bytes = mapper.writeValueAsBytes(state);
        if (bytes.length > 64_000_000) throw new IOException("Wallet history exceeds size limit");
        Files.createDirectories(directory);
        Path temporary = Files.createTempFile(directory, ".scan-", ".tmp");
        try {
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
                ByteBuffer buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) channel.write(buffer);
                channel.force(true);
            }
            Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static boolean matchesStake(String address, String hash, boolean script) {
        byte[] raw;
        try { raw = AddressUtil.addressToBytes(address); }
        catch (Exception failure) { throw new NodeClientException("Malformed scan output address", failure); }
        if (raw == null || raw.length == 0) throw new NodeClientException("Missing scan output address");
        int type = (raw[0] & 255) >>> 4;
        return raw.length == 57 && type <= 3 && (type == 2 || type == 3) == script
                && HexFormat.of().formatHex(raw, 29, 57).equals(hash);
    }

    private static String outpoint(JsonNode point) {
        if (!point.path("txHash").isTextual() || !point.path("index").isIntegralNumber()) throw new NodeClientException("Malformed scan outpoint");
        return point.path("txHash").asText() + '#' + point.path("index").intValue();
    }

    private static String digest(String key) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
