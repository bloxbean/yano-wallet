package com.bloxbean.cardano.yano.wallet.core.wallet;

import com.bloxbean.cardano.client.crypto.MnemonicUtil;
import com.bloxbean.cardano.client.crypto.bip39.Words;
import com.bloxbean.cardano.hdwallet.Wallet;
import com.bloxbean.cardano.yano.wallet.core.config.WalletNetwork;
import com.bloxbean.cardano.yano.wallet.core.vault.FileWalletSecretStore;
import com.bloxbean.cardano.yano.wallet.core.vault.SecretKind;
import com.bloxbean.cardano.yano.wallet.core.vault.WalletSecret;
import com.bloxbean.cardano.yano.wallet.core.vault.WalletVaultException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;

public class FileStoredWalletRepository implements StoredWalletRepository {
    private static final TypeReference<List<StoredWalletIndexEntry>> WALLET_LIST =
            new TypeReference<>() {
            };
    private static final int DEFAULT_ACCOUNT_INDEX = 0;

    private final Path networkWalletDir;
    private final Path indexFile;
    private final WalletNetwork network;
    private final ObjectMapper objectMapper;
    private final SecureRandom secureRandom;
    private final FileWalletSecretStore.Argon2Params vaultKdfParams;

    public FileStoredWalletRepository(Path networkDataDir, WalletNetwork network) {
        this(networkDataDir, network, new SecureRandom(), FileWalletSecretStore.Argon2Params.RECOMMENDED);
    }

    FileStoredWalletRepository(Path networkDataDir, WalletNetwork network, SecureRandom secureRandom,
                               FileWalletSecretStore.Argon2Params vaultKdfParams) {
        this.network = Objects.requireNonNull(network, "network is required");
        this.networkWalletDir = Objects.requireNonNull(networkDataDir, "networkDataDir is required")
                .toAbsolutePath()
                .normalize()
                .resolve("wallets");
        this.indexFile = networkWalletDir.resolve("index.json");
        this.objectMapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
        this.secureRandom = Objects.requireNonNull(secureRandom, "secureRandom is required");
        this.vaultKdfParams = Objects.requireNonNull(vaultKdfParams, "vaultKdfParams is required");
    }

    private FileWalletSecretStore vaultStore(String vaultFile) {
        return new FileWalletSecretStore(networkWalletDir.resolve(vaultFile), secureRandom, vaultKdfParams);
    }

    @Override
    public WalletNetwork network() {
        return network;
    }

    @Override
    public String generateMnemonic() {
        return MnemonicUtil.generateNew(Words.TWENTY_FOUR);
    }

    @Override
    public synchronized StoredWalletCreation createRandomWallet(String name, char[] passphrase) {
        String mnemonic = generateMnemonic();
        StoredWallet wallet = importMnemonic(name, mnemonic, passphrase);
        return new StoredWalletCreation(wallet, mnemonic);
    }

    @Override
    public synchronized StoredWallet importMnemonic(String name, String mnemonic, char[] passphrase) {
        requireName(name);
        requirePassphrase(passphrase);
        String normalizedMnemonic = normalizeMnemonic(mnemonic);
        MnemonicUtil.validateMnemonic(normalizedMnemonic);

        Wallet wallet = Wallet.createFromMnemonic(network.toCclNetwork(), normalizedMnemonic, DEFAULT_ACCOUNT_INDEX);
        String baseAddress = wallet.getBaseAddressString(0);
        String stakeAddress = wallet.getStakeAddress();
        String drepId = wallet.getAccountAtIndex(0).drepId();

        List<StoredWallet> wallets = new ArrayList<>(readIndex());
        wallets.stream()
                .filter(existing -> existing.baseAddress().equals(baseAddress))
                .findFirst()
                .ifPresent(existing -> {
                    throw new WalletVaultException("Wallet already exists for this network: " + existing.name());
                });

        String walletId = nextWalletId(wallets);
        String seedId = walletId;
        String vaultFile = walletId + "/vault.json";
        Instant now = Instant.now();
        StoredWallet profile = new StoredWallet(
                walletId,
                seedId,
                name.trim(),
                network.id(),
                DEFAULT_ACCOUNT_INDEX,
                baseAddress,
                stakeAddress,
                drepId,
                vaultFile,
                now,
                now,
                null,
                null);

        byte[] mnemonicBytes = normalizedMnemonic.getBytes(StandardCharsets.UTF_8);
        WalletSecret secret = new WalletSecret(
                SecretKind.MNEMONIC,
                mnemonicBytes,
                network.id(),
                DEFAULT_ACCOUNT_INDEX,
                now);
        try {
            vaultStore(vaultFile).create(secret, passphrase);
        } finally {
            secret.destroy();
            Arrays.fill(mnemonicBytes, (byte) 0);
        }

        wallets.add(profile);
        writeIndex(wallets);
        return profile;
    }

    @Override
    public synchronized StoredWallet createAccount(String seedId, String name, char[] passphrase) {
        int nextIndex = readIndex().stream()
                .filter(wallet -> wallet.seedId().equals(seedId))
                .mapToInt(StoredWallet::accountIndex)
                .max()
                .orElse(DEFAULT_ACCOUNT_INDEX) + 1;
        return createAccountAt(seedId, name, passphrase, nextIndex);
    }

    @Override
    public synchronized StoredWallet createAccountAt(String seedId, String name, char[] passphrase,
                                                     int accountIndex) {
        requireName(name);
        requirePassphrase(passphrase);
        if (seedId == null || seedId.isBlank()) {
            throw new IllegalArgumentException("seedId is required");
        }
        if (accountIndex < 0) {
            throw new IllegalArgumentException("accountIndex must be >= 0");
        }

        List<StoredWallet> wallets = new ArrayList<>(readIndex());
        StoredWallet seedWallet = wallets.stream()
                .filter(wallet -> wallet.seedId().equals(seedId))
                .min(Comparator.comparingInt(StoredWallet::accountIndex))
                .orElseThrow(() -> new WalletVaultException("Wallet seed not found: " + seedId));

        WalletSecret secret = vaultStore(seedWallet.vaultFile()).unlock(passphrase);
        Wallet wallet;
        try {
            if (secret.kind() != SecretKind.MNEMONIC) {
                throw new WalletVaultException("Unsupported wallet secret kind: " + secret.kind());
            }
            String mnemonic = new String(secret.secretBytes(), StandardCharsets.UTF_8);
            wallet = Wallet.createFromMnemonic(network.toCclNetwork(), mnemonic, accountIndex);
        } finally {
            secret.destroy();
        }

        String baseAddress = wallet.getBaseAddressString(0);
        wallets.stream()
                .filter(existing -> existing.baseAddress().equals(baseAddress))
                .findFirst()
                .ifPresent(existing -> {
                    throw new WalletVaultException("Account already exists for this wallet: " + existing.name());
                });

        String accountId = nextWalletId(wallets);
        Instant now = Instant.now();
        StoredWallet account = new StoredWallet(
                accountId,
                seedWallet.seedId(),
                name.trim(),
                network.id(),
                accountIndex,
                baseAddress,
                wallet.getStakeAddress(),
                wallet.getAccountAtIndex(0).drepId(),
                seedWallet.vaultFile(),
                now,
                now,
                null,
                null);
        wallets.add(account);
        writeIndex(wallets);
        return account;
    }

    @Override
    public synchronized List<DiscoveredAccount> discoverAccounts(String seedId, char[] passphrase,
                                                                 Predicate<String> addressUsed,
                                                                 int maxAccounts, int gapLimit) {
        requirePassphrase(passphrase);
        Objects.requireNonNull(addressUsed, "addressUsed is required");
        if (maxAccounts <= 0 || gapLimit <= 0) {
            throw new IllegalArgumentException("maxAccounts and gapLimit must be positive");
        }
        List<StoredWallet> wallets = new ArrayList<>(readIndex());
        StoredWallet seedWallet = wallets.stream()
                .filter(wallet -> wallet.seedId().equals(seedId))
                .min(Comparator.comparingInt(StoredWallet::accountIndex))
                .orElseThrow(() -> new WalletVaultException("Wallet seed not found: " + seedId));
        if (seedWallet.isHardware()) {
            throw new WalletVaultException("Hardware accounts live on the device: " + seedWallet.name());
        }
        Set<Integer> known = wallets.stream()
                .filter(wallet -> wallet.seedId().equals(seedId))
                .map(StoredWallet::accountIndex)
                .collect(Collectors.toSet());

        // One vault unlock for the whole scan — Argon2id is deliberately expensive.
        WalletSecret secret = vaultStore(seedWallet.vaultFile()).unlock(passphrase);
        List<DiscoveredAccount> found = new ArrayList<>();
        try {
            if (secret.kind() != SecretKind.MNEMONIC) {
                throw new WalletVaultException("Unsupported wallet secret kind: " + secret.kind());
            }
            String mnemonic = new String(secret.secretBytes(), StandardCharsets.UTF_8);
            for (int accountIndex = 0; accountIndex < maxAccounts; accountIndex++) {
                if (known.contains(accountIndex)) {
                    // Already stored, so its emptiness says nothing about later
                    // accounts (a restored seed's account 0 is often unused).
                    continue;
                }
                Wallet wallet = Wallet.createFromMnemonic(network.toCclNetwork(), mnemonic, accountIndex);
                if (!hasHistory(wallet, addressUsed, gapLimit)) {
                    break; // BIP-44 account gap of 1: the first empty account ends the scan.
                }
                found.add(new DiscoveredAccount(accountIndex, wallet.getBaseAddressString(0)));
            }
        } finally {
            secret.destroy();
        }
        return List.copyOf(found);
    }

    /** An account is in use if any address within its gap window has history. */
    private static boolean hasHistory(Wallet wallet, Predicate<String> addressUsed, int gapLimit) {
        for (int addressIndex = 0; addressIndex < gapLimit; addressIndex++) {
            if (addressUsed.test(wallet.getBaseAddressString(addressIndex))) {
                return true;
            }
        }
        return false;
    }

    @Override
    public synchronized StoredWallet addWatchOnlyAccount(String seedId, String name,
                                                         int accountIndex, String accountXpubHex) {
        requireName(name);
        if (accountXpubHex == null || accountXpubHex.isBlank()) {
            throw new IllegalArgumentException("accountXpubHex is required");
        }
        List<StoredWallet> wallets = new ArrayList<>(readIndex());
        StoredWallet seedWallet = wallets.stream()
                .filter(wallet -> wallet.seedId().equals(seedId))
                .min(Comparator.comparingInt(StoredWallet::accountIndex))
                .orElseThrow(() -> new WalletVaultException("Wallet not found: " + seedId));
        if (!seedWallet.isHardware()) {
            throw new WalletVaultException(
                    "Not a hardware wallet: " + seedWallet.name() + " — use createAccount instead");
        }
        return addWatchOnly(wallets, seedId, name, seedWallet.deviceType(), accountIndex, accountXpubHex);
    }

    @Override
    public synchronized StoredWallet addWatchOnlyWallet(String name, String deviceType,
                                                        int accountIndex, String accountXpubHex) {
        requireName(name);
        if (accountXpubHex == null || accountXpubHex.isBlank()) {
            throw new IllegalArgumentException("accountXpubHex is required");
        }
        List<StoredWallet> wallets = new ArrayList<>(readIndex());
        // A fresh import starts its own seed group: seedId = the new wallet id.
        return addWatchOnly(wallets, null, name, deviceType, accountIndex, accountXpubHex);
    }

    /**
     * Derives, de-duplicates and persists a watch-only profile.
     *
     * @param seedId the group to join, or null to start a new group (seedId = id)
     */
    private StoredWallet addWatchOnly(List<StoredWallet> wallets, String seedId, String name,
                                      String deviceType, int accountIndex, String accountXpubHex) {
        Wallet wallet = WatchOnlyWallet.fromHex(network.toCclNetwork(), accountXpubHex, accountIndex);
        String baseAddress = wallet.getBaseAddressString(0);

        wallets.stream()
                .filter(existing -> existing.baseAddress().equals(baseAddress))
                .findFirst()
                .ifPresent(existing -> {
                    throw new WalletVaultException("Wallet already exists for this network: " + existing.name());
                });

        String walletId = nextWalletId(wallets);
        Instant now = Instant.now();
        StoredWallet profile = new StoredWallet(
                walletId,
                seedId == null ? walletId : seedId,
                name.trim(),
                network.id(),
                accountIndex,
                baseAddress,
                wallet.getStakeAddress(),
                null,
                null,
                now,
                now,
                deviceType,
                accountXpubHex);
        wallets.add(profile);
        writeIndex(wallets);
        return profile;
    }

    @Override
    public synchronized UnlockedWallet unlockWatchOnly(String walletId) {
        StoredWallet profile = find(walletId)
                .orElseThrow(() -> new WalletVaultException("Wallet not found: " + walletId));
        if (!profile.isHardware()) {
            throw new WalletVaultException("Not a watch-only wallet: " + walletId);
        }
        Wallet wallet = WatchOnlyWallet.fromHex(network.toCclNetwork(), profile.accountXpubHex(), profile.accountIndex());
        return new UnlockedWallet(profile, wallet);
    }

    @Override
    public synchronized UnlockedWallet unlock(String walletId, char[] passphrase) {
        StoredWallet profile = requireSeedWallet(walletId);
        return buildUnlocked(profile, vaultStore(profile.vaultFile()).unlock(passphrase));
    }

    @Override
    public synchronized UnlockedWallet unlock(String walletId, char[] passphrase,
                                              com.bloxbean.cardano.yano.wallet.core.vault.VaultSecondFactor factor) {
        StoredWallet profile = requireSeedWallet(walletId);
        return buildUnlocked(profile, vaultStore(profile.vaultFile()).unlock(passphrase, factor));
    }

    @Override
    public synchronized List<com.bloxbean.cardano.yano.wallet.core.vault.VaultSecondFactor.FactorDescriptor>
            walletFactors(String walletId) {
        StoredWallet profile = find(walletId)
                .orElseThrow(() -> new WalletVaultException("Wallet not found: " + walletId));
        if (profile.isHardware()) {
            return List.of(); // hardware wallets are protected by the device itself
        }
        return vaultStore(profile.vaultFile()).factorDescriptors();
    }

    @Override
    public synchronized void enrollFactor(String walletId, char[] passphrase,
            com.bloxbean.cardano.yano.wallet.core.vault.VaultSecondFactor.FactorDescriptor descriptor,
            com.bloxbean.cardano.yano.wallet.core.vault.VaultSecondFactor factor, boolean passwordless) {
        StoredWallet profile = requireSeedWallet(walletId);
        vaultStore(profile.vaultFile()).enrollFactor(passphrase, descriptor, factor, passwordless);
    }

    @Override
    public synchronized boolean walletPasswordless(String walletId) {
        StoredWallet profile = find(walletId).orElse(null);
        if (profile == null || profile.isHardware()) {
            return false;
        }
        return vaultStore(profile.vaultFile()).isPasswordless();
    }

    @Override
    public synchronized void addFactor(String walletId, char[] passphrase,
            com.bloxbean.cardano.yano.wallet.core.vault.VaultSecondFactor unlockFactor,
            com.bloxbean.cardano.yano.wallet.core.vault.VaultSecondFactor.FactorDescriptor newDescriptor,
            com.bloxbean.cardano.yano.wallet.core.vault.VaultSecondFactor newFactor) {
        StoredWallet profile = requireSeedWallet(walletId);
        vaultStore(profile.vaultFile()).addFactor(passphrase, unlockFactor, newDescriptor, newFactor);
    }

    @Override
    public synchronized void removeFactor(String walletId, char[] passphrase,
            com.bloxbean.cardano.yano.wallet.core.vault.VaultSecondFactor factor) {
        StoredWallet profile = requireSeedWallet(walletId);
        vaultStore(profile.vaultFile()).removeFactor(passphrase, factor);
    }

    /** Finds a software (seed) wallet by id; the vault store validates the passphrase. */
    private StoredWallet requireSeedWallet(String walletId) {
        StoredWallet profile = find(walletId)
                .orElseThrow(() -> new WalletVaultException("Wallet not found: " + walletId));
        if (profile.isHardware()) {
            throw new WalletVaultException("Hardware wallets have no software vault to unlock");
        }
        return profile;
    }

    private UnlockedWallet buildUnlocked(StoredWallet profile, WalletSecret secret) {
        try {
            if (secret.kind() != SecretKind.MNEMONIC) {
                throw new WalletVaultException("Unsupported wallet secret kind: " + secret.kind());
            }
            String mnemonic = new String(secret.secretBytes(), StandardCharsets.UTF_8);
            Wallet wallet = Wallet.createFromMnemonic(network.toCclNetwork(), mnemonic, profile.accountIndex());
            return new UnlockedWallet(profile, wallet);
        } finally {
            secret.destroy();
        }
    }

    @Override
    public synchronized Optional<StoredWallet> find(String walletId) {
        if (walletId == null || walletId.isBlank()) {
            return Optional.empty();
        }
        return readIndex().stream()
                .filter(wallet -> wallet.id().equals(walletId))
                .findFirst();
    }

    @Override
    public synchronized List<StoredWallet> list() {
        return readIndex().stream()
                .sorted(Comparator.comparing(StoredWallet::createdAt))
                .toList();
    }

    private List<StoredWallet> readIndex() {
        if (!Files.exists(indexFile)) {
            return List.of();
        }
        try {
            return objectMapper.readValue(indexFile.toFile(), WALLET_LIST).stream()
                    .map(entry -> new StoredWallet(
                            entry.id(),
                            entry.seedId() == null || entry.seedId().isBlank() ? entry.id() : entry.seedId(),
                            entry.name(),
                            entry.networkId(),
                            entry.accountIndex(),
                            entry.baseAddress(),
                            entry.stakeAddress(),
                            entry.drepId(),
                            entry.vaultFile(),
                            Instant.parse(entry.createdAt()),
                            Instant.parse(entry.updatedAt()),
                            entry.deviceType(),
                            entry.accountXpubHex()))
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException("Unable to read wallet index: " + indexFile, e);
        }
    }

    private void writeIndex(List<StoredWallet> wallets) {
        try {
            Files.createDirectories(networkWalletDir);
            // Unique temp name + cleanup: see FilePendingTransactionStore.writeAll.
            Path tmp = Files.createTempFile(networkWalletDir, indexFile.getFileName().toString(), ".tmp");
            try {
                objectMapper.writeValue(tmp.toFile(), wallets.stream()
                        .map(wallet -> new StoredWalletIndexEntry(
                                wallet.id(),
                                wallet.seedId(),
                                wallet.name(),
                                wallet.networkId(),
                                wallet.accountIndex(),
                                wallet.baseAddress(),
                                wallet.stakeAddress(),
                                wallet.drepId(),
                                wallet.vaultFile(),
                                wallet.createdAt().toString(),
                                wallet.updatedAt().toString(),
                                wallet.deviceType(),
                                wallet.accountXpubHex()))
                        .toList());
                try {
                    Files.move(tmp, indexFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                } catch (AtomicMoveNotSupportedException e) {
                    Files.move(tmp, indexFile, StandardCopyOption.REPLACE_EXISTING);
                }
            } finally {
                Files.deleteIfExists(tmp);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Unable to write wallet index: " + indexFile, e);
        }
    }

    private String nextWalletId(List<StoredWallet> wallets) {
        Map<String, StoredWallet> existing = new LinkedHashMap<>();
        wallets.forEach(wallet -> existing.put(wallet.id(), wallet));
        String id;
        do {
            byte[] bytes = new byte[16];
            secureRandom.nextBytes(bytes);
            id = "wlt_" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        } while (existing.containsKey(id));
        return id;
    }

    private void requireName(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Wallet name is required");
        }
    }

    private void requirePassphrase(char[] passphrase) {
        if (passphrase == null || passphrase.length == 0) {
            throw new WalletVaultException("Wallet passphrase is required");
        }
    }

    private String normalizeMnemonic(String mnemonic) {
        if (mnemonic == null || mnemonic.isBlank()) {
            throw new IllegalArgumentException("Mnemonic is required");
        }
        return mnemonic.trim().replaceAll("\\s+", " ");
    }

    private record StoredWalletIndexEntry(
            String id,
            String seedId,
            String name,
            String networkId,
            int accountIndex,
            String baseAddress,
            String stakeAddress,
            String drepId,
            String vaultFile,
            String createdAt,
            String updatedAt,
            String deviceType,
            String accountXpubHex) {
    }
}
