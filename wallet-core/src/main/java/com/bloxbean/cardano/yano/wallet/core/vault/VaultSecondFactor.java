package com.bloxbean.cardano.yano.wallet.core.vault;

/**
 * A hardware-backed second factor for the vault KDF (ADR-036). Given the
 * envelope's stored {@link FactorDescriptor} and its random challenge, the
 * implementation asks a physical security key to compute a secret — e.g. a
 * YubiKey HMAC-SHA1 challenge-response — that never leaves the device's
 * control. The store mixes that secret into the Argon2id input alongside the
 * passphrase, so a v3 vault cannot be opened without the key, even with the
 * file <em>and</em> the passphrase.
 *
 * <p>Implementations live in {@code wallet-hardware} (the UI JVM only, per
 * ADR-034); {@code wallet-core} knows only this SPI, never the device code.
 */
@FunctionalInterface
public interface VaultSecondFactor {

    /**
     * Computes the device-derived secret for a challenge. Called once per
     * seal (enrol) and once per unlock, with the challenge stored in the vault.
     *
     * @param descriptor which factor the vault was sealed with (type, slot)
     * @param challenge  the vault's per-vault random challenge (not secret)
     * @return the device's response; the store zeroizes it after key derivation,
     *         so the implementation must not retain it
     */
    byte[] respond(FactorDescriptor descriptor, byte[] challenge);

    /**
     * The factor type this implementation handles (one of the {@code
     * FactorDescriptor} type constants), or {@code null} if it can attempt any.
     * Lets a multi-factor vault invoke a device only on slots it can unlock,
     * avoiding a needless touch on unrelated slots.
     */
    default String type() {
        return null;
    }

    /**
     * Identifies the second factor a v3 vault was sealed with; stored in the
     * envelope. {@code slot} is used by the YubiKey OTP factor; {@code
     * credentialId}/{@code rpId}/{@code requireUv} by the FIDO2 factor.
     */
    record FactorDescriptor(String type, Integer slot, String credentialId, String rpId, boolean requireUv) {
        /** YubiKey / OnlyKey OTP-slot HMAC-SHA1 challenge-response (Y-M1). */
        public static final String YUBIKEY_HMAC_SHA1 = "yubikey-hmac-sha1";
        /** FIDO2 CTAP2 hmac-secret extension (Y-M2). */
        public static final String FIDO2_HMAC_SECRET = "fido2-hmac-secret";

        public FactorDescriptor {
            if (type == null || type.isBlank()) {
                throw new IllegalArgumentException("factor type is required");
            }
        }

        /** A YubiKey OTP-slot HMAC-SHA1 factor on the given slot (1 or 2). */
        public static FactorDescriptor yubikey(int slot) {
            return new FactorDescriptor(YUBIKEY_HMAC_SHA1, slot, null, null, false);
        }

        /** A FIDO2 hmac-secret factor bound to an enrolled credential. */
        public static FactorDescriptor fido2(String credentialId, String rpId, boolean requireUv) {
            if (credentialId == null || credentialId.isBlank()) {
                throw new IllegalArgumentException("credentialId is required for a FIDO2 factor");
            }
            return new FactorDescriptor(FIDO2_HMAC_SECRET, null, credentialId, rpId, requireUv);
        }
    }
}
