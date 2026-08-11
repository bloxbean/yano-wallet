package com.bloxbean.cardano.yano.wallet.ui.contract;

import java.util.List;

/**
 * What a transaction will do to this wallet, ready to render (ADR-042).
 *
 * <p>Plain types only — strings, longs, booleans, enums and lists of records —
 * per the ADR-033 boundary rule: no core, CCL or node types cross into the UI.
 * Every string here is already display-safe; in particular {@link
 * AssetChange#displayName()} has been sanitised, because an asset name is
 * arbitrary bytes chosen by whoever minted it and this record is rendered inside
 * a security dialog.
 *
 * <p>{@link #completeness()} is the field that must never be ignored by a
 * renderer. An {@code INCOMPLETE} summary is not a smaller loss than a complete
 * one — it is an unknown one.
 */
public record TxEffectView(Completeness completeness,
                           String limitation,
                           long netLovelace,
                           long feeLovelace,
                           List<AssetChange> assetChanges,
                           ScriptOutcome scriptOutcome,
                           String scriptMessage,
                           int redeemerCount,
                           long scriptMemory,
                           long scriptSteps,
                           List<RiskItem> risks,
                           int outputCount,
                           int inputCount,
                           int unresolvedInputCount,
                           long collateralLovelace,
                           List<String> certificates,
                           List<WithdrawalItem> withdrawals,
                           List<AssetChange> mint,
                           String rawCborHex) {

    public enum Completeness {
        /** Every input was resolved and classified; the numbers can be trusted. */
        COMPLETE,
        /** Something could not be checked — the real effect may be larger than shown. */
        INCOMPLETE,
        /** The transaction could not be decoded; no number here means anything. */
        UNDECODABLE
    }

    public enum ScriptOutcome {NO_SCRIPTS, SUCCESS, FAILED, COULD_NOT_VERIFY}

    public enum Severity {INFO, WARNING, CRITICAL}

    /**
     * @param displayName sanitised for display; never rendered raw
     * @param quantity    signed decimal string (asset amounts can exceed a long once netted)
     */
    public record AssetChange(String displayName, String policyId, String assetNameHex,
                              String quantity, boolean outgoing) {
    }

    public record RiskItem(Severity severity, String title, String reason) {
    }

    public record WithdrawalItem(String rewardAddress, long lovelace, boolean mine) {
    }

    public TxEffectView {
        assetChanges = assetChanges == null ? List.of() : List.copyOf(assetChanges);
        risks = risks == null ? List.of() : List.copyOf(risks);
        certificates = certificates == null ? List.of() : List.copyOf(certificates);
        withdrawals = withdrawals == null ? List.of() : List.copyOf(withdrawals);
        mint = mint == null ? List.of() : List.copyOf(mint);
    }

    public boolean isComplete() {
        return completeness == Completeness.COMPLETE;
    }

    /** True when the wallet gives up value overall (before considering assets). */
    public boolean isOutgoing() {
        return netLovelace < 0;
    }
}
