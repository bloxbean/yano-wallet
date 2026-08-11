package com.bloxbean.cardano.yano.wallet.core.tx;

import co.nstant.in.cbor.model.Map;
import com.bloxbean.cardano.client.account.Account;
import com.bloxbean.cardano.client.address.Address;
import com.bloxbean.cardano.client.cip.cip30.CIP30DataSigner;
import com.bloxbean.cardano.client.cip.cip30.DataSignature;
import com.bloxbean.cardano.client.common.cbor.CborSerializationUtil;
import com.bloxbean.cardano.client.common.model.Networks;
import com.bloxbean.cardano.client.transaction.spec.Transaction;
import com.bloxbean.cardano.client.transaction.spec.TransactionBody;
import com.bloxbean.cardano.client.transaction.spec.TransactionInput;
import com.bloxbean.cardano.client.transaction.spec.TransactionOutput;
import com.bloxbean.cardano.client.transaction.spec.TransactionWitnessSet;
import com.bloxbean.cardano.client.transaction.spec.Value;
import com.bloxbean.cardano.client.transaction.spec.VkeyWitness;
import com.bloxbean.cardano.client.transaction.spec.Withdrawal;
import com.bloxbean.cardano.client.util.HexUtil;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The CIP-30 signer must add exactly the wallet's witnesses (its payment key,
 * plus the stake key when the tx has certs/withdrawals) and produce a valid
 * CIP-8 data signature. Verified with CCL's own crypto (deserialize the witness
 * set; verify signData via CIP30DataSigner).
 */
class DappSignerTest {

    private static final String MNEMONIC =
            "test walk nut penalty hip pave soap entry language right filter choice";

    private final Account account = new Account(Networks.testnet(), MNEMONIC);

    private String txHex(TransactionBody body) throws Exception {
        Transaction tx = Transaction.builder()
                .body(body)
                .witnessSet(TransactionWitnessSet.builder().build())
                .build();
        return HexUtil.encodeHexString(tx.serialize());
    }

    private TransactionBody.TransactionBodyBuilder baseBody() {
        return TransactionBody.builder()
                .inputs(List.of(TransactionInput.builder().transactionId("aa".repeat(32)).index(0).build()))
                .outputs(List.of(new TransactionOutput(account.baseAddress(),
                        Value.builder().coin(BigInteger.valueOf(1_000_000)).build())))
                .fee(BigInteger.valueOf(170_000))
                .ttl(1000);
    }

    private List<VkeyWitness> witnesses(String witnessSetHex) throws Exception {
        Map map = (Map) CborSerializationUtil.deserialize(HexUtil.decodeHexString(witnessSetHex));
        return TransactionWitnessSet.deserialize(map).getVkeyWitnesses();
    }

    @Test
    void signsPlainTxWithThePaymentKeyOnly() throws Exception {
        String ws = DappSigner.witnessSetHex(account, txHex(baseBody().build()), true);

        List<VkeyWitness> vkeys = witnesses(ws);
        assertThat(vkeys).hasSize(1);
        assertThat(vkeys.get(0).getVkey())
                .isEqualTo(account.hdKeyPair().getPublicKey().getKeyData());
        assertThat(vkeys.get(0).getSignature()).hasSize(64);
    }

    @Test
    void addsStakeWitnessWhenTxHasAWithdrawal() throws Exception {
        TransactionBody body = baseBody()
                .withdrawals(List.of(new Withdrawal(account.stakeAddress(),
                        BigInteger.valueOf(5_000_000))))
                .build();

        List<VkeyWitness> vkeys = witnesses(DappSigner.witnessSetHex(account, txHex(body), true));

        assertThat(vkeys).hasSize(2);
        assertThat(vkeys).extracting(w -> HexUtil.encodeHexString(w.getVkey()))
                .contains(HexUtil.encodeHexString(account.hdKeyPair().getPublicKey().getKeyData()),
                        HexUtil.encodeHexString(account.stakeHdKeyPair().getPublicKey().getKeyData()));
    }

    @Test
    void signDataProducesAVerifiableCip8Signature() {
        byte[] addr = new Address(account.baseAddress()).getBytes();
        byte[] payload = "yano cip-30 test".getBytes();

        DataSignature sig = DappSigner.signData(account, addr, payload);

        assertThat(sig.signature()).isNotBlank();
        assertThat(sig.key()).isNotBlank();
        assertThat(CIP30DataSigner.INSTANCE.verify(sig)).isTrue();
    }
}
