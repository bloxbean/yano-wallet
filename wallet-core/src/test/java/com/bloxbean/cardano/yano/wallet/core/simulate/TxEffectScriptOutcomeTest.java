package com.bloxbean.cardano.yano.wallet.core.simulate;

import com.bloxbean.cardano.client.address.AddressProvider;
import com.bloxbean.cardano.client.address.Credential;
import com.bloxbean.cardano.client.common.model.Networks;
import com.bloxbean.cardano.client.transaction.spec.Transaction;
import com.bloxbean.cardano.client.transaction.spec.TransactionBody;
import com.bloxbean.cardano.client.transaction.spec.TransactionInput;
import com.bloxbean.cardano.client.transaction.spec.TransactionOutput;
import com.bloxbean.cardano.client.transaction.spec.TransactionWitnessSet;
import com.bloxbean.cardano.client.transaction.spec.Value;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.Executor;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ADR-042 SIM-M2: the script-outcome state machine.
 *
 * <p>Everything here defends one distinction. "Your scripts fail" is a reason not
 * to sign — the transaction dies on-chain and burns collateral. "I could not
 * check" is no information at all. The node cannot tell them apart on its own,
 * because it answers {@code EvaluationFailure} both when a script genuinely
 * fails and when it simply cannot see one of the inputs (a chained dApp
 * transaction). Only the engine, which knows whether every input resolved, may
 * promote a failure to a verdict.
 */
class TxEffectScriptOutcomeTest {

    private static final BigInteger ADA = BigInteger.valueOf(1_000_000);
    private static final Executor DIRECT = Runnable::run;

    private static byte[] credential(byte fill) {
        byte[] bytes = new byte[28];
        Arrays.fill(bytes, fill);
        return bytes;
    }

    private final String myAddress = AddressProvider.getBaseAddress(
            Credential.fromKey(credential((byte) 0x11)),
            Credential.fromKey(credential((byte) 0x22)), Networks.testnet()).toBech32();
    private final WalletOwnership ownership = WalletOwnership.ofAddresses(List.of(myAddress));

    private static class FakePort implements TxSimulationPort {
        private ResolvedOutput resolution;
        private ScriptEvaluation evaluation = ScriptEvaluation.success(List.of());
        private SimulationCapabilities capabilities = new SimulationCapabilities(
                SimulationCapabilities.Support.AVAILABLE, SimulationCapabilities.Support.AVAILABLE, null, null);
        int evaluateCalls;

        @Override
        public SimulationCapabilities capabilities() {
            return capabilities;
        }

        @Override
        public ResolvedOutput resolveOutput(String txHash, int outputIndex) {
            return resolution;
        }

        @Override
        public ScriptEvaluation evaluate(String txHex) {
            evaluateCalls++;
            return evaluation;
        }
    }

    private FakePort portResolvingEverythingToUs() {
        FakePort port = new FakePort();
        port.resolution = new ResolvedOutput(myAddress, ADA.multiply(BigInteger.TEN), List.of(), false, false);
        return port;
    }

    /** A fully built script transaction: script-data hash AND attached redeemers. */
    private String scriptTxHex() {
        return txHex(true, true);
    }

    /**
     * A script transaction whose redeemers are not attached yet — the shape a
     * dApp sends when it intends to fill them in after signing.
     */
    private String unattachedScriptTxHex() {
        return txHex(true, false);
    }

    private String plainTxHex() {
        return txHex(false, false);
    }

    /** A fully built script transaction with an extra tweak to the body. */
    private String scriptTxHexWith(
            java.util.function.Consumer<TransactionBody.TransactionBodyBuilder> tweak) {
        return txHex(true, true, tweak);
    }

    private String txHex(boolean withScripts, boolean withRedeemers) {
        return txHex(withScripts, withRedeemers, builder -> { });
    }

    private String txHex(boolean withScripts, boolean withRedeemers,
                         java.util.function.Consumer<TransactionBody.TransactionBodyBuilder> tweak) {
        TransactionBody.TransactionBodyBuilder body = TransactionBody.builder()
                .inputs(List.of(TransactionInput.builder().transactionId("a".repeat(64)).index(0).build()))
                .outputs(List.of(TransactionOutput.builder()
                        .address(myAddress)
                        .value(Value.builder().coin(ADA.multiply(BigInteger.valueOf(9))).build())
                        .build()))
                .fee(ADA);
        if (withScripts) {
            body.scriptDataHash(new byte[32]);
        }
        tweak.accept(body);
        TransactionWitnessSet witnessSet = new TransactionWitnessSet();
        if (withRedeemers) {
            witnessSet.setRedeemers(List.of(com.bloxbean.cardano.client.plutus.spec.Redeemer.builder()
                    .tag(com.bloxbean.cardano.client.plutus.spec.RedeemerTag.Spend)
                    .index(java.math.BigInteger.ZERO)
                    .data(com.bloxbean.cardano.client.plutus.spec.BigIntPlutusData.of(42))
                    .exUnits(com.bloxbean.cardano.client.plutus.spec.ExUnits.builder()
                            .mem(BigInteger.valueOf(1700))
                            .steps(BigInteger.valueOf(476468))
                            .build())
                    .build()));
        }
        try {
            return HexFormat.of().formatHex(Transaction.builder()
                    .body(body.build())
                    .witnessSet(witnessSet)
                    .build()
                    .serialize());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private TxEffect analyse(String hex, FakePort port) {
        return new TxEffectEngine(port, DIRECT).analyse(hex, ownership);
    }

    @Test
    void aTransactionWithoutScriptsIsNeverSentToTheEvaluator() {
        FakePort port = portResolvingEverythingToUs();

        TxEffect effect = analyse(plainTxHex(), port);

        assertThat(effect.scriptOutcome()).isEqualTo(TxEffect.ScriptOutcome.NO_SCRIPTS);
        assertThat(port.evaluateCalls).as("no redeemers means nothing to evaluate").isZero();
    }

    @Test
    void successfulEvaluationCarriesTheExUnits() {
        FakePort port = portResolvingEverythingToUs();
        port.evaluation = ScriptEvaluation.success(List.of(
                new ScriptEvaluation.RedeemerCost("spend", 0, 1700, 476468),
                new ScriptEvaluation.RedeemerCost("mint", 1, 900, 120000)));

        TxEffect effect = analyse(scriptTxHex(), port);

        assertThat(effect.scriptOutcome()).isEqualTo(TxEffect.ScriptOutcome.SUCCESS);
        assertThat(effect.scriptCosts()).hasSize(2);
        assertThat(port.evaluateCalls).isEqualTo(1);
    }

    @Test
    void aGenuineScriptFailureIsReportedAsAFailure() {
        // Every input known + the node ran the scripts + they failed. This is the
        // one case where the wallet may say the transaction will fail on-chain.
        FakePort port = portResolvingEverythingToUs();
        port.evaluation = ScriptEvaluation.failure("validation failed for spend:0");

        TxEffect effect = analyse(scriptTxHex(), port);

        assertThat(effect.scriptOutcome()).isEqualTo(TxEffect.ScriptOutcome.FAILED);
        assertThat(effect.scriptMessage()).contains("spend:0");
    }

    @Test
    void anUnresolvableInputDowngradesAFailureToCouldNotVerify() {
        // ADR-042 limit 3: the node resolves inputs from its own UTxO set, so a
        // chained transaction spending a not-yet-on-chain output comes back as a
        // failure even when its scripts are fine. Reporting that as "your scripts
        // fail" would be a confident lie — and the evaluator must not even be
        // asked, since its answer could not be trusted either way.
        FakePort port = new FakePort();
        port.resolution = null;                                   // not in the UTxO set
        port.evaluation = ScriptEvaluation.failure("unknown input");

        TxEffect effect = analyse(scriptTxHex(), port);

        assertThat(effect.scriptOutcome()).isEqualTo(TxEffect.ScriptOutcome.COULD_NOT_VERIFY);
        assertThat(effect.scriptMessage()).contains("unknown to your node");
        assertThat(port.evaluateCalls).isZero();
    }

    @Test
    void aTransactionWhoseRedeemersAreNotAttachedYetGetsNoVerdict() {
        // The dApp will add redeemers after signing. Evaluating now either errors
        // or "succeeds" having run nothing — both would be confident statements
        // about scripts that never executed.
        FakePort port = portResolvingEverythingToUs();
        port.evaluation = ScriptEvaluation.failure("no redeemer for input 0");

        TxEffect effect = analyse(unattachedScriptTxHex(), port);

        assertThat(effect.scriptOutcome()).isEqualTo(TxEffect.ScriptOutcome.COULD_NOT_VERIFY);
        assertThat(effect.scriptMessage()).contains("not attached yet");
        assertThat(port.evaluateCalls).isZero();
    }

    @Test
    void anUnresolvableReferenceInputAlsoBlocksAVerdict() {
        // The node's evaluator resolves reference inputs too, so one it cannot
        // see produces an EvaluationFailure indistinguishable from a real script
        // error — a chained transaction referencing a sibling's output would be
        // condemned as "your scripts fail" when nothing is wrong with them.
        FakePort port = new FakePort() {
            @Override
            public ResolvedOutput resolveOutput(String txHash, int outputIndex) {
                return txHash.startsWith("f") ? null : super.resolveOutput(txHash, outputIndex);
            }
        };
        port.resolution = new ResolvedOutput(myAddress, ADA.multiply(BigInteger.TEN), List.of(), false, false);
        port.evaluation = ScriptEvaluation.failure("UTXO not found: ff..#0");

        TxEffect effect = analyse(scriptTxHexWith(builder ->
                builder.referenceInputs(List.of(
                        TransactionInput.builder().transactionId("f".repeat(64)).index(0).build()))), port);

        assertThat(effect.scriptOutcome()).isEqualTo(TxEffect.ScriptOutcome.COULD_NOT_VERIFY);
        assertThat(port.evaluateCalls).isZero();
    }

    @Test
    void unpricedCollateralBlocksAVerdictAndDegradesTheSummary() {
        // Collateral is the loss when scripts fail. An unpriced collateral input
        // is not a smaller amount at risk — it is an unknown one.
        FakePort port = new FakePort() {
            @Override
            public ResolvedOutput resolveOutput(String txHash, int outputIndex) {
                return txHash.startsWith("c") ? null : super.resolveOutput(txHash, outputIndex);
            }
        };
        port.resolution = new ResolvedOutput(myAddress, ADA.multiply(BigInteger.TEN), List.of(), false, false);
        port.evaluation = ScriptEvaluation.failure("UTXO not found: cc..#0");

        TxEffect effect = analyse(scriptTxHexWith(builder ->
                builder.collateral(List.of(
                        TransactionInput.builder().transactionId("c".repeat(64)).index(0).build()))), port);

        assertThat(effect.scriptOutcome()).isEqualTo(TxEffect.ScriptOutcome.COULD_NOT_VERIFY);
        assertThat(effect.completeness()).isEqualTo(TxEffect.Completeness.INCOMPLETE);
        assertThat(effect.limitation()).contains("collateral could not be checked");
        assertThat(port.evaluateCalls).isZero();
    }

    @Test
    void anUnavailableEvaluatorIsNeverReportedAsAFailingTransaction() {
        FakePort port = portResolvingEverythingToUs();
        port.evaluation = ScriptEvaluation.unavailable("Script evaluation not initialized.");

        TxEffect effect = analyse(scriptTxHex(), port);

        assertThat(effect.scriptOutcome()).isEqualTo(TxEffect.ScriptOutcome.COULD_NOT_VERIFY);
    }

    @Test
    void aNodeWithoutEvaluationIsNotAskedAndIsReportedHonestly() {
        FakePort port = portResolvingEverythingToUs();
        port.capabilities = new SimulationCapabilities(
                SimulationCapabilities.Support.AVAILABLE, SimulationCapabilities.Support.UNAVAILABLE, null, null);

        TxEffect effect = analyse(scriptTxHex(), port);

        assertThat(effect.scriptOutcome()).isEqualTo(TxEffect.ScriptOutcome.COULD_NOT_VERIFY);
        assertThat(effect.scriptMessage()).contains("cannot evaluate Plutus scripts");
        assertThat(port.evaluateCalls).isZero();
    }

    @Test
    void anEvaluatorThatThrowsIsNeverReportedAsAFailingTransaction() {
        FakePort port = new FakePort() {
            @Override
            public ScriptEvaluation evaluate(String txHex) {
                throw new TxSimulationException("node fell over");
            }
        };
        port.resolution = new ResolvedOutput(myAddress, ADA.multiply(BigInteger.TEN), List.of(), false, false);

        TxEffect effect = analyse(scriptTxHex(), port);

        assertThat(effect.scriptOutcome()).isEqualTo(TxEffect.ScriptOutcome.COULD_NOT_VERIFY);
        assertThat(effect.scriptMessage()).contains("could not be reached");
    }

    @Test
    void theValueDiffSurvivesAScriptFailure() {
        // A failing script does not make the amounts wrong; the user still needs
        // to see what the transaction would have done.
        FakePort port = portResolvingEverythingToUs();
        port.evaluation = ScriptEvaluation.failure("validation failed");

        TxEffect effect = analyse(scriptTxHex(), port);

        assertThat(effect.completeness()).isEqualTo(TxEffect.Completeness.COMPLETE);
        assertThat(effect.lovelaceDelta()).isEqualTo(ADA.negate());
    }
}
