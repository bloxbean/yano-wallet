package com.bloxbean.cardano.yano.wallet.core.tx;

import com.bloxbean.cardano.client.address.Address;
import com.bloxbean.cardano.client.transaction.spec.Transaction;
import com.bloxbean.cardano.client.transaction.spec.TransactionBody;
import com.bloxbean.cardano.client.transaction.spec.TransactionInput;
import com.bloxbean.cardano.client.transaction.spec.TransactionOutput;
import com.bloxbean.cardano.client.transaction.spec.TransactionWitnessSet;
import com.bloxbean.cardano.client.transaction.spec.Value;
import com.bloxbean.cardano.client.transaction.spec.Withdrawal;
import com.bloxbean.cardano.client.util.HexUtil;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Transactions for {@link DappSignerStakeKeyTest}.
 *
 * <p>The two CIP-113 fixtures are verbatim from the programmable-token
 * registration that failed on 2026-08-13, kept as real bytes rather than
 * reconstructed: what broke was a property of exactly these certificates and this
 * withdrawal, and a hand-built approximation would not have reproduced it. The
 * reg transaction carries its original body with an emptied witness set — the
 * script witnesses run to tens of kilobytes and none of them bear on which keys
 * the wallet contributes.
 */
final class Cip113Fixtures {

    private Cip113Fixtures() {
    }

    /** Two RegCerts over SCRIPT credentials; the dApp budgeted one vkey witness. */
    static final String INIT_TX = load("init-tx.hex");

    /** Zero withdrawal from a SCRIPT reward account; required_signers = our payment key. */
    static final String REG_TX = load("reg-tx.hex");

    /** A minimal transaction: one input, one output, nothing else. */
    static final String PLAIN_TX = plain(null, null);

    static String withdrawalFrom(String rewardAddress) {
        return plain(rewardAddress, null);
    }

    static String requiringSigner(byte[] keyHash) {
        return plain(null, keyHash);
    }

    private static String load(String name) {
        try (InputStream in = Cip113Fixtures.class.getResourceAsStream("/cip113/" + name)) {
            if (in == null) {
                throw new IllegalStateException("missing test resource /cip113/" + name);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).strip();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String plain(String rewardAddress, byte[] requiredSigner) {
        TransactionInput input = TransactionInput.builder()
                .transactionId("f26a96a12b7aed2a868f2e249a6e100d2d671c414314593d55be5d497daac949")
                .index(0)
                .build();
        TransactionOutput output = TransactionOutput.builder()
                .address(new Address(HexUtil.decodeHexString(
                        "00c7252730673b7a0fb9dfb68615c4431e821991f7dbe02298e9bd6b65"
                                + "2313d3b872ebcf1ba123b4031c2cb0f279147a36cb3d2c585c0a0231")).toBech32())
                .value(Value.builder().coin(BigInteger.valueOf(2_000_000)).build())
                .build();
        TransactionBody.TransactionBodyBuilder body = TransactionBody.builder()
                .inputs(List.of(input))
                .outputs(List.of(output))
                .fee(BigInteger.valueOf(200_000));
        if (rewardAddress != null) {
            body.withdrawals(List.of(new Withdrawal(rewardAddress, BigInteger.ZERO)));
        }
        if (requiredSigner != null) {
            body.requiredSigners(List.of(requiredSigner));
        }
        Transaction tx = Transaction.builder()
                .body(body.build())
                .witnessSet(TransactionWitnessSet.builder().build())
                .build();
        try {
            return tx.serializeToHex();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
