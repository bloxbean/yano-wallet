package com.bloxbean.cardano.yano.wallet.core.hardware;

import com.bloxbean.cardano.client.address.AddressProvider;
import com.bloxbean.cardano.client.crypto.bip32.key.HdPublicKey;
import com.bloxbean.cardano.client.crypto.cip1852.CIP1852;
import com.bloxbean.cardano.client.util.HexUtil;
import com.bloxbean.cardano.yano.wallet.core.config.WalletNetwork;

import java.util.ArrayList;
import java.util.List;

/**
 * Derives addresses for a watch-only {@link DeviceKeystore} (ADR-034) — with the
 * device disconnected. Given the account-level extended public key the device
 * exported, child keys for payment (role 0 external / role 1 change) and stake
 * (role 2, index 0) are derived in software via CCL's
 * {@code CIP1852.getPublicKeyFromAccountPubKey}, which is standard
 * (non-hardened) BIP32-Ed25519 derivation and therefore reproduces exactly the
 * keys the device derives for the corresponding full paths.
 *
 * <p>The account key already encodes the device's CIP-0003 master-key scheme
 * (Ledger vs Icarus), so no scheme selection is needed here — that distinction
 * only matters when re-deriving a key from a mnemonic in software.
 */
public final class DeviceAddressService {

    private static final int ROLE_EXTERNAL = 0;
    private static final int ROLE_CHANGE = 1;
    private static final int ROLE_STAKE = 2;
    private static final int STAKE_INDEX = 0;

    private final CIP1852 cip1852 = new CIP1852();

    /** External receive address at {@code 1852'/1815'/account'/0/index}. */
    public String receiveAddress(DeviceKeystore keystore, WalletNetwork network, int index) {
        return baseAddress(keystore, network, ROLE_EXTERNAL, index);
    }

    /** Internal change address at {@code 1852'/1815'/account'/1/index}. */
    public String changeAddress(DeviceKeystore keystore, WalletNetwork network, int index) {
        return baseAddress(keystore, network, ROLE_CHANGE, index);
    }

    /** The first {@code count} external receive addresses (indices 0..count-1). */
    public List<String> receiveAddresses(DeviceKeystore keystore, WalletNetwork network, int count) {
        List<String> addresses = new ArrayList<>(Math.max(0, count));
        for (int i = 0; i < count; i++) {
            addresses.add(receiveAddress(keystore, network, i));
        }
        return addresses;
    }

    /** The reward/stake address for the account ({@code .../2/0}). */
    public String stakeAddress(DeviceKeystore keystore, WalletNetwork network) {
        HdPublicKey stakeKey = childKey(keystore, ROLE_STAKE, STAKE_INDEX);
        return AddressProvider.getRewardAddress(stakeKey, network.toCclNetwork()).toBech32();
    }

    private String baseAddress(DeviceKeystore keystore, WalletNetwork network, int role, int index) {
        HdPublicKey paymentKey = childKey(keystore, role, index);
        HdPublicKey stakeKey = childKey(keystore, ROLE_STAKE, STAKE_INDEX);
        return AddressProvider.getBaseAddress(paymentKey, stakeKey, network.toCclNetwork()).toBech32();
    }

    private HdPublicKey childKey(DeviceKeystore keystore, int role, int index) {
        byte[] accountXpub = HexUtil.decodeHexString(keystore.accountXpubHex());
        return cip1852.getPublicKeyFromAccountPubKey(accountXpub, role, index);
    }
}
