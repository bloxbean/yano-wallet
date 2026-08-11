package com.bloxbean.cardano.yano.wallet.core.vault;

import java.util.List;

public interface WalletSecretStore {
    boolean exists();

    void create(WalletSecret secret, char[] passphrase);

    WalletSecret unlock(char[] passphrase);

    void lock();

    void rotatePassphrase(char[] oldPassphrase, char[] newPassphrase);

    /**
     * The hardware second factors this vault is sealed with (ADR-036), one per
     * enrolled key — empty for a passphrase-only vault. Each is a "key slot": any
     * one of them, plus the passphrase, unlocks the vault (backup keys).
     */
    List<VaultSecondFactor.FactorDescriptor> factorDescriptors();

    /** The first enrolled factor, or {@code null} — convenience for "is this factored?". */
    default VaultSecondFactor.FactorDescriptor factorDescriptor() {
        List<VaultSecondFactor.FactorDescriptor> all = factorDescriptors();
        return all.isEmpty() ? null : all.get(0);
    }

    /**
     * Unlocks a vault, resolving a factored vault's challenge through {@code
     * factor}. For a passphrase-only vault the factor is ignored.
     */
    WalletSecret unlock(char[] passphrase, VaultSecondFactor factor);

    /**
     * Seals a passphrase-only vault with its first hardware factor (opt-in). When
     * {@code passwordless} (ADR-040), the vault key derives from the security key
     * alone (no passphrase) — which requires the key's PIN. The 24-word recovery
     * phrase remains the only backup if the key is lost — the caller MUST warn.
     */
    void enrollFactor(char[] passphrase, VaultSecondFactor.FactorDescriptor descriptor,
                      VaultSecondFactor factor, boolean passwordless);

    /** Passphrase + key (not passwordless). */
    default void enrollFactor(char[] passphrase, VaultSecondFactor.FactorDescriptor descriptor,
                              VaultSecondFactor factor) {
        enrollFactor(passphrase, descriptor, factor, false);
    }

    /**
     * Adds a backup factor to an already-factored vault. {@code unlockFactor} is
     * an already-enrolled key (present to unlock the vault now); {@code newFactor}
     * is the key being added, which can then unlock independently.
     */
    void addFactor(char[] passphrase, VaultSecondFactor unlockFactor,
                   VaultSecondFactor.FactorDescriptor newDescriptor, VaultSecondFactor newFactor);

    /**
     * Removes the factor whose key unlocks the vault. Removing the last factor
     * returns the vault to passphrase-only.
     */
    void removeFactor(char[] passphrase, VaultSecondFactor factor);
}
