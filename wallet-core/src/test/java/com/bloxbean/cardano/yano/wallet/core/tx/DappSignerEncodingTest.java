package com.bloxbean.cardano.yano.wallet.core.tx;

import com.bloxbean.cardano.client.account.Account;
import com.bloxbean.cardano.client.common.cbor.CborSerializationUtil;
import com.bloxbean.cardano.client.common.model.Networks;
import com.bloxbean.cardano.client.crypto.config.CryptoConfiguration;
import com.bloxbean.cardano.client.transaction.spec.TransactionWitnessSet;
import com.bloxbean.cardano.client.transaction.spec.VkeyWitness;
import com.bloxbean.cardano.client.transaction.util.TransactionUtil;
import com.bloxbean.cardano.client.util.HexUtil;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * That the signature covers the transaction the dApp will actually submit.
 *
 * <p>A wallet that signs a re-encoding of the transaction produces a witness that
 * looks perfectly well formed and fails only at the node, as
 * {@code InvalidSignaturesInWitnesses} — which the dApp shows as "sign and submit
 * failed". So these tests verify the signature cryptographically against the hash
 * of the ORIGINAL bytes, rather than checking that signing merely returned
 * something.
 *
 * <p>The fixture is the transaction that exposed it on 2026-08-13: its datum
 * contains an indefinite-length CBOR map ({@code bf … ff}), which
 * cardano-client-lib re-encodes as definite-length ({@code a3 …}) — one byte
 * shorter, so the body hash changes and the signature stops matching.
 */
class DappSignerEncodingTest {

    private static final String MNEMONIC =
            "test walk nut penalty hip pave soap entry language right filter choice";

    private static Account account() {
        return new Account(Networks.testnet(), MNEMONIC);
    }

    private static void assertWitnessesVerifyAgainst(String txHex) {
        byte[] txBytes = HexUtil.decodeHexString(txHex);
        byte[] txHash = HexUtil.decodeHexString(TransactionUtil.getTxHash(txBytes));

        String witnessSetHex = DappSigner.witnessSetHex(account(), txHex, true);
        TransactionWitnessSet ws;
        try {
            ws = TransactionWitnessSet.deserialize((co.nstant.in.cbor.model.Map)
                    CborSerializationUtil.deserialize(HexUtil.decodeHexString(witnessSetHex)));
        } catch (Exception e) {
            throw new AssertionError("unreadable witness set", e);
        }

        assertThat(ws.getVkeyWitnesses()).isNotEmpty();
        for (VkeyWitness w : ws.getVkeyWitnesses()) {
            boolean ok = CryptoConfiguration.INSTANCE.getSigningProvider()
                    .verify(w.getSignature(), txHash, w.getVkey());
            assertThat(ok)
                    .as("signature by %s must verify against the ORIGINAL body hash",
                            HexUtil.encodeHexString(w.getVkey()))
                    .isTrue();
        }
    }

    @Test
    void aDatumWithAnIndefiniteLengthMapStillGetsAValidSignature() {
        // The regression. Signing a re-encoding of this transaction yields a
        // signature over a body one byte shorter than the one submitted, and the
        // node answers InvalidSignaturesInWitnesses.
        assertWitnessesVerifyAgainst(Cip113Fixtures.REG_TX);
    }

    @Test
    void theSetupTransactionInTheSameFlowAlsoVerifies() {
        // This one round-tripped cleanly and so went through even while the wallet
        // was signing re-encoded bytes — which is exactly why the failure looked
        // like it belonged to the second transaction.
        assertWitnessesVerifyAgainst(Cip113Fixtures.INIT_TX);
    }

    @Test
    void anOrdinaryPaymentVerifies() {
        assertWitnessesVerifyAgainst(Cip113Fixtures.PLAIN_TX);
    }

    @Test
    void signingLeavesTheTransactionHashUnchanged() {
        // The property underneath all of the above: whatever the dApp encoded is
        // what gets signed, so the id it computes is the id the node computes.
        String before = TransactionUtil.getTxHash(HexUtil.decodeHexString(Cip113Fixtures.REG_TX));

        DappSigner.witnessSetHex(account(), Cip113Fixtures.REG_TX, true);

        assertThat(TransactionUtil.getTxHash(HexUtil.decodeHexString(Cip113Fixtures.REG_TX)))
                .isEqualTo(before);
    }
}
