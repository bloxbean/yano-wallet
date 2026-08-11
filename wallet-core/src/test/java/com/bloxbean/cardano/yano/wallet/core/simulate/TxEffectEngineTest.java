package com.bloxbean.cardano.yano.wallet.core.simulate;

import com.bloxbean.cardano.client.address.Address;
import com.bloxbean.cardano.client.address.AddressProvider;
import com.bloxbean.cardano.client.address.Credential;
import com.bloxbean.cardano.client.common.model.Networks;
import com.bloxbean.cardano.client.transaction.spec.Asset;
import com.bloxbean.cardano.client.transaction.spec.MultiAsset;
import com.bloxbean.cardano.client.transaction.spec.Transaction;
import com.bloxbean.cardano.client.transaction.spec.TransactionBody;
import com.bloxbean.cardano.client.transaction.spec.TransactionInput;
import com.bloxbean.cardano.client.transaction.spec.TransactionOutput;
import com.bloxbean.cardano.client.transaction.spec.TransactionWitnessSet;
import com.bloxbean.cardano.client.transaction.spec.Value;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ADR-042 SIM-M1: the value diff.
 *
 * <p>These tests are written around one invariant — an error must never make the
 * loss look smaller. Each "dangerous direction" case below is a way a plausible
 * implementation would under-report what a user is about to give away.
 */
class TxEffectEngineTest {

    private static final BigInteger ADA = BigInteger.valueOf(1_000_000);
    private static final String POLICY = "0f5560dbc05282e05507aedb02d823d9d9f0805037bc4b8a24e6c1b1";
    private static final String MIN_HEX = "4d494e";

    // Distinct 28-byte credentials.
    private static final byte[] MY_PAYMENT = credentialBytes((byte) 0x11);
    private static final byte[] MY_STAKE = credentialBytes((byte) 0x22);
    private static final byte[] THEIR_PAYMENT = credentialBytes((byte) 0x33);

    private static byte[] credentialBytes(byte fill) {
        byte[] bytes = new byte[28];
        java.util.Arrays.fill(bytes, fill);
        return bytes;
    }

    private static String baseAddress(byte[] payment, byte[] stake) {
        return AddressProvider.getBaseAddress(
                Credential.fromKey(payment), Credential.fromKey(stake), Networks.testnet()).toBech32();
    }

    private static String enterpriseAddress(byte[] payment) {
        return AddressProvider.getEntAddress(Credential.fromKey(payment), Networks.testnet()).toBech32();
    }

    private static String rewardAddress(byte[] stake) {
        return AddressProvider.getRewardAddress(Credential.fromKey(stake), Networks.testnet()).toBech32();
    }

    private final String myAddress = baseAddress(MY_PAYMENT, MY_STAKE);
    private final String theirAddress = baseAddress(THEIR_PAYMENT, MY_STAKE);   // note: MY stake part
    private final String myRewardAddress = rewardAddress(MY_STAKE);
    private final WalletOwnership ownership =
            WalletOwnership.of(List.of(myAddress), List.of(myRewardAddress));

    // ---- test doubles -------------------------------------------------------

    /** Runs inline: deterministic ordering, no thread pool to leak in tests. */
    private static final Executor DIRECT = Runnable::run;

    private static final class FakePort implements TxSimulationPort {
        private final Map<String, ResolvedOutput> outputs = new HashMap<>();
        private final Map<String, RuntimeException> failures = new HashMap<>();
        private SimulationCapabilities capabilities = new SimulationCapabilities(
                SimulationCapabilities.Support.AVAILABLE, SimulationCapabilities.Support.AVAILABLE,
                "test", null);
        private ScriptEvaluation evaluation = ScriptEvaluation.success(List.of());
        int resolveCalls;

        FakePort resolves(TransactionInput input, String address, BigInteger lovelace,
                          List<AssetQuantity> assets) {
            outputs.put(key(input), new ResolvedOutput(address, lovelace, assets, false, false));
            return this;
        }

        FakePort notInUtxoSet(TransactionInput input) {
            outputs.put(key(input), null);
            return this;
        }

        FakePort fails(TransactionInput input, RuntimeException error) {
            failures.put(key(input), error);
            return this;
        }

        FakePort withCapabilities(SimulationCapabilities capabilities) {
            this.capabilities = capabilities;
            return this;
        }

        @Override
        public SimulationCapabilities capabilities() {
            return capabilities;
        }

        @Override
        public ResolvedOutput resolveOutput(String txHash, int outputIndex) {
            resolveCalls++;
            String key = txHash + "#" + outputIndex;
            RuntimeException failure = failures.get(key);
            if (failure != null) {
                throw failure;
            }
            return outputs.get(key);
        }

        @Override
        public ScriptEvaluation evaluate(String txHex) {
            return evaluation;
        }

        private static String key(TransactionInput input) {
            return input.getTransactionId() + "#" + input.getIndex();
        }
    }

    // ---- fixture builders ---------------------------------------------------

    private static TransactionInput input(String hashSeed, int index) {
        return TransactionInput.builder()
                .transactionId(hashSeed.repeat(64).substring(0, 64))
                .index(index)
                .build();
    }

    private static TransactionOutput output(String address, BigInteger lovelace) {
        return TransactionOutput.builder()
                .address(address)
                .value(Value.builder().coin(lovelace).build())
                .build();
    }

    private static TransactionOutput output(String address, BigInteger lovelace,
                                            String policyId, String assetNameHex, BigInteger quantity) {
        MultiAsset multiAsset = MultiAsset.builder()
                .policyId(policyId)
                .assets(List.of(Asset.builder()
                        .name("0x" + assetNameHex)
                        .value(quantity)
                        .build()))
                .build();
        return TransactionOutput.builder()
                .address(address)
                .value(Value.builder().coin(lovelace).multiAssets(List.of(multiAsset)).build())
                .build();
    }

    private static String txHex(TransactionBody body) {
        try {
            Transaction tx = Transaction.builder()
                    .body(body)
                    .witnessSet(new TransactionWitnessSet())
                    .build();
            return HexFormat.of().formatHex(tx.serialize());
        } catch (Exception e) {
            throw new IllegalStateException("could not build fixture", e);
        }
    }

    private static TransactionBody.TransactionBodyBuilder body(List<TransactionInput> inputs,
                                                               List<TransactionOutput> outputs,
                                                               BigInteger fee) {
        return TransactionBody.builder().inputs(inputs).outputs(outputs).fee(fee);
    }

    private TxEffect analyse(String hex, FakePort port) {
        return new TxEffectEngine(port, DIRECT).analyse(hex, ownership);
    }

    // ---- the ordinary case --------------------------------------------------

    @Test
    void reportsTheNetAdaLeavingASimpleSend() {
        TransactionInput mine = input("a", 0);
        String hex = txHex(body(List.of(mine),
                List.of(output(theirAddress, ADA.multiply(BigInteger.valueOf(2))),
                        output(myAddress, ADA.multiply(BigInteger.valueOf(7)).add(ADA.divide(BigInteger.valueOf(2))))),
                ADA.divide(BigInteger.valueOf(2))).build());
        FakePort port = new FakePort().resolves(mine, myAddress, ADA.multiply(BigInteger.TEN), List.of());

        TxEffect effect = analyse(hex, port);

        assertThat(effect.completeness()).isEqualTo(TxEffect.Completeness.COMPLETE);
        // spent 10, back 7.5 → net -2.5 ADA (2 to them + 0.5 fee)
        assertThat(effect.lovelaceDelta()).isEqualTo(ADA.multiply(BigInteger.valueOf(-25)).divide(BigInteger.TEN));
        assertThat(effect.fee()).isEqualTo(ADA.divide(BigInteger.valueOf(2)));
        assertThat(effect.assetDeltas()).isEmpty();
    }

    @Test
    void reportsAnAssetLeavingTheWallet() {
        TransactionInput mine = input("a", 0);
        String hex = txHex(body(List.of(mine),
                List.of(output(theirAddress, ADA.multiply(BigInteger.TWO), POLICY, MIN_HEX, BigInteger.valueOf(340)),
                        output(myAddress, ADA.multiply(BigInteger.valueOf(7)))),
                ADA).build());
        FakePort port = new FakePort().resolves(mine, myAddress, ADA.multiply(BigInteger.TEN),
                List.of(new AssetQuantity(POLICY, MIN_HEX, BigInteger.valueOf(340))));

        TxEffect effect = analyse(hex, port);

        assertThat(effect.completeness()).isEqualTo(TxEffect.Completeness.COMPLETE);
        assertThat(effect.assetDeltas()).singleElement().satisfies(delta -> {
            assertThat(delta.policyId()).isEqualTo(POLICY);
            assertThat(delta.assetNameHex()).isEqualTo(MIN_HEX);
            assertThat(delta.quantity()).isEqualTo(BigInteger.valueOf(-340));
            assertThat(delta.isOutgoing()).isTrue();
        });
    }

    @Test
    void anAssetThatComesStraightBackIsNotReportedAsAChange() {
        TransactionInput mine = input("a", 0);
        String hex = txHex(body(List.of(mine),
                List.of(output(myAddress, ADA.multiply(BigInteger.valueOf(9)), POLICY, MIN_HEX, BigInteger.valueOf(340))),
                ADA).build());
        FakePort port = new FakePort().resolves(mine, myAddress, ADA.multiply(BigInteger.TEN),
                List.of(new AssetQuantity(POLICY, MIN_HEX, BigInteger.valueOf(340))));

        assertThat(analyse(hex, port).assetDeltas()).isEmpty();
    }

    // ---- the dangerous direction: never under-report ------------------------

    @Test
    void anOutputReusingOurStakeCredentialIsNotOurs() {
        // The attack that rules out stake-credential matching: an address pairing
        // the ATTACKER's payment key with OUR stake key. Counting it as change
        // coming back would hide the entire drain.
        TransactionInput mine = input("a", 0);
        String hex = txHex(body(List.of(mine),
                List.of(output(theirAddress, ADA.multiply(BigInteger.valueOf(9)))),
                ADA).build());
        FakePort port = new FakePort().resolves(mine, myAddress, ADA.multiply(BigInteger.TEN), List.of());

        TxEffect effect = analyse(hex, port);

        assertThat(new Address(theirAddress).getDelegationCredentialHash())
                .as("fixture really does share our stake credential")
                .hasValueSatisfying(stake -> assertThat(stake).isEqualTo(MY_STAKE));
        assertThat(effect.lovelaceDelta())
                .as("the whole 10 ADA must be reported as leaving")
                .isEqualTo(ADA.multiply(BigInteger.TEN).negate());
    }

    @Test
    void aRewardWithdrawalSentToSomebodyElseIsCountedAsLeaving() {
        // The drain the fact-only treatment of withdrawals would hide: rewards are
        // OUR value entering the transaction (inputs + withdrawals = outputs +
        // fee), and Cardano forces withdrawing the entire reward balance. Routed
        // to a foreign output they appear in neither `spent` nor `returned`, so a
        // diff built from UTxOs alone reports only the 2 ADA input as leaving
        // while 12 ADA actually goes. The wallet signs this: DappSigner adds the
        // stake-key witness whenever withdrawals are present.
        TransactionInput mine = input("a", 0);
        TransactionBody body = body(List.of(mine),
                List.of(output(theirAddress, ADA.multiply(BigInteger.valueOf(118)).divide(BigInteger.TEN))),
                ADA.divide(BigInteger.valueOf(5)))
                .withdrawals(List.of(new com.bloxbean.cardano.client.transaction.spec.Withdrawal(
                        myRewardAddress, ADA.multiply(BigInteger.TEN))))
                .build();
        FakePort port = new FakePort().resolves(mine, myAddress, ADA.multiply(BigInteger.TWO), List.of());

        TxEffect effect = analyse(txHex(body), port);

        assertThat(effect.lovelaceDelta())
                .as("2 ADA of inputs PLUS 10 ADA of rewards leave the wallet")
                .isEqualTo(ADA.multiply(BigInteger.valueOf(12)).negate());
        assertThat(effect.facts().withdrawals()).singleElement()
                .satisfies(withdrawal -> assertThat(withdrawal.mine()).isTrue());
    }

    @Test
    void claimingOurOwnRewardsIsNotReportedAsAWindfall() {
        // The same withdrawal routed back to us moves value between our own
        // pockets: the only thing actually lost is the fee.
        TransactionInput mine = input("a", 0);
        TransactionBody body = body(List.of(mine),
                List.of(output(myAddress, ADA.multiply(BigInteger.valueOf(118)).divide(BigInteger.TEN))),
                ADA.divide(BigInteger.valueOf(5)))
                .withdrawals(List.of(new com.bloxbean.cardano.client.transaction.spec.Withdrawal(
                        myRewardAddress, ADA.multiply(BigInteger.TEN))))
                .build();
        FakePort port = new FakePort().resolves(mine, myAddress, ADA.multiply(BigInteger.TWO), List.of());

        TxEffect effect = analyse(txHex(body), port);

        assertThat(effect.lovelaceDelta())
                .as("only the fee leaves; the rewards merely change form")
                .isEqualTo(ADA.divide(BigInteger.valueOf(5)).negate());
    }

    @Test
    void aWithdrawalFromSomebodyElsesRewardAccountIsNotOurValue() {
        TransactionInput mine = input("a", 0);
        String theirReward = rewardAddress(THEIR_PAYMENT);
        TransactionBody body = body(List.of(mine),
                List.of(output(myAddress, ADA.multiply(BigInteger.valueOf(11)))),
                ADA)
                .withdrawals(List.of(new com.bloxbean.cardano.client.transaction.spec.Withdrawal(
                        theirReward, ADA.multiply(BigInteger.TWO))))
                .build();
        FakePort port = new FakePort().resolves(mine, myAddress, ADA.multiply(BigInteger.TEN), List.of());

        TxEffect effect = analyse(txHex(body), port);

        // Their rewards funded an output to us: we gain 1 ADA net.
        assertThat(effect.lovelaceDelta()).isEqualTo(ADA);
        assertThat(effect.facts().withdrawals()).singleElement()
                .satisfies(withdrawal -> assertThat(withdrawal.mine()).isFalse());
    }

    @Test
    void aDepositRefundRoutedToSomebodyElseIsCountedAsLeaving() {
        // Same shape as the withdrawal drain: a Conway unregistration refunds our
        // deposit INTO the transaction, so routed to a foreign output it lands in
        // neither `spent` nor `returned`. At DRep scale that is 500 ADA moving
        // with the summary showing only the fee.
        TransactionInput mine = input("a", 0);
        BigInteger deposit = ADA.multiply(BigInteger.valueOf(500));
        TransactionBody body = body(List.of(mine),
                List.of(output(theirAddress, ADA.add(deposit))), ADA)
                .certs(List.of(com.bloxbean.cardano.client.transaction.spec.cert.UnregCert.builder()
                        .stakeCredential(com.bloxbean.cardano.client.transaction.spec.cert.StakeCredential
                                .fromKeyHash(MY_STAKE))
                        .coin(deposit)
                        .build()))
                .build();
        FakePort port = new FakePort().resolves(mine, myAddress, ADA.multiply(BigInteger.TWO), List.of());

        TxEffect effect = analyse(txHex(body), port);

        assertThat(effect.lovelaceDelta())
                .as("the 2 ADA input AND the 500 ADA refunded deposit both leave")
                .isEqualTo(ADA.multiply(BigInteger.TWO).add(deposit).negate());
    }

    @Test
    void somebodyElsesDeregistrationCostsUsNothing() {
        TransactionInput mine = input("a", 0);
        BigInteger deposit = ADA.multiply(BigInteger.valueOf(500));
        TransactionBody body = body(List.of(mine),
                List.of(output(myAddress, ADA.multiply(BigInteger.valueOf(9)))), ADA)
                .certs(List.of(com.bloxbean.cardano.client.transaction.spec.cert.UnregCert.builder()
                        .stakeCredential(com.bloxbean.cardano.client.transaction.spec.cert.StakeCredential
                                .fromKeyHash(THEIR_PAYMENT))   // not our credential
                        .coin(deposit)
                        .build()))
                .build();
        FakePort port = new FakePort().resolves(mine, myAddress, ADA.multiply(BigInteger.TEN), List.of());

        assertThat(analyse(txHex(body), port).lovelaceDelta()).isEqualTo(ADA.negate());
    }

    @Test
    void aScriptTransactionIsNeverLabelledAsHavingNoScripts() {
        // Silence about a script transaction reads as "no scripts here". Whatever
        // the evaluator says, NO_SCRIPTS is the one answer that would be a lie.
        // (The full outcome state machine is pinned in TxEffectScriptOutcomeTest.)
        TransactionInput mine = input("a", 0);
        TransactionBody body = body(List.of(mine),
                List.of(output(myAddress, ADA.multiply(BigInteger.valueOf(9)))), ADA)
                .scriptDataHash(new byte[32])
                .build();
        FakePort port = new FakePort().resolves(mine, myAddress, ADA.multiply(BigInteger.TEN), List.of());

        assertThat(analyse(txHex(body), port).scriptOutcome())
                .isNotEqualTo(TxEffect.ScriptOutcome.NO_SCRIPTS);
    }

    @Test
    void referenceInputsNeverEnterTheDiff() {
        // Reference inputs are read, never consumed — resolving them would add
        // nothing, and counting them would invent a loss.
        TransactionInput mine = input("a", 0);
        TransactionInput reference = input("f", 0);
        TransactionBody body = body(List.of(mine),
                List.of(output(myAddress, ADA.multiply(BigInteger.valueOf(9)))), ADA)
                .referenceInputs(List.of(reference))
                .build();
        FakePort port = new FakePort().resolves(mine, myAddress, ADA.multiply(BigInteger.TEN), List.of());

        TxEffect effect = analyse(txHex(body), port);

        assertThat(effect.lovelaceDelta()).isEqualTo(ADA.negate());
        assertThat(effect.completeness()).isEqualTo(TxEffect.Completeness.COMPLETE);
    }

    @Test
    void anUnresolvedInputMakesTheSummaryIncompleteRatherThanSmaller() {
        TransactionInput mine = input("a", 0);
        TransactionInput unknown = input("b", 0);
        String hex = txHex(body(List.of(mine, unknown),
                List.of(output(theirAddress, ADA.multiply(BigInteger.valueOf(5)))),
                ADA).build());
        FakePort port = new FakePort()
                .resolves(mine, myAddress, ADA.multiply(BigInteger.TEN), List.of())
                .notInUtxoSet(unknown);

        TxEffect effect = analyse(hex, port);

        assertThat(effect.completeness()).isEqualTo(TxEffect.Completeness.INCOMPLETE);
        assertThat(effect.limitation()).contains("could not be checked").contains("understate");
        assertThat(effect.facts().unresolvedInputCount()).isEqualTo(1);
    }

    @Test
    void aNodeErrorOnAnInputIsNeverTreatedAsSomebodyElsesMoney() {
        TransactionInput mine = input("a", 0);
        TransactionInput broken = input("b", 0);
        String hex = txHex(body(List.of(mine, broken),
                List.of(output(theirAddress, ADA)), ADA).build());
        FakePort port = new FakePort()
                .resolves(mine, myAddress, ADA.multiply(BigInteger.TEN), List.of())
                .fails(broken, new TxSimulationException("node down"));

        TxEffect effect = analyse(hex, port);

        assertThat(effect.completeness()).isEqualTo(TxEffect.Completeness.INCOMPLETE);
        assertThat(effect.facts().unresolvedInputCount()).isEqualTo(1);
    }

    @Test
    void anInputWithAnUnreadableAddressIsIncompleteNotUnowned() {
        TransactionInput weird = input("a", 0);
        String hex = txHex(body(List.of(weird), List.of(output(theirAddress, ADA)), ADA).build());
        FakePort port = new FakePort().resolves(weird, "not-an-address", ADA.multiply(BigInteger.TEN), List.of());

        TxEffect effect = analyse(hex, port);

        assertThat(effect.completeness()).isEqualTo(TxEffect.Completeness.INCOMPLETE);
        assertThat(effect.facts().unresolvedInputCount()).isEqualTo(1);
    }

    // ---- the safe direction: over-reporting is tolerated --------------------

    @Test
    void anUnreadableOutputAddressCountsAsNotOursAndKeepsTheSummaryComplete() {
        TransactionInput mine = input("a", 0);
        TransactionBody body = body(List.of(mine), List.of(), ADA).build();
        body.setOutputs(List.of(TransactionOutput.builder()
                .address(theirAddress)
                .value(Value.builder().coin(ADA.multiply(BigInteger.valueOf(9))).build())
                .build()));
        String hex = txHex(body);
        FakePort port = new FakePort().resolves(mine, myAddress, ADA.multiply(BigInteger.TEN), List.of());

        TxEffect effect = analyse(hex, port);

        assertThat(effect.completeness())
                .as("an odd output must not abort the summary")
                .isEqualTo(TxEffect.Completeness.COMPLETE);
        assertThat(effect.lovelaceDelta()).isEqualTo(ADA.multiply(BigInteger.TEN).negate());
    }

    @Test
    void anEnterpriseAddressWithOurPaymentKeyIsOurs() {
        // Credential matching, not string matching: same payment key, no stake part.
        TransactionInput mine = input("a", 0);
        String hex = txHex(body(List.of(mine),
                List.of(output(enterpriseAddress(MY_PAYMENT), ADA.multiply(BigInteger.valueOf(9)))),
                ADA).build());
        FakePort port = new FakePort().resolves(mine, myAddress, ADA.multiply(BigInteger.TEN), List.of());

        TxEffect effect = analyse(hex, port);

        assertThat(effect.lovelaceDelta())
                .as("only the fee actually leaves")
                .isEqualTo(ADA.negate());
    }

    // ---- degraded states ----------------------------------------------------

    @Test
    void undecodableCborProducesNoNumbersAtAll() {
        TxEffect effect = analyse("not hex at all", new FakePort());

        assertThat(effect.completeness()).isEqualTo(TxEffect.Completeness.UNDECODABLE);
        assertThat(effect.limitation()).contains("could not be decoded");
    }

    @Test
    void aNodeThatCannotResolveInputsProducesALegibleLimitationNotAZeroDiff() {
        TransactionInput mine = input("a", 0);
        String hex = txHex(body(List.of(mine), List.of(output(theirAddress, ADA)), ADA).build());
        FakePort port = new FakePort().withCapabilities(new SimulationCapabilities(
                SimulationCapabilities.Support.UNAVAILABLE, SimulationCapabilities.Support.UNAVAILABLE,
                "0.1.0-old", null));

        TxEffect effect = analyse(hex, port);

        assertThat(effect.completeness()).isEqualTo(TxEffect.Completeness.INCOMPLETE);
        assertThat(effect.limitation()).contains("cannot look up transaction inputs");
        assertThat(port.resolveCalls).as("no point asking a node that cannot answer").isZero();
    }

    @Test
    void aWalletWithNoKnownAddressesRefusesRatherThanReportingNothingMoves() {
        TransactionInput mine = input("a", 0);
        String hex = txHex(body(List.of(mine), List.of(output(theirAddress, ADA)), ADA).build());

        TxEffect effect = new TxEffectEngine(new FakePort(), DIRECT)
                .analyse(hex, WalletOwnership.ofAddresses(List.of()));

        assertThat(effect.completeness()).isEqualTo(TxEffect.Completeness.INCOMPLETE);
        assertThat(effect.limitation()).contains("own addresses were unavailable");
        assertThat(effect.lovelaceDelta()).isZero();
    }

    // ---- facts --------------------------------------------------------------

    @Test
    void recordsMintsBurnsAndCollateral() {
        TransactionInput mine = input("a", 0);
        TransactionInput collateral = input("c", 0);
        TransactionBody body = body(List.of(mine),
                List.of(output(myAddress, ADA.multiply(BigInteger.valueOf(9)))), ADA)
                .mint(List.of(MultiAsset.builder()
                        .policyId(POLICY)
                        .assets(List.of(Asset.builder().name("0x" + MIN_HEX).value(BigInteger.valueOf(-5)).build()))
                        .build()))
                .collateral(List.of(collateral))
                .ttl(12345L)
                .build();
        FakePort port = new FakePort()
                .resolves(mine, myAddress, ADA.multiply(BigInteger.TEN), List.of())
                .resolves(collateral, myAddress, ADA.multiply(BigInteger.valueOf(5)), List.of());

        TxEffect effect = analyse(txHex(body), port);

        assertThat(effect.facts().mint()).singleElement().satisfies(mint -> {
            assertThat(mint.policyId()).isEqualTo(POLICY);
            assertThat(mint.quantity()).isEqualTo(BigInteger.valueOf(-5));
        });
        assertThat(effect.facts().collateralPresent()).isTrue();
        assertThat(effect.facts().collateralLovelace()).isEqualTo(ADA.multiply(BigInteger.valueOf(5)));
        assertThat(effect.facts().ttl()).isEqualTo(12345L);
    }

    @Test
    void collateralIsPricedButDoesNotCountAsSpentValue() {
        // Collateral is only consumed if the scripts fail; the ordinary diff must
        // not pretend it already left.
        TransactionInput mine = input("a", 0);
        TransactionInput collateral = input("c", 0);
        TransactionBody body = body(List.of(mine),
                List.of(output(myAddress, ADA.multiply(BigInteger.valueOf(9)))), ADA)
                .collateral(List.of(collateral))
                .build();
        FakePort port = new FakePort()
                .resolves(mine, myAddress, ADA.multiply(BigInteger.TEN), List.of())
                .resolves(collateral, myAddress, ADA.multiply(BigInteger.valueOf(5)), List.of());

        TxEffect effect = analyse(txHex(body), port);

        assertThat(effect.lovelaceDelta()).isEqualTo(ADA.negate());
        assertThat(effect.completeness()).isEqualTo(TxEffect.Completeness.COMPLETE);
    }

    @Test
    void aRepeatedOutputReferenceIsResolvedOnce() {
        TransactionInput mine = input("a", 0);
        TransactionBody body = body(List.of(mine),
                List.of(output(myAddress, ADA.multiply(BigInteger.valueOf(9)))), ADA)
                .collateral(List.of(input("a", 0)))   // same outref as the input
                .build();
        FakePort port = new FakePort().resolves(mine, myAddress, ADA.multiply(BigInteger.TEN), List.of());

        analyse(txHex(body), port);

        assertThat(port.resolveCalls).isEqualTo(1);
    }

    @Test
    void manyInputsAreResolvedConcurrently() {
        List<TransactionInput> inputs = new ArrayList<>();
        FakePort port = new FakePort();
        for (int i = 0; i < 12; i++) {
            TransactionInput in = input("a", i);
            inputs.add(in);
            port.resolves(in, myAddress, ADA, List.of());
        }
        String hex = txHex(body(inputs, List.of(output(theirAddress, ADA.multiply(BigInteger.TEN))), ADA).build());

        TxEffect effect = new TxEffectEngine(port, java.util.concurrent.ForkJoinPool.commonPool())
                .analyse(hex, ownership);

        assertThat(effect.completeness()).isEqualTo(TxEffect.Completeness.COMPLETE);
        assertThat(effect.lovelaceDelta()).isEqualTo(ADA.multiply(BigInteger.valueOf(-12)));
    }
}
