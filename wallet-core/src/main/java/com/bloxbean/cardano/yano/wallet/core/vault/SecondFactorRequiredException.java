package com.bloxbean.cardano.yano.wallet.core.vault;

/**
 * Thrown when a v3 vault (ADR-036) is opened without its hardware second factor.
 * The app layer catches this to prompt for the security key and retry via
 * {@link WalletSecretStore#unlock(char[], VaultSecondFactor)}, rather than
 * surfacing a generic "unable to unlock" as if the passphrase were wrong.
 */
public class SecondFactorRequiredException extends WalletVaultException {

    private final transient VaultSecondFactor.FactorDescriptor descriptor;

    public SecondFactorRequiredException(VaultSecondFactor.FactorDescriptor descriptor) {
        super("This wallet vault requires a hardware security key to unlock.");
        this.descriptor = descriptor;
    }

    /** The factor the vault was sealed with, so the UI can name/select it. */
    public VaultSecondFactor.FactorDescriptor descriptor() {
        return descriptor;
    }
}
