package com.bloxbean.cardano.yano.wallet.core.tx;

import com.bloxbean.cardano.client.account.Account;
import com.bloxbean.cardano.client.address.Address;
import com.bloxbean.cardano.client.cip.cip30.CIP30DataSigner;
import com.bloxbean.cardano.client.cip.cip30.DataSignError;
import com.bloxbean.cardano.client.cip.cip30.DataSignature;
import com.bloxbean.cardano.client.common.cbor.CborSerializationUtil;
import com.bloxbean.cardano.client.transaction.TransactionSigner;
import com.bloxbean.cardano.client.transaction.spec.Transaction;
import com.bloxbean.cardano.client.transaction.spec.TransactionBody;
import com.bloxbean.cardano.client.transaction.spec.TransactionWitnessSet;
import com.bloxbean.cardano.client.transaction.spec.VkeyWitness;
import com.bloxbean.cardano.client.transaction.spec.Withdrawal;
import com.bloxbean.cardano.client.transaction.spec.cert.Certificate;
import com.bloxbean.cardano.client.transaction.util.TransactionUtil;
import com.bloxbean.cardano.client.util.HexUtil;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Signs dApp-provided transactions and data for the CIP-30 connector (ADR-035,
 * CIP30-M2). Software wallets only — the account's keys are used directly.
 *
 * <p>CIP-30 {@code signTx} returns ONLY the witnesses the wallet adds (the dApp
 * merges them into its own transaction), so we sign a copy and return just the
 * new vkey witnesses — the wallet's payment key, which authorizes its inputs, and
 * its stake key <em>only</em> when the transaction genuinely acts on this wallet's
 * stake credential. See {@link #needsStakeKey}: signing with a key the dApp did
 * not budget a witness for is not a harmless extra, it makes the transaction
 * larger than its own fee allows and the node refuses it.
 */
public final class DappSigner {

    private DappSigner() {
    }

    public static String witnessSetHex(Account account, String txHex, boolean partialSign) {
        byte[] txBytes = HexUtil.decodeHexString(txHex);
        Transaction tx;
        try {
            tx = Transaction.deserialize(txBytes);
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid transaction CBOR: " + e.getMessage());
        }

        // Snapshot any witnesses already on the tx (script/other signers) so we
        // return only the ones WE add.
        Set<String> preexisting = vkeyHexes(tx);

        // Sign the ORIGINAL bytes, never a re-encoding of them.
        //
        // account.sign(Transaction) serialises the object back to CBOR and hashes
        // that, which is only correct while a round-trip is byte-exact — and it is
        // not. A dApp datum carrying an indefinite-length map (`bf … ff`) comes back
        // from cardano-client-lib as a definite-length one (`a3 …`), one byte
        // shorter, so the body hash changes and every signature over it is invalid.
        // A CIP-113 registration hit exactly this on 2026-08-13: the node answered
        // InvalidSignaturesInWitnesses and the dApp reported only "sign and submit
        // failed". The setup transaction in the same flow happened to round-trip
        // cleanly and went through, which is what made it look like a problem with
        // the second transaction rather than with signing.
        //
        // TransactionSigner.sign(byte[], …) takes the body slice out of the bytes it
        // was handed, hashes that, and splices the witness in without touching the
        // body — so whatever the dApp encoded is what gets signed.
        byte[] signedBytes = TransactionSigner.INSTANCE.sign(txBytes, account.hdKeyPair());
        if (needsStakeKey(account, tx.getBody())) {
            signedBytes = TransactionSigner.INSTANCE.sign(signedBytes, account.stakeHdKeyPair());
        }

        // The body must be identical to what we were given. This cannot fail with
        // the signer above; it is here because when it DID fail the symptom was a
        // node-side rejection a user could not act on, and the hardware path has
        // carried the same gate since it was written.
        String originalHash = TransactionUtil.getTxHash(txBytes);
        if (!originalHash.equals(TransactionUtil.getTxHash(signedBytes))) {
            throw new IllegalStateException("Refusing to sign: signing changed the transaction's"
                    + " encoding, so the signature would not match what the dApp submits.");
        }

        Transaction signed;
        try {
            signed = Transaction.deserialize(signedBytes);
        } catch (Exception e) {
            throw new IllegalStateException("Could not read back the signed transaction: " + e.getMessage());
        }

        List<VkeyWitness> added = new ArrayList<>();
        if (signed.getWitnessSet() != null && signed.getWitnessSet().getVkeyWitnesses() != null) {
            for (VkeyWitness w : signed.getWitnessSet().getVkeyWitnesses()) {
                if (!preexisting.contains(HexUtil.encodeHexString(w.getVkey()))) {
                    added.add(w);
                }
            }
        }

        TransactionWitnessSet witnessSet = TransactionWitnessSet.builder().vkeyWitnesses(added).build();
        try {
            return HexUtil.encodeHexString(CborSerializationUtil.serialize(witnessSet.serialize()));
        } catch (Exception e) {
            throw new IllegalStateException("Failed to encode witness set: " + e.getMessage());
        }
    }

    /** CIP-8 data signing; the address selects the key (payment vs stake). */
    public static DataSignature signData(Account account, byte[] addressBytes, byte[] payloadBytes) {
        try {
            return CIP30DataSigner.INSTANCE.signData(addressBytes, payloadBytes, account);
        } catch (DataSignError e) {
            throw new IllegalStateException("Data signing failed: " + e.getMessage());
        }
    }

    /**
     * Whether this transaction genuinely needs the wallet's <em>stake</em> key.
     *
     * <p>It used to be enough that the transaction carried any certificate or any
     * withdrawal. That is wrong, and expensively so: a dApp's certificates and
     * withdrawals are usually over <em>script</em> credentials that have nothing
     * to do with this wallet, and every witness we add that the dApp did not plan
     * for makes the transaction ~104 bytes larger than the fee it already committed
     * to. The fee is fixed inside the body we are signing, so the node rejects the
     * result with {@code FeeTooSmallUTxO} and the dApp reports "submit failed".
     * Observed against a CIP-113 token registration on 2026-08-13: the dApp had
     * budgeted 4352 bytes — the unsigned transaction plus exactly one vkey witness
     * — and the second witness put it 104 bytes over, 4,580 lovelace short.
     *
     * <p>So the question is not "does this transaction have certificates" but "is
     * our stake credential one of the things it acts on". Three ways it can be, and
     * certificates are matched by scanning their CBOR for our stake key hash rather
     * than by enumerating certificate types: there are a dozen of them, they gain
     * new members every era, and a credential is always a 28-byte hash — so a hit
     * is conclusive and a miss cannot be a false negative.
     *
     * <p>Erring toward NOT signing is deliberate. Both mistakes fail, but a missing
     * witness fails loudly and only for the rare dApp that wants our stake key —
     * which will name it in {@code required_signers} and so be caught below —
     * while an extra witness breaks every ordinary transaction whose fee was
     * computed exactly.
     */
    private static boolean needsStakeKey(Account account, TransactionBody body) {
        String stakeKeyHash;
        try {
            stakeKeyHash = HexUtil.encodeHexString(
                    account.stakeHdKeyPair().getPublicKey().getKeyHash()).toLowerCase(Locale.ROOT);
        } catch (RuntimeException e) {
            return false; // no stake key to offer
        }

        for (byte[] signer : orEmpty(body.getRequiredSigners())) {
            if (stakeKeyHash.equals(HexUtil.encodeHexString(signer).toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        for (Withdrawal withdrawal : orEmpty(body.getWithdrawals())) {
            if (rewardAddressHex(withdrawal.getRewardAddress()).contains(stakeKeyHash)) {
                return true;
            }
        }
        for (Certificate certificate : orEmpty(body.getCerts())) {
            try {
                if (certificate.getCborHex().toLowerCase(Locale.ROOT).contains(stakeKeyHash)) {
                    return true;
                }
            } catch (Exception e) {
                // A certificate we cannot re-encode tells us nothing either way.
                // Treat it as "not ours" for the reason in the javadoc above.
            }
        }
        return false;
    }

    /** Reward addresses reach us as bech32 or hex depending on the encoder; take both. */
    private static String rewardAddressHex(String rewardAddress) {
        if (rewardAddress == null || rewardAddress.isBlank()) {
            return "";
        }
        try {
            return HexUtil.encodeHexString(new Address(rewardAddress).getBytes()).toLowerCase(Locale.ROOT);
        } catch (RuntimeException e) {
            return rewardAddress.toLowerCase(Locale.ROOT);
        }
    }

    private static <T> List<T> orEmpty(List<T> list) {
        return list == null ? List.of() : list;
    }

    private static Set<String> vkeyHexes(Transaction tx) {
        Set<String> keys = new HashSet<>();
        if (tx.getWitnessSet() != null && tx.getWitnessSet().getVkeyWitnesses() != null) {
            for (VkeyWitness w : tx.getWitnessSet().getVkeyWitnesses()) {
                keys.add(HexUtil.encodeHexString(w.getVkey()));
            }
        }
        return keys;
    }
}
