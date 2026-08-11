package com.bloxbean.cardano.yano.wallet.core.wallet;

import java.time.Instant;

public record StoredWallet(
        String id,
        String seedId,
        String name,
        String networkId,
        int accountIndex,
        String baseAddress,
        String stakeAddress,
        String drepId,
        String vaultFile,
        Instant createdAt,
        Instant updatedAt,
        // Watch-only hardware wallet (ADR-034): the device family and the
        // account-level extended public key. Null for seed (vault-backed) wallets.
        String deviceType,
        String accountXpubHex) {

    public StoredWallet {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("id is required");
        }
        if (seedId == null || seedId.isBlank()) {
            seedId = id;
        }
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name is required");
        }
        if (networkId == null || networkId.isBlank()) {
            throw new IllegalArgumentException("networkId is required");
        }
        if (accountIndex < 0) {
            throw new IllegalArgumentException("accountIndex must not be negative");
        }
        if (baseAddress == null || baseAddress.isBlank()) {
            throw new IllegalArgumentException("baseAddress is required");
        }
        boolean hasVault = vaultFile != null && !vaultFile.isBlank();
        boolean hasDeviceKey = accountXpubHex != null && !accountXpubHex.isBlank();
        if (hasVault == hasDeviceKey) {
            throw new IllegalArgumentException(
                    "exactly one of vaultFile (seed wallet) or accountXpubHex (hardware wallet) is required");
        }
        createdAt = createdAt == null ? Instant.now() : createdAt;
        updatedAt = updatedAt == null ? createdAt : updatedAt;
    }

    /** True for a watch-only hardware wallet (no vault; signs via the device). */
    public boolean isHardware() {
        return accountXpubHex != null && !accountXpubHex.isBlank();
    }
}
