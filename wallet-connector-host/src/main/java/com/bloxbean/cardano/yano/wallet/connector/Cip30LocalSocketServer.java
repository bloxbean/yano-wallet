package com.bloxbean.cardano.yano.wallet.connector;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.Channels;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;

/**
 * The Native Messaging leg of the CIP-30 bridge (ADR-035 M5): a unix domain
 * socket server speaking Chrome's native-messaging framing — 4-byte
 * little-endian length + JSON — so the browser-launched proxy can relay bytes
 * verbatim in both directions. Same request envelope as the WebSocket server
 * (shared {@link Cip30Rpc}); the transport is the only difference.
 *
 * <p>The socket is a filesystem object with owner-only permissions where the
 * platform supports them — unlike a TCP port, other local users can't dial it.
 */
public final class Cip30LocalSocketServer {

    private static final Logger log = LoggerFactory.getLogger(Cip30LocalSocketServer.class);

    /** Chrome rejects host→browser messages over 1 MB; fail loudly before it does. */
    static final int MAX_FRAME_BYTES = 1024 * 1024;

    private final Path socketPath;
    private final Cip30Rpc rpc;
    private volatile ServerSocketChannel serverChannel;
    private volatile boolean running;

    public Cip30LocalSocketServer(Path socketPath, Cip30Wallet wallet, Cip30Approvals approvals) {
        this.socketPath = socketPath;
        this.rpc = new Cip30Rpc(new Cip30Dispatcher(wallet, approvals));
    }

    public Path socketPath() {
        return socketPath;
    }

    public synchronized void start() throws IOException {
        if (running) {
            return;
        }
        Files.createDirectories(socketPath.getParent());
        Files.deleteIfExists(socketPath); // stale socket from a previous run
        serverChannel = ServerSocketChannel.open(StandardProtocolFamily.UNIX);
        serverChannel.bind(UnixDomainSocketAddress.of(socketPath));
        restrictToOwner(socketPath);
        running = true;

        Thread acceptor = new Thread(this::acceptLoop, "cip30-socket-acceptor");
        acceptor.setDaemon(true);
        acceptor.start();
        log.info("CIP-30 native-messaging bridge listening on {}", socketPath);
    }

    public synchronized void stop() {
        running = false;
        try {
            if (serverChannel != null) {
                serverChannel.close();
            }
        } catch (IOException ignored) {
            // Best-effort shutdown.
        }
        try {
            Files.deleteIfExists(socketPath);
        } catch (IOException ignored) {
            // A stale socket file is harmless; start() removes it.
        }
    }

    private void acceptLoop() {
        while (running) {
            SocketChannel connection;
            try {
                connection = serverChannel.accept();
            } catch (ClosedChannelException e) {
                return; // stop() closed us
            } catch (IOException e) {
                if (running) {
                    log.warn("CIP-30 socket accept failed: {}", e.getMessage());
                }
                return;
            }
            Thread worker = new Thread(() -> serve(connection), "cip30-socket-conn");
            worker.setDaemon(true);
            worker.start();
        }
    }

    private void serve(SocketChannel connection) {
        try (connection) {
            InputStream in = Channels.newInputStream(connection);
            OutputStream out = Channels.newOutputStream(connection);
            while (true) {
                String request = readFrame(in);
                if (request == null) {
                    return; // clean disconnect
                }
                writeFrame(out, rpc.handle(request));
            }
        } catch (IOException e) {
            log.debug("CIP-30 socket connection ended: {}", e.getMessage());
        }
    }

    /** Reads one 4-byte-LE-length-prefixed frame; null on clean end-of-stream. */
    static String readFrame(InputStream in) throws IOException {
        byte[] header = in.readNBytes(4);
        if (header.length == 0) {
            return null;
        }
        if (header.length < 4) {
            throw new EOFException("Truncated frame header");
        }
        int length = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN).getInt();
        if (length < 0 || length > MAX_FRAME_BYTES) {
            throw new IOException("Frame length out of range: " + length);
        }
        byte[] payload = in.readNBytes(length);
        if (payload.length < length) {
            throw new EOFException("Truncated frame payload");
        }
        return new String(payload, StandardCharsets.UTF_8);
    }

    static void writeFrame(OutputStream out, String message) throws IOException {
        byte[] payload = message.getBytes(StandardCharsets.UTF_8);
        if (payload.length > MAX_FRAME_BYTES) {
            throw new IOException("Reply exceeds the 1 MB native-messaging limit (" + payload.length + " bytes)");
        }
        byte[] header = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(payload.length).array();
        out.write(header);
        out.write(payload);
        out.flush();
    }

    private static void restrictToOwner(Path path) {
        try {
            Files.setPosixFilePermissions(path, Set.of(
                    PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
        } catch (UnsupportedOperationException | IOException e) {
            // Non-POSIX filesystem (Windows): AF_UNIX still beats a TCP port —
            // the path lives under the user profile, guarded by its ACLs.
            log.debug("Could not restrict socket permissions: {}", e.getMessage());
        }
    }
}
