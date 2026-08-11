package com.bloxbean.cardano.yano.wallet.connector.proxy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.Channels;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The proxy is a raw byte relay between Chrome's stdio and the wallet's unix
 * domain socket — both sides speak the same 4-byte-LE-framed protocol, so the
 * relay must deliver bytes verbatim and shut down when either side closes.
 */
class Cip30NativeProxyTest {

    @TempDir
    Path tempDir;

    @Test
    void relaysFramesBothWaysVerbatim() throws Exception {
        Path socketPath = tempDir.resolve("wallet.sock");
        CountDownLatch served = new CountDownLatch(1);

        // The "wallet": accepts one connection and answers each frame with a
        // reply frame wrapping the request payload.
        try (ServerSocketChannel walletServer = ServerSocketChannel.open(StandardProtocolFamily.UNIX)) {
            walletServer.bind(UnixDomainSocketAddress.of(socketPath));
            Thread wallet = new Thread(() -> {
                try (SocketChannel conn = walletServer.accept()) {
                    InputStream in = Channels.newInputStream(conn);
                    var out = Channels.newOutputStream(conn);
                    String request = readFrame(in);
                    writeFrame(out, "{\"echo\":" + request + "}");
                    served.countDown();
                    // Keep the connection open until the proxy closes it.
                    in.read();
                } catch (IOException ignored) {
                    // Connection closed by the proxy — expected shutdown path.
                }
            });
            wallet.start();

            // "Chrome": piped stdin we write requests into; stdout we capture.
            PipedOutputStream chromeWrites = new PipedOutputStream();
            PipedInputStream proxyStdin = new PipedInputStream(chromeWrites, 64 * 1024);
            ByteArrayOutputStream proxyStdout = new ByteArrayOutputStream();

            Thread proxy = new Thread(() -> {
                try (SocketChannel channel = SocketChannel.open(StandardProtocolFamily.UNIX)) {
                    channel.connect(UnixDomainSocketAddress.of(socketPath));
                    Cip30NativeProxy.relay(proxyStdin, proxyStdout, channel);
                } catch (IOException ignored) {
                    // Shutdown path.
                }
            });
            proxy.start();

            writeFrame(chromeWrites, "{\"id\":\"1\",\"method\":\"enable\"}");
            assertThat(served.await(5, TimeUnit.SECONDS)).isTrue();

            // The reply must arrive on "stdout" as one intact frame.
            long deadline = System.currentTimeMillis() + 5000;
            while (proxyStdout.size() == 0 && System.currentTimeMillis() < deadline) {
                Thread.sleep(20);
            }
            String reply = readFrame(new java.io.ByteArrayInputStream(proxyStdout.toByteArray()));
            assertThat(reply).isEqualTo("{\"echo\":{\"id\":\"1\",\"method\":\"enable\"}}");

            // Chrome closing stdin (extension disconnected) must end the relay.
            chromeWrites.close();
            proxy.join(5000);
            assertThat(proxy.isAlive()).isFalse();
            wallet.join(5000);
        }
    }

    private static String readFrame(InputStream in) throws IOException {
        byte[] header = in.readNBytes(4);
        if (header.length < 4) {
            throw new IOException("no frame");
        }
        int length = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN).getInt();
        return new String(in.readNBytes(length), StandardCharsets.UTF_8);
    }

    private static void writeFrame(java.io.OutputStream out, String message) throws IOException {
        byte[] payload = message.getBytes(StandardCharsets.UTF_8);
        out.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(payload.length).array());
        out.write(payload);
        out.flush();
    }
}
