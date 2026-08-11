package com.bloxbean.cardano.yano.wallet.hardware.fido;

import org.junit.jupiter.api.Test;

import java.security.MessageDigest;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;

/** CTAP2 PIN/UV auth protocol v1 + v2 primitives (ADR-036 Y-M2). */
class PinUvAuthProtocolTest {

    private static final HexFormat HEX = HexFormat.of();

    @Test
    void hkdfMatchesRfc5869TestCase1() {
        // RFC 5869 Appendix A.1 — validates the HKDF-SHA256 used by protocol v2.
        byte[] ikm = HEX.parseHex("0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b");
        byte[] salt = HEX.parseHex("000102030405060708090a0b0c");
        byte[] info = HEX.parseHex("f0f1f2f3f4f5f6f7f8f9");
        byte[] okm = PinUvAuthProtocol.hkdfSha256(ikm, salt, info, 42);
        assertThat(HEX.formatHex(okm)).isEqualTo(
                "3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865");
    }

    @Test
    void v1DerivesSharedSecretAsSha256() throws Exception {
        PinUvAuthProtocol v1 = PinUvAuthProtocol.forVersion(1);
        byte[] z = HEX.parseHex("00112233445566778899aabbccddeeff00112233445566778899aabbccddeeff");
        byte[] expected = MessageDigest.getInstance("SHA-256").digest(z);
        assertThat(v1.kdf(z)).containsExactly(expected).hasSize(32);
    }

    @Test
    void v1EncryptDecryptRoundTripsWithZeroIv() {
        PinUvAuthProtocol v1 = PinUvAuthProtocol.forVersion(1);
        byte[] key = new byte[32];
        byte[] plaintext = HEX.parseHex("00112233445566778899aabbccddeeff00112233445566778899aabbccddeeff");
        byte[] ciphertext = v1.encrypt(key, plaintext);
        assertThat(ciphertext).hasSize(32); // zero-IV, no prefix
        assertThat(v1.decrypt(key, ciphertext)).containsExactly(plaintext);
    }

    @Test
    void v1AuthenticateTruncatesToSixteenBytes() {
        PinUvAuthProtocol v1 = PinUvAuthProtocol.forVersion(1);
        byte[] tag = v1.authenticate(new byte[32], new byte[]{1, 2, 3});
        assertThat(tag).hasSize(16);
        assertThat(tag).containsExactly(
                java.util.Arrays.copyOf(PinUvAuthProtocol.hmacSha256(new byte[32], new byte[]{1, 2, 3}), 16));
    }

    @Test
    void v2DerivesSixtyFourByteSharedSecretDeterministically() {
        PinUvAuthProtocol v2 = PinUvAuthProtocol.forVersion(2);
        byte[] z = HEX.parseHex("00112233445566778899aabbccddeeff00112233445566778899aabbccddeeff");
        byte[] first = v2.kdf(z);
        assertThat(first).hasSize(64);
        assertThat(v2.kdf(z)).containsExactly(first); // deterministic
    }

    @Test
    void v2EncryptPrependsRandomIvAndDecryptRecovers() {
        PinUvAuthProtocol v2 = PinUvAuthProtocol.forVersion(2);
        byte[] shared = v2.kdf(new byte[32]); // 64 bytes
        byte[] plaintext = HEX.parseHex("00112233445566778899aabbccddeeff00112233445566778899aabbccddeeff");

        byte[] c1 = v2.encrypt(shared, plaintext);
        byte[] c2 = v2.encrypt(shared, plaintext);
        assertThat(c1).hasSize(16 + 32);          // IV || ciphertext
        assertThat(c1).isNotEqualTo(c2);          // random IV each time
        assertThat(v2.decrypt(shared, c1)).containsExactly(plaintext);
        assertThat(v2.decrypt(shared, c2)).containsExactly(plaintext);
    }

    @Test
    void v2AuthenticateUsesHmacHalfAndIsFullLength() {
        PinUvAuthProtocol v2 = PinUvAuthProtocol.forVersion(2);
        byte[] shared = v2.kdf(new byte[32]); // 64 bytes: hmacKey || aesKey
        byte[] tag = v2.authenticate(shared, new byte[]{9, 9, 9});
        assertThat(tag).hasSize(32);
        byte[] hmacHalf = java.util.Arrays.copyOfRange(shared, 0, 32);
        assertThat(tag).containsExactly(PinUvAuthProtocol.hmacSha256(hmacHalf, new byte[]{9, 9, 9}));
    }
}
