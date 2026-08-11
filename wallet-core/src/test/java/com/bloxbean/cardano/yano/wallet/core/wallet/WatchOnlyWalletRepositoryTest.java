package com.bloxbean.cardano.yano.wallet.core.wallet;

import com.bloxbean.cardano.client.crypto.bip32.Bip32Type;
import com.bloxbean.cardano.client.crypto.bip32.HdKeyGenerator;
import com.bloxbean.cardano.client.crypto.bip32.HdKeyPair;
import com.bloxbean.cardano.client.util.HexUtil;
import com.bloxbean.cardano.yano.wallet.core.config.WalletNetwork;
import com.bloxbean.cardano.yano.wallet.core.vault.WalletVaultException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A watch-only hardware wallet (ADR-034) must derive exactly the addresses the
 * device produces. Built from the ledgerjs Speculos-seed account key, its
 * receive address at index 1 must equal the canonical ledgerjs testnet vector.
 */
class WatchOnlyWalletRepositoryTest {

    private static final String MNEMONIC =
            "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about";
    // ledgerjs deriveAddress vector: base(1852'/1815'/0'/0/1, 1852'/1815'/0'/2/0), Networks.Testnet.
    private static final String ADDR_TESTNET_INDEX1 =
            "addr_test1qpd9xypc9xnnstp2kas3r7mf7ylxn4sksfxxypvwgnc63vcayfawlf9hwv2fzuygt2km5v92kvf8e3s3mk7ynxw77cwq9nnhk4";

    @Test
    void watchOnlyWallet_derivesDeviceMatchingAddress_andPersists(@TempDir Path dir) {
        String accountXpubHex = HexUtil.encodeHexString(ledgerAccountXpub());
        FileStoredWalletRepository repository = new FileStoredWalletRepository(dir, WalletNetwork.PREPROD);

        StoredWallet profile = repository.addWatchOnlyWallet("My Ledger", "LEDGER", 0, accountXpubHex);
        assertThat(profile.isHardware()).isTrue();
        assertThat(profile.vaultFile()).isNull();
        assertThat(profile.accountXpubHex()).isEqualTo(accountXpubHex);

        UnlockedWallet unlocked = repository.unlockWatchOnly(profile.id());
        assertThat(unlocked.wallet().getBaseAddressString(1)).isEqualTo(ADDR_TESTNET_INDEX1);

        // Round-trips through the on-disk index, still flagged as hardware.
        StoredWallet reread = repository.find(profile.id()).orElseThrow();
        assertThat(reread.isHardware()).isTrue();
        assertThat(reread.accountXpubHex()).isEqualTo(accountXpubHex);
        assertThat(repository.list()).hasSize(1);
    }

    @Test
    void watchOnlyAccount_joinsTheDevicesExistingSeedGroup(@TempDir Path dir) {
        // Two accounts of ONE device must group under one card (ADR-037), unlike
        // two separate imports which each start their own group.
        FileStoredWalletRepository repository = new FileStoredWalletRepository(dir, WalletNetwork.PREPROD);
        StoredWallet account0 = repository.addWatchOnlyWallet(
                "My Ledger", "LEDGER", 0, HexUtil.encodeHexString(ledgerAccountXpub(0)));

        StoredWallet account1 = repository.addWatchOnlyAccount(
                account0.seedId(), "Trading", 1, HexUtil.encodeHexString(ledgerAccountXpub(1)));

        assertThat(account1.seedId()).isEqualTo(account0.seedId());
        assertThat(account1.id()).isNotEqualTo(account0.id());
        assertThat(account1.accountIndex()).isEqualTo(1);
        assertThat(account1.deviceType()).isEqualTo("LEDGER"); // inherited from the group
        assertThat(account1.isHardware()).isTrue();
        assertThat(account1.baseAddress()).isNotEqualTo(account0.baseAddress());
        assertThat(account1.stakeAddress()).isNotEqualTo(account0.stakeAddress());
        assertThat(repository.listAccounts(account0.seedId()))
                .extracting(StoredWallet::accountIndex).containsExactly(0, 1);
    }

    @Test
    void watchOnlyAccount_rejectsUnknownGroupAndSoftwareWallets(@TempDir Path dir) {
        FileStoredWalletRepository repository = new FileStoredWalletRepository(dir, WalletNetwork.PREPROD);
        String xpub = HexUtil.encodeHexString(ledgerAccountXpub(1));

        assertThatThrownBy(() -> repository.addWatchOnlyAccount("no-such-seed", "X", 1, xpub))
                .isInstanceOf(WalletVaultException.class)
                .hasMessageContaining("Wallet not found");

        StoredWallet software = repository.importMnemonic("Software", MNEMONIC, "passphrase".toCharArray());
        assertThatThrownBy(() -> repository.addWatchOnlyAccount(software.seedId(), "X", 1, xpub))
                .isInstanceOf(WalletVaultException.class)
                .hasMessageContaining("Not a hardware wallet");
    }

    @Test
    void watchOnlyAccount_rejectsReimportingAnAccountAlreadyStored(@TempDir Path dir) {
        FileStoredWalletRepository repository = new FileStoredWalletRepository(dir, WalletNetwork.PREPROD);
        StoredWallet account0 = repository.addWatchOnlyWallet(
                "My Ledger", "LEDGER", 0, HexUtil.encodeHexString(ledgerAccountXpub(0)));

        // Same xpub/index again — the dedup guard is by derived address.
        assertThatThrownBy(() -> repository.addWatchOnlyAccount(
                account0.seedId(), "Dup", 0, HexUtil.encodeHexString(ledgerAccountXpub(0))))
                .isInstanceOf(WalletVaultException.class)
                .hasMessageContaining("already exists");
    }

    private static byte[] ledgerAccountXpub() {
        return ledgerAccountXpub(0);
    }

    private static byte[] ledgerAccountXpub(int accountIndex) {
        HdKeyGenerator generator = new HdKeyGenerator();
        HdKeyPair root = generator.getRootKeyPairFromMnemonic(MNEMONIC, Bip32Type.LEDGER);
        HdKeyPair account = generator.getChildKeyPair(
                generator.getChildKeyPair(generator.getChildKeyPair(root, 1852L, true), 1815L, true),
                (long) accountIndex, true);
        byte[] xpub = new byte[64];
        System.arraycopy(account.getPublicKey().getKeyData(), 0, xpub, 0, 32);
        System.arraycopy(account.getPublicKey().getChainCode(), 0, xpub, 32, 32);
        return xpub;
    }
}
