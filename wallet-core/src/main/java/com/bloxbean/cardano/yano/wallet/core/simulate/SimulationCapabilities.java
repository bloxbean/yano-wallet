package com.bloxbean.cardano.yano.wallet.core.simulate;

/**
 * What the connected node can actually do for transaction simulation (ADR-042
 * SIM-M0). Determined by <em>probing the endpoints</em>, not by comparing version
 * strings: a node reports its build-time {@code quarkus.application.version}, and
 * a locally built node can be newer than the pinned release while reporting an
 * older version (observed: a post-pre12 build reporting {@code 0.1.0-pre11}).
 * {@link #nodeVersion()} is therefore carried for the error message only — it
 * never drives a decision.
 *
 * <p>Each capability is tri-state on purpose. A probe that fails because the node
 * hiccuped must not be recorded as "the node cannot do this" — that would
 * permanently degrade a perfectly capable node on one bad request. {@link
 * Support#UNKNOWN} says "we could not tell", which the caller reports honestly.
 */
public record SimulationCapabilities(Support utxoLookup,
                                     Support scriptEvaluation,
                                     String nodeVersion,
                                     String detail) {

    /** Whether a probed capability is present, absent, or could not be determined. */
    public enum Support {
        AVAILABLE, UNAVAILABLE, UNKNOWN;

        public boolean isAvailable() {
            return this == AVAILABLE;
        }
    }

    public SimulationCapabilities {
        utxoLookup = utxoLookup == null ? Support.UNKNOWN : utxoLookup;
        scriptEvaluation = scriptEvaluation == null ? Support.UNKNOWN : scriptEvaluation;
    }

    /**
     * True when inputs can be resolved, which is what a value diff needs. Script
     * evaluation is a separate, optional enrichment: a node that resolves UTxOs
     * but cannot evaluate still produces a useful (and honest) summary.
     */
    public boolean canResolveInputs() {
        return utxoLookup.isAvailable();
    }

    /** True when both halves are available — a full simulation is possible. */
    public boolean canSimulateFully() {
        return utxoLookup.isAvailable() && scriptEvaluation.isAvailable();
    }

    /**
     * A single plain-language line for the degraded prompt, or {@code null} when
     * everything is available. Never surfaces a raw status code (ADR-041: the
     * History regression of 2026-08-07 is the precedent).
     */
    public String limitation() {
        if (canSimulateFully()) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        if (!utxoLookup.isAvailable()) {
            sb.append(utxoLookup == Support.UNAVAILABLE
                    ? "This node cannot look up transaction inputs, so the effect on your wallet cannot be computed."
                    : "Could not confirm whether this node can look up transaction inputs.");
        }
        if (!scriptEvaluation.isAvailable()) {
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(scriptEvaluation == Support.UNAVAILABLE
                    ? "This node cannot evaluate Plutus scripts, so script outcomes are not checked."
                    : "Could not confirm whether this node can evaluate Plutus scripts.");
        }
        if (detail != null && !detail.isBlank()) {
            sb.append(' ').append(detail);
        }
        if (nodeVersion != null && !nodeVersion.isBlank()) {
            sb.append(" (node reports version ").append(nodeVersion).append(')');
        }
        return sb.toString();
    }
}
