package com.bloxbean.cardano.yano.wallet.launcher;

import com.bloxbean.cardano.yano.wallet.core.config.UpstreamRelay;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A managed Yano node running as a supervised child process (ADR-033 A3).
 * Spawns the node on its own REST port with an isolated chainstate, polls
 * {@code /status} until it answers, and shuts it down cleanly. UI-facing
 * lifecycle state is exposed via {@link #state()}.
 */
public final class ManagedNode implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(ManagedNode.class);

    public enum State {STOPPED, STARTING, RUNNING, FAILED}

    private final NodeLaunchSpec spec;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2)).build();
    private final AtomicReference<State> state = new AtomicReference<>(State.STOPPED);
    private final java.util.concurrent.atomic.AtomicBoolean closing =
            new java.util.concurrent.atomic.AtomicBoolean(false);
    private volatile Process process;
    private volatile String failureReason;

    public ManagedNode(NodeLaunchSpec spec) {
        this.spec = spec;
    }

    public NodeLaunchSpec spec() {
        return spec;
    }

    public State state() {
        return state.get();
    }

    public String failureReason() {
        return failureReason;
    }

    public String baseUrl() {
        return spec.baseUrl();
    }

    /**
     * Starts the node process and blocks until its REST API answers or the
     * timeout elapses. Devnet chainstate is wiped first (the chain is
     * regenerated each launch); real-network chainstate persists.
     *
     * @return true if the node became reachable within the timeout
     */
    public boolean startAndAwaitReady(Duration timeout) {
        // Only the spawn is guarded; the poll loop below runs WITHOUT the
        // monitor so close() (e.g. from a shutdown hook) can destroy a
        // still-starting node instead of blocking for the whole timeout.
        synchronized (this) {
            if (closing.get()) {
                return false;
            }
            if (state.get() == State.RUNNING && isReachable()) {
                return true;
            }
            state.set(State.STARTING);
            failureReason = null;
            try {
                prepareChainstate();
                process = spawn();
            } catch (IOException e) {
                failAndLog("Unable to start node process: " + e.getMessage(), e);
                return false;
            }
        }

        Process current = process;
        long deadline = System.nanoTime() + timeout.toNanos();
        int polls = 0;
        while (System.nanoTime() < deadline) {
            if (closing.get()) {
                return false;
            }
            if (current != null && !current.isAlive()) {
                failAndLog("Node process exited (code " + safeExitValue(current) + "). Last lines of "
                        + spec.logFile() + ":\n" + logTail(spec.logFile(), 12), null);
                return false;
            }
            if (isReachable()) {
                state.set(State.RUNNING);
                log.info("Managed node ready at {}", spec.baseUrl());
                return true;
            }
            // A node that hit a fatal error can stay alive serving failures for
            // the whole (long) timeout — scan its log so the user sees the real
            // cause in seconds, not after a 45-minute wait.
            if (++polls % 10 == 0) {
                String fatal = fatalLogError(spec.logFile());
                if (fatal != null) {
                    failAndLog("Node hit a fatal error: " + fatal
                            + tmpDirHint(fatal)
                            + "\nLast lines of " + spec.logFile() + ":\n" + logTail(spec.logFile(), 12), null);
                    return false;
                }
            }
            sleep(500);
        }
        failAndLog("Node did not become ready within " + timeout.toSeconds() + "s. Last lines of "
                + spec.logFile() + ":\n" + logTail(spec.logFile(), 12), null);
        return false;
    }

    /**
     * Error patterns after which a node never becomes ready — waiting out the
     * full start timeout on these only hides the cause from the user.
     */
    private static final List<String> FATAL_LOG_PATTERNS = List.of(
            "org.rocksdb.RocksDBException",
            "Corruption:",
            "Invalid or corrupt jarfile",
            "Address already in use",
            "java.lang.OutOfMemoryError",
            "UnsupportedClassVersionError");

    /** The first fatal line in the log tail, or null if none seen (yet). */
    static String fatalLogError(java.nio.file.Path logFile) {
        try {
            if (logFile == null || !Files.isReadable(logFile)) {
                return null;
            }
            List<String> lines = Files.readAllLines(logFile);
            List<String> tail = lines.subList(Math.max(0, lines.size() - 200), lines.size());
            for (String line : tail) {
                for (String pattern : FATAL_LOG_PATTERNS) {
                    if (line.contains(pattern)) {
                        return line.strip();
                    }
                }
            }
            return null;
        } catch (IOException e) {
            return null; // an unreadable log is not itself fatal
        }
    }

    /**
     * A corruption under /tmp is very likely macOS's periodic cleanup (files
     * not accessed for 3 days are deleted) — say so, or the user re-copies the
     * chainstate into the same trap.
     */
    private String tmpDirHint(String fatalLine) {
        if (!fatalLine.contains("Corruption") && !fatalLine.contains("RocksDB")) {
            return "";
        }
        String chainstate = String.valueOf(spec.chainstateDir());
        if (chainstate.startsWith("/tmp/") || chainstate.startsWith("/private/tmp/")
                || chainstate.startsWith("/var/folders/")) {
            return "\nNote: the node data lives under " + chainstate + " — macOS periodically DELETES"
                    + " files in /tmp that haven't been accessed for a few days, which corrupts the"
                    + " database. Use a persistent --data-dir (e.g. ~/.yano-wallet).";
        }
        return "";
    }

    /**
     * The last {@code maxLines} of the node's log — surfaced in failure messages
     * so the real cause (bad jar, port in use, an actual lock, …) is visible
     * instead of a guess. The log is truncated per run, so it stays small.
     */
    private static String logTail(java.nio.file.Path logFile, int maxLines) {
        try {
            if (logFile == null || !Files.isReadable(logFile)) {
                return "(no log file at " + logFile + ")";
            }
            List<String> lines = Files.readAllLines(logFile);
            List<String> tail = lines.subList(Math.max(0, lines.size() - maxLines), lines.size());
            return tail.isEmpty() ? "(log is empty)" : String.join("\n", tail);
        } catch (IOException e) {
            return "(could not read " + logFile + ": " + e.getMessage() + ")";
        }
    }

    /** The node's log file (stdout/stderr capture), for the UI's log viewer. */
    public Path logFile() {
        return spec.logFile();
    }

    // Reading the whole log every poll would re-scan a multi-MB sync log; cap the
    // read to the last chunk (the tail is all the UI shows anyway).
    private static final int TAIL_READ_BYTES = 256 * 1024;

    /**
     * The last {@code maxLines} of a log file for the UI, decoded leniently so a
     * concurrent writer's half-flushed multibyte char never throws (it becomes a
     * replacement char instead of an empty view). Only the final
     * {@value #TAIL_READ_BYTES} bytes are read, so a large sync log stays cheap.
     * Returns an empty list if the file is missing/unreadable.
     */
    public static List<String> tailLines(Path logFile, int maxLines) {
        if (logFile == null || maxLines <= 0 || !Files.isReadable(logFile)) {
            return List.of();
        }
        try {
            long size = Files.size(logFile);
            long from = Math.max(0, size - TAIL_READ_BYTES);
            byte[] bytes;
            try (var channel = Files.newByteChannel(logFile, StandardOpenOption.READ)) {
                channel.position(from);
                ByteBuffer buffer = ByteBuffer.allocate((int) (size - from));
                while (buffer.hasRemaining() && channel.read(buffer) > 0) {
                    // keep reading until the tail window is filled or EOF
                }
                bytes = java.util.Arrays.copyOf(buffer.array(), buffer.position());
            }
            String[] split = new String(bytes, StandardCharsets.UTF_8).split("\n", -1);
            List<String> lines = new ArrayList<>();
            // If we started mid-file, the first element is a partial line — drop it.
            int start = from > 0 ? 1 : 0;
            for (int i = start; i < split.length; i++) {
                String line = split[i];
                lines.add(line.endsWith("\r") ? line.substring(0, line.length() - 1) : line);
            }
            // The file usually ends with a newline, leaving a trailing empty element.
            if (!lines.isEmpty() && lines.get(lines.size() - 1).isEmpty()) {
                lines.remove(lines.size() - 1);
            }
            int fromIndex = Math.max(0, lines.size() - maxLines);
            return new ArrayList<>(lines.subList(fromIndex, lines.size()));
        } catch (IOException e) {
            return List.of();
        }
    }

    private static int safeExitValue(Process process) {
        try {
            return process.exitValue();
        } catch (IllegalThreadStateException e) {
            return -1;
        }
    }

    /** True once the node's REST API answers 200 on /status. */
    public boolean isReachable() {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(spec.baseUrl() + "status"))
                    .timeout(Duration.ofSeconds(2))
                    .GET()
                    .build();
            HttpResponse<Void> response = httpClient.send(request, HttpResponse.BodyHandlers.discarding());
            return response.statusCode() == 200;
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return false;
        }
    }

    private Process spawn() throws IOException {
        List<String> command = new ArrayList<>();
        // System-property overrides (Quarkus/MicroProfile config beats application.yml):
        // custom REST + N2N ports and an isolated chainstate keep the managed
        // node clear of any default Yano on 7070/13337/./chainstate. For a jar
        // these -D flags MUST precede -jar (anything after -jar is a program
        // argument, not a JVM option); for a native binary they precede the
        // binary too.
        if (spec.nativeBinary()) {
            command.add(spec.nodeJar().toString());
            addSysProp(command, "quarkus.profile", spec.quarkusProfile());
            addSysProp(command, "quarkus.http.port", String.valueOf(spec.httpPort()));
            addSysProp(command, "yano.server.port", String.valueOf(spec.n2nPort()));
            addSysProp(command, "yano.storage.path", spec.chainstateDir().toAbsolutePath().toString());
            addUpstreamRelays(command);
        } else {
            command.add(spec.javaExecutable());
            addSysProp(command, "quarkus.profile", spec.quarkusProfile());
            addSysProp(command, "quarkus.http.port", String.valueOf(spec.httpPort()));
            addSysProp(command, "yano.server.port", String.valueOf(spec.n2nPort()));
            addSysProp(command, "yano.storage.path", spec.chainstateDir().toAbsolutePath().toString());
            addUpstreamRelays(command);
            command.add("-jar");
            command.add(spec.nodeJar().toString());
        }

        Files.createDirectories(spec.logFile().toAbsolutePath().getParent());
        ProcessBuilder builder = new ProcessBuilder(command)
                .directory(spec.workingDir().toFile())
                .redirectErrorStream(true)
                .redirectOutput(spec.logFile().toFile());
        log.info("Starting managed node: {} (workingDir={}, http={}, chainstate={})",
                spec.nodeJar().getFileName(), spec.workingDir(), spec.httpPort(), spec.chainstateDir());
        return builder.start();
    }

    /**
     * Configures the node's upstream relays (E18), so a relay that stops
     * delivering costs a failover rather than the whole sync.
     *
     * <p>{@code trusted-failover} rather than {@code static-multi}: this is about
     * staying connected, not about trusting several peers against each other, and
     * the quorum machinery is a cost with no benefit here. Bulk download stays on
     * one peer ({@code single-trusted}) — this buys resilience, not throughput.
     *
     * <p>Discovery is left alone deliberately. Yano can find relays from the ledger
     * and from a peer snapshot, which is the only real answer to hardcoded hosts
     * going stale, but it also means a desktop wallet dialling many peers. That is
     * a behaviour change that deserves its own opt-in and its own testing.
     *
     * <p>Emits nothing when the network has no relays (the two devnets), leaving
     * the node's own profile to decide — a devnet's upstream is whatever the user
     * is running locally, and we have no business guessing at it.
     */
    // Package-private so the exact property names can be pinned by a test. They
    // are a contract with the node's config keys, spelled in a string, and a typo
    // in one would be silently ignored by MicroProfile Config — the node would
    // start happily on a single upstream and the failover would simply not exist.
    void addUpstreamRelays(List<String> command) {
        List<UpstreamRelay> relays = spec.relays();
        if (relays.isEmpty()) {
            return;
        }
        addSysProp(command, "yano.upstream.mode", "trusted-failover");
        addSysProp(command, "yano.upstream.sync.bulk-source", "single-trusted");
        for (int i = 0; i < relays.size(); i++) {
            UpstreamRelay relay = relays.get(i);
            String prefix = "yano.upstream.peers[" + i + "].";
            addSysProp(command, prefix + "id", "relay-" + i);
            addSysProp(command, prefix + "host", relay.host());
            addSysProp(command, prefix + "port", String.valueOf(relay.port()));
            // Ascending priority = declaration order, so a user's own relay is
            // tried before the shipped defaults appended after it.
            addSysProp(command, prefix + "priority", String.valueOf(i));
            addSysProp(command, prefix + "trust", "trusted");
        }
        log.info("Managed node upstream relays ({}): {}", spec.network().id(), relays);
    }

    private void prepareChainstate() throws IOException {
        // Devnet regenerates genesis (and thus its chain) every launch, so a
        // stale chainstate aborts startup with a bootstrap-marker mismatch;
        // wipe it. Real networks have fixed genesis — keep the chainstate to
        // resume syncing.
        if (!spec.network().production() && spec.network().id().equals("devnet")) {
            deleteRecursively(spec.chainstateDir());
        }
        Files.createDirectories(spec.chainstateDir().toAbsolutePath().getParent());
    }

    private static void addSysProp(List<String> command, String key, String value) {
        command.add("-D" + key + "=" + value);
    }

    @Override
    public void close() {
        // Set before touching process so an in-flight startAndAwaitReady poll
        // aborts and stops racing us.
        closing.set(true);
        Process current = process;
        if (current == null) {
            httpClient.close();
            return;
        }
        log.info("Stopping managed node ({})", spec.baseUrl());
        current.destroy();
        try {
            if (!current.waitFor(20, java.util.concurrent.TimeUnit.SECONDS)) {
                current.destroyForcibly();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            current.destroyForcibly();
        }
        process = null;
        state.set(State.STOPPED);
        httpClient.close(); // release the poll HttpClient's selector/executor threads
    }

    private void failAndLog(String reason, Throwable cause) {
        this.failureReason = reason;
        state.set(State.FAILED);
        if (cause != null) {
            log.error("Managed node failed: {}", reason, cause);
        } else {
            log.error("Managed node failed: {}", reason);
        }
        Process current = process;
        if (current != null && current.isAlive()) {
            current.destroyForcibly();
        }
    }

    private static void deleteRecursively(java.nio.file.Path path) throws IOException {
        if (!Files.exists(path)) {
            return;
        }
        try (var walk = Files.walk(path)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException e) {
                    throw new java.io.UncheckedIOException(e);
                }
            });
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
