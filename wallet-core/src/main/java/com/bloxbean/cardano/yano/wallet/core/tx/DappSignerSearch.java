package com.bloxbean.cardano.yano.wallet.core.tx;

import com.bloxbean.cardano.client.account.Account;
import com.bloxbean.cardano.client.address.Address;
import com.bloxbean.cardano.client.api.UtxoSupplier;
import com.bloxbean.cardano.client.transaction.spec.Transaction;
import com.bloxbean.cardano.client.transaction.spec.TransactionInput;
import com.bloxbean.cardano.client.transaction.util.TransactionUtil;
import com.bloxbean.cardano.client.util.HexUtil;
import com.bloxbean.cardano.hdwallet.Wallet;
import com.bloxbean.cardano.yano.wallet.core.wallet.WalletAddresses;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Bounded, history-independent software signing discovery in one selected HD account.
 * No signatures are made here. Receive and change chains each get their own 30/50 window;
 * explicitly known paths are searched first. Never interpret an unmatched hash as absent
 * from the seed: it may belong to another party, account, or an index outside the window.
 */
public final class DappSignerSearch {
    public static final int DEFAULT_LIMIT = 30;
    public static final int EXTENDED_LIMIT = 50;
    public static final int MAX_KNOWN_PATHS = 10_000;

    private DappSignerSearch() {}

    /** Public payment path within the selected account; never contains key material. */
    public record KeyPath(int role, int index) {
        public KeyPath {
            if ((role != 0 && role != 1) || index < 0)
                throw new IllegalArgumentException("Invalid payment path");
        }
        public String describe(int account) {
            return "m/1852'/1815'/" + account + "'/" + role + "/" + index;
        }
    }

    /** Immutable review data bound to the exact original transaction body. */
    public record Plan(String txHash, int account, List<KeyPath> paymentPaths,
                       boolean stake, List<String> unmatched, int limit) {
        public Plan {
            paymentPaths = List.copyOf(paymentPaths);
            unmatched = List.copyOf(unmatched);
        }
        public boolean hasSigners() { return stake || !paymentPaths.isEmpty(); }
        public String description() {
            StringBuilder text = new StringBuilder("Selected HD account: " + account
                    + "\nSearched known paths and receive/change indexes 0–" + (limit - 1) + ".\n");
            for (KeyPath path : paymentPaths) text.append("Payment: ").append(path.describe(account)).append('\n');
            if (stake) text.append("Stake: m/1852'/1815'/").append(account).append("'/2/0\n");
            text.append("Unmatched signer hashes: ").append(unmatched.size());
            for (String hash : unmatched) text.append('\n').append(hash);
            if (!unmatched.isEmpty()) text.append("\nThese may belong to other wallets or lie outside this search.");
            return text.toString();
        }
    }

    /** Resolve actual spent/collateral outputs, never reference inputs, to establish input ownership. */
    public static Plan transaction(Wallet wallet, String txHex, UtxoSupplier supplier,
                                   Collection<KeyPath> known, int limit) {
        if (txHex == null || txHex.length() > 131072)
            throw new IllegalArgumentException("Transaction exceeds signing review size limit");
        Transaction tx;
        try { tx = Transaction.deserialize(HexUtil.decodeHexString(txHex)); }
        catch (Exception e) { throw new IllegalArgumentException("Invalid transaction CBOR", e); }
        Set<String> wanted = new LinkedHashSet<>();
        if (tx.getBody().getRequiredSigners() != null)
            for (byte[] hash : tx.getBody().getRequiredSigners()) {
                if (hash.length != 28) throw new IllegalArgumentException("Invalid required signer hash");
                wanted.add(HexUtil.encodeHexString(hash));
            }
        Set<TransactionInput> inputs = new LinkedHashSet<>();
        if (tx.getBody().getInputs() != null) inputs.addAll(tx.getBody().getInputs());
        if (tx.getBody().getCollateral() != null) inputs.addAll(tx.getBody().getCollateral());
        if (inputs.size() > 256) throw new IllegalArgumentException("Too many inputs for signing review");
        for (TransactionInput input : inputs) {
            var output = supplier.getTxOutput(input.getTransactionId(), input.getIndex())
                    .orElseThrow(() -> new IllegalArgumentException("Cannot resolve signing input "
                            + input.getTransactionId() + "#" + input.getIndex()));
            if (!input.getTransactionId().equals(output.getTxHash()) || input.getIndex() != output.getOutputIndex())
                throw new IllegalArgumentException("Resolved input identity mismatch");
            String hash = paymentHash(new Address(output.getAddress()).getBytes());
            if (hash != null) wanted.add(hash);
        }
        Set<String> witnessed = DappSigner.verifiedWitnessHashes(tx, HexUtil.decodeHexString(txHex));
        wanted.removeAll(witnessed);
        Account primary = wallet.getAccountAtIndex(0);
        boolean stake = DappSigner.needsStakeKey(primary, tx.getBody())
                && !witnessed.contains(HexUtil.encodeHexString(primary.stakeHdKeyPair().getPublicKey().getKeyHash()));
        if (stake) wanted.remove(HexUtil.encodeHexString(primary.stakeHdKeyPair().getPublicKey().getKeyHash()));
        List<KeyPath> paths = match(wallet, wanted, known, limit);
        return new Plan(TransactionUtil.getTxHash(HexUtil.decodeHexString(txHex)), wallet.getAccountNo(),
                paths, stake, List.copyOf(wanted), limit);
    }

    /** One data-signing credential, bound to exact address and payload bytes. */
    public record DataPlan(int account, String address, String payload, KeyPath paymentPath,
                           boolean stake, int limit) {
        public boolean hasSigner() { return stake || paymentPath != null; }
        public String description() {
            return "Selected HD account: " + account + "\nSearched known paths and receive/change indexes 0–" + (limit - 1)
                    + ".\n" + (paymentPath != null ? "Payment: " + paymentPath.describe(account)
                    : stake ? "Stake: m/1852'/1815'/" + account + "'/2/0" : "Requested key not found in this account within the search window.");
        }
    }

    /** Search a CIP-8 key without consulting address history or signing unrelated keys. */
    public static DataPlan data(Wallet wallet, byte[] address, byte[] payload, Collection<KeyPath> known, int limit) {
        if (payload.length > 65536) throw new IllegalArgumentException("Data payload exceeds 64 KB");
        if (limit != DEFAULT_LIMIT && limit != EXTENDED_LIMIT)
            throw new IllegalArgumentException("Signer search must use 30 or 50 addresses per chain");
        if (address.length != 29 && address.length != 57) throw new IllegalArgumentException("Unsupported signing address length");
        int type = (address[0] & 255) >>> 4;
        if (!((type == 0 || type == 2) && address.length == 57 || (type == 6 || type == 14) && address.length == 29))
            throw new IllegalArgumentException("Data signing requires a key payment or key reward address");
        Account primary = wallet.getAccountAtIndex(0);
        if ((address[0] & 15) != (new Address(primary.baseAddress()).getBytes()[0] & 15))
            throw new IllegalArgumentException("Signing address is on another network");
        String hash = HexUtil.encodeHexString(Arrays.copyOfRange(address, 1, 29));
        boolean stake = type == 14 && hash.equals(HexUtil.encodeHexString(primary.stakeHdKeyPair().getPublicKey().getKeyHash()));
        List<KeyPath> paths = type == 14 ? List.of() : match(wallet, new LinkedHashSet<>(List.of(hash)), known, limit);
        return new DataPlan(wallet.getAccountNo(), HexUtil.encodeHexString(address), HexUtil.encodeHexString(payload),
                paths.isEmpty() ? null : paths.getFirst(), stake, limit);
    }

    private static List<KeyPath> match(Wallet wallet, Set<String> wanted, Collection<KeyPath> known, int limit) {
        if (limit != DEFAULT_LIMIT && limit != EXTENDED_LIMIT)
            throw new IllegalArgumentException("Signer search must use 30 or 50 addresses per chain");
        if (known.size() > MAX_KNOWN_PATHS) throw new IllegalArgumentException("Too many known signing paths");
        Set<KeyPath> candidates = new LinkedHashSet<>(known);
        for (int index = 0; index < limit; index++) {
            candidates.add(new KeyPath(0, index));
            candidates.add(new KeyPath(1, index));
        }
        List<KeyPath> matches = new ArrayList<>();
        for (KeyPath path : candidates) {
            if (wanted.isEmpty()) break;
            if (Thread.currentThread().isInterrupted()) throw new IllegalStateException("Signer search interrupted");
            Account key = WalletAddresses.account(wallet, path.role(), path.index());
            if (wanted.remove(HexUtil.encodeHexString(key.hdKeyPair().getPublicKey().getKeyHash())))
                matches.add(path);
        }
        return matches;
    }

    private static String paymentHash(byte[] address) {
        if (address.length < 29) throw new IllegalArgumentException("Invalid Shelley address");
        int type = (address[0] & 0xff) >>> 4;
        if (type == 0 || type == 2 || type == 4 || type == 6)
            return HexUtil.encodeHexString(Arrays.copyOfRange(address, 1, 29));
        if (type == 1 || type == 3 || type == 5 || type == 7) return null;
        throw new IllegalArgumentException("Unsupported signing input address type");
    }
}
