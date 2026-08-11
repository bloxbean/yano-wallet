package com.bloxbean.cardano.yano.wallet.core.vault;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Envelope v3 — hardware-second-factor vault (ADR-036). A stub {@link VaultSecondFactor}
 * computes HMAC-SHA1 over the challenge with a fixed "device secret", standing in
 * for a YubiKey so the crypto (seal / unlock / enrol / remove) is fully verifiable
 * without hardware.
 */
class FileWalletSecretStoreV3Test {
    // Small parameters keep the suite fast; production defaults live in Argon2Params.RECOMMENDED.
    private static final FileWalletSecretStore.Argon2Params TEST_PARAMS =
            new FileWalletSecretStore.Argon2Params(1024, 1, 1);
    private static final byte[] DEVICE_SECRET = "yubikey-slot-2-hmac-secret".getBytes(StandardCharsets.UTF_8);
    private static final VaultSecondFactor.FactorDescriptor DESCRIPTOR =
            VaultSecondFactor.FactorDescriptor.yubikey(2);

    @TempDir
    Path tempDir;

    @Test
    void enrolThenUnlockWithFactorRoundTrips() {
        FileWalletSecretStore store = seededStore();
        byte[] seed = "plain-wallet-secret".getBytes(StandardCharsets.UTF_8);

        store.enrollFactor("passphrase".toCharArray(), DESCRIPTOR, hmacFactor(DEVICE_SECRET));

        assertThat(store.storedVersion()).isEqualTo(4);
        assertThat(store.factorDescriptor().type())
                .isEqualTo(VaultSecondFactor.FactorDescriptor.YUBIKEY_HMAC_SHA1);
        assertThat(store.factorDescriptor().slot()).isEqualTo(2);

        WalletSecret unlocked = store.unlock("passphrase".toCharArray(), hmacFactor(DEVICE_SECRET));
        assertThat(unlocked.secretBytes()).containsExactly(seed);
    }

    @Test
    void unlockWithoutFactorSignalsSecondFactorRequired() {
        FileWalletSecretStore store = seededStore();
        store.enrollFactor("passphrase".toCharArray(), DESCRIPTOR, hmacFactor(DEVICE_SECRET));

        assertThatThrownBy(() -> store.unlock("passphrase".toCharArray()))
                .isInstanceOf(SecondFactorRequiredException.class);
        // The typed exception carries the descriptor so the UI can name the key.
        SecondFactorRequiredException ex = catchSecondFactor(store);
        assertThat(ex.descriptor().type()).isEqualTo(VaultSecondFactor.FactorDescriptor.YUBIKEY_HMAC_SHA1);
    }

    @Test
    void wrongFactorSecretFailsUnlock() {
        FileWalletSecretStore store = seededStore();
        store.enrollFactor("passphrase".toCharArray(), DESCRIPTOR, hmacFactor(DEVICE_SECRET));

        byte[] wrongDevice = "a-different-yubikey".getBytes(StandardCharsets.UTF_8);
        assertThatThrownBy(() -> store.unlock("passphrase".toCharArray(), hmacFactor(wrongDevice)))
                .isInstanceOf(WalletVaultException.class)
                .hasMessageContaining("Unable to unlock wallet vault");
    }

    @Test
    void rightFactorWrongPassphraseFailsUnlock() {
        FileWalletSecretStore store = seededStore();
        store.enrollFactor("passphrase".toCharArray(), DESCRIPTOR, hmacFactor(DEVICE_SECRET));

        assertThatThrownBy(() -> store.unlock("wrong".toCharArray(), hmacFactor(DEVICE_SECRET)))
                .isInstanceOf(WalletVaultException.class);
    }

    @Test
    void removeFactorReturnsVaultToPassphraseOnly() {
        FileWalletSecretStore store = seededStore();
        byte[] seed = "plain-wallet-secret".getBytes(StandardCharsets.UTF_8);
        store.enrollFactor("passphrase".toCharArray(), DESCRIPTOR, hmacFactor(DEVICE_SECRET));

        store.removeFactor("passphrase".toCharArray(), hmacFactor(DEVICE_SECRET));

        assertThat(store.storedVersion()).isEqualTo(FileWalletSecretStore.CURRENT_VERSION);
        assertThat(store.factorDescriptor()).isNull();
        assertThat(store.unlock("passphrase".toCharArray()).secretBytes()).containsExactly(seed);
    }

    @Test
    void enrolOnAlreadyFactoredVaultRequiresRemoveFirst() {
        FileWalletSecretStore store = seededStore();
        store.enrollFactor("passphrase".toCharArray(), DESCRIPTOR, hmacFactor(DEVICE_SECRET));

        // A second enrol without removing first can't unlock the v3 vault (no old
        // factor supplied) — surfaces as SecondFactorRequired, documenting the flow.
        assertThatThrownBy(() ->
                store.enrollFactor("passphrase".toCharArray(), DESCRIPTOR, hmacFactor(DEVICE_SECRET)))
                .isInstanceOf(SecondFactorRequiredException.class);
    }

    @Test
    void emptyFactorResponseIsRejected() {
        FileWalletSecretStore store = seededStore();
        VaultSecondFactor emptyFactor = (descriptor, challenge) -> new byte[0];

        assertThatThrownBy(() -> store.enrollFactor("passphrase".toCharArray(), DESCRIPTOR, emptyFactor))
                .isInstanceOf(WalletVaultException.class)
                .hasMessageContaining("empty response");
    }

    @Test
    void v3EnvelopeStoresChallengeAndFactorButNoDeviceSecretOrSeed() throws Exception {
        Path vaultFile = tempDir.resolve("wallet-vault.json");
        FileWalletSecretStore store = new FileWalletSecretStore(vaultFile, new SecureRandom(), TEST_PARAMS);
        store.create(secret("plain-wallet-secret"), "passphrase".toCharArray());
        store.enrollFactor("passphrase".toCharArray(), DESCRIPTOR, hmacFactor(DEVICE_SECRET));

        String json = Files.readString(vaultFile);
        assertThat(json).contains("\"version\" : 4");
        assertThat(json).contains(VaultSecondFactor.FactorDescriptor.YUBIKEY_HMAC_SHA1);
        assertThat(json).contains("challenge");
        // Neither the seed nor the device secret is ever written to disk.
        assertThat(json).doesNotContain("plain-wallet-secret");
        assertThat(json).doesNotContain(Base64.getEncoder().encodeToString(DEVICE_SECRET));
    }

    @Test
    void enrolAndUnlockFido2ShapedFactorPreservesDescriptor() {
        FileWalletSecretStore store = seededStore();
        byte[] seed = "plain-wallet-secret".getBytes(StandardCharsets.UTF_8);
        var descriptor = VaultSecondFactor.FactorDescriptor.fido2("Y3JlZGVudGlhbA==", "yano-vault", true);
        // Stub authenticator: HMAC keyed by the credential id (per-credential + deterministic).
        VaultSecondFactor factor = (d, challenge) -> hmac(d.credentialId().getBytes(StandardCharsets.UTF_8), challenge);

        store.enrollFactor("passphrase".toCharArray(), descriptor, factor);

        assertThat(store.storedVersion()).isEqualTo(4);
        var stored = store.factorDescriptor();
        assertThat(stored.type()).isEqualTo(VaultSecondFactor.FactorDescriptor.FIDO2_HMAC_SECRET);
        assertThat(stored.credentialId()).isEqualTo("Y3JlZGVudGlhbA==");
        assertThat(stored.rpId()).isEqualTo("yano-vault");
        assertThat(stored.requireUv()).isTrue();
        assertThat(stored.slot()).isNull();

        assertThat(store.unlock("passphrase".toCharArray(), factor).secretBytes()).containsExactly(seed);
    }

    @Test
    void backupKeyUnlocksAndSurvivesRemovalOfPrimary() {
        FileWalletSecretStore store = seededStore();
        byte[] seed = "plain-wallet-secret".getBytes(StandardCharsets.UTF_8);
        VaultSecondFactor keyA = hmacFactor("device-A".getBytes(StandardCharsets.UTF_8));
        VaultSecondFactor keyB = hmacFactor("device-B".getBytes(StandardCharsets.UTF_8));

        store.enrollFactor("passphrase".toCharArray(),
                VaultSecondFactor.FactorDescriptor.yubikey(2), keyA);
        store.addFactor("passphrase".toCharArray(), keyA,
                VaultSecondFactor.FactorDescriptor.yubikey(1), keyB); // backup key

        assertThat(store.factorDescriptors()).hasSize(2);
        // Either enrolled key opens the same vault.
        assertThat(store.unlock("passphrase".toCharArray(), keyA).secretBytes()).containsExactly(seed);
        assertThat(store.unlock("passphrase".toCharArray(), keyB).secretBytes()).containsExactly(seed);

        // Removing the primary leaves the backup working; the primary no longer opens it.
        store.removeFactor("passphrase".toCharArray(), keyA);
        assertThat(store.factorDescriptors()).hasSize(1);
        assertThat(store.unlock("passphrase".toCharArray(), keyB).secretBytes()).containsExactly(seed);
        assertThatThrownBy(() -> store.unlock("passphrase".toCharArray(), keyA))
                .isInstanceOf(WalletVaultException.class);
    }

    @Test
    void passwordlessVaultUnlocksWithTheKeyAlone() {
        FileWalletSecretStore store = seededStore();
        byte[] seed = "plain-wallet-secret".getBytes(StandardCharsets.UTF_8);
        var descriptor = VaultSecondFactor.FactorDescriptor.fido2("Y3JlZA==", "yano-vault", true); // UV
        VaultSecondFactor factor = (d, challenge) -> hmac(d.credentialId().getBytes(StandardCharsets.UTF_8), challenge);

        store.enrollFactor("passphrase".toCharArray(), descriptor, factor, true); // passwordless

        assertThat(store.storedVersion()).isEqualTo(4);
        assertThat(store.isPasswordless()).isTrue();
        // Opens with just the key — no passphrase.
        assertThat(store.unlock(null, factor).secretBytes()).containsExactly(seed);
        // The old passphrase alone no longer opens it (there is no passphrase slot).
        assertThatThrownBy(() -> store.unlock("passphrase".toCharArray()))
                .isInstanceOf(SecondFactorRequiredException.class);
    }

    @Test
    void passwordlessRequiresThePin() {
        FileWalletSecretStore store = seededStore();
        var touchOnly = VaultSecondFactor.FactorDescriptor.fido2("Y3JlZA==", "yano-vault", false); // no UV
        VaultSecondFactor factor = (d, challenge) -> hmac(d.credentialId().getBytes(StandardCharsets.UTF_8), challenge);

        assertThatThrownBy(() -> store.enrollFactor("passphrase".toCharArray(), touchOnly, factor, true))
                .isInstanceOf(WalletVaultException.class)
                .hasMessageContaining("PIN");
    }

    @Test
    void plainV2VaultIsUnaffectedByV3Support() {
        FileWalletSecretStore store = seededStore();
        assertThat(store.storedVersion()).isEqualTo(2);
        assertThat(store.factorDescriptor()).isNull();
        assertThat(store.unlock("passphrase".toCharArray()).secretBytes())
                .containsExactly("plain-wallet-secret".getBytes(StandardCharsets.UTF_8));
    }

    // --- helpers ---

    /** A store over a freshly created passphrase-only (v2) vault. */
    private FileWalletSecretStore seededStore() {
        Path vaultFile = tempDir.resolve("wallet-vault.json");
        FileWalletSecretStore store = new FileWalletSecretStore(vaultFile, new SecureRandom(), TEST_PARAMS);
        store.create(secret("plain-wallet-secret"), "passphrase".toCharArray());
        return store;
    }

    private WalletSecret secret(String value) {
        return new WalletSecret(
                SecretKind.MNEMONIC,
                value.getBytes(StandardCharsets.UTF_8),
                "preprod",
                0,
                Instant.parse("2026-05-01T00:00:00Z"));
    }

    private static byte[] hmac(byte[] key, byte[] message) {
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(key, "HmacSHA1"));
            return mac.doFinal(message);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Stub security key: HMAC-SHA1(deviceSecret, challenge) — a YubiKey slot in software. */
    private static VaultSecondFactor hmacFactor(byte[] deviceSecret) {
        return (descriptor, challenge) -> {
            try {
                Mac mac = Mac.getInstance("HmacSHA1");
                mac.init(new SecretKeySpec(deviceSecret, "HmacSHA1"));
                return mac.doFinal(challenge);
            } catch (GeneralSecurityException e) {
                throw new IllegalStateException(e);
            }
        };
    }

    private static SecondFactorRequiredException catchSecondFactor(FileWalletSecretStore store) {
        try {
            store.unlock("passphrase".toCharArray());
            throw new AssertionError("expected SecondFactorRequiredException");
        } catch (SecondFactorRequiredException e) {
            return e;
        }
    }
}
