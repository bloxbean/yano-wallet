package com.bloxbean.cardano.yano.wallet.connector;

import com.bloxbean.cardano.client.api.model.Utxo;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.Channels;
import java.nio.channels.SocketChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * End-to-end over a real unix domain socket, speaking Chrome's native-messaging
 * framing (4-byte little-endian length + JSON) — what the browser-launched
 * proxy relays verbatim (ADR-035 M5).
 */
class Cip30LocalSocketServerTest {

    private static final ObjectMapper M = new ObjectMapper();

    @TempDir
    Path tempDir;
    private Cip30LocalSocketServer server;

    @BeforeEach
    void setUp() throws Exception {
        server = new Cip30LocalSocketServer(tempDir.resolve("cip30.sock"), new ReadyWallet(), new AlwaysConnected());
        server.start();
    }

    @AfterEach
    void tearDown() {
        server.stop();
    }

    @Test
    void answersEnableThenGetNetworkIdOverOneConnection() throws Exception {
        try (SocketChannel channel = connect()) {
            InputStream in = Channels.newInputStream(channel);
            OutputStream out = Channels.newOutputStream(channel);

            Cip30LocalSocketServer.writeFrame(out, "{\"id\":\"1\",\"method\":\"enable\",\"origin\":\"https://dapp\"}");
            JsonNode enabled = M.readTree(Cip30LocalSocketServer.readFrame(in));
            assertThat(enabled.path("result").asBoolean()).isTrue();

            Cip30LocalSocketServer.writeFrame(out, "{\"id\":\"2\",\"method\":\"getNetworkId\",\"origin\":\"https://dapp\"}");
            JsonNode net = M.readTree(Cip30LocalSocketServer.readFrame(in));
            assertThat(net.path("id").asText()).isEqualTo("2");
            assertThat(net.path("result").asInt()).isEqualTo(0);
        }
    }

    @Test
    void unknownMethodReturnsInvalidError() throws Exception {
        try (SocketChannel channel = connect()) {
            InputStream in = Channels.newInputStream(channel);
            OutputStream out = Channels.newOutputStream(channel);

            Cip30LocalSocketServer.writeFrame(out, "{\"id\":\"9\",\"method\":\"frobnicate\",\"origin\":\"https://dapp\"}");
            JsonNode res = M.readTree(Cip30LocalSocketServer.readFrame(in));
            assertThat(res.path("error").path("code").asInt()).isEqualTo(Cip30Exception.INVALID_REQUEST);
        }
    }

    @Test
    void restartReplacesAStaleSocketFile() throws Exception {
        server.stop();
        // Simulate a crashed previous run leaving the socket file behind.
        Files.writeString(server.socketPath(), "");
        server = new Cip30LocalSocketServer(server.socketPath(), new ReadyWallet(), new AlwaysConnected());
        server.start();

        try (SocketChannel channel = connect()) {
            OutputStream out = Channels.newOutputStream(channel);
            Cip30LocalSocketServer.writeFrame(out, "{\"id\":\"1\",\"method\":\"isEnabled\",\"origin\":\"https://dapp\"}");
            assertThat(Cip30LocalSocketServer.readFrame(Channels.newInputStream(channel))).contains("result");
        }
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void socketIsOwnerOnly() throws Exception {
        Set<PosixFilePermission> perms = Files.getPosixFilePermissions(server.socketPath());
        assertThat(perms).containsExactlyInAnyOrder(
                PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
    }

    @Test
    void framingRoundTripsUtf8() throws Exception {
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        String message = "{\"note\":\"₳ ↔ 語\"}";
        Cip30LocalSocketServer.writeFrame(sink, message);

        byte[] bytes = sink.toByteArray();
        // Little-endian length prefix, exactly Chrome's native-messaging header.
        int declared = (bytes[0] & 0xff) | ((bytes[1] & 0xff) << 8) | ((bytes[2] & 0xff) << 16) | ((bytes[3] & 0xff) << 24);
        assertThat(declared).isEqualTo(bytes.length - 4);
        assertThat(Cip30LocalSocketServer.readFrame(new ByteArrayInputStream(bytes))).isEqualTo(message);
    }

    @Test
    void rejectsOversizedAndTruncatedFrames() throws Exception {
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        sink.write(new byte[]{(byte) 0xff, (byte) 0xff, (byte) 0xff, 0x7f}); // ~2 GB declared
        assertThatThrownBy(() -> Cip30LocalSocketServer.readFrame(new ByteArrayInputStream(sink.toByteArray())))
                .hasMessageContaining("out of range");

        byte[] truncated = {0x0a, 0x00, 0x00, 0x00, '{'}; // declares 10, carries 1
        assertThatThrownBy(() -> Cip30LocalSocketServer.readFrame(new ByteArrayInputStream(truncated)))
                .hasMessageContaining("Truncated");

        assertThat(Cip30LocalSocketServer.readFrame(new ByteArrayInputStream(new byte[0]))).isNull();
    }

    private SocketChannel connect() throws Exception {
        SocketChannel channel = SocketChannel.open(StandardProtocolFamily.UNIX);
        channel.connect(UnixDomainSocketAddress.of(server.socketPath()));
        return channel;
    }

    // --- stubs (mirror Cip30BridgeServerTest) ---

    private static final class ReadyWallet implements Cip30Wallet {
        public boolean isReady() {
            return true;
        }

        public int networkId() {
            return 0;
        }

        public List<String> usedAddresses() {
            return List.of();
        }

        public List<String> unusedAddresses() {
            return List.of();
        }

        public String changeAddress() {
            return null;
        }

        public List<String> rewardAddresses() {
            return List.of();
        }

        public List<Utxo> utxos() {
            return List.of();
        }

        public String signTx(String txHex, boolean partialSign) {
            return "";
        }

        public DataSignature signData(String signerAddress, String payloadHex) {
            return new DataSignature("", "");
        }

        public String submitTx(String txHex) {
            return "";
        }
    }

    private static final class AlwaysConnected implements Cip30Approvals {
        public boolean isConnected(String origin) {
            return true;
        }

        public boolean confirmConnect(String origin) {
            return true;
        }

        public boolean confirmSign(String origin, String txHex, boolean partialSign) {
            return true;
        }

        public boolean confirmSignData(String origin, String address) {
            return true;
        }
    }
}
