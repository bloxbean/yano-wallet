package com.bloxbean.cardano.yano.wallet.hardware.fido;

import org.junit.jupiter.api.Test;

import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;

/** CTAP2 canonical CBOR encoding, checked against known RFC 8949 vectors. */
class Ctap2CborTest {

    private static final HexFormat HEX = HexFormat.of();

    @Test
    void encodesUnsignedIntegersShortestForm() {
        assertThat(hex(Ctap2Cbor.integer(0))).isEqualTo("00");
        assertThat(hex(Ctap2Cbor.integer(23))).isEqualTo("17");
        assertThat(hex(Ctap2Cbor.integer(24))).isEqualTo("1818");
        assertThat(hex(Ctap2Cbor.integer(255))).isEqualTo("18ff");
        assertThat(hex(Ctap2Cbor.integer(256))).isEqualTo("190100");
        assertThat(hex(Ctap2Cbor.integer(65536))).isEqualTo("1a00010000");
    }

    @Test
    void encodesNegativeIntegers() {
        assertThat(hex(Ctap2Cbor.integer(-1))).isEqualTo("20");
        assertThat(hex(Ctap2Cbor.integer(-7))).isEqualTo("26");    // COSE ES256 alg
        assertThat(hex(Ctap2Cbor.integer(-25))).isEqualTo("3818"); // COSE ECDH-ES+HKDF-256
    }

    @Test
    void encodesByteAndTextStrings() {
        assertThat(hex(Ctap2Cbor.bytes(new byte[]{1, 2, 3, 4}))).isEqualTo("4401020304");
        assertThat(hex(Ctap2Cbor.text("a"))).isEqualTo("6161");
        assertThat(hex(Ctap2Cbor.text("id"))).isEqualTo("626964");
    }

    @Test
    void encodesArrays() {
        assertThat(hex(Ctap2Cbor.array(Ctap2Cbor.integer(1), Ctap2Cbor.integer(2), Ctap2Cbor.integer(3))))
                .isEqualTo("83010203");
    }

    @Test
    void mapSortsIntegerKeysAscendingRegardlessOfInsertionOrder() {
        byte[] map = Ctap2Cbor.map()
                .put(2, Ctap2Cbor.text("b"))
                .put(1, Ctap2Cbor.text("a"))
                .build();
        // a2 (map,2) | 01 6161 | 02 6162
        assertThat(hex(map)).isEqualTo("a2016161026162");
    }

    @Test
    void mapSortsTextKeysShorterFirstThenBytewise() {
        byte[] map = Ctap2Cbor.map()
                .put("name", Ctap2Cbor.integer(1))
                .put("id", Ctap2Cbor.integer(2))
                .build();
        // "id" (len 2) sorts before "name" (len 4): a2 | 626964 02 | 646e616d65 01
        assertThat(hex(map)).isEqualTo("a262696402646e616d6501");
    }

    private static String hex(byte[] bytes) {
        return HEX.formatHex(bytes);
    }
}
