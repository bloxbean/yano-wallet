package com.bloxbean.cardano.yano.wallet.core.wallet;

import com.bloxbean.cardano.yano.wallet.core.config.WalletNetwork;
import com.bloxbean.cardano.yano.wallet.core.vault.FileWalletSecretStore;
import com.bloxbean.cardano.yano.wallet.core.vault.WalletVaultException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FileStoredWalletRepositoryTest {
    private static final String MNEMONIC =
            "drive useless envelope shine range ability time copper alarm museum near flee wrist "
                    + "live type device meadow allow churn purity wisdom praise drop code";
    private static final FileWalletSecretStore.Argon2Params TEST_PARAMS =
            new FileWalletSecretStore.Argon2Params(1024, 1, 1);

    @TempDir
    Path tempDir;

    @Test
    void importsMnemonicIntoEncryptedNetworkWalletDirectoryAndUnlocksIt() throws Exception {
        FileStoredWalletRepository repository = repository(WalletNetwork.PREPROD);

        StoredWallet stored = repository.importMnemonic(
                "Preprod test wallet",
                MNEMONIC,
                "passphrase".toCharArray());

        assertThat(stored.networkId()).isEqualTo("preprod");
        assertThat(stored.baseAddress()).startsWith("addr_test");
        assertThat(stored.stakeAddress()).startsWith("stake_test");
        assertThat(stored.drepId()).isNotBlank();
        assertThat(repository.list()).containsExactly(stored);

        Path networkDir = tempDir.resolve("preprod").resolve("wallets");
        String indexJson = Files.readString(networkDir.resolve("index.json"));
        String vaultJson = Files.readString(networkDir.resolve(stored.vaultFile()));
        assertThat(indexJson).contains("Preprod test wallet");
        assertThat(indexJson).doesNotContain(MNEMONIC);
        assertThat(vaultJson).doesNotContain(MNEMONIC);
        assertThat(vaultJson).contains("ciphertext");
        assertThat(vaultJson).contains("argon2id");

        UnlockedWallet unlocked = repository.unlock(stored.id(), "passphrase".toCharArray());
        assertThat(unlocked.profile()).isEqualTo(stored);
        assertThat(unlocked.wallet().getBaseAddressString(0)).isEqualTo(stored.baseAddress());
    }

    @Test
    void buildsMultiAddressAccountViewWithStakeAddressAndDrepId() {
        FileStoredWalletRepository repository = repository(WalletNetwork.PREPROD);
        StoredWallet stored = repository.importMnemonic("Wallet", MNEMONIC, "passphrase".toCharArray());
        UnlockedWallet unlocked = repository.unlock(stored.id(), "passphrase".toCharArray());

        WalletAccountView view = new WalletAddressService().accountView(stored, unlocked.wallet(), 5);

        assertThat(view.stakeAddress()).isEqualTo(stored.stakeAddress());
        assertThat(view.drepId()).isEqualTo(stored.drepId());
        assertThat(view.receiveAddresses()).hasSize(5);
        assertThat(view.receiveAddresses())
                .extracting(WalletAddressView::addressIndex)
                .containsExactly(0, 1, 2, 3, 4);
        assertThat(view.receiveAddresses())
                .extracting(WalletAddressView::role)
                .containsOnly("receive");
        assertThat(view.receiveAddresses())
                .extracting(WalletAddressView::derivationPath)
                .containsExactly(
                        "m/1852'/1815'/0'/0/0",
                        "m/1852'/1815'/0'/0/1",
                        "m/1852'/1815'/0'/0/2",
                        "m/1852'/1815'/0'/0/3",
                        "m/1852'/1815'/0'/0/4");
        assertThat(view.receiveAddresses())
                .extracting(WalletAddressView::baseAddress)
                .allSatisfy(address -> assertThat(address).startsWith("addr_test"));
        assertThat(view.receiveAddresses())
                .extracting(WalletAddressView::enterpriseAddress)
                .allSatisfy(address -> assertThat(address).startsWith("addr_test"));
    }

    @Test
    void createsAdditionalAccountUnderSameEncryptedWalletSeed() throws Exception {
        FileStoredWalletRepository repository = repository(WalletNetwork.PREPROD);
        StoredWallet account0 = repository.importMnemonic("Wallet", MNEMONIC, "passphrase".toCharArray());

        StoredWallet account1 = repository.createAccount(account0.seedId(), "Trading", "passphrase".toCharArray());

        assertThat(account0.seedId()).isEqualTo(account0.id());
        assertThat(account1.seedId()).isEqualTo(account0.seedId());
        assertThat(account1.accountIndex()).isEqualTo(1);
        assertThat(account1.vaultFile()).isEqualTo(account0.vaultFile());
        assertThat(account1.baseAddress()).isNotEqualTo(account0.baseAddress());
        assertThat(repository.listAccounts(account0.seedId()))
                .extracting(StoredWallet::accountIndex)
                .containsExactly(0, 1);

        UnlockedWallet unlocked = repository.unlock(account1.id(), "passphrase".toCharArray());
        assertThat(unlocked.profile()).isEqualTo(account1);
        assertThat(unlocked.wallet().getAccountNo()).isEqualTo(1);
        assertThat(unlocked.wallet().getBaseAddressString(0)).isEqualTo(account1.baseAddress());

        Path networkDir = tempDir.resolve("preprod").resolve("wallets");
        String vaultJson = Files.readString(networkDir.resolve(account1.vaultFile()));
        assertThat(vaultJson).doesNotContain(MNEMONIC);
    }

    @Test
    void eachAccountHasItsOwnStakeKeyAndDrepIdentity() {
        // Accounts are independent wallets, not just extra addresses: staking,
        // rewards and governance all key off per-account credentials (ADR-037).
        FileStoredWalletRepository repository = repository(WalletNetwork.PREPROD);
        StoredWallet account0 = repository.importMnemonic("Wallet", MNEMONIC, "passphrase".toCharArray());

        StoredWallet account1 = repository.createAccount(account0.seedId(), "Trading", "passphrase".toCharArray());

        assertThat(account1.stakeAddress()).startsWith("stake_test")
                .isNotEqualTo(account0.stakeAddress());
        assertThat(account1.drepId()).isNotBlank().isNotEqualTo(account0.drepId());
    }

    @Test
    void discoversAccountsWithHistoryAndStopsAtTheFirstEmptyOne() {
        // A restored seed stores only account 0; discovery finds the rest (ADR-037).
        FileStoredWalletRepository repository = repository(WalletNetwork.PREPROD);
        StoredWallet account0 = repository.importMnemonic("Wallet", MNEMONIC, "passphrase".toCharArray());

        // Chain state: accounts 0..2 used, 3 empty, 4 used (must NOT be reached —
        // BIP-44 stops at the first empty account).
        Set<String> usedAddresses = new HashSet<>();
        for (int account : new int[]{0, 1, 2, 4}) {
            usedAddresses.add(baseAddress(account, 0));
        }

        List<StoredWalletRepository.DiscoveredAccount> found = repository.discoverAccounts(
                account0.seedId(), "passphrase".toCharArray(), usedAddresses::contains, 20, 20);

        // Account 0 is already stored, so only 1 and 2 are offered.
        assertThat(found).extracting(StoredWalletRepository.DiscoveredAccount::accountIndex)
                .containsExactly(1, 2);
        assertThat(found.get(0).baseAddress()).isEqualTo(baseAddress(1, 0));
        assertThat(repository.listAccounts(account0.seedId())).hasSize(1); // discovery persists nothing
    }

    @Test
    void discoversAnAccountWhoseHistoryStartsBeyondItsFirstAddress() {
        // Account 1's first address is unused but address 3 has history — the gap
        // window must find it, otherwise the account is silently lost.
        FileStoredWalletRepository repository = repository(WalletNetwork.PREPROD);
        StoredWallet account0 = repository.importMnemonic("Wallet", MNEMONIC, "passphrase".toCharArray());
        Set<String> usedAddresses = Set.of(baseAddress(0, 0), baseAddress(1, 3));

        List<StoredWalletRepository.DiscoveredAccount> found = repository.discoverAccounts(
                account0.seedId(), "passphrase".toCharArray(), usedAddresses::contains, 20, 20);

        assertThat(found).extracting(StoredWalletRepository.DiscoveredAccount::accountIndex)
                .containsExactly(1);
    }

    @Test
    void discoveryFindsNothingForAnUnusedSeedAndRejectsWrongPassphrase() {
        FileStoredWalletRepository repository = repository(WalletNetwork.PREPROD);
        StoredWallet account0 = repository.importMnemonic("Wallet", MNEMONIC, "right".toCharArray());

        assertThat(repository.discoverAccounts(
                account0.seedId(), "right".toCharArray(), address -> false, 20, 20)).isEmpty();

        assertThatThrownBy(() -> repository.discoverAccounts(
                account0.seedId(), "wrong".toCharArray(), address -> true, 20, 20))
                .isInstanceOf(WalletVaultException.class);
    }

    @Test
    void createAccountAtDerivesTheRequestedIndexNotTheNextFreeOne() {
        // Adding only SOME discovered accounts must still derive the approved
        // ones: with account 0 stored, adding discovered account 3 must be
        // account 3 — "next free index" would silently create account 1.
        FileStoredWalletRepository repository = repository(WalletNetwork.PREPROD);
        StoredWallet account0 = repository.importMnemonic("Wallet", MNEMONIC, "passphrase".toCharArray());

        StoredWallet account3 = repository.createAccountAt(
                account0.seedId(), "Account 3", "passphrase".toCharArray(), 3);

        assertThat(account3.accountIndex()).isEqualTo(3);
        assertThat(account3.baseAddress()).isEqualTo(baseAddress(3, 0));
        assertThat(account3.seedId()).isEqualTo(account0.seedId());
    }

    @Test
    void createAccountAtRejectsAnAccountAlreadyStored() {
        FileStoredWalletRepository repository = repository(WalletNetwork.PREPROD);
        StoredWallet account0 = repository.importMnemonic("Wallet", MNEMONIC, "passphrase".toCharArray());

        assertThatThrownBy(() -> repository.createAccountAt(
                account0.seedId(), "Duplicate", "passphrase".toCharArray(), 0))
                .isInstanceOf(WalletVaultException.class);
    }

    @Test
    void discoveryContinuesPastAStoredAccountThatHasNoHistory() {
        // The restore case this feature exists for: the seed was only ever used at
        // account 1, so the freshly restored account 0 is empty. Stopping there
        // would report "no accounts found" and strand the funds.
        FileStoredWalletRepository repository = repository(WalletNetwork.PREPROD);
        StoredWallet account0 = repository.importMnemonic("Wallet", MNEMONIC, "passphrase".toCharArray());
        Set<String> usedAddresses = Set.of(baseAddress(1, 0));

        List<StoredWalletRepository.DiscoveredAccount> found = repository.discoverAccounts(
                account0.seedId(), "passphrase".toCharArray(), usedAddresses::contains, 20, 20);

        assertThat(found).extracting(StoredWalletRepository.DiscoveredAccount::accountIndex)
                .containsExactly(1);
    }

    @Test
    void discoveryResultsSkipStoredAccountsAndMayContainGaps() {
        // Results are NOT contiguous: stored accounts are filtered out of the
        // results. Callers must add each found account at ITS index
        // (createAccountAt), never at "the next free one".
        FileStoredWalletRepository repository = repository(WalletNetwork.PREPROD);
        StoredWallet account0 = repository.importMnemonic("Wallet", MNEMONIC, "passphrase".toCharArray());
        StoredWallet account1 = repository.createAccount(account0.seedId(), "One", "passphrase".toCharArray());
        assertThat(account1.accountIndex()).isEqualTo(1);

        Set<String> usedAddresses = Set.of(
                baseAddress(0, 0), baseAddress(1, 0), baseAddress(2, 0), baseAddress(3, 0));

        List<StoredWalletRepository.DiscoveredAccount> found = repository.discoverAccounts(
                account0.seedId(), "passphrase".toCharArray(), usedAddresses::contains, 20, 20);

        // 0 and 1 stored -> skipped; 2 and 3 offered. Adding only account 3 must
        // derive account 3, which is why createAccountAt exists.
        assertThat(found).extracting(StoredWalletRepository.DiscoveredAccount::accountIndex)
                .containsExactly(2, 3);
    }

    /** The address a given account/address index derives to, straight from CCL. */
    private static String baseAddress(int accountIndex, int addressIndex) {
        return com.bloxbean.cardano.hdwallet.Wallet
                .createFromMnemonic(WalletNetwork.PREPROD.toCclNetwork(), MNEMONIC, accountIndex)
                .getBaseAddressString(addressIndex);
    }

    @Test
    void createAccountRequiresCorrectPassphrase() {
        FileStoredWalletRepository repository = repository(WalletNetwork.PREPROD);
        StoredWallet account0 = repository.importMnemonic("Wallet", MNEMONIC, "right".toCharArray());

        assertThatThrownBy(() -> repository.createAccount(account0.seedId(), "Trading", "wrong".toCharArray()))
                .isInstanceOf(WalletVaultException.class);
        assertThat(repository.listAccounts(account0.seedId())).hasSize(1);
    }

    @Test
    void rejectsDuplicateWalletForSameNetwork() {
        FileStoredWalletRepository repository = repository(WalletNetwork.PREPROD);
        repository.importMnemonic("One", MNEMONIC, "passphrase".toCharArray());

        assertThatThrownBy(() -> repository.importMnemonic("Two", MNEMONIC, "passphrase".toCharArray()))
                .isInstanceOf(WalletVaultException.class)
                .hasMessageContaining("Wallet already exists");
    }

    @Test
    void separatesWalletsByNetworkDirectory() {
        FileStoredWalletRepository preprod = repository(WalletNetwork.PREPROD);
        FileStoredWalletRepository preview = repository(WalletNetwork.PREVIEW);

        StoredWallet preprodWallet = preprod.importMnemonic("Preprod", MNEMONIC, "passphrase".toCharArray());
        StoredWallet previewWallet = preview.importMnemonic("Preview", MNEMONIC, "passphrase".toCharArray());

        assertThat(preprod.network()).isEqualTo(WalletNetwork.PREPROD);
        assertThat(preprodWallet.networkId()).isEqualTo("preprod");
        assertThat(previewWallet.networkId()).isEqualTo("preview");
        assertThat(Files.exists(tempDir.resolve("preprod").resolve("wallets").resolve("index.json"))).isTrue();
        assertThat(Files.exists(tempDir.resolve("preview").resolve("wallets").resolve("index.json"))).isTrue();
    }

    @Test
    void createsRandomWalletAndReturnsMnemonicForUserBackup() {
        FileStoredWalletRepository repository = repository(WalletNetwork.PREPROD);

        StoredWalletCreation created = repository.createRandomWallet(
                "Generated wallet",
                "passphrase".toCharArray());

        assertThat(created.mnemonic().split("\\s+")).hasSize(24);
        assertThat(created.wallet().name()).isEqualTo("Generated wallet");
        assertThat(repository.unlock(created.wallet().id(), "passphrase".toCharArray())
                .wallet()
                .getBaseAddressString(0)).isEqualTo(created.wallet().baseAddress());
    }

    @Test
    void wrongPassphraseDoesNotUnlockStoredWallet() {
        FileStoredWalletRepository repository = repository(WalletNetwork.PREPROD);
        StoredWallet stored = repository.importMnemonic("Wallet", MNEMONIC, "right".toCharArray());

        assertThatThrownBy(() -> repository.unlock(stored.id(), "wrong".toCharArray()))
                .isInstanceOf(WalletVaultException.class);
    }

    private FileStoredWalletRepository repository(WalletNetwork network) {
        return new FileStoredWalletRepository(tempDir.resolve(network.id()), network, new SecureRandom(), TEST_PARAMS);
    }
}
