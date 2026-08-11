package com.bloxbean.cardano.yano.wallet.core.simulate;

import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ADR-042 SIM-M3: the risk signals.
 *
 * <p>Two failure modes are being guarded against, and they pull in opposite
 * directions. A missed signal leaves a user uninformed; a false signal teaches
 * them to dismiss the whole panel, which costs more than it saves. So the tests
 * assert both that real conditions raise signals and that unknowable ones stay
 * silent.
 */
class RiskSignalDetectorTest {

    private static final BigInteger ADA = BigInteger.valueOf(1_000_000);
    private static final String POLICY = "0f5560dbc05282e05507aedb02d823d9d9f0805037bc4b8a24e6c1b1";

    private static TxEffect effect(BigInteger lovelaceDelta, TxEffect.Completeness completeness,
                                   TxEffect.ScriptOutcome outcome, TxFacts facts,
                                   List<TxEffect.AssetDelta> assets) {
        return new TxEffect(completeness, completeness == TxEffect.Completeness.COMPLETE ? null : "unchecked",
                lovelaceDelta, ADA, assets, outcome, null, List.of(), facts, List.of());
    }

    private static TxFacts facts() {
        return TxFacts.empty();
    }

    private static TxFacts facts(List<TxFacts.AssetDelta> mint, List<String> certificates,
                                 List<TxFacts.Withdrawal> withdrawals, BigInteger collateral,
                                 boolean collateralPresent, long ttl, int scriptOutputs, int datumOutputs) {
        return new TxFacts(mint, certificates, withdrawals, collateral, collateralPresent,
                ttl, 0L, 1, 1, 0, scriptOutputs, datumOutputs, false);
    }

    private static List<RiskSignal.Kind> kinds(List<RiskSignal> signals) {
        return signals.stream().map(RiskSignal::kind).toList();
    }

    @Test
    void aFailingScriptIsTheLoudestSignalAndNamesTheCost() {
        List<RiskSignal> signals = RiskSignalDetector.detect(
                effect(ADA.negate(), TxEffect.Completeness.COMPLETE, TxEffect.ScriptOutcome.FAILED,
                        facts(List.of(), List.of(), List.of(), ADA.multiply(BigInteger.valueOf(5)),
                                true, 0L, 0, 0),
                        List.of()),
                WalletContext.unknown());

        assertThat(signals).first().satisfies(signal -> {
            assertThat(signal.kind()).isEqualTo(RiskSignal.Kind.SCRIPT_FAILURE);
            assertThat(signal.severity()).isEqualTo(RiskSignal.Severity.CRITICAL);
            assertThat(signal.reason()).contains("collateral");
        });
    }

    @Test
    void anIncompleteSummaryIsAlwaysSignalled() {
        List<RiskSignal> signals = RiskSignalDetector.detect(
                effect(ADA.negate(), TxEffect.Completeness.INCOMPLETE,
                        TxEffect.ScriptOutcome.NO_SCRIPTS, facts(), List.of()),
                WalletContext.unknown());

        assertThat(kinds(signals)).contains(RiskSignal.Kind.INCOMPLETE_SUMMARY);
    }

    @Test
    void spendingNearlyTheWholeBalanceIsFlagged() {
        List<RiskSignal> signals = RiskSignalDetector.detect(
                effect(ADA.multiply(BigInteger.valueOf(99)).negate(), TxEffect.Completeness.COMPLETE,
                        TxEffect.ScriptOutcome.NO_SCRIPTS, facts(), List.of()),
                new WalletContext(ADA.multiply(BigInteger.valueOf(100)), 0L));

        assertThat(kinds(signals)).contains(RiskSignal.Kind.TOTAL_VALUE_DRAIN);
    }

    @Test
    void anOrdinaryPaymentIsNotCalledADrain() {
        List<RiskSignal> signals = RiskSignalDetector.detect(
                effect(ADA.multiply(BigInteger.valueOf(5)).negate(), TxEffect.Completeness.COMPLETE,
                        TxEffect.ScriptOutcome.NO_SCRIPTS, facts(), List.of()),
                new WalletContext(ADA.multiply(BigInteger.valueOf(100)), 0L));

        assertThat(kinds(signals)).doesNotContain(RiskSignal.Kind.TOTAL_VALUE_DRAIN);
    }

    @Test
    void theDrainHeuristicStaysSilentWithoutABalance() {
        // Guessing here would produce a false alarm on every transaction from a
        // wallet whose balance could not be read.
        List<RiskSignal> signals = RiskSignalDetector.detect(
                effect(ADA.multiply(BigInteger.valueOf(999)).negate(), TxEffect.Completeness.COMPLETE,
                        TxEffect.ScriptOutcome.NO_SCRIPTS, facts(), List.of()),
                WalletContext.unknown());

        assertThat(kinds(signals)).doesNotContain(RiskSignal.Kind.TOTAL_VALUE_DRAIN);
    }

    @Test
    void theDrainHeuristicStaysSilentOnNumbersWeCouldNotVerify() {
        List<RiskSignal> signals = RiskSignalDetector.detect(
                effect(ADA.multiply(BigInteger.valueOf(99)).negate(), TxEffect.Completeness.INCOMPLETE,
                        TxEffect.ScriptOutcome.NO_SCRIPTS, facts(), List.of()),
                new WalletContext(ADA.multiply(BigInteger.valueOf(100)), 0L));

        assertThat(kinds(signals)).doesNotContain(RiskSignal.Kind.TOTAL_VALUE_DRAIN);
    }

    @Test
    void anOutgoingAssetIsFlaggedOnceRegardlessOfHowMany() {
        List<RiskSignal> signals = RiskSignalDetector.detect(
                effect(ADA.negate(), TxEffect.Completeness.COMPLETE, TxEffect.ScriptOutcome.NO_SCRIPTS,
                        facts(),
                        List.of(new TxEffect.AssetDelta(POLICY, "4d494e", BigInteger.valueOf(-1)),
                                new TxEffect.AssetDelta(POLICY, "484f534b59", BigInteger.valueOf(-2)))),
                WalletContext.unknown());

        assertThat(kinds(signals)).filteredOn(kind -> kind == RiskSignal.Kind.ASSET_LEAVING).hasSize(1);
    }

    @Test
    void anIncomingAssetIsNotFlaggedAsLeaving() {
        List<RiskSignal> signals = RiskSignalDetector.detect(
                effect(ADA.negate(), TxEffect.Completeness.COMPLETE, TxEffect.ScriptOutcome.NO_SCRIPTS,
                        facts(), List.of(new TxEffect.AssetDelta(POLICY, "4d494e", BigInteger.TEN))),
                WalletContext.unknown());

        assertThat(kinds(signals)).doesNotContain(RiskSignal.Kind.ASSET_LEAVING);
    }

    @Test
    void mintingAndBurningAreNamedDistinctly() {
        var minted = RiskSignalDetector.detect(
                effect(ADA.negate(), TxEffect.Completeness.COMPLETE, TxEffect.ScriptOutcome.NO_SCRIPTS,
                        facts(List.of(new TxFacts.AssetDelta(POLICY, "4d494e", BigInteger.TEN)),
                                List.of(), List.of(), BigInteger.ZERO, false, 0L, 0, 0),
                        List.of()),
                WalletContext.unknown());
        var burned = RiskSignalDetector.detect(
                effect(ADA.negate(), TxEffect.Completeness.COMPLETE, TxEffect.ScriptOutcome.NO_SCRIPTS,
                        facts(List.of(new TxFacts.AssetDelta(POLICY, "4d494e", BigInteger.valueOf(-10))),
                                List.of(), List.of(), BigInteger.ZERO, false, 0L, 0, 0),
                        List.of()),
                WalletContext.unknown());

        assertThat(minted).anySatisfy(s -> assertThat(s.title()).isEqualTo("Creates new tokens"));
        assertThat(burned).anySatisfy(s -> assertThat(s.title()).isEqualTo("Destroys tokens"));
    }

    @Test
    void aDeregistrationPointsTheUserAtWhereItsDepositGoes() {
        // Conway unregistrations carry their refund and the engine counts it; the
        // legacy certificate does not, so the signal has to work for both. What
        // it must never do is stay silent about a refund that can be routed
        // anywhere.
        List<RiskSignal> signals = RiskSignalDetector.detect(
                effect(ADA.negate(), TxEffect.Completeness.COMPLETE, TxEffect.ScriptOutcome.NO_SCRIPTS,
                        facts(List.of(), List.of("StakeDeregistration"), List.of(),
                                BigInteger.ZERO, false, 0L, 0, 0),
                        List.of()),
                WalletContext.unknown());

        assertThat(signals).anySatisfy(signal -> {
            assertThat(signal.kind()).isEqualTo(RiskSignal.Kind.CERTIFICATE);
            assertThat(signal.reason()).contains("deposit").contains("where that refund goes");
        });
    }

    @Test
    void certificateWordingsDoNotMisdescribeWhatTheyAre() {
        // Each of these class names matches more than one of the reason cascade's
        // tests; a confidently wrong description of a certificate is a small
        // version of the problem this whole feature exists to fix.
        record Case(String certificate, String mustContain, String mustNotContain) {
        }
        List.of(new Case("PoolRetirement", "retires a stake pool", "check the outputs"),
                new Case("PoolRegistration", "registers or updates a stake pool", "delegated to"),
                new Case("StakeVoteDelegCert", "both", null),
                new Case("StakeDelegation", "which stake pool", "governance"),
                new Case("RegDRepCert", "governance", "stake pool"),
                new Case("GenesisKeyDelegation", "protocol-level", "stake pool"))
                .forEach(testCase -> {
                    List<RiskSignal> signals = RiskSignalDetector.detect(
                            effect(ADA.negate(), TxEffect.Completeness.COMPLETE,
                                    TxEffect.ScriptOutcome.NO_SCRIPTS,
                                    facts(List.of(), List.of(testCase.certificate()), List.of(),
                                            BigInteger.ZERO, false, 0L, 0, 0),
                                    List.of()),
                            WalletContext.unknown());
                    assertThat(signals).filteredOn(s -> s.kind() == RiskSignal.Kind.CERTIFICATE)
                            .singleElement()
                            .satisfies(signal -> {
                                assertThat(signal.reason()).as(testCase.certificate())
                                        .containsIgnoringCase(testCase.mustContain());
                                if (testCase.mustNotContain() != null) {
                                    assertThat(signal.reason()).as(testCase.certificate())
                                            .doesNotContainIgnoringCase(testCase.mustNotContain());
                                }
                            });
                });
    }

    @Test
    void anExpiredValidityWindowIsFlaggedOnlyWhenTheSlotIsKnown() {
        TxFacts expired = facts(List.of(), List.of(), List.of(), BigInteger.ZERO, false, 100L, 0, 0);

        assertThat(kinds(RiskSignalDetector.detect(
                effect(ADA.negate(), TxEffect.Completeness.COMPLETE, TxEffect.ScriptOutcome.NO_SCRIPTS,
                        expired, List.of()),
                new WalletContext(null, 500L))))
                .contains(RiskSignal.Kind.VALIDITY_WINDOW);

        assertThat(kinds(RiskSignalDetector.detect(
                effect(ADA.negate(), TxEffect.Completeness.COMPLETE, TxEffect.ScriptOutcome.NO_SCRIPTS,
                        expired, List.of()),
                WalletContext.unknown())))
                .doesNotContain(RiskSignal.Kind.VALIDITY_WINDOW);
    }

    @Test
    void anImplausiblyDistantValidityWindowIsMentioned() {
        TxFacts farFuture = facts(List.of(), List.of(), List.of(), BigInteger.ZERO, false,
                500L + (400L * 24 * 60 * 60), 0, 0);

        List<RiskSignal> signals = RiskSignalDetector.detect(
                effect(ADA.negate(), TxEffect.Completeness.COMPLETE, TxEffect.ScriptOutcome.NO_SCRIPTS,
                        farFuture, List.of()),
                new WalletContext(null, 500L));

        assertThat(signals).anySatisfy(signal -> {
            assertThat(signal.kind()).isEqualTo(RiskSignal.Kind.VALIDITY_WINDOW);
            assertThat(signal.reason()).contains("long after");
        });
    }

    @Test
    void anOrdinaryTtlIsNotMentionedAtAll() {
        TxFacts normal = facts(List.of(), List.of(), List.of(), BigInteger.ZERO, false, 1000L, 0, 0);

        assertThat(kinds(RiskSignalDetector.detect(
                effect(ADA.negate(), TxEffect.Completeness.COMPLETE, TxEffect.ScriptOutcome.NO_SCRIPTS,
                        normal, List.of()),
                new WalletContext(null, 500L))))
                .doesNotContain(RiskSignal.Kind.VALIDITY_WINDOW);
    }

    @Test
    void scriptOutputsAndDatumsAreDisclosedAsInformation() {
        List<RiskSignal> signals = RiskSignalDetector.detect(
                effect(ADA.negate(), TxEffect.Completeness.COMPLETE, TxEffect.ScriptOutcome.SUCCESS,
                        facts(List.of(), List.of(), List.of(), BigInteger.ZERO, false, 0L, 1, 1),
                        List.of()),
                WalletContext.unknown());

        assertThat(kinds(signals)).contains(RiskSignal.Kind.UNKNOWN_SCRIPT_OUTPUT,
                RiskSignal.Kind.DATUM_BEARING_OUTPUT);
        assertThat(signals).filteredOn(s -> s.kind() == RiskSignal.Kind.UNKNOWN_SCRIPT_OUTPUT)
                .allSatisfy(s -> assertThat(s.severity()).isEqualTo(RiskSignal.Severity.INFO));
    }

    @Test
    void aPlainPaymentRaisesNothing() {
        // Alert fatigue is a real cost: an ordinary send must be quiet.
        List<RiskSignal> signals = RiskSignalDetector.detect(
                effect(ADA.multiply(BigInteger.TWO).negate(), TxEffect.Completeness.COMPLETE,
                        TxEffect.ScriptOutcome.NO_SCRIPTS, facts(), List.of()),
                new WalletContext(ADA.multiply(BigInteger.valueOf(100)), 500L));

        assertThat(signals).isEmpty();
    }

    @Test
    void everySignalCarriesAReason() {
        List<RiskSignal> signals = RiskSignalDetector.detect(
                effect(ADA.multiply(BigInteger.valueOf(99)).negate(), TxEffect.Completeness.INCOMPLETE,
                        TxEffect.ScriptOutcome.FAILED,
                        facts(List.of(new TxFacts.AssetDelta(POLICY, "4d494e", BigInteger.TEN)),
                                List.of("StakeDelegation"),
                                List.of(new TxFacts.Withdrawal("stake_test1abc", ADA, true)),
                                ADA, true, 100L, 1, 1),
                        List.of(new TxEffect.AssetDelta(POLICY, "4d494e", BigInteger.valueOf(-1)))),
                new WalletContext(ADA.multiply(BigInteger.valueOf(100)), 500L));

        assertThat(signals).isNotEmpty();
        assertThat(signals).allSatisfy(signal -> {
            assertThat(signal.title()).isNotBlank();
            assertThat(signal.reason()).as(signal.title()).isNotBlank();
        });
    }
}
