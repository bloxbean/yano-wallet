package com.bloxbean.cardano.yano.wallet.connector.proxy;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.Channels;
import java.nio.channels.SocketChannel;
import java.nio.file.Path;

/**
 * The Chrome Native Messaging host for the Yano CIP-30 connector (ADR-035 M5).
 *
 * <p>Chrome launches this process per extension connection and speaks Chrome's
 * native-messaging protocol over stdin/stdout: 4-byte little-endian length +
 * JSON payload. The wallet app listens on a unix domain socket using the SAME
 * framing — so this proxy never parses a message; it is a raw byte relay in
 * both directions, and message boundaries survive because both ends are
 * streams parsed by their consumers.
 *
 * <p>Security properties over the old localhost WebSocket: Chrome verifies the
 * calling extension against the host manifest's {@code allowed_origins} (only
 * the Yano extension can launch this), and the socket is a filesystem object
 * with owner-only permissions instead of a TCP port any local process can dial.
 *
 * <p>Invocation: {@code Cip30NativeProxy <socket-path> [chrome-origin...]} —
 * the launcher script written by the wallet bakes in the socket path; Chrome
 * appends its own arguments (the extension origin), which we ignore.
 */
public final class Cip30NativeProxy {

    private Cip30NativeProxy() {
    }

    public static void main(String[] args) {
        if (args.length < 1) {
            System.err.println("usage: Cip30NativeProxy <socket-path>");
            System.exit(2);
        }
        Path socketPath = Path.of(args[0]);
        try (SocketChannel channel = SocketChannel.open(StandardProtocolFamily.UNIX)) {
            channel.connect(UnixDomainSocketAddress.of(socketPath));
            relay(System.in, System.out, channel);
        } catch (IOException e) {
            // Chrome shows nothing to the page; the extension sees a disconnect
            // and reports "wallet not reachable". Log for chrome://extensions.
            System.err.println("yano-cip30-proxy: " + e.getMessage());
            System.exit(1);
        }
        System.exit(0);
    }

    /**
     * Pumps stdin→socket on a second thread and socket→stdout on the caller's;
     * returns when either side closes. Package-private for tests.
     */
    static void relay(InputStream stdin, OutputStream stdout, SocketChannel channel) throws IOException {
        OutputStream toWallet = Channels.newOutputStream(channel);
        InputStream fromWallet = Channels.newInputStream(channel);

        Thread up = new Thread(() -> {
            try {
                pump(stdin, toWallet);
            } catch (IOException ignored) {
                // Falls through to shutdown below.
            } finally {
                // Chrome closed our stdin (extension disconnected): closing the
                // channel unblocks the downstream pump so the process can exit.
                closeQuietly(channel);
            }
        }, "cip30-proxy-up");
        up.setDaemon(true);
        up.start();

        try {
            pump(fromWallet, stdout);
        } finally {
            closeQuietly(channel);
        }
    }

    private static void pump(InputStream in, OutputStream out) throws IOException {
        byte[] buffer = new byte[64 * 1024];
        int read;
        while ((read = in.read(buffer)) != -1) {
            out.write(buffer, 0, read);
            out.flush(); // each chunk may complete a message; never sit on bytes
        }
    }

    private static void closeQuietly(SocketChannel channel) {
        try {
            channel.close();
        } catch (IOException ignored) {
            // Best-effort shutdown.
        }
    }
}
