package com.bloxbean.cardano.yano.wallet.app;

import com.bloxbean.cardano.client.api.model.Utxo;
import com.bloxbean.cardano.yano.wallet.connector.Cip30Approvals;
import com.bloxbean.cardano.yano.wallet.connector.Cip30LocalSocketServer;
import com.bloxbean.cardano.yano.wallet.connector.Cip30Wallet;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The full Native Messaging path minus Chrome itself (ADR-035 M5): the REAL
 * proxy jar (the one the installer ships) launched as a separate process, fed
 * Chrome's native-messaging frames on stdin, relaying to a live
 * {@link Cip30LocalSocketServer} — replies must come back framed on stdout.
 */
@DisabledOnOs(OS.WINDOWS)
class NativeMessagingEndToEndTest {

    @TempDir
    Path tempDir;

    @Test
    void proxyProcessRelaysARequestToTheWalletAndBack() throws Exception {
        // Extract the shipped proxy jar, exactly as the installer does.
        Path proxyJar = tempDir.resolve("cip30-proxy.jar");
        try (InputStream in = getClass().getResourceAsStream("/native-host/cip30-proxy.jar")) {
            Files.copy(in, proxyJar, StandardCopyOption.REPLACE_EXISTING);
        }

        Path socketPath = tempDir.resolve("cip30.sock");
        Cip30LocalSocketServer server = new Cip30LocalSocketServer(socketPath, new StubWallet(), new OpenDoor());
        server.start();
        try {
            Process proxy = new ProcessBuilder(
                    Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                    "-jar", proxyJar.toString(), socketPath.toString(),
                    "chrome-extension://test-origin/")
                    .redirectErrorStream(false)
                    .start();
            try {
                writeFrame(proxy.getOutputStream(),
                        "{\"id\":\"e2e\",\"method\":\"getNetworkId\",\"origin\":\"https://dapp\"}");
                JsonNode reply = new ObjectMapper().readTree(readFrame(proxy.getInputStream()));

                assertThat(reply.path("id").asText()).isEqualTo("e2e");
                assertThat(reply.path("result").asInt()).isEqualTo(0);

                // Chrome closing stdin must end the host process.
                proxy.getOutputStream().close();
                assertThat(proxy.waitFor() == 0 || !proxy.isAlive()).isTrue();
            } finally {
                proxy.destroyForcibly();
            }
        } finally {
            server.stop();
        }
    }

    private static void writeFrame(OutputStream out, String message) throws Exception {
        byte[] payload = message.getBytes(StandardCharsets.UTF_8);
        out.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(payload.length).array());
        out.write(payload);
        out.flush();
    }

    private static String readFrame(InputStream in) throws Exception {
        byte[] header = in.readNBytes(4);
        assertThat(header).hasSize(4);
        int length = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN).getInt();
        return new String(in.readNBytes(length), StandardCharsets.UTF_8);
    }

    // --- stubs ---

    private static final class StubWallet implements Cip30Wallet {
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

    private static final class OpenDoor implements Cip30Approvals {
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
