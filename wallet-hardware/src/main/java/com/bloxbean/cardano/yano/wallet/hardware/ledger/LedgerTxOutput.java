package com.bloxbean.cardano.yano.wallet.hardware.ledger;

import java.math.BigInteger;
import java.util.List;

/**
 * A transaction output for Ledger signing (ADR-034, extended for ADR-035 M4),
 * addressed to a third party by raw address bytes, with an ADA amount, optional
 * native-asset groups, and — for Plutus outputs — an optional datum and
 * reference script.
 *
 * @param addressBytes    raw Shelley address bytes (header + credentials)
 * @param coin            lovelace amount
 * @param assets          native-asset groups (empty for ADA-only), in canonical order
 * @param datum           datum hash or inline datum; null when absent
 * @param referenceScript reference script CBOR bytes; null when absent
 * @param format          Ledger output format for THIS output (0 = legacy array,
 *                        1 = Babbage map); null = use the request-level default.
 *                        Per-output because real transactions mix formats — e.g.
 *                        a legacy-array change output beside a map-format script
 *                        output — and the device re-encodes each output as told.
 */
public record LedgerTxOutput(byte[] addressBytes, BigInteger coin, List<LedgerAssetGroup> assets,
                             LedgerDatum datum, byte[] referenceScript, Integer format) {

    public LedgerTxOutput {
        if (addressBytes == null || addressBytes.length == 0) {
            throw new IllegalArgumentException("addressBytes is required");
        }
        if (coin == null || coin.signum() < 0) {
            throw new IllegalArgumentException("coin must be >= 0");
        }
        assets = assets == null ? List.of() : List.copyOf(assets);
        if (referenceScript != null && referenceScript.length == 0) {
            throw new IllegalArgumentException("referenceScript must be non-empty when present");
        }
    }

    /** An output with datum/reference script, using the request-level format. */
    public LedgerTxOutput(byte[] addressBytes, BigInteger coin, List<LedgerAssetGroup> assets,
                          LedgerDatum datum, byte[] referenceScript) {
        this(addressBytes, coin, assets, datum, referenceScript, null);
    }

    /** An output without datum or reference script. */
    public LedgerTxOutput(byte[] addressBytes, BigInteger coin, List<LedgerAssetGroup> assets) {
        this(addressBytes, coin, assets, null, null, null);
    }

    /** An ADA-only output. */
    public LedgerTxOutput(byte[] addressBytes, BigInteger coin) {
        this(addressBytes, coin, List.of(), null, null, null);
    }
}
