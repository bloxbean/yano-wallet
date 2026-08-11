package com.bloxbean.cardano.yano.wallet.core.simulate;

/**
 * A derived observation about a transaction, with the plain-language reason it
 * was raised (ADR-042). These are heuristics and must be presented as such:
 * they inform a decision, they do not make one. A transaction with no signals is
 * not thereby safe, and a transaction with several is not thereby an attack.
 */
public record RiskSignal(Kind kind, Severity severity, String title, String reason) {

    public enum Kind {
        ASSET_LEAVING,
        MINT_OR_BURN,
        COLLATERAL_AT_RISK,
        UNKNOWN_SCRIPT_OUTPUT,
        TOTAL_VALUE_DRAIN,
        DATUM_BEARING_OUTPUT,
        SCRIPT_FAILURE,
        CERTIFICATE,
        WITHDRAWAL,
        VALIDITY_WINDOW,
        INCOMPLETE_SUMMARY
    }

    /**
     * How loudly to present the signal. {@code CRITICAL} is reserved for facts
     * that cost the user money on their own — a failing script that burns
     * collateral, or the whole balance leaving.
     */
    public enum Severity {INFO, WARNING, CRITICAL}
}
