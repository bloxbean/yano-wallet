package com.bloxbean.cardano.yano.wallet.core.hardware;

/**
 * A watch-only account backed by a hardware device (ADR-034). Holds only public
 * key material — never a seed or private key. Address derivation, balance, and
 * history all run off these xpubs with the device disconnected (via CCL's
 * {@code CIP1852.getPublicKeyFromAccountPubKey}); only signing needs the device.
 *
 * <p>Populated in HW-M2 (account import via {@code getExtendedPublicKey}); this
 * record anchors the model now so the keystore/repository layer can distinguish
 * a device account from a seed account.
 *
 * @param type            device family
 * @param accountIndex    CIP-1852 account index (the {@code account'} level)
 * @param accountXpubHex  hex of the account-level extended public key
 *                        (32-byte pubkey || 32-byte chain code)
 * @param stakeXpubHex    hex of the stake (role 2) extended public key
 */
public record DeviceKeystore(DeviceType type, int accountIndex,
                             String accountXpubHex, String stakeXpubHex) {

    public DeviceKeystore {
        if (type == null) {
            throw new IllegalArgumentException("device type is required");
        }
        if (accountIndex < 0) {
            throw new IllegalArgumentException("accountIndex must be >= 0");
        }
        if (accountXpubHex == null || accountXpubHex.isBlank()) {
            throw new IllegalArgumentException("accountXpubHex is required");
        }
    }
}
