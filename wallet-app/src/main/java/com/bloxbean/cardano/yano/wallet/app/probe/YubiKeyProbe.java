package com.bloxbean.cardano.yano.wallet.app.probe;

import com.bloxbean.cardano.yano.wallet.core.vault.VaultSecondFactor;
import com.bloxbean.cardano.yano.wallet.hardware.yubikey.YubiKeyChallengeResponse;

import java.util.HexFormat;

/**
 * ADR-036 Y-M1 device probe: runs a YubiKey HMAC-SHA1 challenge-response against
 * a fixed 32-byte challenge, twice, and checks the two responses match — proving
 * the transport works and is deterministic (the property the v3 vault relies on)
 * before any real vault is sealed with the key.
 *
 * <pre>
 *   ./gradlew :wallet-app:yubikeyProbe            # slot 2 (default)
 *   ./gradlew :wallet-app:yubikeyProbe -Pslot=1
 * </pre>
 *
 * Cross-check the printed response against {@code ykman otp calculate <slot> <challenge>}.
 */
public final class YubiKeyProbe {

    private YubiKeyProbe() {
    }

    public static void main(String[] args) {
        int slot = args.length > 0 ? Integer.parseInt(args[0].trim()) : 2;

        // Fixed 0x01..0x20 challenge; last byte non-zero so it matches `ykman otp
        // calculate` byte-for-byte (no variable-input padding ambiguity).
        byte[] challenge = new byte[32];
        for (int i = 0; i < challenge.length; i++) {
            challenge[i] = (byte) (i + 1);
        }
        HexFormat hex = HexFormat.of();
        System.out.println("YubiKey HMAC-SHA1 probe (ADR-036 Y-M1)");
        System.out.println("  slot      : " + slot);
        System.out.println("  challenge : " + hex.formatHex(challenge));
        System.out.println("--- device inventory ---");
        System.out.print(YubiKeyChallengeResponse.diagnostics());
        System.out.println("------------------------");

        var factor = new YubiKeyChallengeResponse();
        var descriptor = VaultSecondFactor.FactorDescriptor.yubikey(slot);

        try {
            System.out.println("  touch your YubiKey if it blinks...");
            byte[] first = factor.respond(descriptor, challenge);
            byte[] second = factor.respond(descriptor, challenge);

            System.out.println("  response  : " + hex.formatHex(first));
            boolean deterministic = java.util.Arrays.equals(first, second);
            System.out.println("  repeatable: " + (deterministic ? "YES (both calls match)" : "NO — MISMATCH!"));
            System.out.println("  verify    : ykman otp calculate " + slot + " " + hex.formatHex(challenge));
            if (!deterministic) {
                System.err.println("  second    : " + hex.formatHex(second));
                System.exit(1);
            }
            System.out.println("OK");
        } catch (RuntimeException e) {
            System.err.println("FAILED: " + e.getMessage());
            System.exit(1);
        }
    }
}
