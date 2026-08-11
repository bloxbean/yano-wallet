package com.bloxbean.cardano.yano.wallet.hardware.ledger;

import java.math.BigInteger;
import java.util.List;

/**
 * Everything the device needs to re-derive a transaction body and sign it
 * (ADR-034 stream, extended for ADR-035 M4 / Plutus). The device recomputes the
 * tx hash from these fields, so they must reproduce the host's CBOR exactly —
 * including the encoding knobs ({@code tagCborSets}, {@code outputFormat}). The
 * caller compares the returned hash with its own; a mismatch means the
 * translation was wrong and nothing is submitted.
 *
 * <p>Nullable fields are simply absent from the stream. Use {@link #builder()};
 * the field list is far past a sane constructor signature.
 */
public record LedgerSignRequest(
        int networkId,
        long protocolMagic,
        int signingMode,
        List<LedgerTxInput> inputs,
        List<LedgerTxOutput> outputs,
        BigInteger fee,
        Long ttl,
        List<byte[]> certificates,
        List<byte[]> withdrawals,
        List<byte[]> votingProcedures,
        byte[] auxiliaryDataHash,
        Long validityIntervalStart,
        List<LedgerAssetGroup> mint,
        byte[] scriptDataHash,
        List<LedgerTxInput> collateralInputs,
        List<byte[]> requiredSigners,
        LedgerTxOutput collateralOutput,
        BigInteger totalCollateral,
        List<LedgerTxInput> referenceInputs,
        BigInteger treasury,
        BigInteger donation,
        boolean tagCborSets,
        int outputFormat,
        List<long[]> signingPaths) {

    public LedgerSignRequest {
        inputs = orEmpty(inputs);
        outputs = orEmpty(outputs);
        certificates = orEmpty(certificates);
        withdrawals = orEmpty(withdrawals);
        votingProcedures = orEmpty(votingProcedures);
        mint = orEmpty(mint);
        collateralInputs = orEmpty(collateralInputs);
        requiredSigners = orEmpty(requiredSigners);
        referenceInputs = orEmpty(referenceInputs);
        signingPaths = orEmpty(signingPaths);
    }

    private static <T> List<T> orEmpty(List<T> list) {
        return list == null ? List.of() : List.copyOf(list);
    }

    /** True when the tx carries anything that requires the device's Plutus mode. */
    public boolean needsPlutusMode() {
        return scriptDataHash != null || !collateralInputs.isEmpty() || !requiredSigners.isEmpty()
                || collateralOutput != null || totalCollateral != null;
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Mutable builder; only networkId/protocolMagic/fee and the signing paths are really required. */
    public static final class Builder {
        private int networkId;
        private long protocolMagic;
        private int signingMode = LedgerCardanoApp.SIGNING_MODE_ORDINARY;
        private List<LedgerTxInput> inputs = List.of();
        private List<LedgerTxOutput> outputs = List.of();
        private BigInteger fee = BigInteger.ZERO;
        private Long ttl;
        private List<byte[]> certificates = List.of();
        private List<byte[]> withdrawals = List.of();
        private List<byte[]> votingProcedures = List.of();
        private byte[] auxiliaryDataHash;
        private Long validityIntervalStart;
        private List<LedgerAssetGroup> mint = List.of();
        private byte[] scriptDataHash;
        private List<LedgerTxInput> collateralInputs = List.of();
        private List<byte[]> requiredSigners = List.of();
        private LedgerTxOutput collateralOutput;
        private BigInteger totalCollateral;
        private List<LedgerTxInput> referenceInputs = List.of();
        private BigInteger treasury;
        private BigInteger donation;
        private boolean tagCborSets;
        private int outputFormat;
        private List<long[]> signingPaths = List.of();

        public Builder networkId(int v) { networkId = v; return this; }
        public Builder protocolMagic(long v) { protocolMagic = v; return this; }
        public Builder signingMode(int v) { signingMode = v; return this; }
        public Builder inputs(List<LedgerTxInput> v) { inputs = v; return this; }
        public Builder outputs(List<LedgerTxOutput> v) { outputs = v; return this; }
        public Builder fee(BigInteger v) { fee = v; return this; }
        public Builder ttl(Long v) { ttl = v; return this; }
        public Builder certificates(List<byte[]> v) { certificates = v; return this; }
        public Builder withdrawals(List<byte[]> v) { withdrawals = v; return this; }
        public Builder votingProcedures(List<byte[]> v) { votingProcedures = v; return this; }
        public Builder auxiliaryDataHash(byte[] v) { auxiliaryDataHash = v; return this; }
        public Builder validityIntervalStart(Long v) { validityIntervalStart = v; return this; }
        public Builder mint(List<LedgerAssetGroup> v) { mint = v; return this; }
        public Builder scriptDataHash(byte[] v) { scriptDataHash = v; return this; }
        public Builder collateralInputs(List<LedgerTxInput> v) { collateralInputs = v; return this; }
        public Builder requiredSigners(List<byte[]> v) { requiredSigners = v; return this; }
        public Builder collateralOutput(LedgerTxOutput v) { collateralOutput = v; return this; }
        public Builder totalCollateral(BigInteger v) { totalCollateral = v; return this; }
        public Builder referenceInputs(List<LedgerTxInput> v) { referenceInputs = v; return this; }
        public Builder treasury(BigInteger v) { treasury = v; return this; }
        public Builder donation(BigInteger v) { donation = v; return this; }
        public Builder tagCborSets(boolean v) { tagCborSets = v; return this; }
        public Builder outputFormat(int v) { outputFormat = v; return this; }
        public Builder signingPaths(List<long[]> v) { signingPaths = v; return this; }

        public LedgerSignRequest build() {
            return new LedgerSignRequest(networkId, protocolMagic, signingMode, inputs, outputs, fee, ttl,
                    certificates, withdrawals, votingProcedures, auxiliaryDataHash, validityIntervalStart,
                    mint, scriptDataHash, collateralInputs, requiredSigners, collateralOutput,
                    totalCollateral, referenceInputs, treasury, donation, tagCborSets, outputFormat,
                    signingPaths);
        }
    }
}
