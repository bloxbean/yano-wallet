package com.bloxbean.cardano.yano.wallet.core.wallet;

import com.bloxbean.cardano.yano.wallet.core.config.WalletNetwork;
import com.bloxbean.cardano.yano.wallet.core.vault.VaultSecondFactor;

import java.util.List;
import java.util.Optional;

/**
 * Repository of stored wallets for a single network. Secrets never leave the
 * vault except through {@link #unlock}; every operation that needs key
 * material takes the passphrase instead of a mnemonic.
 */
public interface StoredWalletRepository {
    WalletNetwork network();

    String generateMnemonic();

    StoredWalletCreation createRandomWallet(String name, char[] passphrase);

    StoredWallet importMnemonic(String name, String mnemonic, char[] passphrase);

    /**
     * Derives the next account from an existing seed. The seed's vault is
     * unlocked with the passphrase; the mnemonic never crosses this API.
     */
    StoredWallet createAccount(String seedId, String name, char[] passphrase);

    /**
     * Derives a specific CIP-1852 account of a stored seed — used when adding
     * accounts found by {@link #discoverAccounts} (ADR-037), where the index must
     * be the discovered one and not simply the next free slot.
     *
     * @throws com.bloxbean.cardano.yano.wallet.core.vault.WalletVaultException
     *         if the seed is unknown, the passphrase is wrong, or that account is
     *         already stored
     */
    StoredWallet createAccountAt(String seedId, String name, char[] passphrase, int accountIndex);

    UnlockedWallet unlock(String walletId, char[] passphrase);

    /** Unlocks a factored (ADR-036) wallet, resolving the challenge via the security key. */
    UnlockedWallet unlock(String walletId, char[] passphrase, VaultSecondFactor factor);

    /** The security-key factors a wallet's vault is sealed with (empty = passphrase-only). */
    List<VaultSecondFactor.FactorDescriptor> walletFactors(String walletId);

    /**
     * Seals a passphrase-only wallet with its first security key (ADR-036, opt-in).
     * When {@code passwordless} (ADR-040), the vault opens with the key + PIN alone.
     */
    void enrollFactor(String walletId, char[] passphrase,
                      VaultSecondFactor.FactorDescriptor descriptor, VaultSecondFactor factor,
                      boolean passwordless);

    /** True if the wallet's vault is passwordless (unlock needs only the key + PIN). */
    boolean walletPasswordless(String walletId);

    /** Adds a backup security key (unlock with an already-enrolled key). */
    void addFactor(String walletId, char[] passphrase, VaultSecondFactor unlockFactor,
                   VaultSecondFactor.FactorDescriptor newDescriptor, VaultSecondFactor newFactor);

    /** Removes the security key that unlocks the wallet; the last one returns it to passphrase-only. */
    void removeFactor(String walletId, char[] passphrase, VaultSecondFactor factor);

    /**
     * Registers a watch-only hardware wallet (ADR-034) from a device's
     * account-level extended public key. No vault or passphrase — the seed lives
     * on the device; only public key material is stored.
     */
    StoredWallet addWatchOnlyWallet(String name, String deviceType, int accountIndex, String accountXpubHex);

    /**
     * Registers another account of an already-imported hardware wallet (ADR-037),
     * keeping it in the same {@code seedId} group so the UI shows one card per
     * device. The caller asserts the device identity by choosing the group; the
     * new account inherits the group's device type.
     *
     * @throws com.bloxbean.cardano.yano.wallet.core.vault.WalletVaultException
     *         if the group is unknown or is not a hardware wallet
     */
    StoredWallet addWatchOnlyAccount(String seedId, String name, int accountIndex, String accountXpubHex);

    /**
     * Opens a watch-only (hardware) wallet into a session. No passphrase: address
     * derivation, balance, and history run off the stored account public key.
     * Signing is performed via the device, not this session.
     */
    UnlockedWallet unlockWatchOnly(String walletId);

    Optional<StoredWallet> find(String walletId);

    List<StoredWallet> list();

    /**
     * Finds CIP-1852 accounts of a stored seed that have on-chain history but are
     * not stored yet (ADR-037) — what a restored seed needs, since restore only
     * creates account 0.
     *
     * <p>Scanning stops at the first unknown account with no history (BIP-44 uses
     * an account gap of 1); already-stored accounts are skipped without ending the
     * scan, so results may contain gaps. Nothing is persisted: the caller confirms,
     * then calls {@link #createAccountAt} — <b>not</b> {@link #createAccount},
     * whose next-free-index derivation would silently create a different account
     * than the one the user approved.
     *
     * @param addressUsed probes the chain for an address's history — supplied by
     *                    the caller so this layer stays node-agnostic
     * @param maxAccounts hard stop on how many indexes to probe
     * @param gapLimit    addresses probed per account before calling it unused
     * @throws com.bloxbean.cardano.yano.wallet.core.vault.WalletVaultException
     *         if the seed is unknown, is a hardware wallet (its accounts live on
     *         the device), or the passphrase is wrong
     */
    List<DiscoveredAccount> discoverAccounts(String seedId, char[] passphrase,
                                             java.util.function.Predicate<String> addressUsed,
                                             int maxAccounts, int gapLimit);

    /** An account found on-chain but not stored yet. */
    record DiscoveredAccount(int accountIndex, String baseAddress) {
    }

    default List<StoredWallet> listAccounts(String seedId) {
        return list().stream()
                .filter(wallet -> wallet.seedId().equals(seedId))
                .toList();
    }
}
