package com.bloxbean.cardano.yano.wallet.connector;

import com.bloxbean.cardano.client.api.model.Utxo;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The consent gating: only isEnabled/enable/getExtensions run before a site is connected. */
class Cip30DispatcherTest {

    private static final ObjectMapper M = new ObjectMapper();
    private static final String ORIGIN = "https://dapp.example";

    private static Cip30Wallet readyWallet() {
        return new StubWallet(true);
    }

    @Test
    void isEnabledReflectsAllowlist() {
        var approvals = new StubApprovals(false, true);
        var d = new Cip30Dispatcher(readyWallet(), approvals);
        assertThat(d.handle("isEnabled", null, ORIGIN)).isEqualTo(false);
        approvals.connected = true;
        assertThat(d.handle("isEnabled", null, ORIGIN)).isEqualTo(true);
    }

    @Test
    void enablePromptsAndGrants() {
        var approvals = new StubApprovals(false, true); // not connected, will grant
        var d = new Cip30Dispatcher(readyWallet(), approvals);
        assertThat(d.handle("enable", null, ORIGIN)).isEqualTo(true);
        assertThat(approvals.promptCount).isEqualTo(1);
    }

    @Test
    void enableRefusedWhenUserDeclines() {
        var approvals = new StubApprovals(false, false); // not connected, will decline
        var d = new Cip30Dispatcher(readyWallet(), approvals);
        assertThat(d.handle("enable", null, ORIGIN)).isEqualTo(false);
    }

    @Test
    void apiMethodBeforeConnectIsRefused() {
        var d = new Cip30Dispatcher(readyWallet(), new StubApprovals(false, false));
        assertThatThrownBy(() -> d.handle("getNetworkId", null, ORIGIN))
                .isInstanceOf(Cip30Exception.class)
                .satisfies(e -> assertThat(((Cip30Exception) e).code()).isEqualTo(Cip30Exception.REFUSED));
    }

    @Test
    void apiMethodWhenLockedIsInternalError() {
        var d = new Cip30Dispatcher(new StubWallet(false), new StubApprovals(true, true));
        assertThatThrownBy(() -> d.handle("getNetworkId", null, ORIGIN))
                .isInstanceOf(Cip30Exception.class)
                .satisfies(e -> assertThat(((Cip30Exception) e).code()).isEqualTo(Cip30Exception.INTERNAL_ERROR));
    }

    @Test
    void getNetworkIdWhenConnected() {
        var d = new Cip30Dispatcher(readyWallet(), new StubApprovals(true, true));
        assertThat(d.handle("getNetworkId", null, ORIGIN)).isEqualTo(0);
    }

    @Test
    void signTxPromptsAndReturnsWitnessWhenApproved() throws Exception {
        var d = new Cip30Dispatcher(readyWallet(), new StubApprovals(true, true));
        Object res = d.handle("signTx", M.readTree("{\"tx\":\"00\",\"partialSign\":true}"), ORIGIN);
        assertThat(res).isEqualTo("witness-set-hex");
    }

    @Test
    void signTxRefusedWhenUserDeclines() {
        var d = new Cip30Dispatcher(readyWallet(), new StubApprovals(true, false)); // connected, declines sign
        assertThatThrownBy(() -> d.handle("signTx",
                M.readTree("{\"tx\":\"00\"}"), ORIGIN))
                .isInstanceOf(Cip30Exception.class)
                .satisfies(e -> assertThat(((Cip30Exception) e).code()).isEqualTo(Cip30Exception.REFUSED));
    }

    @Test
    void theConsentGateSeesExactlyTheTransactionThatWillBeSigned() throws Exception {
        // ADR-042: this module cannot summarise (it is node-free), so it hands the
        // raw CBOR over. Describing something other than what gets signed is the
        // failure this contract exists to prevent.
        var approvals = new StubApprovals(true, true);
        var d = new Cip30Dispatcher(readyWallet(), approvals);

        d.handle("signTx", M.readTree("{\"tx\":\"84a4beef\",\"partialSign\":true}"), ORIGIN);

        assertThat(approvals.lastSignedTxHex).isEqualTo("84a4beef");
        assertThat(approvals.lastPartialSign).isTrue();
    }

    @Test
    void signDataUsesItsOwnPromptRatherThanATransactionSummary() throws Exception {
        var approvals = new StubApprovals(true, true);
        var d = new Cip30Dispatcher(readyWallet(), approvals);

        d.handle("signData", M.readTree("{\"addr\":\"00aabb\",\"payload\":\"cafe\"}"), ORIGIN);

        assertThat(approvals.lastSignDataAddress).isEqualTo("00aabb");
        assertThat(approvals.lastSignedTxHex).as("signData must not go through the transaction prompt").isNull();
    }

    @Test
    void signDataRefusedWhenUserDeclines() {
        var d = new Cip30Dispatcher(readyWallet(), new StubApprovals(true, false));
        assertThatThrownBy(() -> d.handle("signData",
                M.readTree("{\"addr\":\"00aabb\",\"payload\":\"cafe\"}"), ORIGIN))
                .isInstanceOf(Cip30Exception.class)
                .satisfies(e -> assertThat(((Cip30Exception) e).code()).isEqualTo(Cip30Exception.REFUSED));
    }

    @Test
    void unknownMethodIsInvalid() {
        var d = new Cip30Dispatcher(readyWallet(), new StubApprovals(true, true));
        assertThatThrownBy(() -> d.handle("frobnicate", null, ORIGIN))
                .isInstanceOf(Cip30Exception.class)
                .satisfies(e -> assertThat(((Cip30Exception) e).code()).isEqualTo(Cip30Exception.INVALID_REQUEST));
    }

    // --- stubs ---

    private static final class StubWallet implements Cip30Wallet {
        private final boolean ready;

        StubWallet(boolean ready) {
            this.ready = ready;
        }

        public boolean isReady() {
            return ready;
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
            return "witness-set-hex";
        }

        public DataSignature signData(String signerAddress, String payloadHex) {
            return new DataSignature("", "");
        }

        public String submitTx(String txHex) {
            return "";
        }
    }

    private static final class StubApprovals implements Cip30Approvals {
        boolean connected;
        final boolean grant;
        int promptCount;
        String lastSignedTxHex;
        Boolean lastPartialSign;
        String lastSignDataAddress;

        StubApprovals(boolean connected, boolean grant) {
            this.connected = connected;
            this.grant = grant;
        }

        public boolean isConnected(String origin) {
            return connected;
        }

        public boolean confirmConnect(String origin) {
            promptCount++;
            if (grant) {
                connected = true;
            }
            return grant;
        }

        public boolean confirmSign(String origin, String txHex, boolean partialSign) {
            lastSignedTxHex = txHex;
            lastPartialSign = partialSign;
            return grant;
        }

        public boolean confirmSignData(String origin, String address) {
            lastSignDataAddress = address;
            return grant;
        }
    }
}
