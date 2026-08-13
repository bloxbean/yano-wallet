package com.bloxbean.cardano.yano.wallet.app;

import com.bloxbean.cardano.client.address.Address;
import com.bloxbean.cardano.client.transaction.spec.Transaction;
import com.bloxbean.cardano.client.transaction.spec.TransactionBody;
import com.bloxbean.cardano.client.transaction.spec.TransactionInput;
import com.bloxbean.cardano.client.transaction.spec.TransactionOutput;
import com.bloxbean.cardano.client.transaction.spec.TransactionWitnessSet;
import com.bloxbean.cardano.client.transaction.spec.Value;
import com.bloxbean.cardano.client.util.HexUtil;
import com.bloxbean.cardano.yano.wallet.connector.Cip30Exception;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * When a failed submit is worth retrying (bloxbean/yano#66).
 *
 * <p>Yano's mempool resolves inputs against the persisted UTxO set alone, so a
 * transaction spending an output of another transaction still in the mempool is
 * refused for the ~20s until the parent reaches a block. cardano-node accepts
 * these, so a dApp submitting a chained pair works everywhere else — which is how
 * a CIP-113 token registration failed on 2026-08-13 with its setup transaction
 * on chain and the one spending its output rejected.
 *
 * <p>The retry has to stay narrow: a rejection that is not this must surface
 * immediately, or every genuinely invalid transaction costs the user a silent
 * 45-second wait before the same error.
 */
class ChainedSubmitRetryTest {

    private static final String PARENT =
            "f46c8c8dca903fefe4955f434a9093ba19da7be0baf010df3b924195b151eced";
    private static final String STRANGER =
            "101209e9c8973c82c17ab0278bce9cbf45dd753f801d13a94b56f8fc391dabda";

    private WalletCip30Wallet wallet;

    @BeforeEach
    void setUp() {
        wallet = new WalletCip30Wallet(null, () -> null);
    }

    private static String txSpending(String parentTxHash, int index) {
        TransactionOutput output = TransactionOutput.builder()
                .address(new Address(HexUtil.decodeHexString(
                        "00c7252730673b7a0fb9dfb68615c4431e821991f7dbe02298e9bd6b65"
                                + "2313d3b872ebcf1ba123b4031c2cb0f279147a36cb3d2c585c0a0231")).toBech32())
                .value(Value.builder().coin(BigInteger.valueOf(2_000_000)).build())
                .build();
        Transaction tx = Transaction.builder()
                .body(TransactionBody.builder()
                        .inputs(List.of(TransactionInput.builder()
                                .transactionId(parentTxHash).index(index).build()))
                        .outputs(List.of(output))
                        .fee(BigInteger.valueOf(200_000))
                        .build())
                .witnessSet(TransactionWitnessSet.builder().build())
                .build();
        try {
            return tx.serializeToHex();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static Cip30Exception notFound() {
        return Cip30Exception.internal(
                "Node rejected the transaction: UtxoNotFound: UTXO not found: " + PARENT + "#2");
    }

    @Test
    void waitsWhenTheMissingInputComesFromATransactionWeJustSubmitted() {
        wallet.submitted.put(PARENT, Boolean.TRUE);

        TransactionInput waitFor = wallet.chainedParentAwaitingABlock(txSpending(PARENT, 2), notFound());

        assertThat(waitFor).isNotNull();
        assertThat(waitFor.getTransactionId()).isEqualTo(PARENT);
        assertThat(waitFor.getIndex()).isEqualTo(2);
    }

    @Test
    void doesNotWaitForAnInputWeNeverCreated() {
        // The output really does not exist. Retrying cannot help, and pretending
        // otherwise costs the user 45 seconds before the identical error.
        wallet.submitted.put(PARENT, Boolean.TRUE);

        assertThat(wallet.chainedParentAwaitingABlock(txSpending(STRANGER, 1), notFound()))
                .isNull();
    }

    @Test
    void doesNotWaitOnAFreshSessionThatHasSubmittedNothing() {
        assertThat(wallet.chainedParentAwaitingABlock(txSpending(PARENT, 2), notFound()))
                .isNull();
    }

    @Test
    void anyOtherRejectionSurfacesImmediately() {
        wallet.submitted.put(PARENT, Boolean.TRUE);
        String tx = txSpending(PARENT, 2);

        for (String reason : List.of(
                "Node rejected the transaction: FeeTooSmallUTxO",
                "Node rejected the transaction: ScriptFailure: validator returned false",
                "Node rejected the transaction: BadInputsUTxO",
                "Failed to submit transaction: connection refused")) {
            assertThat(wallet.chainedParentAwaitingABlock(tx, Cip30Exception.internal(reason)))
                    .as(reason)
                    .isNull();
        }
    }

    @Test
    void collateralAndReferenceInputsCountToo() {
        // The node resolves all three kinds through the same loop, so a parent
        // referenced as collateral or as a reference input stalls just the same.
        wallet.submitted.put(PARENT, Boolean.TRUE);

        Transaction tx = Transaction.builder()
                .body(TransactionBody.builder()
                        .inputs(List.of(TransactionInput.builder()
                                .transactionId(STRANGER).index(0).build()))
                        .collateral(List.of(TransactionInput.builder()
                                .transactionId(PARENT).index(2).build()))
                        .outputs(List.of())
                        .fee(BigInteger.valueOf(200_000))
                        .build())
                .witnessSet(TransactionWitnessSet.builder().build())
                .build();
        String hex;
        try {
            hex = tx.serializeToHex();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }

        assertThat(wallet.chainedParentAwaitingABlock(hex, notFound())).isNotNull();
    }

    @Test
    void unreadableCborIsNotAChainingProblem() {
        wallet.submitted.put(PARENT, Boolean.TRUE);

        assertThat(wallet.chainedParentAwaitingABlock("not cbor at all", notFound())).isNull();
    }
}
