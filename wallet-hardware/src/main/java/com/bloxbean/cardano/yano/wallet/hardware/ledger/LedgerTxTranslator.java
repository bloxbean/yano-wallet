package com.bloxbean.cardano.yano.wallet.hardware.ledger;

import co.nstant.in.cbor.CborDecoder;
import co.nstant.in.cbor.model.Array;
import co.nstant.in.cbor.model.ByteString;
import co.nstant.in.cbor.model.DataItem;
import co.nstant.in.cbor.model.MajorType;
import co.nstant.in.cbor.model.Map;
import co.nstant.in.cbor.model.NegativeInteger;
import co.nstant.in.cbor.model.Number;
import com.bloxbean.cardano.client.util.HexUtil;

import java.io.ByteArrayInputStream;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Translates an arbitrary (dApp-built) transaction's CBOR into a
 * {@link LedgerSignRequest} (ADR-035 M4b). Works on the raw CBOR data items —
 * not a normalizing object model — because the device re-derives the tx hash
 * from what we stream, so the encoding knobs (CBOR set tag 258, per-output
 * legacy-array vs Babbage-map format) must be read off the dApp's own bytes.
 *
 * <p>The translation is best-effort by design: an unsupported body field throws
 * {@link UnsupportedTxException} with a plain-language reason, and any silent
 * mistake is caught by the tx-hash gate (device hash != host hash → abort).
 */
public final class LedgerTxTranslator {

    /** Conway transaction-body map keys (CDDL). */
    private static final int KEY_INPUTS = 0;
    private static final int KEY_OUTPUTS = 1;
    private static final int KEY_FEE = 2;
    private static final int KEY_TTL = 3;
    private static final int KEY_CERTS = 4;
    private static final int KEY_WITHDRAWALS = 5;
    private static final int KEY_AUX_DATA_HASH = 7;
    private static final int KEY_VALIDITY_START = 8;
    private static final int KEY_MINT = 9;
    private static final int KEY_SCRIPT_DATA_HASH = 11;
    private static final int KEY_COLLATERAL_INPUTS = 13;
    private static final int KEY_REQUIRED_SIGNERS = 14;
    private static final int KEY_NETWORK_ID = 15;
    private static final int KEY_COLLATERAL_RETURN = 16;
    private static final int KEY_TOTAL_COLLATERAL = 17;
    private static final int KEY_REFERENCE_INPUTS = 18;
    private static final int KEY_VOTING_PROCEDURES = 19;
    private static final int KEY_PROPOSAL_PROCEDURES = 20;
    private static final int KEY_TREASURY = 21;
    private static final int KEY_DONATION = 22;

    private static final long TAG_CBOR_SET = 258;
    private static final long TAG_ENCODED_CBOR = 24;

    /** A dApp transaction the translator (or the device) cannot represent yet. */
    public static class UnsupportedTxException extends RuntimeException {
        public UnsupportedTxException(String message) {
            super(message);
        }
    }

    /**
     * What the translator needs to know about the wallet.
     *
     * @param ownedInputs    {@code "txHashHex#index"} keys of UTxOs the wallet owns
     *                       — decides whether the payment path witnesses inputs
     * @param paymentKeyHash the wallet's payment key hash (28 bytes) — matches
     *                       required signers to the key path form
     * @param paymentPath    the payment path ({@code 1852'/1815'/account'/0/0})
     */
    public record Context(int networkId, long protocolMagic, Set<String> ownedInputs,
                          byte[] paymentKeyHash, long[] paymentPath) {
        public Context {
            ownedInputs = ownedInputs == null ? Set.of() : Set.copyOf(ownedInputs);
        }
    }

    private LedgerTxTranslator() {
    }

    /** Translates a full transaction ({@code [body, witnesses, isValid?, auxData?]}). */
    public static LedgerSignRequest translate(byte[] txCbor, Context ctx) {
        DataItem root = decodeSingle(txCbor);
        if (!(root instanceof Array envelope) || envelope.getDataItems().isEmpty()) {
            throw new UnsupportedTxException("Not a transaction: expected [body, witnesses, ...]");
        }
        DataItem bodyItem = envelope.getDataItems().get(0);
        if (!(bodyItem instanceof Map body)) {
            throw new UnsupportedTxException("Transaction body is not a CBOR map");
        }

        LedgerSignRequest.Builder request = LedgerSignRequest.builder()
                .networkId(ctx.networkId()).protocolMagic(ctx.protocolMagic());
        boolean tagCborSets = false;
        boolean plutus = false;
        List<LedgerTxInput> inputs = List.of();
        List<LedgerTxInput> collateral = List.of();
        boolean requiredSignerIsOurs = false;

        for (DataItem keyItem : body.getKeys()) {
            int key = intKey(keyItem);
            DataItem value = body.get(keyItem);
            switch (key) {
                case KEY_INPUTS -> {
                    tagCborSets |= hasSetTag(value);
                    inputs = readInputs(value);
                    request.inputs(inputs);
                }
                case KEY_OUTPUTS -> request.outputs(readOutputs(value));
                case KEY_FEE -> request.fee(toBigInteger(value));
                case KEY_TTL -> request.ttl(toBigInteger(value).longValueExact());
                case KEY_AUX_DATA_HASH -> request.auxiliaryDataHash(bytes32(value, "auxiliary data hash"));
                case KEY_VALIDITY_START -> request.validityIntervalStart(toBigInteger(value).longValueExact());
                case KEY_MINT -> request.mint(readMultiAsset(value, true));
                case KEY_SCRIPT_DATA_HASH -> {
                    request.scriptDataHash(bytes32(value, "script data hash"));
                    plutus = true;
                }
                case KEY_COLLATERAL_INPUTS -> {
                    tagCborSets |= hasSetTag(value);
                    collateral = readInputs(value);
                    request.collateralInputs(collateral);
                    plutus = true;
                }
                case KEY_REQUIRED_SIGNERS -> {
                    tagCborSets |= hasSetTag(value);
                    List<byte[]> signers = new ArrayList<>();
                    for (DataItem item : asArray(value, "required signers").getDataItems()) {
                        byte[] hash = ((ByteString) item).getBytes();
                        if (ctx.paymentKeyHash() != null && java.util.Arrays.equals(hash, ctx.paymentKeyHash())) {
                            signers.add(LedgerCardanoApp.requiredSignerPath(ctx.paymentPath()));
                            requiredSignerIsOurs = true;
                        } else {
                            signers.add(LedgerCardanoApp.requiredSignerHash(hash));
                        }
                    }
                    request.requiredSigners(signers);
                    plutus = true;
                }
                case KEY_COLLATERAL_RETURN -> {
                    request.collateralOutput(readOutput(value));
                    plutus = true;
                }
                case KEY_TOTAL_COLLATERAL -> {
                    request.totalCollateral(toBigInteger(value));
                    plutus = true;
                }
                case KEY_REFERENCE_INPUTS -> {
                    tagCborSets |= hasSetTag(value);
                    request.referenceInputs(readInputs(value));
                    plutus = true;
                }
                case KEY_TREASURY -> request.treasury(toBigInteger(value));
                case KEY_DONATION -> request.donation(toBigInteger(value));
                case KEY_CERTS -> throw new UnsupportedTxException(
                        "This dApp transaction contains certificates — not supported with a hardware wallet yet.");
                case KEY_WITHDRAWALS -> throw new UnsupportedTxException(
                        "This dApp transaction contains reward withdrawals — not supported with a hardware wallet yet.");
                case KEY_VOTING_PROCEDURES -> throw new UnsupportedTxException(
                        "This dApp transaction contains governance votes — not supported with a hardware wallet yet.");
                case KEY_PROPOSAL_PROCEDURES -> throw new UnsupportedTxException(
                        "This dApp transaction contains governance proposals — not supported with a hardware wallet yet.");
                case KEY_NETWORK_ID -> throw new UnsupportedTxException(
                        "This dApp transaction pins an explicit network id — not supported with a hardware wallet yet.");
                default -> throw new UnsupportedTxException(
                        "This dApp transaction uses an unrecognized body field (" + key
                                + ") — not supported with a hardware wallet yet.");
            }
        }

        request.tagCborSets(tagCborSets);
        request.signingMode(plutus ? LedgerCardanoApp.SIGNING_MODE_PLUTUS
                : LedgerCardanoApp.SIGNING_MODE_ORDINARY);

        // The payment path witnesses when the wallet owns an input (or collateral),
        // or is explicitly a required signer.
        boolean ownsInput = ownsAny(inputs, ctx) || ownsAny(collateral, ctx);
        if (ownsInput || requiredSignerIsOurs) {
            request.signingPaths(List.of(ctx.paymentPath()));
        } else {
            throw new UnsupportedTxException(
                    "This transaction has nothing for this wallet to sign (no owned inputs or required signers).");
        }
        return request.build();
    }

    private static boolean ownsAny(List<LedgerTxInput> inputs, Context ctx) {
        for (LedgerTxInput input : inputs) {
            if (ctx.ownedInputs().contains(input.txHashHex() + "#" + input.index())) {
                return true;
            }
        }
        return false;
    }

    // --- CBOR helpers ---

    private static DataItem decodeSingle(byte[] cbor) {
        try {
            List<DataItem> items = new CborDecoder(new ByteArrayInputStream(cbor)).decode();
            if (items.isEmpty()) {
                throw new UnsupportedTxException("Empty CBOR");
            }
            return items.get(0);
        } catch (UnsupportedTxException e) {
            throw e;
        } catch (Exception e) {
            throw new UnsupportedTxException("Invalid transaction CBOR: " + e.getMessage());
        }
    }

    private static boolean hasSetTag(DataItem item) {
        // A tag-258 set is still an Array in cbor-java; the tag rides on the item.
        return item.getTag() != null && item.getTag().getValue() == TAG_CBOR_SET;
    }

    private static Array asArray(DataItem item, String what) {
        if (!(item instanceof Array array)) {
            throw new UnsupportedTxException("Expected an array for " + what);
        }
        return array;
    }

    private static int intKey(DataItem keyItem) {
        if (keyItem.getMajorType() != MajorType.UNSIGNED_INTEGER) {
            throw new UnsupportedTxException("Non-integer transaction body key");
        }
        return ((Number) keyItem).getValue().intValueExact();
    }

    private static BigInteger toBigInteger(DataItem item) {
        if (item instanceof NegativeInteger negative) {
            return negative.getValue();
        }
        if (item.getMajorType() == MajorType.UNSIGNED_INTEGER) {
            return ((Number) item).getValue();
        }
        throw new UnsupportedTxException("Expected an integer, found " + item.getMajorType());
    }

    private static byte[] bytes32(DataItem item, String what) {
        byte[] bytes = ((ByteString) item).getBytes();
        if (bytes.length != 32) {
            throw new UnsupportedTxException(what + " must be 32 bytes");
        }
        return bytes;
    }

    private static List<LedgerTxInput> readInputs(DataItem value) {
        List<LedgerTxInput> inputs = new ArrayList<>();
        for (DataItem item : asArray(value, "inputs").getDataItems()) {
            Array pair = asArray(item, "input");
            byte[] txHash = ((ByteString) pair.getDataItems().get(0)).getBytes();
            int index = ((Number) pair.getDataItems().get(1)).getValue().intValueExact();
            inputs.add(new LedgerTxInput(HexUtil.encodeHexString(txHash), index));
        }
        return inputs;
    }

    private static List<LedgerTxOutput> readOutputs(DataItem value) {
        List<LedgerTxOutput> outputs = new ArrayList<>();
        for (DataItem item : asArray(value, "outputs").getDataItems()) {
            outputs.add(readOutput(item));
        }
        return outputs;
    }

    /** Legacy array {@code [addr, value, ?datum_hash]} or Babbage map {@code {0:addr,1:value,2:datum,3:script}}. */
    private static LedgerTxOutput readOutput(DataItem item) {
        if (item instanceof Array legacy) {
            List<DataItem> fields = legacy.getDataItems();
            byte[] address = ((ByteString) fields.get(0)).getBytes();
            ValueParts value = readValue(fields.get(1));
            LedgerDatum datum = fields.size() > 2
                    ? LedgerDatum.hash(bytes32(fields.get(2), "output datum hash")) : null;
            return new LedgerTxOutput(address, value.coin(), value.assets(), datum, null,
                    OUTPUT_FORMAT_ARRAY);
        }
        if (item instanceof Map map) {
            byte[] address = null;
            ValueParts value = null;
            LedgerDatum datum = null;
            byte[] refScript = null;
            for (DataItem keyItem : map.getKeys()) {
                int key = intKey(keyItem);
                DataItem field = map.get(keyItem);
                switch (key) {
                    case 0 -> address = ((ByteString) field).getBytes();
                    case 1 -> value = readValue(field);
                    case 2 -> datum = readDatumOption(field);
                    case 3 -> refScript = readWrappedCbor(field, "reference script");
                    default -> throw new UnsupportedTxException("Unrecognized output field " + key);
                }
            }
            if (address == null || value == null) {
                throw new UnsupportedTxException("Output is missing its address or value");
            }
            return new LedgerTxOutput(address, value.coin(), value.assets(), datum, refScript,
                    OUTPUT_FORMAT_MAP);
        }
        throw new UnsupportedTxException("Unrecognized output encoding: " + item.getMajorType());
    }

    static final int OUTPUT_FORMAT_ARRAY = 0;
    static final int OUTPUT_FORMAT_MAP = 1;

    private record ValueParts(BigInteger coin, List<LedgerAssetGroup> assets) {
    }

    /** {@code coin} or {@code [coin, multiasset]}. */
    private static ValueParts readValue(DataItem item) {
        if (item.getMajorType() == MajorType.UNSIGNED_INTEGER) {
            return new ValueParts(((Number) item).getValue(), List.of());
        }
        Array pair = asArray(item, "output value");
        BigInteger coin = ((Number) pair.getDataItems().get(0)).getValue();
        List<LedgerAssetGroup> assets = readMultiAsset(pair.getDataItems().get(1), false);
        return new ValueParts(coin, assets);
    }

    /** Multiasset map: policy → (assetName → amount); mint amounts may be negative. */
    private static List<LedgerAssetGroup> readMultiAsset(DataItem item, boolean allowNegative) {
        if (!(item instanceof Map policies)) {
            throw new UnsupportedTxException("Expected a multiasset map");
        }
        List<LedgerAssetGroup> groups = new ArrayList<>();
        for (DataItem policyKey : policies.getKeys()) {
            byte[] policyId = ((ByteString) policyKey).getBytes();
            Map assets = (Map) policies.get(policyKey);
            List<LedgerToken> tokens = new ArrayList<>();
            for (DataItem nameKey : assets.getKeys()) {
                byte[] assetName = ((ByteString) nameKey).getBytes();
                BigInteger amount = toBigInteger(assets.get(nameKey));
                if (amount.signum() < 0 && !allowNegative) {
                    throw new UnsupportedTxException("Negative token amount outside mint");
                }
                tokens.add(new LedgerToken(assetName, amount));
            }
            groups.add(new LedgerAssetGroup(policyId, tokens));
        }
        return groups;
    }

    /** Conway datum_option: {@code [0, hash]} or {@code [1, tag24(bytes)]}. */
    private static LedgerDatum readDatumOption(DataItem item) {
        Array option = asArray(item, "datum option");
        int type = ((Number) option.getDataItems().get(0)).getValue().intValueExact();
        if (type == 0) {
            return LedgerDatum.hash(bytes32(option.getDataItems().get(1), "datum hash"));
        }
        if (type == 1) {
            return LedgerDatum.inline(readWrappedCbor(option.getDataItems().get(1), "inline datum"));
        }
        throw new UnsupportedTxException("Unrecognized datum option " + type);
    }

    /** A tag-24 wrapped CBOR byte string (inline datum / reference script). */
    private static byte[] readWrappedCbor(DataItem item, String what) {
        if (!(item instanceof ByteString byteString)
                || item.getTag() == null || item.getTag().getValue() != TAG_ENCODED_CBOR) {
            throw new UnsupportedTxException("Expected tag-24 wrapped bytes for " + what);
        }
        return byteString.getBytes();
    }
}
