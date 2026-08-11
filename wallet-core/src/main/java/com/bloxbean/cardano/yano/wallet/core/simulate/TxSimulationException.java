package com.bloxbean.cardano.yano.wallet.core.simulate;

/**
 * The node could not be asked. Distinct from a node that answers "no such
 * output": the latter is information, this is the absence of information, and
 * ADR-042 requires the two to lead to different summaries — an input we could
 * not ask about degrades the whole summary to "cannot fully determine" rather
 * than being quietly counted as somebody else's.
 */
public class TxSimulationException extends RuntimeException {

    public TxSimulationException(String message) {
        super(message);
    }

    public TxSimulationException(String message, Throwable cause) {
        super(message, cause);
    }
}
