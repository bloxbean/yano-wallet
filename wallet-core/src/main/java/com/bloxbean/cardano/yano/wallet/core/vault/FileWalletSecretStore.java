package com.bloxbean.cardano.yano.wallet.core.vault;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.bouncycastle.crypto.generators.Argon2BytesGenerator;
import org.bouncycastle.crypto.params.Argon2Parameters;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.crypto.Cipher;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Objects;

/**
 * File-backed encrypted vault for a single wallet secret.
 *
 * <p>Envelopes:
 * <ul>
 *   <li><b>v1</b> (legacy): PBKDF2WithHmacSHA256 + AES-256-GCM. Rewritten as v2
 *       on the next successful unlock.</li>
 *   <li><b>v2</b> (default, passphrase-only): Argon2id + AES-256-GCM.</li>
 *   <li><b>v4</b> (ADR-036, opt-in hardware second factor): one or more
 *       <em>key slots</em>. Each slot encrypts the seed independently under
 *       {@code Argon2id(passphrase ‖ factorSecret)}, where the factor secret is
 *       an HMAC the security key computes over a per-slot challenge. Any one
 *       enrolled key plus the passphrase unlocks — extra slots are backup keys.
 *       The device secret and the seed are never written.</li>
 * </ul>
 */
public class FileWalletSecretStore implements WalletSecretStore {
    private static final Logger log = LoggerFactory.getLogger(FileWalletSecretStore.class);

    public static final int CURRENT_VERSION = 2;
    private static final int LEGACY_VERSION = 1;
    /** Envelope v4: v2 plus one or more hardware second-factor key slots (ADR-036). */
    private static final int FACTORED_VERSION = 4;

    public static final String KDF_ARGON2ID = "argon2id";
    private static final String KDF_PBKDF2 = "PBKDF2WithHmacSHA256";
    private static final String CIPHER = "AES/GCM/NoPadding";
    private static final String AES = "AES";
    private static final int SALT_BYTES = 16;
    private static final int NONCE_BYTES = 12;
    // Per-slot second-factor challenge. Random, stored (not secret); 32 bytes is
    // within the YubiKey HMAC-SHA1 limit and is the exact FIDO2 hmac-secret salt size.
    private static final int CHALLENGE_BYTES = 32;
    private static final int KEY_BITS = 256;
    private static final int GCM_TAG_BITS = 128;

    /**
     * Argon2id cost parameters. Defaults follow ADR-033: an offline-attackable
     * seed file warrants memory-hard parameters well above interactive-login
     * recommendations.
     *
     * <p>Upper bounds are a security control: unlock() feeds parameters read
     * from the vault file into the KDF, so a tampered file with an absurd
     * memory cost must fail validation instead of driving the JVM into an
     * OutOfMemoryError.
     */
    public record Argon2Params(int memoryKb, int iterations, int parallelism) {
        public static final int MAX_MEMORY_KB = 1024 * 1024; // 1 GiB (ADR-033 upper range)
        public static final int MAX_ITERATIONS = 128;
        public static final int MAX_PARALLELISM = 32;

        public static final Argon2Params RECOMMENDED = new Argon2Params(256 * 1024, 3, 2);

        public Argon2Params {
            if (iterations <= 0 || iterations > MAX_ITERATIONS) {
                throw new IllegalArgumentException("iterations must be in 1.." + MAX_ITERATIONS);
            }
            if (parallelism <= 0 || parallelism > MAX_PARALLELISM) {
                throw new IllegalArgumentException("parallelism must be in 1.." + MAX_PARALLELISM);
            }
            if (memoryKb < 8 * parallelism || memoryKb > MAX_MEMORY_KB) {
                throw new IllegalArgumentException(
                        "memoryKb must be in " + (8 * parallelism) + ".." + MAX_MEMORY_KB);
            }
        }
    }

    // Ignore unknown properties so a version peek (and cross-version reads) don't fail.
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private final Path vaultFile;
    private final SecureRandom secureRandom;
    private final Argon2Params argon2Params;
    private final ObjectMapper objectMapper;

    public FileWalletSecretStore(Path vaultFile) {
        this(vaultFile, SecureRandomHolder.INSTANCE, Argon2Params.RECOMMENDED);
    }

    public FileWalletSecretStore(Path vaultFile, SecureRandom secureRandom, Argon2Params argon2Params) {
        this.vaultFile = Objects.requireNonNull(vaultFile, "vaultFile is required");
        this.secureRandom = Objects.requireNonNull(secureRandom, "secureRandom is required");
        this.argon2Params = Objects.requireNonNull(argon2Params, "argon2Params is required");
        this.objectMapper = MAPPER;
    }

    @Override
    public boolean exists() {
        return Files.exists(vaultFile);
    }

    @Override
    public void create(WalletSecret secret, char[] passphrase) {
        if (exists()) {
            throw new WalletVaultException("Wallet vault already exists: " + vaultFile);
        }
        writeFlat(secret, passphrase);
    }

    @Override
    public WalletSecret unlock(char[] passphrase) {
        return unlock(passphrase, null);
    }

    @Override
    public WalletSecret unlock(char[] passphrase, VaultSecondFactor factor) {
        if (!exists()) {
            throw new WalletVaultException("Wallet vault does not exist: " + vaultFile);
        }
        if (storedVersion() == FACTORED_VERSION) {
            return unlockFactored(passphrase, factor).secret; // may be passwordless (ADR-040)
        }
        requirePassphrase(passphrase);
        return unlockFlat(passphrase);
    }

    /** True for a passwordless factored vault (ADR-040): unlock needs only the security key + PIN. */
    public boolean isPasswordless() {
        return exists() && storedVersion() == FACTORED_VERSION && allPasswordless(readFactored());
    }

    private WalletSecret unlockFlat(char[] passphrase) {
        try {
            EncryptedVaultFile encrypted = objectMapper.readValue(vaultFile.toFile(), EncryptedVaultFile.class);
            validateFlat(encrypted);

            byte[] salt = decode(encrypted.salt());
            byte[] nonce = decode(encrypted.nonce());
            byte[] ciphertext = decode(encrypted.ciphertext());
            SecretKeySpec key = deriveFlatKey(passphrase, salt, encrypted);
            try {
                WalletSecret secret = parsePayload(decrypt(ciphertext, key, nonce));
                if (encrypted.version() == LEGACY_VERSION) {
                    upgradeToCurrentVersion(secret, passphrase);
                }
                return secret;
            } finally {
                Arrays.fill(salt, (byte) 0);
                Arrays.fill(nonce, (byte) 0);
                Arrays.fill(ciphertext, (byte) 0);
            }
        } catch (GeneralSecurityException e) {
            throw new WalletVaultException("Unable to unlock wallet vault", e);
        } catch (IOException | IllegalArgumentException e) {
            throw new WalletVaultException("Invalid wallet vault file", e);
        }
    }

    private record Unlocked(WalletSecret secret, int slotIndex) {
    }

    private Unlocked unlockFactored(char[] passphrase, VaultSecondFactor factor) {
        FactoredVaultFile vault = readFactored();
        if (!allPasswordless(vault)) {
            requirePassphrase(passphrase); // passphrase-based slots need it; passwordless don't
        }
        if (factor == null) {
            throw new SecondFactorRequiredException(descriptorOf(vault.slots().get(0)));
        }
        String type = factor.type();
        for (int i = 0; i < vault.slots().size(); i++) {
            Slot slot = vault.slots().get(i);
            if (type != null && !type.equals(slot.factorType())) {
                continue; // a slot this factor can't unlock — don't touch the device for it
            }
            WalletSecret secret = tryDecryptSlot(slot, passphrase, factor);
            if (secret != null) {
                return new Unlocked(secret, i);
            }
        }
        throw new WalletVaultException("Unable to unlock wallet vault — wrong passphrase or security key");
    }

    /** Decrypts one slot, or returns null if this passphrase+factor doesn't open it. */
    private WalletSecret tryDecryptSlot(Slot slot, char[] passphrase, VaultSecondFactor factor) {
        VaultSecondFactor.FactorDescriptor descriptor = descriptorOf(slot);
        byte[] challenge = decode(slot.challenge());
        byte[] factorSecret = null;
        try {
            factorSecret = requireFactorResponse(factor, descriptor, challenge);
            SecretKeySpec key = deriveSlotKey(passphrase, factorSecret, decode(slot.salt()),
                    paramsOf(slot), slot.isPasswordless());
            return parsePayload(decrypt(decode(slot.ciphertext()), key, decode(slot.nonce())));
        } catch (GeneralSecurityException e) {
            return null; // GCM tag mismatch — wrong passphrase or wrong key for this slot
        } catch (IOException e) {
            throw new WalletVaultException("Invalid wallet vault file", e);
        } finally {
            if (factorSecret != null) {
                Arrays.fill(factorSecret, (byte) 0);
            }
            Arrays.fill(challenge, (byte) 0);
        }
    }

    @Override
    public void lock() {
        // File-backed store has no long-lived unlocked state.
    }

    @Override
    public void rotatePassphrase(char[] oldPassphrase, char[] newPassphrase) {
        if (storedVersion() == FACTORED_VERSION) {
            throw new WalletVaultException("Remove the security key before changing the passphrase");
        }
        WalletSecret secret = unlock(oldPassphrase);
        try {
            writeFlat(secret, newPassphrase);
        } finally {
            secret.destroy();
        }
    }

    @Override
    public List<VaultSecondFactor.FactorDescriptor> factorDescriptors() {
        if (!exists() || storedVersion() != FACTORED_VERSION) {
            return List.of();
        }
        List<VaultSecondFactor.FactorDescriptor> descriptors = new ArrayList<>();
        for (Slot slot : readFactored().slots()) {
            descriptors.add(descriptorOf(slot));
        }
        return descriptors;
    }

    @Override
    public void enrollFactor(char[] passphrase, VaultSecondFactor.FactorDescriptor descriptor,
                             VaultSecondFactor factor, boolean passwordless) {
        Objects.requireNonNull(descriptor, "descriptor is required");
        Objects.requireNonNull(factor, "factor is required");
        requirePasswordlessNeedsUv(passwordless, descriptor);
        // Requires a passphrase-only vault: unlock() throws SecondFactorRequired
        // for an already-factored vault, so adding another key uses addFactor.
        // The passphrase unlocks the current vault one last time; a passwordless
        // slot then no longer uses it.
        WalletSecret secret = unlock(passphrase);
        try {
            Slot slot = encryptSlot(secret, passphrase, descriptor, factor, passwordless);
            writeFactored(List.of(slot));
            log.info("Sealed wallet vault with second factor '{}' (passwordless={}): {}",
                    descriptor.type(), passwordless, vaultFile);
        } finally {
            secret.destroy();
        }
    }

    private static void requirePasswordlessNeedsUv(boolean passwordless,
                                                   VaultSecondFactor.FactorDescriptor descriptor) {
        if (passwordless && !descriptor.requireUv()) {
            throw new WalletVaultException("A passwordless vault requires the security key's PIN");
        }
    }

    @Override
    public void addFactor(char[] passphrase, VaultSecondFactor unlockFactor,
                          VaultSecondFactor.FactorDescriptor newDescriptor, VaultSecondFactor newFactor) {
        Objects.requireNonNull(unlockFactor, "unlockFactor is required");
        Objects.requireNonNull(newDescriptor, "newDescriptor is required");
        Objects.requireNonNull(newFactor, "newFactor is required");
        if (storedVersion() != FACTORED_VERSION) {
            throw new WalletVaultException("This vault has no security key yet — use enrollFactor");
        }
        FactoredVaultFile vault = readFactored();
        boolean passwordless = allPasswordless(vault);
        requirePasswordlessNeedsUv(passwordless, newDescriptor); // a backup key must match the mode
        Unlocked unlocked = unlockFactored(passphrase, unlockFactor);
        WalletSecret secret = unlocked.secret;
        try {
            List<Slot> slots = new ArrayList<>(vault.slots());
            slots.add(encryptSlot(secret, passphrase, newDescriptor, newFactor, passwordless));
            writeFactored(slots);
            log.info("Added backup second factor '{}' to wallet vault: {}", newDescriptor.type(), vaultFile);
        } finally {
            secret.destroy();
        }
    }

    @Override
    public void removeFactor(char[] passphrase, VaultSecondFactor factor) {
        Objects.requireNonNull(factor, "factor is required");
        if (storedVersion() != FACTORED_VERSION) {
            return; // nothing to remove
        }
        FactoredVaultFile vault = readFactored();
        Unlocked unlocked = unlockFactored(passphrase, factor);
        WalletSecret secret = unlocked.secret;
        try {
            List<Slot> remaining = new ArrayList<>(vault.slots());
            remaining.remove(unlocked.slotIndex);
            if (remaining.isEmpty()) {
                writeFlat(secret, passphrase); // back to passphrase-only v2
            } else {
                writeFactored(remaining);
            }
            log.info("Removed a second factor from wallet vault: {}", vaultFile);
        } finally {
            secret.destroy();
        }
    }

    /** Version of the on-disk envelope, or -1 if the vault does not exist. */
    public int storedVersion() {
        if (!exists()) {
            return -1;
        }
        try {
            return objectMapper.readValue(vaultFile.toFile(), VersionPeek.class).version();
        } catch (IOException e) {
            throw new WalletVaultException("Invalid wallet vault file", e);
        }
    }

    private void upgradeToCurrentVersion(WalletSecret secret, char[] passphrase) {
        try {
            writeFlat(secret, passphrase);
            log.info("Upgraded wallet vault to v{} (argon2id): {}", CURRENT_VERSION, vaultFile);
        } catch (RuntimeException e) {
            // The unlocked secret is still valid; the legacy vault stays readable.
            log.warn("Unable to upgrade wallet vault {} to v{}: {}", vaultFile, CURRENT_VERSION, e.getMessage());
        }
    }

    // --- writing ---

    /** Writes a passphrase-only v2 vault. */
    private void writeFlat(WalletSecret secret, char[] passphrase) {
        Objects.requireNonNull(secret, "secret is required");
        requirePassphrase(passphrase);

        byte[] salt = randomBytes(SALT_BYTES);
        byte[] nonce = randomBytes(NONCE_BYTES);
        SecretKeySpec key = deriveArgon2Key(passphrase, salt, argon2Params);
        byte[] plaintext = null;
        byte[] ciphertext = null;
        byte[] payloadSecret = secret.secretBytes();
        try {
            plaintext = objectMapper.writeValueAsBytes(payloadOf(secret, payloadSecret));
            ciphertext = encrypt(plaintext, key, nonce);
            writeAtomically(new EncryptedVaultFile(
                    CURRENT_VERSION, KDF_ARGON2ID,
                    argon2Params.iterations(), argon2Params.memoryKb(), argon2Params.parallelism(),
                    CIPHER, encode(salt), encode(nonce), encode(ciphertext)));
        } catch (GeneralSecurityException | IOException e) {
            throw new WalletVaultException("Unable to write wallet vault", e);
        } finally {
            Arrays.fill(payloadSecret, (byte) 0);
            if (plaintext != null) Arrays.fill(plaintext, (byte) 0);
            if (ciphertext != null) Arrays.fill(ciphertext, (byte) 0);
            Arrays.fill(salt, (byte) 0);
            Arrays.fill(nonce, (byte) 0);
        }
    }

    private void writeFactored(List<Slot> slots) {
        writeAtomically(new FactoredVaultFile(FACTORED_VERSION, KDF_ARGON2ID, CIPHER, List.copyOf(slots)));
    }

    /** Encrypts the seed under one key slot (a fresh challenge + salt + nonce). */
    private Slot encryptSlot(WalletSecret secret, char[] passphrase,
                             VaultSecondFactor.FactorDescriptor descriptor, VaultSecondFactor factor,
                             boolean passwordless) {
        byte[] salt = randomBytes(SALT_BYTES);
        byte[] nonce = randomBytes(NONCE_BYTES);
        byte[] challenge = randomBytes(CHALLENGE_BYTES);
        byte[] factorSecret = null;
        byte[] plaintext = null;
        byte[] ciphertext = null;
        byte[] payloadSecret = secret.secretBytes();
        try {
            factorSecret = requireFactorResponse(factor, descriptor, challenge);
            SecretKeySpec key = deriveSlotKey(passphrase, factorSecret, salt, argon2Params, passwordless);
            plaintext = objectMapper.writeValueAsBytes(payloadOf(secret, payloadSecret));
            ciphertext = encrypt(plaintext, key, nonce);
            boolean isFido2 = descriptor.credentialId() != null;
            return new Slot(
                    argon2Params.iterations(), argon2Params.memoryKb(), argon2Params.parallelism(),
                    encode(salt), encode(nonce), encode(ciphertext),
                    descriptor.type(), descriptor.slot(), encode(challenge),
                    isFido2 ? descriptor.credentialId() : null,
                    isFido2 ? descriptor.rpId() : null,
                    isFido2 ? descriptor.requireUv() : null,
                    passwordless ? Boolean.TRUE : null);
        } catch (GeneralSecurityException | IOException e) {
            throw new WalletVaultException("Unable to seal wallet vault slot", e);
        } finally {
            Arrays.fill(payloadSecret, (byte) 0);
            if (factorSecret != null) Arrays.fill(factorSecret, (byte) 0);
            if (plaintext != null) Arrays.fill(plaintext, (byte) 0);
            if (ciphertext != null) Arrays.fill(ciphertext, (byte) 0);
            Arrays.fill(salt, (byte) 0);
            Arrays.fill(nonce, (byte) 0);
            Arrays.fill(challenge, (byte) 0);
        }
    }

    private void writeAtomically(Object envelope) {
        Path parent = vaultFile.toAbsolutePath().getParent();
        try {
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Path tempFile = parent != null
                    ? Files.createTempFile(parent, vaultFile.getFileName().toString(), ".tmp")
                    : Files.createTempFile(vaultFile.getFileName().toString(), ".tmp");
            try {
                objectMapper.writerWithDefaultPrettyPrinter().writeValue(tempFile.toFile(), envelope);
                try {
                    Files.move(tempFile, vaultFile,
                            StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                } catch (AtomicMoveNotSupportedException e) {
                    Files.move(tempFile, vaultFile, StandardCopyOption.REPLACE_EXISTING);
                }
            } finally {
                Files.deleteIfExists(tempFile);
            }
        } catch (IOException e) {
            throw new WalletVaultException("Unable to write wallet vault", e);
        }
    }

    // --- crypto ---

    private byte[] encrypt(byte[] plaintext, SecretKeySpec key, byte[] nonce) throws GeneralSecurityException {
        Cipher cipher = Cipher.getInstance(CIPHER);
        cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_BITS, nonce));
        return cipher.doFinal(plaintext);
    }

    private byte[] decrypt(byte[] ciphertext, SecretKeySpec key, byte[] nonce) throws GeneralSecurityException {
        Cipher cipher = Cipher.getInstance(CIPHER);
        cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_BITS, nonce));
        return cipher.doFinal(ciphertext);
    }

    private SecretKeySpec deriveFlatKey(char[] passphrase, byte[] salt, EncryptedVaultFile encrypted) {
        if (encrypted.version() == LEGACY_VERSION) {
            return derivePbkdf2Key(passphrase, salt, encrypted.iterations());
        }
        return deriveArgon2Key(passphrase, salt, new Argon2Params(
                encrypted.memoryKb(), encrypted.iterations(), encrypted.parallelism()));
    }

    private static byte[] requireFactorResponse(VaultSecondFactor factor,
                                                VaultSecondFactor.FactorDescriptor descriptor, byte[] challenge) {
        byte[] response = factor.respond(descriptor, challenge);
        if (response == null || response.length == 0) {
            throw new WalletVaultException("Security key returned an empty response");
        }
        return response;
    }

    /**
     * The per-slot AES key: {@code Argon2id(passphrase ‖ factorSecret)} normally,
     * or {@code Argon2id(factorSecret)} for a passwordless slot (ADR-040) — where
     * the security key + its PIN replace the passphrase entirely.
     */
    private SecretKeySpec deriveSlotKey(char[] passphrase, byte[] factorSecret, byte[] salt,
                                        Argon2Params params, boolean passwordless) {
        return passwordless
                ? deriveArgon2Key(factorSecret, salt, params)
                : deriveArgon2KeyWithFactor(passphrase, factorSecret, salt, params);
    }

    /** Argon2id over {@code passphrase ‖ factorSecret} (ADR-036). */
    private SecretKeySpec deriveArgon2KeyWithFactor(char[] passphrase, byte[] factorSecret,
                                                    byte[] salt, Argon2Params params) {
        byte[] passphraseBytes = utf8Bytes(passphrase);
        byte[] combined = new byte[passphraseBytes.length + factorSecret.length];
        try {
            System.arraycopy(passphraseBytes, 0, combined, 0, passphraseBytes.length);
            System.arraycopy(factorSecret, 0, combined, passphraseBytes.length, factorSecret.length);
            return deriveArgon2Key(combined, salt, params);
        } finally {
            Arrays.fill(passphraseBytes, (byte) 0);
            Arrays.fill(combined, (byte) 0);
        }
    }

    /** UTF-8 encodes a passphrase to bytes without an unclearable String copy. */
    private static byte[] utf8Bytes(char[] chars) {
        ByteBuffer buffer = StandardCharsets.UTF_8.encode(CharBuffer.wrap(chars));
        byte[] out = new byte[buffer.remaining()];
        buffer.get(out);
        if (buffer.hasArray()) {
            Arrays.fill(buffer.array(), (byte) 0);
        }
        return out;
    }

    /** Argon2id over raw password bytes — shared by v2 and the factored slots. */
    private SecretKeySpec deriveArgon2Key(byte[] password, byte[] salt, Argon2Params params) {
        Argon2BytesGenerator generator = argon2(salt, params);
        byte[] key = new byte[KEY_BITS / 8];
        try {
            generator.generateBytes(password, key);
            return new SecretKeySpec(key, AES);
        } finally {
            Arrays.fill(key, (byte) 0);
        }
    }

    private SecretKeySpec deriveArgon2Key(char[] passphrase, byte[] salt, Argon2Params params) {
        Argon2BytesGenerator generator = argon2(salt, params);
        byte[] key = new byte[KEY_BITS / 8];
        try {
            generator.generateBytes(passphrase, key);
            return new SecretKeySpec(key, AES);
        } finally {
            Arrays.fill(key, (byte) 0);
        }
    }

    private static Argon2BytesGenerator argon2(byte[] salt, Argon2Params params) {
        Argon2Parameters parameters = new Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
                .withVersion(Argon2Parameters.ARGON2_VERSION_13)
                .withSalt(salt)
                .withMemoryAsKB(params.memoryKb())
                .withIterations(params.iterations())
                .withParallelism(params.parallelism())
                .build();
        Argon2BytesGenerator generator = new Argon2BytesGenerator();
        generator.init(parameters);
        return generator;
    }

    private SecretKeySpec derivePbkdf2Key(char[] passphrase, byte[] salt, int iterations) {
        PBEKeySpec keySpec = new PBEKeySpec(passphrase, salt, iterations, KEY_BITS);
        byte[] encoded = null;
        try {
            SecretKeyFactory factory = SecretKeyFactory.getInstance(KDF_PBKDF2);
            encoded = factory.generateSecret(keySpec).getEncoded();
            return new SecretKeySpec(encoded, AES);
        } catch (GeneralSecurityException e) {
            throw new WalletVaultException("Unable to derive wallet vault key", e);
        } finally {
            keySpec.clearPassword();
            if (encoded != null) {
                Arrays.fill(encoded, (byte) 0);
            }
        }
    }

    // --- helpers ---

    private WalletSecret parsePayload(byte[] plaintext) throws IOException {
        WalletSecretPayload payload = null;
        try {
            payload = objectMapper.readValue(plaintext, WalletSecretPayload.class);
            // WalletSecret's constructor clones; the payload's array is zeroized below.
            return new WalletSecret(
                    SecretKind.valueOf(payload.kind()),
                    payload.secret(),
                    payload.network(),
                    payload.accountIndex(),
                    Instant.parse(payload.createdAt()));
        } finally {
            if (payload != null && payload.secret() != null) {
                Arrays.fill(payload.secret(), (byte) 0);
            }
            Arrays.fill(plaintext, (byte) 0);
        }
    }

    private static WalletSecretPayload payloadOf(WalletSecret secret, byte[] secretBytes) {
        return new WalletSecretPayload(secret.kind().name(), secretBytes,
                secret.network(), secret.accountIndex(), secret.createdAt().toString());
    }

    private FactoredVaultFile readFactored() {
        try {
            FactoredVaultFile vault = objectMapper.readValue(vaultFile.toFile(), FactoredVaultFile.class);
            validateFactored(vault);
            return vault;
        } catch (IOException e) {
            throw new WalletVaultException("Invalid wallet vault file", e);
        }
    }

    private static boolean allPasswordless(FactoredVaultFile vault) {
        return vault.slots().stream().allMatch(Slot::isPasswordless);
    }

    private static Argon2Params paramsOf(Slot slot) {
        return new Argon2Params(slot.memoryKb(), slot.iterations(), slot.parallelism());
    }

    private static VaultSecondFactor.FactorDescriptor descriptorOf(Slot slot) {
        return new VaultSecondFactor.FactorDescriptor(
                slot.factorType(), slot.factorSlot(), slot.factorCredentialId(),
                slot.factorRpId(), Boolean.TRUE.equals(slot.factorRequireUv()));
    }

    private byte[] randomBytes(int length) {
        byte[] bytes = new byte[length];
        secureRandom.nextBytes(bytes);
        return bytes;
    }

    private void validateFlat(EncryptedVaultFile encrypted) {
        switch (encrypted.version()) {
            case LEGACY_VERSION -> {
                if (!KDF_PBKDF2.equals(encrypted.kdf())) {
                    throw new WalletVaultException("Unsupported wallet vault KDF: " + encrypted.kdf());
                }
                if (encrypted.iterations() <= 0) {
                    throw new WalletVaultException("Invalid wallet vault KDF iterations");
                }
            }
            case CURRENT_VERSION -> {
                if (!KDF_ARGON2ID.equals(encrypted.kdf())) {
                    throw new WalletVaultException("Unsupported wallet vault KDF: " + encrypted.kdf());
                }
                if (encrypted.iterations() <= 0
                        || encrypted.memoryKb() == null || encrypted.memoryKb() <= 0
                        || encrypted.parallelism() == null || encrypted.parallelism() <= 0) {
                    throw new WalletVaultException("Invalid wallet vault KDF parameters");
                }
            }
            default -> throw new WalletVaultException("Unsupported wallet vault version: " + encrypted.version());
        }
        if (!CIPHER.equals(encrypted.cipher())) {
            throw new WalletVaultException("Unsupported wallet vault cipher: " + encrypted.cipher());
        }
    }

    private void validateFactored(FactoredVaultFile vault) {
        if (vault.version() != FACTORED_VERSION) {
            throw new WalletVaultException("Unsupported wallet vault version: " + vault.version());
        }
        if (!KDF_ARGON2ID.equals(vault.kdf()) || !CIPHER.equals(vault.cipher())) {
            throw new WalletVaultException("Unsupported wallet vault KDF/cipher");
        }
        if (vault.slots() == null || vault.slots().isEmpty()) {
            throw new WalletVaultException("Factored wallet vault has no key slots");
        }
        for (Slot slot : vault.slots()) {
            if (slot.iterations() <= 0 || slot.memoryKb() == null || slot.memoryKb() <= 0
                    || slot.parallelism() == null || slot.parallelism() <= 0) {
                throw new WalletVaultException("Invalid wallet vault slot KDF parameters");
            }
            if (slot.factorType() == null || slot.factorType().isBlank()
                    || slot.challenge() == null || slot.challenge().isBlank()
                    || slot.ciphertext() == null || slot.ciphertext().isBlank()) {
                throw new WalletVaultException("Invalid wallet vault key slot");
            }
            if (VaultSecondFactor.FactorDescriptor.FIDO2_HMAC_SECRET.equals(slot.factorType())
                    && (slot.factorCredentialId() == null || slot.factorCredentialId().isBlank())) {
                throw new WalletVaultException("FIDO2 vault slot is missing its credential id");
            }
        }
    }

    private void requirePassphrase(char[] passphrase) {
        if (passphrase == null || passphrase.length == 0) {
            throw new WalletVaultException("Wallet passphrase is required");
        }
    }

    private String encode(byte[] bytes) {
        return Base64.getEncoder().encodeToString(bytes);
    }

    private byte[] decode(String value) {
        return Base64.getDecoder().decode(value.getBytes(StandardCharsets.US_ASCII));
    }

    private record VersionPeek(int version) {
    }

    /** v1/v2 passphrase-only envelope. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private record EncryptedVaultFile(
            int version, String kdf, int iterations, Integer memoryKb, Integer parallelism,
            String cipher, String salt, String nonce, String ciphertext) {
    }

    /** v4 factored envelope: the seed encrypted independently under each key slot. */
    private record FactoredVaultFile(int version, String kdf, String cipher, List<Slot> slots) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private record Slot(
            int iterations, Integer memoryKb, Integer parallelism,
            String salt, String nonce, String ciphertext,
            String factorType, Integer factorSlot, String challenge,
            String factorCredentialId, String factorRpId, Boolean factorRequireUv,
            Boolean passwordless) {

        boolean isPasswordless() {
            return Boolean.TRUE.equals(passwordless);
        }
    }

    /**
     * The secret travels as {@code byte[]} so every copy the store retains can
     * be zeroized — an immutable String copy of the seed would live on the heap
     * unclearable. Jackson maps the byte[] to/from the same Base64 JSON string
     * the v1 format used, so the on-disk field name stays {@code secretBase64}.
     */
    private record WalletSecretPayload(
            String kind,
            @com.fasterxml.jackson.annotation.JsonProperty("secretBase64") byte[] secret,
            String network,
            int accountIndex,
            String createdAt) {
    }

    private static final class SecureRandomHolder {
        private static final SecureRandom INSTANCE = new SecureRandom();
    }
}
