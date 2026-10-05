package com.bloxbean.cardano.yano.wallet.core.wallet;

import com.bloxbean.cardano.hdwallet.Wallet;
import com.bloxbean.cardano.client.crypto.bip32.key.HdPublicKey;
import com.bloxbean.cardano.client.util.HexUtil;

import java.util.List;
import java.util.Objects;
import java.util.stream.IntStream;

public class WalletAddressService {
    /** Raw verification keys only: no private material or extended-key chain codes. */
    public record AddressDetails(String address, String paymentPath, String stakePath,
                                 String paymentPublicKey, String paymentKeyHash,
                                 String stakePublicKey, String stakeKeyHash) {}

    public AddressDetails addressDetails(Wallet wallet, int index) {
        if (index < 0) throw new IllegalArgumentException("Address index must be non-negative");
        HdPublicKey payment;
        HdPublicKey stake;
        if (wallet instanceof WatchOnlyWallet watchOnly) {
            payment = watchOnly.childKey(0, index);
            stake = watchOnly.childKey(2, 0);
        } else {
            var account = wallet.getAccountAtIndex(index);
            payment = account.hdKeyPair().getPublicKey();
            stake = account.stakeHdKeyPair().getPublicKey();
        }
        return new AddressDetails(wallet.getBaseAddressString(index),
                receiveDerivationPath(wallet.getAccountNo(), index),
                "m/1852'/1815'/" + wallet.getAccountNo() + "'/2/0",
                HexUtil.encodeHexString(payment.getKeyData()), HexUtil.encodeHexString(payment.getKeyHash()),
                HexUtil.encodeHexString(stake.getKeyData()), HexUtil.encodeHexString(stake.getKeyHash()));
    }

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
