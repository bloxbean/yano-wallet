package com.bloxbean.cardano.yano.wallet.connector;

import co.nstant.in.cbor.model.Array;
import com.bloxbean.cardano.client.address.Address;
import com.bloxbean.cardano.client.api.model.Amount;
import com.bloxbean.cardano.client.api.model.Utxo;
import com.bloxbean.cardano.client.api.util.UtxoUtil;
import com.bloxbean.cardano.client.common.cbor.CborSerializationUtil;
import com.bloxbean.cardano.client.transaction.spec.TransactionInput;
import com.bloxbean.cardano.client.transaction.spec.TransactionOutput;
import com.bloxbean.cardano.client.transaction.spec.Value;
import com.bloxbean.cardano.client.util.HexUtil;

import java.math.BigInteger;
import java.util.List;

/**
 * Encodes wallet data into the CIP-30 wire format (all hex): addresses as raw
 * address bytes, a {@code Value} as CBOR, and a UTxO as a
 * {@code TransactionUnspentOutput} = the CBOR array {@code [input, output]}. The
 * UTxO encoder is round-trip-tested against CCL's own CIP-30 deserializer.
 */
final class Cip30Codec {

    private Cip30Codec() {
    }

    /** Raw address bytes (bech32 → bytes) as hex — the CIP-30 Address format. */
    static String addressHex(String bech32) {
        return HexUtil.encodeHexString(new Address(bech32).getBytes());
    }

    /** A UTxO as a CIP-30 {@code TransactionUnspentOutput}: CBOR {@code [input, output]}, hex. */
    static String utxoHex(Utxo utxo) {
        try {
            TransactionInput input = new TransactionInput(utxo.getTxHash(), utxo.getOutputIndex());
            TransactionOutput output = outputOf(utxo);
            Array pair = new Array();
            pair.add(input.serialize());
            pair.add(output.serialize());
            return HexUtil.encodeHexString(CborSerializationUtil.serialize(pair));
        } catch (Exception e) {
            throw Cip30Exception.internal("Failed to encode UTxO: " + e.getMessage());
        }
    }

    /** The total {@code Value} across the given UTxOs, CBOR hex — CIP-30 getBalance. */
    static String balanceHex(List<Utxo> utxos) {
        Value total = Value.builder().coin(BigInteger.ZERO).build();
        for (Utxo utxo : utxos) {
            total = total.add(outputOf(utxo).getValue());
        }
        try {
            return HexUtil.encodeHexString(CborSerializationUtil.serialize(total.serialize()));
        } catch (Exception e) {
            throw Cip30Exception.internal("Failed to encode balance: " + e.getMessage());
        }
    }

    /** True if the UTxO holds only lovelace (a valid collateral candidate). */
    static boolean isPureAda(Utxo utxo) {
        List<Amount> amounts = utxo.getAmount();
        return amounts != null && amounts.size() == 1
                && "lovelace".equals(amounts.get(0).getUnit());
    }

    static BigInteger lovelace(Utxo utxo) {
        for (Amount a : utxo.getAmount()) {
            if ("lovelace".equals(a.getUnit())) {
                return a.getQuantity();
            }
        }
        return BigInteger.ZERO;
    }

    /** A short human summary of a dApp transaction for the sign approval (outputs, total out, fee). */
    private static TransactionOutput outputOf(Utxo utxo) {
        TransactionOutput output = new TransactionOutput(
                utxo.getAddress(), Value.builder().coin(BigInteger.ZERO).build());
        UtxoUtil.copyUtxoValuesToOutput(output, utxo); // fills coin + multiassets (+ datum/refscript)
        return output;
    }
}
