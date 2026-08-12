package com.bloxbean.cardano.yano.wallet.core.simulate;

/**
 * What the effect engine needs from a node (ADR-042). Implemented by
 * wallet-node-client over the Yano REST API, following the {@code NodeStatusPort}
 * / {@code HistoryPort} pattern — wallet-core stays free of HTTP.
 *
 * <p>Implementations are called on the CIP-30 bridge worker thread under a hard
 * overall deadline, so every method must be individually bounded and must not
 * retry indefinitely.
 */
public interface TxSimulationPort {

    /**
     * What this node can do, probed rather than inferred from a version string.
     * Implementations should cache the result — this is called per signing
     * request and the answer only changes when the node is replaced.
     */
    SimulationCapabilities capabilities();

    /**
     * Resolves an output reference.
     *
     * @return the output, or {@code null} when the node positively reports it
     *         cannot name that output — the creating transaction is not on-chain,
     *         or it has no output at that index. A {@code null} is an
     *         <em>answer</em>, not an error, but it is not one that says whose the
     *         output was, so callers still treat it as unresolved.
     * @throws TxSimulationException when the node could not be asked at all — the
     *         caller must treat this as "unresolved", never as "not mine".
     */
    ResolvedOutput resolveOutput(String txHash, int outputIndex);

    /**
     * Evaluates the transaction's Plutus scripts. Never throws for an evaluation
     * that simply failed or is unavailable — those are {@link ScriptEvaluation}
     * outcomes, because the caller must be able to render them.
     */
    ScriptEvaluation evaluate(String txHex);
}
