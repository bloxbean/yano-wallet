package com.bloxbean.cardano.yano.wallet.app;

import com.bloxbean.cardano.client.crypto.bip32.Bip32Type;
import com.bloxbean.cardano.client.crypto.bip32.HdKeyGenerator;
import com.bloxbean.cardano.client.crypto.bip32.HdKeyPair;
import com.bloxbean.cardano.client.crypto.bip32.key.HdPublicKey;
import com.bloxbean.cardano.client.transaction.spec.TransactionWitnessSet;
import com.bloxbean.cardano.client.transaction.spec.VkeyWitness;
import com.bloxbean.cardano.client.util.HexUtil;
import com.bloxbean.cardano.yano.wallet.hardware.ledger.LedgerBip32;
import com.bloxbean.cardano.yano.wallet.hardware.ledger.LedgerWitness;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The witness set built from device signatures must carry the correct public key
 * for each signing path (ADR-034). Checked against ledgerjs Speculos-seed
 * vectors: payment {@code .../0/1} → {@code b3d5f4…}, stake {@code .../2/0} →
 * {@code 66610e…}.
 */
class HardwareSigningTest {

    private static final String MNEMONIC =
            "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about";
    private static final String PAYMENT_PK_0_1 =
            "b3d5f4158f0c391ee2a28a2e285f218f3e895ff6ff59cb9369c64b03b5bab5eb";
    private static final String STAKE_PK_2_0 =
            "66610efd336e1137c525937b76511fbcf2a0e6bcf0d340a67bcb39bc870d85e8";

    @Test
    void witnessSet_mapsPaymentAndStakePathsToTheirPublicKeys() {
        String accountXpubHex = HexUtil.encodeHexString(ledgerAccountXpub());
        byte[] fakeSig = new byte[64];

        List<LedgerWitness> witnesses = List.of(
                new LedgerWitness(LedgerBip32.paymentPath(0, 0, 1), fakeSig),
                new LedgerWitness(LedgerBip32.stakePath(0), fakeSig));

        TransactionWitnessSet set = HardwareSigning.witnessSet(accountXpubHex, witnesses);
        List<VkeyWitness> vkeys = set.getVkeyWitnesses();

        assertThat(vkeys).hasSize(2);
        assertThat(HexUtil.encodeHexString(vkeys.get(0).getVkey())).isEqualTo(PAYMENT_PK_0_1);
        assertThat(HexUtil.encodeHexString(vkeys.get(1).getVkey())).isEqualTo(STAKE_PK_2_0);
    }

    private static byte[] ledgerAccountXpub() {
        HdKeyGenerator generator = new HdKeyGenerator();
        HdKeyPair root = generator.getRootKeyPairFromMnemonic(MNEMONIC, Bip32Type.LEDGER);
        HdKeyPair account = generator.getChildKeyPair(
                generator.getChildKeyPair(generator.getChildKeyPair(root, 1852L, true), 1815L, true), 0L, true);
        HdPublicKey pub = account.getPublicKey();
        byte[] xpub = new byte[64];
        System.arraycopy(pub.getKeyData(), 0, xpub, 0, 32);
        System.arraycopy(pub.getChainCode(), 0, xpub, 32, 32);
        return xpub;
    }
}
