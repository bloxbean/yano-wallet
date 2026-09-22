package com.bloxbean.cardano.yano.wallet.core.service;

/** A definite rejection requiring fresh input selection and renewed approval. */
public final class MempoolConflictException extends RuntimeException {
    public MempoolConflictException(String nodeReason) {
        super("Another pending transaction already uses an input from this draft. "
                + "Rebuild and review the payment with the latest available funds. Node response: " + nodeReason);
    }
}
