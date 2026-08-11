package com.bloxbean.cardano.yano.wallet.connector;

import com.bloxbean.cardano.client.api.model.Utxo;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end over a real WebSocket: a client sends CIP-30 JSON frames and the
 * server answers. Proves the transport + framing + dispatch wiring, not just the
 * dispatcher in isolation.
 */
class Cip30BridgeServerTest {

    private static final ObjectMapper M = new ObjectMapper();
    private Cip30BridgeServer server;

    @BeforeEach
    void setUp() throws Exception {
        // port 0 → OS picks a free port; connected + ready so read methods work.
        server = new Cip30BridgeServer(0, new ReadyWallet(), new AlwaysConnected());
        server.start();
        // Give the server thread a moment to bind and report its port.
        for (int i = 0; i < 50 && server.port() <= 0; i++) {
            Thread.sleep(20);
        }
        assertThat(server.port()).isGreaterThan(0);
    }

    @AfterEach
    void tearDown() {
        server.stop();
    }

    @Test
    void answersEnableThenGetNetworkId() throws Exception {
        assertThat(call("{\"id\":\"1\",\"method\":\"enable\",\"origin\":\"https://dapp\"}")
                .path("result").asBoolean()).isTrue();

        JsonNode net = call("{\"id\":\"2\",\"method\":\"getNetworkId\",\"origin\":\"https://dapp\"}");
        assertThat(net.path("id").asText()).isEqualTo("2");
        assertThat(net.path("result").asInt()).isEqualTo(0);
    }

    @Test
    void unknownMethodReturnsInvalidError() throws Exception {
        JsonNode res = call("{\"id\":\"9\",\"method\":\"frobnicate\",\"origin\":\"https://dapp\"}");
        assertThat(res.path("error").path("code").asInt()).isEqualTo(Cip30Exception.INVALID_REQUEST);
    }

    /** Opens a socket, sends one frame, returns the first parsed reply. */
    private JsonNode call(String request) throws Exception {
        CompletableFuture<String> reply = new CompletableFuture<>();
        WebSocket ws = HttpClient.newHttpClient().newWebSocketBuilder()
                .buildAsync(URI.create("ws://127.0.0.1:" + server.port() + "/cip30"), new WebSocket.Listener() {
                    @Override
                    public CompletableFuture<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
                        reply.complete(data.toString());
                        webSocket.request(1);
                        return null;
                    }
                })
                .get(5, TimeUnit.SECONDS);
        ws.sendText(request, true);
        String text = reply.get(5, TimeUnit.SECONDS);
        ws.sendClose(WebSocket.NORMAL_CLOSURE, "done");
        return M.readTree(text);
    }

    // --- stubs ---

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
