package com.bloxbean.cardano.yano.wallet.nodeclient;

import com.bloxbean.cardano.client.transaction.spec.Transaction;
import com.bloxbean.cardano.client.transaction.util.TransactionUtil;
import com.bloxbean.cardano.yano.wallet.core.service.MempoolConflictException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Local reservations, independent of history's display timeouts. No pending outputs are invented. */
final class PendingInputs {
    private static final Pattern CLAIMED_INPUT = Pattern.compile(
            "regular input is already claimed by ([0-9a-f]{64}): ([0-9a-f]{64}#[0-9]+)",
            Pattern.CASE_INSENSITIVE);
    record Reservation(long ttl, Set<String> inputs) {}

    private final ObjectMapper mapper = new ObjectMapper();
    private Map<String, Reservation> reservations = new HashMap<>();
    private Path file;

    synchronized void persistAt(Path path) {
        if (file != null) throw new IllegalStateException("Pending input storage is already configured");
        if (!reservations.isEmpty()) throw new IllegalStateException("Configure storage before submitting transactions");
        file = path.toAbsolutePath();
        if (Files.exists(file)) {
            try {
                reservations = mapper.readValue(file.toFile(), new TypeReference<Map<String, Reservation>>() {});
                if (reservations == null || reservations.values().stream()
                        .anyMatch(r -> r == null || r.inputs() == null || r.inputs().contains(null))) {
                    throw new IOException("Invalid pending input records");
                }
            } catch (IOException e) {
                throw new UncheckedIOException("Cannot read pending input reservations", e);
            }
        }
    }

    synchronized Set<String> snapshot() {
        Set<String> inputs = new HashSet<>();
        reservations.values().forEach(r -> inputs.addAll(r.inputs()));
        return Set.copyOf(inputs);
    }

    synchronized boolean isEmpty() {
        return reservations.isEmpty();
    }

    synchronized void expire(NodeStatus status) {
        // A lagging index may still advertise outputs already spent on chain.
        if (!status.utxoIndexCaughtUp()) return;
        Map<String, Reservation> next = new HashMap<>(reservations);
        if (next.values().removeIf(r -> r.ttl() > 0 && status.slot() > r.ttl())) update(next);
    }

    synchronized void refresh(YanoNodeClient client) {
        if (reservations.isEmpty()) return;
        NodeStatus status = client.getStatus();
        expire(status);
        if (!status.utxoIndexCaughtUp()) return;
        // Transactions without an upper validity bound cannot be expired by a wall-clock timeout.
        // Once indexed in a block, their inputs are also absent from the confirmed UTxO view.
        for (String hash : Set.copyOf(reservations.keySet())) {
            if ("in_block".equals(client.getTxStatus(hash).status())) release(hash);
        }
    }

    synchronized String reserve(byte[] cbor) {
        try {
            Transaction tx = Transaction.deserialize(cbor);
            String hash = TransactionUtil.getTxHash(tx);
            // Even an identical signed transaction must not be automatically submitted again.
            Set<String> inputs = new HashSet<>();
            tx.getBody().getInputs().forEach(i -> inputs.add(i.getTransactionId() + "#" + i.getIndex()));
            Set<String> blocked = snapshot();
            if (inputs.stream().anyMatch(blocked::contains)) {
                throw new MempoolConflictException("This wallet already submitted a transaction using these inputs. "
                        + "Wait for confirmation if no other confirmed funds are available.");
            }
            Map<String, Reservation> next = new HashMap<>(reservations);
            next.put(hash, new Reservation(tx.getBody().getTtl(), Set.copyOf(inputs)));
            update(next); // Durable before the request can reach the node.
            return hash;
        } catch (MempoolConflictException | UncheckedIOException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Cannot track transaction inputs; transaction was not submitted", e);
        }
    }

    synchronized void release(String hash) {
        Map<String, Reservation> next = new HashMap<>(reservations);
        if (next.remove(hash) != null) update(next);
    }

    /** Replace the rejected draft's reservation with the actual owner's reported input. */
    synchronized boolean recordConflict(String rejectedHash, String reason) {
        var match = CLAIMED_INPUT.matcher(reason);
        if (!match.find()) return false;
        String owner = match.group(1).toLowerCase(java.util.Locale.ROOT);
        String input = match.group(2).toLowerCase(java.util.Locale.ROOT);
        Reservation rejected = reservations.get(rejectedHash);
        if (rejected == null || !rejected.inputs().contains(input)) return false;
        Map<String, Reservation> next = new HashMap<>(reservations);
        next.remove(rejectedHash);
        Reservation existing = next.get(owner);
        Set<String> inputs = new HashSet<>(existing == null ? Set.of() : existing.inputs());
        inputs.add(input);
        // The rejected draft's TTL tells us nothing about the claiming transaction's validity.
        next.put(owner, new Reservation(existing == null ? 0 : existing.ttl(), Set.copyOf(inputs)));
        update(next);
        return true;
    }

    private void update(Map<String, Reservation> next) {
        if (file != null) {
            try {
                Files.createDirectories(file.getParent());
                Path tmp = Files.createTempFile(file.getParent(), "pending-inputs-", ".tmp");
                try {
                    mapper.writeValue(tmp.toFile(), next);
                    Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                } finally {
                    Files.deleteIfExists(tmp);
                }
            } catch (IOException e) {
                throw new UncheckedIOException("Cannot persist pending input reservations", e);
            }
        }
        reservations = next;
    }
}
