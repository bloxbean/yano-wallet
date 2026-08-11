package com.bloxbean.cardano.yano.wallet.core.tx;

import com.bloxbean.cardano.client.account.Account;
import com.bloxbean.cardano.client.cip.cip30.CIP30DataSigner;
import com.bloxbean.cardano.client.cip.cip30.DataSignError;
import com.bloxbean.cardano.client.cip.cip30.DataSignature;
import com.bloxbean.cardano.client.common.cbor.CborSerializationUtil;
import com.bloxbean.cardano.client.transaction.spec.Transaction;
import com.bloxbean.cardano.client.transaction.spec.TransactionBody;
import com.bloxbean.cardano.client.transaction.spec.TransactionWitnessSet;
import com.bloxbean.cardano.client.transaction.spec.VkeyWitness;
import com.bloxbean.cardano.client.util.HexUtil;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Signs dApp-provided transactions and data for the CIP-30 connector (ADR-035,
 * CIP30-M2). Software wallets only — the account's keys are used directly.
 *
 * <p>CIP-30 {@code signTx} returns ONLY the witnesses the wallet adds (the dApp
 * merges them into its own transaction), so we sign a copy and return just the
 * new vkey witnesses — the wallet's payment key (which authorizes its inputs) and
 * its stake key when the transaction carries certificates or withdrawals.
 */
public final class DappSigner {

    private DappSigner() {
    }

    public static String witnessSetHex(Account account, String txHex, boolean partialSign) {
        Transaction tx;
        try {
            tx = Transaction.deserialize(HexUtil.decodeHexString(txHex));
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid transaction CBOR: " + e.getMessage());
        }

        // Snapshot any witnesses already on the tx (script/other signers) so we
        // return only the ones WE add.
        Set<String> preexisting = vkeyHexes(tx);

        Transaction signed = account.sign(tx); // payment key — authorizes the wallet's inputs
        TransactionBody body = tx.getBody();
        if (notEmpty(body.getCerts()) || notEmpty(body.getWithdrawals())) {
            signed = account.signWithStakeKey(signed); // stake key — for certs/withdrawals
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

    private static Set<String> vkeyHexes(Transaction tx) {
        Set<String> keys = new HashSet<>();
        if (tx.getWitnessSet() != null && tx.getWitnessSet().getVkeyWitnesses() != null) {
            for (VkeyWitness w : tx.getWitnessSet().getVkeyWitnesses()) {
                keys.add(HexUtil.encodeHexString(w.getVkey()));
            }
        }
        return keys;
    }

    private static boolean notEmpty(List<?> list) {
        return list != null && !list.isEmpty();
    }
}
