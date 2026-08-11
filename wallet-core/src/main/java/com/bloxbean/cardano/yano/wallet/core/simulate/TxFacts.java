package com.bloxbean.cardano.yano.wallet.core.simulate;

import java.math.BigInteger;
import java.util.List;

/**
 * The "what else happens" half of ADR-042's summary: everything a transaction
 * does beyond moving value. These are read straight from the decoded body, so
 * they are available even when input resolution fails — a transaction that
 * deregisters a stake key is worth showing whether or not we could price it.
 */
public record TxFacts(List<AssetDelta> mint,
                      List<String> certificates,
                      List<Withdrawal> withdrawals,
                      BigInteger collateralLovelace,
                      boolean collateralPresent,
                      long ttl,
                      long validityStart,
                      int outputCount,
                      int inputCount,
                      int unresolvedInputCount,
                      int scriptOutputCount,
                      int datumOutputCount,
                      boolean hasRedeemers) {

    /** A minted (positive) or burned (negative) asset amount. */
    public record AssetDelta(String policyId, String assetNameHex, BigInteger quantity) {
    }

    /** A reward withdrawal: which reward account, and how much. */
    public record Withdrawal(String rewardAddress, BigInteger lovelace, boolean mine) {
    }

    public TxFacts {
        mint = mint == null ? List.of() : List.copyOf(mint);
        certificates = certificates == null ? List.of() : List.copyOf(certificates);
        withdrawals = withdrawals == null ? List.of() : List.copyOf(withdrawals);
        collateralLovelace = collateralLovelace == null ? BigInteger.ZERO : collateralLovelace;
    }

    public static TxFacts empty() {
        return new TxFacts(List.of(), List.of(), List.of(), BigInteger.ZERO, false,
                0L, 0L, 0, 0, 0, 0, 0, false);
    }
}
