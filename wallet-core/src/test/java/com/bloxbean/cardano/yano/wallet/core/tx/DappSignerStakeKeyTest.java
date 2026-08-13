package com.bloxbean.cardano.yano.wallet.core.tx;

import com.bloxbean.cardano.client.account.Account;
import com.bloxbean.cardano.client.common.cbor.CborSerializationUtil;
import com.bloxbean.cardano.client.common.model.Networks;
import com.bloxbean.cardano.client.transaction.spec.TransactionWitnessSet;
import com.bloxbean.cardano.client.util.HexUtil;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Which keys a dApp transaction is signed with (ADR-035).
 *
 * <p>Every case here guards one number: how many vkey witnesses come back. A dApp
 * commits to a fee before it asks us to sign, and that fee is computed for a
 * specific witness count. An extra witness makes the transaction ~104 bytes larger
 * than its own fee covers, so the node answers {@code FeeTooSmallUTxO} and the user
 * sees "submit failed" — with nothing to suggest the wallet over-signed.
 *
 * <p>The two fixtures are the real transactions from a CIP-113 programmable-token
 * registration that failed this way on 2026-08-13.
 */
class DappSignerStakeKeyTest {

    private static final String MNEMONIC =
            "test walk nut penalty hip pave soap entry language right filter choice";

    private static Account account() {
        return new Account(Networks.testnet(), MNEMONIC);
    }

    private static int witnessCount(String witnessSetHex) {
        try {
            TransactionWitnessSet ws = TransactionWitnessSet.deserialize(
                    (co.nstant.in.cbor.model.Map) CborSerializationUtil.deserialize(
                            HexUtil.decodeHexString(witnessSetHex)));
            return ws.getVkeyWitnesses() == null ? 0 : ws.getVkeyWitnesses().size();
        } catch (Exception e) {
            throw new AssertionError("unreadable witness set: " + e, e);
        }
    }

    @Test
    void certificatesOverScriptCredentialsDoNotDrawTheStakeKey() {
        // The CIP-113 "init" transaction: two RegCerts, both over SCRIPT
        // credentials (8201581c…), registering the token's own scripts. Nothing to
        // do with this wallet's stake key. The dApp budgeted for exactly one vkey
        // witness — 4352 bytes against a 4248-byte body — so a second one put it
        // 4,580 lovelace short of its own declared fee.
        String hex = Cip113Fixtures.INIT_TX;

        assertThat(witnessCount(DappSigner.witnessSetHex(account(), hex, true)))
                .as("payment key only")
                .isEqualTo(1);
    }

    @Test
    void aWithdrawalFromSomeoneElsesRewardAccountDoesNotDrawTheStakeKey() {
        // The CIP-113 "reg" transaction: a zero-value withdrawal from reward
        // account f09839a0df… — the f0 header means a SCRIPT credential, so the
        // withdrawal is the validator's, not ours. Its required_signers names our
        // PAYMENT key, which the one witness below covers.
        String hex = Cip113Fixtures.REG_TX;

        assertThat(witnessCount(DappSigner.witnessSetHex(account(), hex, true)))
                .as("payment key only")
                .isEqualTo(1);
    }

    @Test
    void aWithdrawalFromOUROwnRewardAccountStillDrawsTheStakeKey() {
        // The other direction matters just as much: narrowing the rule must not
        // start dropping a witness that is genuinely required.
        String hex = Cip113Fixtures.withdrawalFrom(account().stakeAddress());

        assertThat(witnessCount(DappSigner.witnessSetHex(account(), hex, true)))
                .as("payment key + stake key")
                .isEqualTo(2);
    }

    @Test
    void ourStakeKeyInRequiredSignersDrawsIt() {
        // A dApp that wants our stake key says so here, which is why erring toward
        // not signing is safe.
        String hex = Cip113Fixtures.requiringSigner(
                account().stakeHdKeyPair().getPublicKey().getKeyHash());

        assertThat(witnessCount(DappSigner.witnessSetHex(account(), hex, true)))
                .as("payment key + stake key")
                .isEqualTo(2);
    }

    @Test
    void aPlainPaymentNeedsOnlyThePaymentKey() {
        assertThat(witnessCount(DappSigner.witnessSetHex(account(), Cip113Fixtures.PLAIN_TX, true)))
                .isEqualTo(1);
    }
}
