package com.bloxbean.cardano.yano.wallet.hardware.yubikey;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The YubiKey wire framing/CRC (ADR-036 Y-M1), verified without a device. The
 * strongest check is the CRC residual property the on-device response read
 * relies on: crc16(data ‖ its-own-CRC) == 0xF0B8.
 */
class YubiKeyProtocolTest {

    @Test
    void crcResidualHoldsForHdlcFrameCheck() {
        // The device transmits the ones-complement of the CRC (HDLC/X-25 FCS),
        // little-endian; recomputing crc16 over data+FCS yields the 0xF0B8 magic
        // residual the response read checks. This mirrors real device framing.
        byte[] data = {0x11, 0x22, 0x33, 0x44, 0x55, 0x66, 0x77, (byte) 0x88};
        byte[] withCrc = appendFrameCheck(data);

        assertThat(YubiKeyProtocol.crc16(withCrc, withCrc.length)).isEqualTo(YubiKeyProtocol.CRC_OK_RESIDUAL);
    }

    @Test
    void padsShortChallengeTo64WithZeros() {
        byte[] challenge = new byte[32];
        for (int i = 0; i < 32; i++) {
            challenge[i] = (byte) (i + 1); // last byte 32 (non-zero)
        }
        byte[] payload = YubiKeyProtocol.padChallengeTo64(challenge);

        assertThat(payload).hasSize(64);
        assertThat(payload[31]).isEqualTo((byte) 32);
        for (int i = 32; i < 64; i++) {
            assertThat(payload[i]).isEqualTo((byte) 0x00);
        }
    }

    @Test
    void padsWith0xFFWhenChallengeEndsInZero() {
        byte[] challenge = new byte[32]; // all zeros → last byte is 0x00
        byte[] payload = YubiKeyProtocol.padChallengeTo64(challenge);

        for (int i = 32; i < 64; i++) {
            assertThat(payload[i]).isEqualTo((byte) 0xFF);
        }
    }

    @Test
    void sixtyFourByteChallengeIsUnpadded() {
        byte[] challenge = new byte[64];
        challenge[63] = 0x09;
        assertThat(YubiKeyProtocol.padChallengeTo64(challenge)).containsExactly(challenge);
    }

    @Test
    void rejectsEmptyOrOversizeChallenge() {
        assertThatThrownBy(() -> YubiKeyProtocol.padChallengeTo64(new byte[0]))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> YubiKeyProtocol.padChallengeTo64(new byte[65]))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void buildsFrameWithSlotAndLittleEndianCrc() {
        byte[] payload = YubiKeyProtocol.padChallengeTo64(nonZeroChallenge(32));
        byte[] frame = YubiKeyProtocol.buildFrame(payload, YubiKeyProtocol.SLOT_CHAL_HMAC2);

        assertThat(frame).hasSize(70);
        assertThat(frame[64]).isEqualTo((byte) YubiKeyProtocol.SLOT_CHAL_HMAC2);
        int crc = YubiKeyProtocol.crc16(payload, 64);
        assertThat(frame[65]).isEqualTo((byte) (crc & 0xFF));
        assertThat(frame[66]).isEqualTo((byte) ((crc >>> 8) & 0xFF));
        assertThat(frame[67]).isZero();
        assertThat(frame[68]).isZero();
        assertThat(frame[69]).isZero();
    }

    @Test
    void writeReportsSkipEmptyMiddleReportsButKeepFirstAndLast() {
        byte[] frame = YubiKeyProtocol.buildFrame(
                YubiKeyProtocol.padChallengeTo64(nonZeroChallenge(32)), YubiKeyProtocol.SLOT_CHAL_HMAC2);
        List<byte[]> reports = YubiKeyProtocol.writeReports(frame);

        // A 32-byte challenge with 0x00 padding leaves seq 5..8 all-zero → skipped;
        // seq 0..4 carry challenge bytes and seq 9 carries slot+CRC.
        int[] sequences = reports.stream()
                .mapToInt(r -> r[YubiKeyProtocol.REPORT_DATA_SIZE] & YubiKeyProtocol.SEQ_MASK)
                .toArray();
        assertThat(sequences).containsExactly(0, 1, 2, 3, 4, 9);
        // Every report's flag byte sets SLOT_WRITE_FLAG.
        assertThat(reports).allSatisfy(r ->
                assertThat(r[YubiKeyProtocol.REPORT_DATA_SIZE] & YubiKeyProtocol.SLOT_WRITE_FLAG).isNotZero());
        assertThat(reports).allSatisfy(r -> assertThat(r).hasSize(8));
    }

    @Test
    void extractDigestReturnsFirst20BytesWhenCrcValid() {
        byte[] digest = new byte[20];
        for (int i = 0; i < 20; i++) {
            digest[i] = (byte) (0xA0 + i);
        }
        byte[] withCrc = appendFrameCheck(digest);
        byte[] collected = new byte[28]; // device sends 4×7 bytes; the rest is padding
        System.arraycopy(withCrc, 0, collected, 0, withCrc.length);

        assertThat(YubiKeyProtocol.extractDigest(collected)).containsExactly(digest);
    }

    @Test
    void extractDigestRejectsCorruptResponse() {
        byte[] collected = appendFrameCheck(new byte[20]);
        collected[0] ^= 0x01; // flip a bit after the CRC was computed

        assertThatThrownBy(() -> YubiKeyProtocol.extractDigest(collected))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("CRC");
    }

    /** Appends the HDLC-style frame check (ones-complement CRC, little-endian) the device uses. */
    private static byte[] appendFrameCheck(byte[] data) {
        int fcs = (~YubiKeyProtocol.crc16(data, data.length)) & 0xFFFF;
        byte[] out = new byte[data.length + 2];
        System.arraycopy(data, 0, out, 0, data.length);
        out[data.length] = (byte) (fcs & 0xFF);
        out[data.length + 1] = (byte) ((fcs >>> 8) & 0xFF);
        return out;
    }

    @Test
    void statusFlagHelpers() {
        assertThat(YubiKeyProtocol.responsePending((byte) 0x40)).isTrue();
        assertThat(YubiKeyProtocol.responsePending((byte) 0x00)).isFalse();
        assertThat(YubiKeyProtocol.awaitingTouch((byte) 0x25)).isTrue();
        assertThat(YubiKeyProtocol.sequence((byte) 0x43)).isEqualTo(3);
        assertThat(YubiKeyProtocol.writeFlagClear((byte) 0x00)).isTrue();
        assertThat(YubiKeyProtocol.writeFlagClear((byte) 0x80)).isFalse();
    }

    private static byte[] nonZeroChallenge(int length) {
        byte[] challenge = new byte[length];
        for (int i = 0; i < length; i++) {
            challenge[i] = (byte) (i + 1);
        }
        return challenge;
    }
}
