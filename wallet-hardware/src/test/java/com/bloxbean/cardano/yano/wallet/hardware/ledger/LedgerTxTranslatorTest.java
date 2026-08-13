package com.bloxbean.cardano.yano.wallet.hardware.ledger;

import com.bloxbean.cardano.client.address.Address;
import com.bloxbean.cardano.client.address.AddressProvider;
import com.bloxbean.cardano.client.address.Credential;
import com.bloxbean.cardano.client.common.model.Networks;
import com.bloxbean.cardano.client.plutus.spec.BigIntPlutusData;
import com.bloxbean.cardano.client.plutus.spec.PlutusData;
import com.bloxbean.cardano.client.transaction.spec.Asset;
import com.bloxbean.cardano.client.transaction.spec.MultiAsset;
import com.bloxbean.cardano.client.transaction.spec.Transaction;
import com.bloxbean.cardano.client.transaction.spec.TransactionBody;
import com.bloxbean.cardano.client.transaction.spec.TransactionInput;
import com.bloxbean.cardano.client.transaction.spec.TransactionOutput;
import com.bloxbean.cardano.client.transaction.spec.TransactionWitnessSet;
import com.bloxbean.cardano.client.transaction.spec.Value;
import com.bloxbean.cardano.client.transaction.spec.cert.StakeCredential;
import com.bloxbean.cardano.client.transaction.spec.cert.PoolRetirement;
import com.bloxbean.cardano.client.transaction.spec.cert.StakeRegistration;
import com.bloxbean.cardano.client.util.HexUtil;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The CBOR→LedgerSignRequest translator must read a dApp transaction's fields
 * AND its encoding knobs off the raw bytes (ADR-035 M4b). We test against CBOR
 * produced by CCL — the encoding the device stream was verified against on
 * hardware in HW-M3 (tag-258 input sets, legacy-array plain outputs,
 * Babbage-map outputs when a datum/script is present).
 */
class LedgerTxTranslatorTest {

    private static final String TX0 = "aa".repeat(32);
    private static final byte[] PAYMENT_KEY_HASH = keyHash(1);
    private static final long[] PAYMENT_PATH = LedgerBip32.paymentPath(0, 0, 0);

    private static byte[] keyHash(int fill) {
        byte[] hash = new byte[28];
        java.util.Arrays.fill(hash, (byte) fill);
        return hash;
    }

    private static String testAddress() {
        return AddressProvider.getBaseAddress(
                Credential.fromKey(keyHash(1)), Credential.fromKey(keyHash(2)), Networks.testnet()).toBech32();
    }

    private static LedgerTxTranslator.Context context() {
        return new LedgerTxTranslator.Context(0, 1,
                Set.of(TX0 + "#0"), PAYMENT_KEY_HASH, PAYMENT_PATH);
    }

    private static byte[] serialize(TransactionBody body) throws Exception {
        return Transaction.builder().body(body)
                .witnessSet(TransactionWitnessSet.builder().build()).build().serialize();
    }

    private static TransactionBody.TransactionBodyBuilder baseBody() {
        return TransactionBody.builder()
                .inputs(List.of(TransactionInput.builder().transactionId(TX0).index(0).build()))
                .outputs(List.of(new TransactionOutput(testAddress(),
                        Value.builder().coin(BigInteger.valueOf(2_000_000)).build())))
                .fee(BigInteger.valueOf(180_000))
                .ttl(9_999);
    }

    @Test
    void simplePayment_knobsMatchDeviceVerifiedCclEncoding() throws Exception {
        LedgerSignRequest request = LedgerTxTranslator.translate(serialize(baseBody().build()), context());

        assertThat(request.tagCborSets()).isTrue(); // CCL Conway inputs are tag-258 sets (HW-M3)
        assertThat(request.signingMode()).isEqualTo(LedgerCardanoApp.SIGNING_MODE_ORDINARY);
        assertThat(request.inputs()).hasSize(1);
        assertThat(request.inputs().get(0).txHashHex()).isEqualTo(TX0);
        assertThat(request.outputs()).hasSize(1);
        assertThat(request.outputs().get(0).format()).isEqualTo(LedgerTxTranslator.OUTPUT_FORMAT_ARRAY);
        assertThat(request.outputs().get(0).coin()).isEqualTo(BigInteger.valueOf(2_000_000));
        assertThat(request.outputs().get(0).addressBytes())
                .isEqualTo(new Address(testAddress()).getBytes());
        assertThat(request.fee()).isEqualTo(BigInteger.valueOf(180_000));
        assertThat(request.ttl()).isEqualTo(9_999L);
        assertThat(request.signingPaths()).containsExactly(PAYMENT_PATH);
    }

    @Test
    void plutusTx_translatesScriptFieldsAndPicksPlutusMode() throws Exception {
        PlutusData datum = BigIntPlutusData.of(42);
        byte[] refScript = HexUtil.decodeHexString("82015820" + "cc".repeat(32));
        TransactionOutput scriptOutput = new TransactionOutput(
                testAddress(), Value.builder().coin(BigInteger.valueOf(1_500_000)).build(),
                null, datum, refScript);

        TransactionBody body = baseBody()
                .outputs(List.of(scriptOutput))
                .scriptDataHash(keyHash32(7))
                .collateral(List.of(TransactionInput.builder().transactionId("bb".repeat(32)).index(1).build()))
                .requiredSigners(List.of(PAYMENT_KEY_HASH, keyHash(9)))
                .totalCollateral(BigInteger.valueOf(5_000_000))
                .collateralReturn(new TransactionOutput(testAddress(),
                        Value.builder().coin(BigInteger.valueOf(4_000_000)).build()))
                .referenceInputs(List.of(TransactionInput.builder().transactionId("dd".repeat(32)).index(2).build()))
                .build();

        LedgerSignRequest request = LedgerTxTranslator.translate(serialize(body), context());

        assertThat(request.signingMode()).isEqualTo(LedgerCardanoApp.SIGNING_MODE_PLUTUS);
        assertThat(request.scriptDataHash()).isEqualTo(keyHash32(7));
        assertThat(request.collateralInputs()).hasSize(1);
        assertThat(request.collateralInputs().get(0).index()).isEqualTo(1);
        assertThat(request.totalCollateral()).isEqualTo(BigInteger.valueOf(5_000_000));
        assertThat(request.collateralOutput()).isNotNull();
        assertThat(request.collateralOutput().coin()).isEqualTo(BigInteger.valueOf(4_000_000));
        assertThat(request.referenceInputs()).hasSize(1);

        // Required signers: ours becomes the path form, the other stays a hash.
        assertThat(request.requiredSigners()).hasSize(2);
        assertThat(request.requiredSigners().get(0)[0]).isEqualTo((byte) 0x00);
        assertThat(request.requiredSigners().get(1)[0]).isEqualTo((byte) 0x01);

        // The script output is Babbage-map format with the inline datum + ref script.
        LedgerTxOutput out = request.outputs().get(0);
        assertThat(out.format()).isEqualTo(LedgerTxTranslator.OUTPUT_FORMAT_MAP);
        assertThat(out.datum()).isNotNull();
        assertThat(out.datum().type()).isEqualTo(LedgerDatum.TYPE_INLINE);
        assertThat(out.datum().bytes()).isEqualTo(datum.serializeToBytes());
        assertThat(out.referenceScript()).isEqualTo(refScript);
    }

    @Test
    void mintBurn_translatesNegativeAmounts() throws Exception {
        MultiAsset burn = MultiAsset.builder()
                .policyId("ee".repeat(28))
                .assets(List.of(new Asset("0x544f4b", BigInteger.valueOf(-5))))
                .build();
        TransactionBody body = baseBody().mint(List.of(burn)).build();

        LedgerSignRequest request = LedgerTxTranslator.translate(serialize(body), context());

        assertThat(request.mint()).hasSize(1);
        assertThat(request.mint().get(0).tokens().get(0).amount()).isEqualTo(BigInteger.valueOf(-5));
        assertThat(request.signingMode()).isEqualTo(LedgerCardanoApp.SIGNING_MODE_ORDINARY);
    }

    @Test
    void aCertificateOverSomeoneElsesKeyGoesAsAHashCredential() throws Exception {
        // E4. Wire bytes from ledgerjs utils/serialize.ts#serializeCredential:
        // type(0=legacy registration) || credentialType(2=key hash) || hash(28).
        TransactionBody body = baseBody()
                .certs(List.of(new StakeRegistration(StakeCredential.fromKeyHash(keyHash(2)))))
                .build();

        LedgerSignRequest request = LedgerTxTranslator.translate(serialize(body), context());

        assertThat(request.certificates()).hasSize(1);
        byte[] cert = request.certificates().get(0);
        assertThat(cert[0]).as("legacy stake registration").isEqualTo((byte) 0x00);
        assertThat(cert[1]).as("credential type: key hash").isEqualTo((byte) 0x02);
        assertThat(cert).hasSize(2 + 28);
    }

    @Test
    void aScriptCredentialCertificateUsesTheScriptHashType() throws Exception {
        // The CIP-113 shape. script-hash is 1, NOT 2 — the v8 interaction path in
        // ledgerjs numbers the same concept differently, so this is pinned.
        TransactionBody body = baseBody()
                .certs(List.of(new StakeRegistration(StakeCredential.fromScriptHash(keyHash(3)))))
                .build();

        LedgerSignRequest request = LedgerTxTranslator.translate(serialize(body), context());

        byte[] cert = request.certificates().get(0);
        assertThat(cert[1]).as("credential type: script hash").isEqualTo((byte) 0x01);
    }

    @Test
    void anUnknownCertificateTypeIsStillRefusedInPlainLanguage() throws Exception {
        // The translator's contract is unchanged for anything we cannot build
        // exactly: refuse with a reason, never guess at device bytes.
        TransactionBody body = baseBody()
                .certs(List.of(new PoolRetirement(keyHash(4), 300)))
                .build();

        assertThatThrownBy(() -> LedgerTxTranslator.translate(serialize(body), context()))
                .isInstanceOf(LedgerTxTranslator.UnsupportedTxException.class)
                .hasMessageContaining("certificate of type");
    }

    @Test
    void nothingToSign_isRejected() throws Exception {
        byte[] cbor = serialize(baseBody().build());
        LedgerTxTranslator.Context stranger = new LedgerTxTranslator.Context(
                0, 1, Set.of(), keyHash(8), PAYMENT_PATH);

        assertThatThrownBy(() -> LedgerTxTranslator.translate(cbor, stranger))
                .isInstanceOf(LedgerTxTranslator.UnsupportedTxException.class)
                .hasMessageContaining("nothing for this wallet to sign");
    }

    private static byte[] keyHash32(int fill) {
        byte[] hash = new byte[32];
        java.util.Arrays.fill(hash, (byte) fill);
        return hash;
    }
}
