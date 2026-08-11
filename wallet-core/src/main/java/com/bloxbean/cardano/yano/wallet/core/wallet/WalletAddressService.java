package com.bloxbean.cardano.yano.wallet.core.wallet;

import com.bloxbean.cardano.hdwallet.Wallet;

import java.util.List;
import java.util.Objects;
import java.util.stream.IntStream;

public class WalletAddressService {
    public WalletAccountView accountView(StoredWallet profile, Wallet wallet, int receiveAddressCount) {
        Objects.requireNonNull(profile, "profile is required");
        Objects.requireNonNull(wallet, "wallet is required");
        if (receiveAddressCount <= 0) {
            throw new IllegalArgumentException("receiveAddressCount must be positive");
        }

        int accountIndex = profile.accountIndex();
        List<WalletAddressView> receiveAddresses = IntStream.range(0, receiveAddressCount)
                .mapToObj(index -> receiveAddress(wallet, accountIndex, index))
                .toList();

        // Stake address and DRep id come from the stored profile (public,
        // recorded at creation) rather than a key-bearing Account, so this works
        // for watch-only hardware wallets too.
        return new WalletAccountView(
                profile.id(),
                profile.name(),
                profile.networkId(),
                accountIndex,
                profile.stakeAddress() != null ? profile.stakeAddress() : wallet.getStakeAddress(),
                profile.drepId(),
                receiveAddresses);
    }

    private WalletAddressView receiveAddress(Wallet wallet, int accountIndex, int addressIndex) {
        return new WalletAddressView(
                accountIndex,
                addressIndex,
                "receive",
                receiveDerivationPath(accountIndex, addressIndex),
                wallet.getBaseAddress(addressIndex).toBech32(),
                wallet.getEntAddress(addressIndex).toBech32());
    }

    private String receiveDerivationPath(int accountIndex, int addressIndex) {
        return "m/1852'/1815'/" + accountIndex + "'/0/" + addressIndex;
    }
}
