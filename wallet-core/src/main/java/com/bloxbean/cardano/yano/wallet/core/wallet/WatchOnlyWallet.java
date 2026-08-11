package com.bloxbean.cardano.yano.wallet.core.wallet;

import com.bloxbean.cardano.client.account.Account;
import com.bloxbean.cardano.client.address.Address;
import com.bloxbean.cardano.client.address.AddressProvider;
import com.bloxbean.cardano.client.api.model.WalletUtxo;
import com.bloxbean.cardano.client.common.model.Network;
import com.bloxbean.cardano.client.crypto.bip32.HdKeyPair;
import com.bloxbean.cardano.client.crypto.bip32.key.HdPublicKey;
import com.bloxbean.cardano.client.crypto.cip1852.CIP1852;
import com.bloxbean.cardano.client.transaction.spec.Transaction;
import com.bloxbean.cardano.client.util.HexUtil;
import com.bloxbean.cardano.hdwallet.Wallet;

import java.util.Optional;
import java.util.Set;

/**
 * A watch-only {@link Wallet} backed by an account-level extended public key
 * (ADR-034) — for hardware wallets, whose seed never reaches the host. Public
 * address derivation (base/enterprise/stake) works via standard non-hardened
 * CIP-1852 child derivation, so balance, receive, and history flow through the
 * same services as a seed wallet. Every operation that needs private keys
 * (account materialisation, signing, mnemonic/root key) throws — hardware
 * signing is routed to the device, not this object.
 */
public final class WatchOnlyWallet implements Wallet {

    private static final int ROLE_EXTERNAL = 0;
    private static final int ROLE_STAKE = 2;
    private static final int STAKE_INDEX = 0;

    private final Network network;
    private final byte[] accountXpub;
    private final CIP1852 cip1852 = new CIP1852();

    private int accountNo;
    private int gapLimit = 20;
    private int[] indexesToScan;

    public WatchOnlyWallet(Network network, byte[] accountXpub, int accountNo) {
        if (accountXpub == null || accountXpub.length != 64) {
            throw new IllegalArgumentException("accountXpub must be 64 bytes (pubkey || chain code)");
        }
        this.network = network;
        this.accountXpub = accountXpub.clone();
        this.accountNo = accountNo;
    }

    public static WatchOnlyWallet fromHex(Network network, String accountXpubHex, int accountNo) {
        return new WatchOnlyWallet(network, HexUtil.decodeHexString(accountXpubHex), accountNo);
    }

    private HdPublicKey childKey(int role, int index) {
        return cip1852.getPublicKeyFromAccountPubKey(accountXpub, role, index);
    }

    // --- public address derivation (all the services need) ---

    @Override
    public Address getBaseAddress(int index) {
        return AddressProvider.getBaseAddress(childKey(ROLE_EXTERNAL, index), childKey(ROLE_STAKE, STAKE_INDEX), network);
    }

    @Override
    public Address getBaseAddress(int account, int index) {
        return getBaseAddress(index);
    }

    @Override
    public String getBaseAddressString(int index) {
        return getBaseAddress(index).toBech32();
    }

    @Override
    public Address getEntAddress(int index) {
        return AddressProvider.getEntAddress(childKey(ROLE_EXTERNAL, index), network);
    }

    @Override
    public String getStakeAddress() {
        return AddressProvider.getRewardAddress(childKey(ROLE_STAKE, STAKE_INDEX), network).toBech32();
    }

    @Override
    public Network getNetwork() {
        return network;
    }

    // --- scan / account bookkeeping ---

    @Override
    public int getAccountNo() {
        return accountNo;
    }

    @Override
    public void setAccountNo(int accountNo) {
        this.accountNo = accountNo;
    }

    @Override
    public int getGapLimit() {
        return gapLimit;
    }

    @Override
    public void setGapLimit(int gapLimit) {
        this.gapLimit = gapLimit;
    }

    @Override
    public int[] getIndexesToScan() {
        return indexesToScan;
    }

    @Override
    public void setIndexesToScan(int[] indexesToScan) {
        this.indexesToScan = indexesToScan;
    }

    @Override
    public Optional<HdKeyPair> getRootKeyPair() {
        return Optional.empty();
    }

    @Override
    public Optional<byte[]> getRootPvtKey() {
        return Optional.empty();
    }

    // --- private-key operations are unavailable (watch-only) ---

    @Override
    public Account getAccountAtIndex(int index) {
        throw watchOnly();
    }

    @Override
    public Account getAccount(int account, int index) {
        throw watchOnly();
    }

    @Override
    public String getMnemonic() {
        throw watchOnly();
    }

    @Override
    public Transaction sign(Transaction transaction, Set<WalletUtxo> utxos) {
        throw watchOnly();
    }

    @Override
    public Transaction signWithStakeKey(Transaction transaction) {
        throw watchOnly();
    }

    private static UnsupportedOperationException watchOnly() {
        return new UnsupportedOperationException("watch-only hardware wallet: signing happens on the device");
    }
}
