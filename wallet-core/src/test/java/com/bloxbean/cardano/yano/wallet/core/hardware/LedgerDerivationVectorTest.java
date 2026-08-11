package com.bloxbean.cardano.yano.wallet.core.hardware;

import com.bloxbean.cardano.client.address.AddressProvider;
import com.bloxbean.cardano.client.crypto.bip32.Bip32Type;
import com.bloxbean.cardano.client.crypto.bip32.HdKeyGenerator;
import com.bloxbean.cardano.client.crypto.bip32.HdKeyPair;
import com.bloxbean.cardano.client.crypto.bip32.key.HdPublicKey;
import com.bloxbean.cardano.client.crypto.cip1852.CIP1852;
import com.bloxbean.cardano.client.util.HexUtil;
import com.bloxbean.cardano.yano.wallet.core.config.WalletNetwork;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Cross-checks our watch-only derivation against the canonical
 * {@code ledgerjs-hw-app-cardano} test vectors (ADR-034): the same values a
 * physical Ledger produces for the Speculos test seed. This guarantees the
 * address our wallet derives from a device's account key equals the address the
 * device itself shows — the whole point of hardware support.
 *
 * <p>Vectors are for the BIP-39 seed
 * {@code abandon abandon ... about} (all-zero entropy) that ledgerjs uses:
 * <ul>
 *   <li>{@code 1852'/1815'/0'/0/1} public key {@code b3d5f4...}</li>
 *   <li>{@code 1852'/1815'/0'/2/0} public key {@code 66610e...}</li>
 *   <li>base address of the two → {@code addr1qdd9x...} / {@code addr_test1qpd9x...}</li>
 * </ul>
 */
class LedgerDerivationVectorTest {

    private static final String MNEMONIC =
            "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about";

    private static final String PAYMENT_PK_0_1 =
            "b3d5f4158f0c391ee2a28a2e285f218f3e895ff6ff59cb9369c64b03b5bab5eb";
    private static final String STAKE_PK_2_0 =
            "66610efd336e1137c525937b76511fbcf2a0e6bcf0d340a67bcb39bc870d85e8";
    // ledgerjs Networks.Testnet == networkId 0, matching our PREPROD/PREVIEW
    // address network tag (HRP "addr_test"). Mainnet uses the identical key
    // hashes, differing only in the header's network nibble.
    private static final String ADDR_TESTNET =
            "addr_test1qpd9xypc9xnnstp2kas3r7mf7ylxn4sksfxxypvwgnc63vcayfawlf9hwv2fzuygt2km5v92kvf8e3s3mk7ynxw77cwq9nnhk4";

    @Test
    void addressConstruction_fromDevicePublicKeys_matchesLedger() {
        // Given the raw keys a device derives, our address must match the device's.
        HdPublicKey payment = HdPublicKey.fromBytes(padTo64(HexUtil.decodeHexString(PAYMENT_PK_0_1)));
        HdPublicKey stake = HdPublicKey.fromBytes(padTo64(HexUtil.decodeHexString(STAKE_PK_2_0)));

        assertThat(AddressProvider.getBaseAddress(payment, stake, WalletNetwork.PREPROD.toCclNetwork()).toBech32())
                .isEqualTo(ADDR_TESTNET);
        assertThat(AddressProvider.getBaseAddress(payment, stake, WalletNetwork.MAINNET.toCclNetwork()).toBech32())
                .startsWith("addr1");
    }

    @Test
    void watchOnlyChildDerivation_fromLedgerAccountKey_matchesLedgerChildKeys() {
        byte[] accountXpub = ledgerAccountXpub();
        CIP1852 cip1852 = new CIP1852();

        assertThat(HexUtil.encodeHexString(cip1852.getPublicKeyFromAccountPubKey(accountXpub, 0, 1).getKeyData()))
                .isEqualTo(PAYMENT_PK_0_1);
        assertThat(HexUtil.encodeHexString(cip1852.getPublicKeyFromAccountPubKey(accountXpub, 2, 0).getKeyData()))
                .isEqualTo(STAKE_PK_2_0);
    }

    @Test
    void deviceAddressService_derivesLedgerAddress() {
        DeviceKeystore keystore = new DeviceKeystore(DeviceType.LEDGER, 0,
                HexUtil.encodeHexString(ledgerAccountXpub()), null);
        DeviceAddressService service = new DeviceAddressService();

        // ledgerjs base-address vector uses payment 0/1 + stake 2/0 on testnet.
        assertThat(service.receiveAddress(keystore, WalletNetwork.PREPROD, 1)).isEqualTo(ADDR_TESTNET);
        assertThat(service.receiveAddress(keystore, WalletNetwork.MAINNET, 1)).startsWith("addr1");
    }

    @Test
    void icarusScheme_producesDifferentKeys_soSchemeMatters() {
        // The historical Cardano-on-Ledger pitfall: a Ledger derives its ROOT key
        // with the CIP-3 LEDGER scheme, not Icarus. So a Ledger account key differs
        // from an Icarus one for the same mnemonic — reproducing a Ledger in
        // software needs Bip32Type.LEDGER. (Our hardware path avoids this entirely:
        // the account key comes from the device already scheme-correct.)
        byte[] icarusAccountXpub = accountXpub(Bip32Type.ICARUS);
        String icarusChild = HexUtil.encodeHexString(
                new CIP1852().getPublicKeyFromAccountPubKey(icarusAccountXpub, 0, 1).getKeyData());

        assertThat(icarusChild).isNotEqualTo(PAYMENT_PK_0_1);
    }

    /** Account key the Ledger exports for the test seed (LEDGER master-key scheme). */
    private static byte[] ledgerAccountXpub() {
        return accountXpub(Bip32Type.LEDGER);
    }

    private static byte[] accountXpub(Bip32Type scheme) {
        HdKeyGenerator generator = new HdKeyGenerator();
        HdKeyPair root = generator.getRootKeyPairFromMnemonic(MNEMONIC, scheme);
        HdKeyPair purpose = generator.getChildKeyPair(root, 1852L, true);
        HdKeyPair coin = generator.getChildKeyPair(purpose, 1815L, true);
        HdKeyPair account = generator.getChildKeyPair(coin, 0L, true);
        HdPublicKey pub = account.getPublicKey();
        byte[] xpub = new byte[64];
        System.arraycopy(pub.getKeyData(), 0, xpub, 0, 32);
        System.arraycopy(pub.getChainCode(), 0, xpub, 32, 32);
        return xpub;
    }

    private static byte[] padTo64(byte[] publicKey32) {
        byte[] out = new byte[64];
        System.arraycopy(publicKey32, 0, out, 0, Math.min(32, publicKey32.length));
        return out;
    }
}
