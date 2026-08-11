package com.bloxbean.cardano.yano.wallet.app;

import com.bloxbean.cardano.yano.wallet.core.simulate.RiskSignal;
import com.bloxbean.cardano.yano.wallet.core.simulate.TxEffect;
import com.bloxbean.cardano.yano.wallet.core.simulate.TxFacts;
import com.bloxbean.cardano.yano.wallet.ui.contract.TxEffectView;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ADR-042: the core→UI mapping. This is the last place a hostile string can be
 * neutralised and the last place a number can be mangled, so both are pinned
 * here rather than left to the renderer.
 */
class TxEffectViewMappingTest {

    private static final String POLICY = "0f5560dbc05282e05507aedb02d823d9d9f0805037bc4b8a24e6c1b1";

    private static String hex(String text) {
        return HexFormat.of().formatHex(text.getBytes(StandardCharsets.UTF_8));
    }

    private static TxEffect effectWithAsset(String assetNameHex, BigInteger quantity) {
        return new TxEffect(TxEffect.Completeness.COMPLETE, null,
                BigInteger.valueOf(-2_500_000), BigInteger.valueOf(200_000),
                List.of(new TxEffect.AssetDelta(POLICY, assetNameHex, quantity)),
                TxEffect.ScriptOutcome.NO_SCRIPTS, null, List.of(), TxFacts.empty(), List.of());
    }

    @Test
    void carriesTheDiffAcrossTheBoundaryAsPlainTypes() {
        TxEffectView view = TxEffectSummariser.toView(
                effectWithAsset(hex("MIN"), BigInteger.valueOf(-340)), "84a4");

        assertThat(view.completeness()).isEqualTo(TxEffectView.Completeness.COMPLETE);
        assertThat(view.netLovelace()).isEqualTo(-2_500_000L);
        assertThat(view.feeLovelace()).isEqualTo(200_000L);
        assertThat(view.rawCborHex()).isEqualTo("84a4");
        assertThat(view.assetChanges()).singleElement().satisfies(asset -> {
            assertThat(asset.displayName()).isEqualTo("MIN");
            assertThat(asset.quantity()).isEqualTo("-340");
            assertThat(asset.outgoing()).isTrue();
            assertThat(asset.policyId()).isEqualTo(POLICY);
        });
    }

    @Test
    void hostileAssetNamesAreSanitisedBeforeReachingTheUi() {
        TxEffectView view = TxEffectSummariser.toView(
                effectWithAsset(hex("ADA‮gnivaeL"), BigInteger.valueOf(-1)), "84a4");

        assertThat(view.assetChanges()).singleElement().satisfies(asset -> {
            assertThat(asset.displayName()).doesNotContain("‮");
            // The raw hex is still carried, so nothing is hidden by sanitising.
            assertThat(asset.assetNameHex()).isEqualTo(hex("ADA‮gnivaeL"));
        });
    }

    @Test
    void assetQuantitiesBeyondALongSurviveAsText() {
        BigInteger huge = new BigInteger("92233720368547758070");
        TxEffectView view = TxEffectSummariser.toView(effectWithAsset(hex("BIG"), huge), "84a4");

        assertThat(view.assetChanges()).singleElement()
                .satisfies(asset -> assertThat(asset.quantity()).isEqualTo(huge.toString()));
    }

    @Test
    void anAbsurdLovelaceValueSaturatesRatherThanReplacingTheWholeSummary() {
        // Lovelace cannot legitimately exceed a long; a malformed transaction that
        // claims otherwise must still produce a prompt.
        TxEffect effect = new TxEffect(TxEffect.Completeness.COMPLETE, null,
                new BigInteger("-999999999999999999999999"), BigInteger.ZERO,
                List.of(), TxEffect.ScriptOutcome.NO_SCRIPTS, null, List.of(), TxFacts.empty(), List.of());

        assertThat(TxEffectSummariser.toView(effect, "84a4").netLovelace()).isEqualTo(Long.MIN_VALUE);
    }

    @Test
    void incompletenessAndItsReasonSurviveTheMapping() {
        TxEffect effect = new TxEffect(TxEffect.Completeness.INCOMPLETE,
                "One of this transaction's inputs could not be checked.",
                BigInteger.ZERO, BigInteger.ZERO, List.of(),
                TxEffect.ScriptOutcome.COULD_NOT_VERIFY, null, List.of(),
                new TxFacts(List.of(), List.of(), List.of(), BigInteger.ZERO, false,
                        0, 0, 1, 2, 1, 0, 0, false),
                List.of(new RiskSignal(RiskSignal.Kind.INCOMPLETE_SUMMARY, RiskSignal.Severity.WARNING,
                        "Not fully checked", "One input could not be resolved.")));

        TxEffectView view = TxEffectSummariser.toView(effect, "84a4");

        assertThat(view.completeness()).isEqualTo(TxEffectView.Completeness.INCOMPLETE);
        assertThat(view.limitation()).contains("could not be checked");
        assertThat(view.unresolvedInputCount()).isEqualTo(1);
        assertThat(view.risks()).singleElement().satisfies(risk -> {
            assertThat(risk.severity()).isEqualTo(TxEffectView.Severity.WARNING);
            assertThat(risk.title()).isEqualTo("Not fully checked");
        });
    }

    @Test
    void theDegradedViewCarriesNoNumbersAndKeepsTheRawTransaction() {
        TxEffectView view = TxEffectSummariser.degraded("84a4", "The node did not answer in time.");

        assertThat(view.completeness()).isEqualTo(TxEffectView.Completeness.INCOMPLETE);
        assertThat(view.netLovelace()).isZero();
        assertThat(view.assetChanges()).isEmpty();
        assertThat(view.scriptOutcome()).isEqualTo(TxEffectView.ScriptOutcome.COULD_NOT_VERIFY);
        assertThat(view.limitation()).isEqualTo("The node did not answer in time.");
        assertThat(view.rawCborHex()).isEqualTo("84a4");
    }
}
