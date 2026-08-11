package com.bloxbean.cardano.yano.wallet.hardware.fido;

import com.bloxbean.cardano.yano.wallet.core.hardware.HardwareWalletException;
import com.bloxbean.cardano.yano.wallet.core.vault.VaultSecondFactor;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.function.Supplier;

/**
 * A {@link VaultSecondFactor} backed by a FIDO2 authenticator's hmac-secret
 * extension (ADR-036 Y-M2). Enrol is two-phase: {@link #enroll} creates a
 * non-resident credential and returns a {@link VaultSecondFactor.FactorDescriptor}
 * carrying its id; {@link #respond} then derives the per-vault secret from that
 * credential on every unlock. Deterministic for a fixed credential + challenge.
 *
 * <p>Holds no keys. For UV (PIN) vaults a {@code pinProvider} supplies the FIDO2
 * PIN when needed; touch-only vaults leave it null.
 */
public final class Fido2HmacSecret implements VaultSecondFactor {

    /** The relying-party id our credentials are scoped to (not a real domain). */
    public static final String DEFAULT_RP_ID = "yano-vault";
    private static final byte[] USER_ID = "yano-vault-user0".getBytes(StandardCharsets.UTF_8);
    private static final int SALT_BYTES = 32;

    private final String rpId;
    private final Supplier<char[]> pinProvider;
    private final Runnable onTouch;

    public Fido2HmacSecret() {
        this(DEFAULT_RP_ID, null, null);
    }

    public Fido2HmacSecret(String rpId, Supplier<char[]> pinProvider, Runnable onTouch) {
        this.rpId = rpId == null ? DEFAULT_RP_ID : rpId;
        this.pinProvider = pinProvider;
        this.onTouch = onTouch;
    }

    /**
     * Sets a FIDO2 PIN on the connected key (when none is set). Key-global and
     * removable only by a FIDO2 reset — callers must warn the user first.
     */
    public static void setPin(char[] newPin, Runnable onTouch) {
        try (CtapHidDevice device = CtapHidDevice.open()) {
            if (onTouch != null) {
                device.onTouchNeeded(onTouch);
            }
            new Ctap2Client(device).setPin(newPin);
        }
    }

    @Override
    public String type() {
        return FactorDescriptor.FIDO2_HMAC_SECRET;
    }

    /** True if this instance can supply a PIN (i.e. can enrol/unlock UV vaults). */
    public boolean supportsUv() {
        return pinProvider != null;
    }

    /**
     * Enrols a fresh hmac-secret credential and returns the descriptor to seal
     * the vault with. Uses UV (PIN) when a {@code pinProvider} is set, else
     * touch-only. The caller passes the returned descriptor to
     * {@link com.bloxbean.cardano.yano.wallet.core.vault.WalletSecretStore#enrollFactor}.
     */
    public FactorDescriptor enroll() {
        boolean uv = pinProvider != null;
        try (CtapHidDevice device = CtapHidDevice.open()) {
            if (onTouch != null) {
                device.onTouchNeeded(onTouch);
            }
            Ctap2Client client = new Ctap2Client(device);
            char[] pin = uv ? pinProvider.get() : null;
            try {
                byte[] credentialId = client.makeHmacSecretCredential(rpId, USER_ID, pin);
                return FactorDescriptor.fido2(
                        Base64.getEncoder().encodeToString(credentialId), rpId, uv);
            } finally {
                if (pin != null) {
                    Arrays.fill(pin, '\0');
                }
            }
        }
    }

    @Override
    public byte[] respond(FactorDescriptor descriptor, byte[] challenge) {
        if (challenge == null || challenge.length != SALT_BYTES) {
            throw new HardwareWalletException("FIDO2 hmac-secret needs a " + SALT_BYTES + "-byte challenge");
        }
        byte[] credentialId = Base64.getDecoder().decode(descriptor.credentialId());
        String rp = descriptor.rpId() != null ? descriptor.rpId() : rpId;
        char[] pin = descriptor.requireUv() ? requirePin() : null;
        try (CtapHidDevice device = CtapHidDevice.open()) {
            if (onTouch != null) {
                device.onTouchNeeded(onTouch);
            }
            Ctap2Client client = new Ctap2Client(device);
            try {
                return client.getHmacSecret(rp, credentialId, challenge, pin);
            } finally {
                if (pin != null) {
                    Arrays.fill(pin, '\0');
                }
            }
        }
    }

    private char[] requirePin() {
        if (pinProvider == null) {
            throw new HardwareWalletException(
                    "This vault requires the security key's PIN, but no PIN was provided");
        }
        return pinProvider.get();
    }
}
