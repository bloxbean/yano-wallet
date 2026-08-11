package com.bloxbean.cardano.yano.wallet.app;

import com.bloxbean.cardano.client.crypto.cip1852.CIP1852;
import com.bloxbean.cardano.client.transaction.spec.TransactionWitnessSet;
import com.bloxbean.cardano.client.transaction.spec.VkeyWitness;
import com.bloxbean.cardano.client.util.HexUtil;
import com.bloxbean.cardano.yano.wallet.hardware.ledger.LedgerWitness;

import java.util.ArrayList;
import java.util.List;

/**
 * Turns the device's per-path signatures into a CCL {@link TransactionWitnessSet}
 * (ADR-034). Each witness path {@code 1852'/1815'/account'/role/index} maps to
 * the public key derived from the account xpub at {@code role/index}, giving a
 * {@code VkeyWitness(pubkey, signature)}.
 */
final class HardwareSigning {

    private HardwareSigning() {
    }

    static TransactionWitnessSet witnessSet(String accountXpubHex, List<LedgerWitness> witnesses) {
        byte[] accountXpub = HexUtil.decodeHexString(accountXpubHex);
        CIP1852 cip1852 = new CIP1852();
        List<VkeyWitness> vkeys = new ArrayList<>();
        for (LedgerWitness witness : witnesses) {
            long[] path = witness.path();
            // Full path is 1852'/1815'/account'/role/index; role and index are the
            // last two elements (non-hardened).
            int role = (int) path[path.length - 2];
            int index = (int) path[path.length - 1];
            byte[] pubKey = cip1852.getPublicKeyFromAccountPubKey(accountXpub, role, index).getKeyData();
            vkeys.add(new VkeyWitness(pubKey, witness.signature()));
        }
        return TransactionWitnessSet.builder().vkeyWitnesses(vkeys).build();
    }
}
