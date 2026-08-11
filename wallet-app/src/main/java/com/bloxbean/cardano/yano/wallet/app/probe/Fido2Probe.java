package com.bloxbean.cardano.yano.wallet.app.probe;

import com.bloxbean.cardano.yano.wallet.hardware.fido.Ctap2Client;
import com.bloxbean.cardano.yano.wallet.hardware.fido.CtapHidDevice;

import java.io.Console;
import java.util.Arrays;
import java.util.HexFormat;

/**
 * ADR-036 Y-M2 device probe: enrols a throwaway FIDO2 hmac-secret credential,
 * then derives the secret for a fixed salt twice and checks the two match —
 * proving the CTAP2 transport + hmac-secret round-trip works and is
 * deterministic (the property the v3 vault relies on) before wiring it into the
 * vault. Uses UV (PIN) if the key has one set, else touch-only.
 *
 * <pre>
 *   ./gradlew :wallet-app:fido2Probe          (or a direct `java -cp ...` run)
 *   YANO_FIDO_PIN=1234 ... Fido2Probe         (non-interactive PIN)
 * </pre>
 */
public final class Fido2Probe {

    private static final String RP_ID = "yano-vault";

    private Fido2Probe() {
    }

    public static void main(String[] args) {
        HexFormat hex = HexFormat.of();
        byte[] salt = new byte[32];
        for (int i = 0; i < salt.length; i++) {
            salt[i] = (byte) (i + 1);
        }
        byte[] userId = "yano-vault-user0".getBytes(java.nio.charset.StandardCharsets.UTF_8); // 16 bytes

        System.out.println("FIDO2 hmac-secret probe (ADR-036 Y-M2)");
        System.out.println("  salt : " + hex.formatHex(salt));

        try (CtapHidDevice device = CtapHidDevice.open()) {
            device.onTouchNeeded(() -> System.out.println("  >> touch your security key"));
            Ctap2Client client = new Ctap2Client(device);

            Ctap2Client.Info info = client.getInfo();
            System.out.println("  hmac-secret supported : " + info.hmacSecret());
            System.out.println("  FIDO2 PIN set         : " + info.clientPinSet());
            System.out.println("  PIN/UV auth protocol  : " + info.pinProtocol());
            if (!info.hmacSecret()) {
                System.err.println("FAILED: this key does not support hmac-secret");
                System.exit(1);
            }

            char[] pin = info.clientPinSet() ? readPin() : null;
            System.out.println(pin == null ? "  mode : touch-only (non-UV)" : "  mode : UV (PIN)");

            System.out.println("Enrolling a throwaway credential (touch when it blinks)...");
            byte[] credentialId = client.makeHmacSecretCredential(RP_ID, userId, pin);
            System.out.println("  credentialId : " + hex.formatHex(credentialId));

            System.out.println("Deriving hmac-secret twice (touch each time)...");
            byte[] first = client.getHmacSecret(RP_ID, credentialId, salt, pin);
            byte[] second = client.getHmacSecret(RP_ID, credentialId, salt, pin);
            if (pin != null) {
                Arrays.fill(pin, '\0');
            }

            System.out.println("  output     : " + hex.formatHex(first));
            boolean deterministic = Arrays.equals(first, second);
            System.out.println("  repeatable : " + (deterministic ? "YES (both calls match)" : "NO — MISMATCH!"));
            if (!deterministic || first.length != 32) {
                System.err.println("  second     : " + hex.formatHex(second));
                System.exit(1);
            }
            System.out.println("OK");
        } catch (RuntimeException e) {
            System.err.println("FAILED: " + e.getMessage());
            System.exit(1);
        }
    }

    private static char[] readPin() {
        String env = System.getenv("YANO_FIDO_PIN");
        if (env != null && !env.isBlank()) {
            return env.toCharArray();
        }
        Console console = System.console();
        if (console != null) {
            return console.readPassword("  FIDO2 PIN: ");
        }
        throw new IllegalStateException("A FIDO2 PIN is required — set YANO_FIDO_PIN or run in a terminal");
    }
}
