package com.bloxbean.cardano.yano.wallet.core.wallet;

import com.bloxbean.cardano.client.account.Account;
import com.bloxbean.cardano.client.address.Address;
import com.bloxbean.cardano.client.crypto.cip1852.DerivationPath;
import com.bloxbean.cardano.hdwallet.Wallet;

/** Shelley base addresses on the receive (0) and change (1) chains. */
public final class WalletAddresses {
    private WalletAddresses() {}

    public static DerivationPath path(int account, int role, int index) {
        if ((role != 0 && role != 1) || account < 0 || index < 0) {
            throw new IllegalArgumentException("Invalid payment address path");
        }
        var path = role == 0
                ? DerivationPath.createExternalAddressDerivationPathForAccount(account)
                : DerivationPath.createInternalAddressDerivationPathForAccount(account);
        path.getIndex().setValue(index);
        return path;
    }

    public static Address baseAddress(Wallet wallet, int role, int index) {
        var path = path(wallet.getAccountNo(), role, index);
        if (role == 0) {
            return new Address(wallet.getBaseAddressString(index), path);
        }
        if (wallet instanceof WatchOnlyWallet watchOnly) {
            return new Address(watchOnly.getChangeAddress(index).toBech32(), path);
        }
        return new Address(account(wallet, role, index).baseAddress(), path);
    }

    public static Account account(Wallet wallet, int role, int index) {
        if (role == 0) return wallet.getAccountAtIndex(index);
        return Account.createFromRootKey(wallet.getNetwork(), wallet.getRootPvtKey()
                .orElseThrow(() -> new IllegalStateException("Wallet has no software signing key")),
                path(wallet.getAccountNo(), role, index));
    }
}
