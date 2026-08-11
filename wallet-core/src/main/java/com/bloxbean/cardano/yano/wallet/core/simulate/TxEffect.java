package com.bloxbean.cardano.yano.wallet.core.simulate;

import java.math.BigInteger;
import java.util.List;

/**
 * What a transaction does to this wallet (ADR-042) — the answer the user is
 * actually being asked to authorise.
 *
 * <p>Deltas are signed and net: negative means leaving. {@code lovelaceDelta}
 * already includes the fee, because the fee is paid out of our inputs; {@link
 * #fee()} is carried separately so the prompt can say how much of the loss is
 * just the network charge.
 *
 * <p>{@link #completeness()} is not decoration. A summary that could not resolve
 * every input is not a smaller loss — it is an unknown one, and the prompt must
 * say so rather than show a confident number.
 */
public record TxEffect(Completeness completeness,
                       String limitation,
                       BigInteger lovelaceDelta,
                       BigInteger fee,
                       List<AssetDelta> assetDeltas,
                       ScriptOutcome scriptOutcome,
                       String scriptMessage,
                       List<ScriptEvaluation.RedeemerCost> scriptCosts,
                       TxFacts facts,
                       List<RiskSignal> risks) {

    public enum Completeness {
        /** Every input resolved and was classified; the numbers can be trusted. */
        COMPLETE,
        /** At least one input could not be resolved or classified — the real loss may be larger. */
        INCOMPLETE,
        /** The transaction could not even be decoded; nothing here is meaningful. */
        UNDECODABLE
    }

    public enum ScriptOutcome {
        /** No redeemers — there is nothing to evaluate. */
        NO_SCRIPTS,
        /** The node ran every script and they succeeded. */
        SUCCESS,
        /** The node ran the scripts and they failed: this will fail on-chain and burn collateral. */
        FAILED,
        /** Scripts exist but could not be checked. Says nothing about whether they would pass. */
        COULD_NOT_VERIFY
    }

    /** A signed per-asset change. {@code quantity} is negative when the asset leaves. */
    public record AssetDelta(String policyId, String assetNameHex, BigInteger quantity) {
        public boolean isOutgoing() {
            return quantity.signum() < 0;
        }
    }

    public TxEffect {
        lovelaceDelta = lovelaceDelta == null ? BigInteger.ZERO : lovelaceDelta;
        fee = fee == null ? BigInteger.ZERO : fee;
        assetDeltas = assetDeltas == null ? List.of() : List.copyOf(assetDeltas);
        scriptCosts = scriptCosts == null ? List.of() : List.copyOf(scriptCosts);
        risks = risks == null ? List.of() : List.copyOf(risks);
        facts = facts == null ? TxFacts.empty() : facts;
        scriptOutcome = scriptOutcome == null ? ScriptOutcome.NO_SCRIPTS : scriptOutcome;
    }

    /** A transaction whose CBOR could not be decoded — the honest zero-information answer. */
    public static TxEffect undecodable(String limitation) {
        return new TxEffect(Completeness.UNDECODABLE, limitation, BigInteger.ZERO, BigInteger.ZERO,
                List.of(), ScriptOutcome.COULD_NOT_VERIFY, null, List.of(), TxFacts.empty(), List.of());
    }

    /** With a copy of the script findings replaced — used as evaluation completes. */
    public TxEffect withScript(ScriptOutcome outcome, String message,
                               List<ScriptEvaluation.RedeemerCost> costs) {
        return new TxEffect(completeness, limitation, lovelaceDelta, fee, assetDeltas,
                outcome, message, costs, facts, risks);
    }

    /** With a copy carrying the derived risk signals (SIM-M3). */
    public TxEffect withRisks(List<RiskSignal> newRisks) {
        return new TxEffect(completeness, limitation, lovelaceDelta, fee, assetDeltas,
                scriptOutcome, scriptMessage, scriptCosts, facts, newRisks);
    }

    public boolean isComplete() {
        return completeness == Completeness.COMPLETE;
    }
}
