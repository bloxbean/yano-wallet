package com.bloxbean.cardano.yano.wallet.app;

import com.bloxbean.cardano.client.common.model.Networks;
import com.bloxbean.cardano.client.common.cbor.CborSerializationUtil;
import com.bloxbean.cardano.client.transaction.spec.*;
import com.bloxbean.cardano.client.util.HexUtil;
import com.bloxbean.cardano.hdwallet.Wallet;
import com.bloxbean.cardano.yano.wallet.core.config.WalletNetwork;
import com.bloxbean.cardano.yano.wallet.core.service.WalletService;
import com.bloxbean.cardano.yano.wallet.core.tx.FilePendingTransactionStore;
import com.bloxbean.cardano.yano.wallet.core.wallet.FileStoredWalletRepository;
import com.bloxbean.cardano.yano.wallet.core.wallet.WalletAddresses;
import com.bloxbean.cardano.yano.wallet.ui.contract.Cip30Prompt;
import com.bloxbean.cardano.yano.wallet.ui.contract.TxEffectView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigInteger;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.*;

/** Tests actual consent-to-sign wiring with disposable keys and no node/wallet UI. */
class Cip30SignerSearchApprovalTest {
    private static final String MNEMONIC = "test walk nut penalty hip pave soap entry language right filter choice";
    @TempDir Path dir;
    private final Wallet hd = Wallet.createFromMnemonic(Networks.testnet(), MNEMONIC);
    private final AtomicReference<WalletService.Session> current = new AtomicReference<>();
    private WalletService service;
    private Backend backend;
    private WalletCip30Wallet wallet;
    private Cip30ApprovalGate gate;
    private final Prompt prompt = new Prompt();

    static class Backend extends WalletBackendManager {
        ActiveConnection connection;
        Backend(Path path) { super(path); }
        @Override public ActiveConnection active() { return connection; }
    }
    static class Prompt implements Cip30Prompt {
        boolean extend;
        boolean approve = true;
        int extensions;
        int approvals;
        String description;
        Runnable beforeApprove = () -> {};
        public boolean confirmConnect(String origin) { return true; }
        public boolean confirmSignData(String origin, String address) { return false; }
        public boolean confirmSignData(String origin, String address, String description, String payload) {
            approvals++; this.description = description; beforeApprove.run(); return approve;
        }
        public boolean confirmSign(String origin, TxEffectView effect) { throw new AssertionError("Missing signer review"); }
        public boolean confirmExtendedSignerSearch(String origin, String description) { extensions++; return extend; }
        public boolean confirmSign(String origin, TxEffectView effect, String description) {
            approvals++;
            this.description = description;
            beforeApprove.run();
            return approve;
        }
    }

    @BeforeEach void setup() {
        var repository = new FileStoredWalletRepository(dir, WalletNetwork.PREPROD);
        var stored = repository.importMnemonic("test", MNEMONIC, "disposable".toCharArray());
        service = WalletService.localOnly(repository, new FilePendingTransactionStore(dir.resolve("pending.json")), "test node unavailable");
        current.set(service.unlock(stored.id(), "disposable".toCharArray()));
        backend = new Backend(dir);
        backend.connection = new WalletBackendManager.ActiveConnection(null, WalletNetwork.PREPROD, "", null, service);
        wallet = new WalletCip30Wallet(backend, current::get);
        gate = new Cip30ApprovalGate(new Cip30AllowlistStore(dir), prompt, new TxEffectSummariser(backend, current::get), wallet);
    }

    private byte[] hash(int role, int index) {
        return WalletAddresses.account(hd, role, index).hdKeyPair().getPublicKey().getKeyHash();
    }
    private String tx(byte[]... hashes) throws Exception {
        return Transaction.builder().body(TransactionBody.builder().inputs(List.of())
                .outputs(List.of(new TransactionOutput(hd.getBaseAddressString(0), Value.builder().coin(BigInteger.ONE).build())))
                .fee(BigInteger.valueOf(200_000)).requiredSigners(List.of(hashes)).build())
                .witnessSet(TransactionWitnessSet.builder().build()).build().serializeToHex();
    }
    private int count(String witnesses) throws Exception {
        return TransactionWitnessSet.deserialize((co.nstant.in.cbor.model.Map)
                CborSerializationUtil.deserialize(HexUtil.decodeHexString(witnesses))).getVkeyWitnesses().size();
    }

    private String dataAddress(int role, int index) { return "60" + HexUtil.encodeHexString(hash(role, index)); }

    @Test void coseSignsUnusedReceiveAndChangePathsWithOneUseApproval() {
        for (int role : new int[]{0, 1}) for (int index : new int[]{1, 2, 29}) {
            String address = dataAddress(role, index);
            assertThat(gate.confirmSignData("https://test.invalid", address, "010203")).isTrue();
            assertThat(prompt.description).contains("/" + role + "/" + index);
            var result = wallet.signData(address, "010203");
            var signature = new com.bloxbean.cardano.client.cip.cip30.DataSignature(result.signature(), result.key());
            assertThat(com.bloxbean.cardano.client.cip.cip30.CIP30DataSigner.INSTANCE.verify(signature)).isTrue();
            assertThat(result.key()).contains(HexUtil.encodeHexString(WalletAddresses.account(hd, role, index).hdKeyPair().getPublicKey().getKeyData()));
            assertThatThrownBy(() -> wallet.signData(address, "010203")).hasMessageContaining("No matching approval");
        }
        assertThat(prompt.extensions).isZero();
    }

    @Test void coseExpansionIsExplicitAndNeverSignsIndex50() {
        String address = dataAddress(1, 49);
        assertThatThrownBy(() -> gate.confirmSignData("https://test.invalid", address, "01")).hasMessageContaining("No matching data signing key");
        prompt.extend = true;
        assertThat(gate.confirmSignData("https://test.invalid", address, "01")).isTrue();
        assertThat(prompt.description).contains("/1/49");
        assertThat(wallet.signData(address, "01").signature()).isNotBlank();
        assertThatThrownBy(() -> gate.confirmSignData("https://test.invalid", dataAddress(0, 50), "01")).hasMessageContaining("No matching data signing key");
    }

    @Test void coseRejectsChangedPayloadAddressSessionNetworkAndDeclinedConsent() {
        String address = dataAddress(0, 1);
        assertThat(gate.confirmSignData("https://test.invalid", address, "01")).isTrue();
        assertThatThrownBy(() -> wallet.signData(address, "02")).hasMessageContaining("No matching approval");
        assertThat(gate.confirmSignData("https://test.invalid", address, "01")).isTrue();
        assertThatThrownBy(() -> wallet.signData(dataAddress(0, 2), "01")).hasMessageContaining("No matching approval");
        prompt.approve = false;
        assertThat(gate.confirmSignData("https://test.invalid", address, "01")).isFalse();
        assertThatThrownBy(() -> wallet.signData(address, "01")).hasMessageContaining("No matching approval");
        prompt.approve = true;
        assertThat(gate.confirmSignData("https://test.invalid", address, "01")).isTrue();
        backend.connection = new WalletBackendManager.ActiveConnection(null, WalletNetwork.PREVIEW, "", null, service);
        assertThatThrownBy(() -> wallet.signData(address, "01")).hasMessageContaining("No matching approval");
        prompt.beforeApprove = () -> current.set(null);
        assertThatThrownBy(() -> gate.confirmSignData("https://test.invalid", address, "01")).hasMessageContaining("Account or network changed");
    }

    @Test void oneApprovalSignsThreeAddressesWithoutExtension() throws Exception {
        String tx = tx(hash(0, 0), hash(0, 1), hash(1, 2));
        assertThat(gate.confirmSign("https://test.invalid", tx, true)).isTrue();
        assertThat(prompt.extensions).isZero();
        assertThat(prompt.approvals).isEqualTo(1);
        assertThat(prompt.description).contains("/0/0", "/0/1", "/1/2");
        assertThat(count(wallet.signTx(tx, true))).isEqualTo(3);
        assertThatThrownBy(() -> wallet.signTx(tx, true)).hasMessageContaining("No matching approval");
    }

    @Test void extensionNeedsExplicitConsentAndCoversBothChains() throws Exception {
        String tx = tx(hash(0, 35), hash(1, 49));
        prompt.extend = true;
        assertThat(gate.confirmSign("https://test.invalid", tx, true)).isTrue();
        assertThat(prompt.extensions).isEqualTo(1);
        assertThat(prompt.description).contains("0–49", "/0/35", "/1/49");
        assertThat(count(wallet.signTx(tx, true))).isEqualTo(2);
    }

    @Test void decliningExtensionAllowsOnlyReviewedPartialSignatures() throws Exception {
        String tx = tx(hash(0, 1), hash(1, 35));
        assertThat(gate.confirmSign("https://test.invalid", tx, true)).isTrue();
        assertThat(prompt.extensions).isEqualTo(1);
        assertThat(prompt.description).contains("Unmatched signer hashes: 1").doesNotContain("Payment: m/1852'/1815'/0'/1/35");
        assertThat(count(wallet.signTx(tx, true))).isEqualTo(1);
    }

    @Test void fullSigningRejectsUnmatchedKeysEvenAfterExtension() throws Exception {
        String tx = tx(hash(0, 0), hash(1, 50));
        prompt.extend = true;
        assertThatThrownBy(() -> gate.confirmSign("https://test.invalid", tx, false)).hasMessageContaining("Cannot fully sign");
        assertThat(prompt.approvals).isZero();
        assertThatThrownBy(() -> wallet.signTx(tx, false)).hasMessageContaining("No matching approval");
    }

    @Test void rejectedApprovalCannotSign() throws Exception {
        String tx = tx(hash(0, 0));
        prompt.approve = false;
        assertThat(gate.confirmSign("https://test.invalid", tx, true)).isFalse();
        assertThatThrownBy(() -> wallet.signTx(tx, true)).hasMessageContaining("No matching approval");
    }

    @Test void noMatchDoesNotProduceAnUnrelatedPrimaryKeySignature() throws Exception {
        String tx = tx(hash(1, 50));
        prompt.extend = true;
        assertThatThrownBy(() -> gate.confirmSign("https://test.invalid", tx, true)).hasMessageContaining("No matching signing keys");
        assertThat(prompt.approvals).isZero();
    }

    @Test void activeSessionChangeDuringPromptRejects() throws Exception {
        String tx = tx(hash(0, 0));
        prompt.beforeApprove = () -> current.set(null);
        assertThatThrownBy(() -> gate.confirmSign("https://test.invalid", tx, true)).hasMessageContaining("Account or network changed");
        assertThatThrownBy(() -> wallet.signTx(tx, true)).hasMessageContaining("No matching approval");
    }

    @Test void networkChangeAfterApprovalAndBodyMutationReject() throws Exception {
        String original = tx(hash(0, 0));
        assertThat(gate.confirmSign("https://test.invalid", original, true)).isTrue();
        assertThatThrownBy(() -> wallet.signTx(tx(hash(0, 1)), true)).hasMessageContaining("No matching approval");
        assertThat(gate.confirmSign("https://test.invalid", original, true)).isTrue();
        backend.connection = new WalletBackendManager.ActiveConnection(null, WalletNetwork.PREVIEW, "", null, service);
        assertThatThrownBy(() -> wallet.signTx(original, true)).hasMessageContaining("No matching approval");
    }

    @Test void generatedPathBeyond50IsKnownWithinSession() throws Exception {
        current.get().addressDetails(75);
        String tx = tx(hash(0, 75));
        assertThat(gate.confirmSign("https://test.invalid", tx, false)).isTrue();
        assertThat(prompt.extensions).isZero();
        assertThat(count(wallet.signTx(tx, false))).isEqualTo(1);
    }
}
