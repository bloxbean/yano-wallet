package com.bloxbean.cardano.yano.wallet.connector;

import co.nstant.in.cbor.model.DataItem;
import com.bloxbean.cardano.client.address.Address;
import com.bloxbean.cardano.client.address.AddressProvider;
import com.bloxbean.cardano.client.address.Credential;
import com.bloxbean.cardano.client.api.model.Amount;
import com.bloxbean.cardano.client.api.model.Utxo;
import com.bloxbean.cardano.client.cip.cip30.CIP30UtxoSupplier;
import com.bloxbean.cardano.client.common.cbor.CborSerializationUtil;
import com.bloxbean.cardano.client.common.model.Networks;
import com.bloxbean.cardano.client.transaction.spec.Value;
import com.bloxbean.cardano.client.util.HexUtil;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The CIP-30 UTxO/Value encoders must match the wire format exactly. We prove it
 * by round-tripping through CCL's own CIP-30 deserializer (CIP30UtxoSupplier) —
 * if our hex decodes back to the same UTxO, the encoding is CIP-30-correct.
 */
class Cip30CodecTest {

    private static final String POLICY_ASSET =
            "00112233445566778899aabbccddeeff00112233445566778899aabb" + "544f4b"; // policyId + "TOK"

    private static String testAddress() {
        byte[] pay = new byte[28];
        pay[0] = 1;
        byte[] stake = new byte[28];
        stake[0] = 2;
        Address addr = AddressProvider.getBaseAddress(
                Credential.fromKey(pay), Credential.fromKey(stake), Networks.testnet());
        return addr.toBech32();
    }

    @Test
    void utxoRoundTripsThroughCclDeserializer() throws Exception {
        String address = testAddress();
        Utxo utxo = Utxo.builder()
                .txHash("aa".repeat(32))
                .outputIndex(2)
                .address(address)
                .amount(List.of(
                        Amount.lovelace(BigInteger.valueOf(2_000_000)),
                        Amount.asset(POLICY_ASSET, BigInteger.valueOf(5))))
                .build();

        String hex = Cip30Codec.utxoHex(utxo);
        List<Utxo> back = new CIP30UtxoSupplier(List.of(hex)).getAll(address);

        assertThat(back).hasSize(1);
        Utxo r = back.get(0);
        assertThat(r.getTxHash()).isEqualTo(utxo.getTxHash());
        assertThat(r.getOutputIndex()).isEqualTo(2);
        assertThat(r.getAddress()).isEqualTo(address);
        assertThat(r.getAmount()).anySatisfy(a -> {
            assertThat(a.getUnit()).isEqualTo("lovelace");
            assertThat(a.getQuantity()).isEqualTo(BigInteger.valueOf(2_000_000));
        });
        assertThat(r.getAmount()).anySatisfy(a ->
                assertThat(a.getQuantity()).isEqualTo(BigInteger.valueOf(5)));
    }

    @Test
    void balanceSumsAllUtxosIntoAValue() throws Exception {
        String address = testAddress();
        Utxo a = Utxo.builder().txHash("aa".repeat(32)).outputIndex(0).address(address)
                .amount(List.of(Amount.lovelace(BigInteger.valueOf(2_000_000)))).build();
        Utxo b = Utxo.builder().txHash("bb".repeat(32)).outputIndex(1).address(address)
                .amount(List.of(Amount.lovelace(BigInteger.valueOf(3_500_000)))).build();

        String hex = Cip30Codec.balanceHex(List.of(a, b));
        DataItem item = CborSerializationUtil.deserialize(HexUtil.decodeHexString(hex));
        Value total = Value.deserialize(item);

        assertThat(total.getCoin()).isEqualTo(BigInteger.valueOf(5_500_000));
    }

    @Test
    void addressHexIsRawAddressBytes() {
        String address = testAddress();
        String hex = Cip30Codec.addressHex(address);
        assertThat(hex).isEqualTo(HexUtil.encodeHexString(new Address(address).getBytes()));
    }

    @Test
    void pureAdaDetection() {
        String address = testAddress();
        Utxo ada = Utxo.builder().txHash("aa".repeat(32)).outputIndex(0).address(address)
                .amount(List.of(Amount.lovelace(BigInteger.valueOf(5_000_000)))).build();
        Utxo withToken = Utxo.builder().txHash("bb".repeat(32)).outputIndex(0).address(address)
                .amount(List.of(Amount.lovelace(BigInteger.valueOf(2_000_000)),
                        Amount.asset(POLICY_ASSET, BigInteger.ONE))).build();
        assertThat(Cip30Codec.isPureAda(ada)).isTrue();
        assertThat(Cip30Codec.isPureAda(withToken)).isFalse();
    }
}
