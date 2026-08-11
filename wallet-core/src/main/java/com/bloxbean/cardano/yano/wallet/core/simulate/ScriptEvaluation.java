package com.bloxbean.cardano.yano.wallet.core.simulate;

import java.util.List;

/**
 * The node's raw answer to "do this transaction's scripts succeed, and at what
 * cost" (ADR-042 SIM-M2), from {@code POST /utils/txs/evaluate}.
 *
 * <p>This is deliberately the <em>node's</em> answer, not the wallet's verdict.
 * A {@link Outcome#FAILURE} here does not yet mean "this transaction will fail
 * on-chain": the node resolves inputs from its own UTxO set, so a transaction
 * spending an output that is not yet on-chain (a chained dApp transaction) comes
 * back as a failure even when its scripts are fine. Only {@code TxEffectEngine},
 * which knows whether every input resolved, may promote this to a real script
 * failure — see ADR-042 limit 3.
 */
public record ScriptEvaluation(Outcome outcome, List<RedeemerCost> costs, String message) {

    public enum Outcome {
        /** Every redeemer evaluated; {@link #costs()} holds the ExUnits. */
        SUCCESS,
        /** The node returned {@code EvaluationFailure} with {@link #message()}. */
        FAILURE,
        /** Evaluation could not be performed at all (endpoint absent, node error, timeout). */
        UNAVAILABLE
    }

    /** ExUnits for one redeemer, keyed by the Ogmios {@code tag:index} pair (e.g. {@code spend:0}). */
    public record RedeemerCost(String tag, int index, long memory, long steps) {
    }

    public ScriptEvaluation {
        costs = costs == null ? List.of() : List.copyOf(costs);
    }

    public static ScriptEvaluation success(List<RedeemerCost> costs) {
        return new ScriptEvaluation(Outcome.SUCCESS, costs, null);
    }

    public static ScriptEvaluation failure(String message) {
        return new ScriptEvaluation(Outcome.FAILURE, List.of(), message);
    }

    public static ScriptEvaluation unavailable(String message) {
        return new ScriptEvaluation(Outcome.UNAVAILABLE, List.of(), message);
    }
}
