package com.bloxbean.cardano.yano.wallet.core.vault;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.crypto.Cipher;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FileWalletSecretStoreTest {
    // Small parameters keep the test suite fast; production defaults are in Argon2Params.RECOMMENDED.
    private static final FileWalletSecretStore.Argon2Params TEST_PARAMS =
            new FileWalletSecretStore.Argon2Params(1024, 1, 1);

    @TempDir
    Path tempDir;

    @Test
    void createAndUnlockRoundTripsEncryptedSecret() {
        Path vaultFile = tempDir.resolve("wallet-vault.json");
        FileWalletSecretStore store = newStore(vaultFile);
        byte[] secretBytes = "plain-wallet-secret".getBytes(StandardCharsets.UTF_8);
        WalletSecret secret = new WalletSecret(
                SecretKind.MNEMONIC,
                secretBytes,
                "preprod",
                0,
                Instant.parse("2026-05-01T00:00:00Z"));

        store.create(secret, "correct horse battery staple".toCharArray());

        WalletSecret unlocked = store.unlock("correct horse battery staple".toCharArray());
        assertThat(unlocked.kind()).isEqualTo(SecretKind.MNEMONIC);
        assertThat(unlocked.secretBytes()).containsExactly(secretBytes);
        assertThat(unlocked.network()).isEqualTo("preprod");
        assertThat(unlocked.accountIndex()).isZero();
        assertThat(unlocked.createdAt()).isEqualTo(secret.createdAt());
        assertThat(store.storedVersion()).isEqualTo(FileWalletSecretStore.CURRENT_VERSION);
    }

    @Test
    void writesArgon2idEnvelope() throws Exception {
        Path vaultFile = tempDir.resolve("wallet-vault.json");
        FileWalletSecretStore store = newStore(vaultFile);
        store.create(secret("plain-wallet-secret"), "passphrase".toCharArray());

        String vaultJson = Files.readString(vaultFile);
        assertThat(vaultJson).contains("argon2id");
        assertThat(vaultJson).contains("memoryKb");
        assertThat(vaultJson).contains("parallelism");
        assertThat(store.storedVersion()).isEqualTo(2);
    }

    @Test
    void wrongPassphraseFailsUnlock() {
        Path vaultFile = tempDir.resolve("wallet-vault.json");
        FileWalletSecretStore store = newStore(vaultFile);
        store.create(secret("plain-wallet-secret"), "right-passphrase".toCharArray());

        assertThatThrownBy(() -> store.unlock("wrong-passphrase".toCharArray()))
                .isInstanceOf(WalletVaultException.class)
                .hasMessageContaining("Unable to unlock wallet vault");
    }

    @Test
    void vaultFileDoesNotContainPlaintextSecret() throws Exception {
        Path vaultFile = tempDir.resolve("wallet-vault.json");
        FileWalletSecretStore store = newStore(vaultFile);
        byte[] secretBytes = "plain-wallet-secret".getBytes(StandardCharsets.UTF_8);

        store.create(secret("plain-wallet-secret"), "passphrase".toCharArray());

        String vaultJson = Files.readString(vaultFile);
        assertThat(vaultJson).doesNotContain("plain-wallet-secret");
        assertThat(vaultJson).doesNotContain(Base64.getEncoder().encodeToString(secretBytes));
        assertThat(vaultJson).contains("ciphertext");
    }

    @Test
    void rotatePassphraseReencryptsVault() {
        Path vaultFile = tempDir.resolve("wallet-vault.json");
        FileWalletSecretStore store = newStore(vaultFile);
        byte[] secretBytes = "plain-wallet-secret".getBytes(StandardCharsets.UTF_8);
        store.create(secret("plain-wallet-secret"), "old-passphrase".toCharArray());

        store.rotatePassphrase("old-passphrase".toCharArray(), "new-passphrase".toCharArray());

        assertThatThrownBy(() -> store.unlock("old-passphrase".toCharArray()))
                .isInstanceOf(WalletVaultException.class);
        WalletSecret unlocked = store.unlock("new-passphrase".toCharArray());
        assertThat(unlocked.secretBytes()).containsExactly(secretBytes);
    }

    @Test
    void unlocksLegacyV1VaultAndUpgradesItToV2() throws Exception {
        Path vaultFile = tempDir.resolve("wallet-vault.json");
        byte[] secretBytes = "legacy-wallet-secret".getBytes(StandardCharsets.UTF_8);
        writeLegacyV1Vault(vaultFile, secretBytes, "passphrase".toCharArray());

        FileWalletSecretStore store = newStore(vaultFile);
        assertThat(store.storedVersion()).isEqualTo(1);

        WalletSecret unlocked = store.unlock("passphrase".toCharArray());
        assertThat(unlocked.kind()).isEqualTo(SecretKind.MNEMONIC);
        assertThat(unlocked.secretBytes()).containsExactly(secretBytes);

        // The vault must now be rewritten with the current envelope and stay unlockable.
        assertThat(store.storedVersion()).isEqualTo(FileWalletSecretStore.CURRENT_VERSION);
        assertThat(Files.readString(vaultFile)).contains("argon2id");
        WalletSecret unlockedAgain = store.unlock("passphrase".toCharArray());
        assertThat(unlockedAgain.secretBytes()).containsExactly(secretBytes);
    }

    @Test
    void legacyV1VaultWithWrongPassphraseFailsWithoutUpgrade() throws Exception {
        Path vaultFile = tempDir.resolve("wallet-vault.json");
        writeLegacyV1Vault(vaultFile, "legacy-wallet-secret".getBytes(StandardCharsets.UTF_8), "right".toCharArray());

        FileWalletSecretStore store = newStore(vaultFile);
        assertThatThrownBy(() -> store.unlock("wrong".toCharArray()))
                .isInstanceOf(WalletVaultException.class);
        assertThat(store.storedVersion()).isEqualTo(1);
    }

    @Test
    void rejectsTamperedCiphertext() throws Exception {
        Path vaultFile = tempDir.resolve("wallet-vault.json");
        FileWalletSecretStore store = newStore(vaultFile);
        store.create(secret("plain-wallet-secret"), "passphrase".toCharArray());

        ObjectMapper mapper = new ObjectMapper();
        Map<String, Object> envelope = mapper.readValue(vaultFile.toFile(), LinkedHashMap.class);
        byte[] ciphertext = Base64.getDecoder().decode((String) envelope.get("ciphertext"));
        ciphertext[0] ^= 0x01;
        envelope.put("ciphertext", Base64.getEncoder().encodeToString(ciphertext));
        mapper.writeValue(vaultFile.toFile(), envelope);

        assertThatThrownBy(() -> store.unlock("passphrase".toCharArray()))
                .isInstanceOf(WalletVaultException.class);
    }

    @Test
    void rejectsAbsurdKdfMemoryFromTamperedVault() throws Exception {
        Path vaultFile = tempDir.resolve("wallet-vault.json");
        FileWalletSecretStore store = newStore(vaultFile);
        store.create(secret("plain-wallet-secret"), "passphrase".toCharArray());

        ObjectMapper mapper = new ObjectMapper();
        Map<String, Object> envelope = mapper.readValue(vaultFile.toFile(), LinkedHashMap.class);
        // A tampered memory cost must fail validation, not drive the JVM into an OOM.
        envelope.put("memoryKb", 2_000_000_000);
        mapper.writeValue(vaultFile.toFile(), envelope);

        assertThatThrownBy(() -> store.unlock("passphrase".toCharArray()))
                .isInstanceOf(WalletVaultException.class);
    }

    @Test
    void rejectsUnsupportedVersion() throws Exception {
        Path vaultFile = tempDir.resolve("wallet-vault.json");
        FileWalletSecretStore store = newStore(vaultFile);
        store.create(secret("plain-wallet-secret"), "passphrase".toCharArray());

        ObjectMapper mapper = new ObjectMapper();
        Map<String, Object> envelope = mapper.readValue(vaultFile.toFile(), LinkedHashMap.class);
        envelope.put("version", 99);
        mapper.writeValue(vaultFile.toFile(), envelope);

        assertThatThrownBy(() -> store.unlock("passphrase".toCharArray()))
                .isInstanceOf(WalletVaultException.class)
                .hasMessageContaining("version");
    }

    @Test
    void secretBytesAreDefensivelyCopied() {
        byte[] original = "plain-wallet-secret".getBytes(StandardCharsets.UTF_8);
        WalletSecret secret = new WalletSecret(
                SecretKind.ACCOUNT_PRIVATE_KEY,
                original,
                "devnet",
                0,
                Instant.parse("2026-05-01T00:00:00Z"));

        original[0] = 'X';
        byte[] extracted = secret.secretBytes();
        extracted[1] = 'Y';

        assertThat(secret.secretBytes()).containsExactly("plain-wallet-secret".getBytes(StandardCharsets.UTF_8));
    }

    private FileWalletSecretStore newStore(Path vaultFile) {
        return new FileWalletSecretStore(vaultFile, new SecureRandom(), TEST_PARAMS);
    }

    private WalletSecret secret(String value) {
        return new WalletSecret(
                SecretKind.MNEMONIC,
                value.getBytes(StandardCharsets.UTF_8),
                "preprod",
                0,
                Instant.parse("2026-05-01T00:00:00Z"));
    }

    /**
     * Writes a vault exactly as the feat/wallet_mvp v1 implementation did:
     * PBKDF2WithHmacSHA256-derived AES key + AES-256-GCM envelope.
     */
    private void writeLegacyV1Vault(Path vaultFile, byte[] secretBytes, char[] passphrase) throws Exception {
        int iterations = 10_000;
        SecureRandom random = new SecureRandom();
        byte[] salt = new byte[16];
        byte[] nonce = new byte[12];
        random.nextBytes(salt);
        random.nextBytes(nonce);

        ObjectMapper mapper = new ObjectMapper();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("kind", SecretKind.MNEMONIC.name());
        payload.put("secretBase64", Base64.getEncoder().encodeToString(secretBytes));
        payload.put("network", "preprod");
        payload.put("accountIndex", 0);
        payload.put("createdAt", "2026-05-01T00:00:00Z");
        byte[] plaintext = mapper.writeValueAsBytes(payload);

        PBEKeySpec keySpec = new PBEKeySpec(passphrase, salt, iterations, 256);
        SecretKeyFactory factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
        SecretKeySpec key = new SecretKeySpec(factory.generateSecret(keySpec).getEncoded(), "AES");
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, nonce));
        byte[] ciphertext = cipher.doFinal(plaintext);

        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("version", 1);
        envelope.put("kdf", "PBKDF2WithHmacSHA256");
        envelope.put("iterations", iterations);
        envelope.put("cipher", "AES/GCM/NoPadding");
        envelope.put("salt", Base64.getEncoder().encodeToString(salt));
        envelope.put("nonce", Base64.getEncoder().encodeToString(nonce));
        envelope.put("ciphertext", Base64.getEncoder().encodeToString(ciphertext));
        Files.createDirectories(vaultFile.getParent());
        mapper.writeValue(vaultFile.toFile(), envelope);
    }
}
