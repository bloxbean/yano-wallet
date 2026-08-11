package com.bloxbean.cardano.yano.wallet.hardware.ledger;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LedgerHidFramingTest {

    private static final int CHANNEL = 0x0101;

    @Test
    void apduCommand_serializesGetVersion() {
        byte[] serialized = new ApduCommand(0xD7, 0x00, 0x00, 0x00, new byte[0]).serialize();
        assertThat(serialized).containsExactly(0xD7, 0x00, 0x00, 0x00, 0x00);
    }

    @Test
    void apduCommand_serializesWithData() {
        byte[] serialized = new ApduCommand(0xD7, 0x10, 0x01, 0x02, new byte[]{0x0a, 0x0b}).serialize();
        assertThat(serialized).containsExactly(0xD7, 0x10, 0x01, 0x02, 0x02, 0x0a, 0x0b);
    }

    @Test
    void wrapCommand_shortApdu_isOneChannelFramedPaddedReport() {
        byte[] apdu = {(byte) 0xD7, 0x00, 0x00, 0x00, 0x00};
        List<byte[]> reports = LedgerHidFraming.wrapCommand(CHANNEL, apdu);

        assertThat(reports).hasSize(1);
        byte[] r = reports.get(0);
        assertThat(r).hasSize(LedgerHidFraming.REPORT_SIZE);
        // channel (0x0101), tag (0x05), seq (0x0000), length (0x0005)
        assertThat(new byte[]{r[0], r[1], r[2], r[3], r[4], r[5], r[6]})
                .containsExactly(0x01, 0x01, 0x05, 0x00, 0x00, 0x00, 0x05);
        // payload then zero padding
        assertThat(new byte[]{r[7], r[8], r[9], r[10], r[11]})
                .containsExactly(0xD7, 0x00, 0x00, 0x00, 0x00);
        assertThat(r[12]).isZero();
    }

    @Test
    void wrapThenReassemble_roundTrips_acrossReportBoundaries() {
        for (int size : new int[]{0, 1, 5, 56, 57, 58, 59, 60, 200, 1024}) {
            byte[] payload = sequential(size);
            List<byte[]> reports = LedgerHidFraming.wrapCommand(CHANNEL, payload);

            LedgerHidFraming.ResponseAssembler assembler = new LedgerHidFraming.ResponseAssembler(CHANNEL);
            for (byte[] report : reports) {
                assertThat(assembler.isComplete()).isFalse();
                assembler.add(report);
            }
            assertThat(assembler.isComplete()).as("complete for size %d", size).isTrue();
            assertThat(assembler.payload()).as("payload for size %d", size).isEqualTo(payload);
        }
    }

    @Test
    void multiReport_continuationHeadersUseFiveByteHeader() {
        byte[] payload = sequential(58); // 57 in report 0, 1 byte spills into report 1
        List<byte[]> reports = LedgerHidFraming.wrapCommand(CHANNEL, payload);
        assertThat(reports).hasSize(2);
        byte[] second = reports.get(1);
        // channel, tag, seq=1, then payload continues at offset 5 (no length header)
        assertThat(new byte[]{second[0], second[1], second[2], second[3], second[4]})
                .containsExactly(0x01, 0x01, 0x05, 0x00, 0x01);
        assertThat(second[5]).isEqualTo((byte) 57); // 58th payload byte (0-indexed value 57)
    }

    @Test
    void apduResponse_fromPayload_splitsStatusWord() {
        ApduResponse ok = ApduResponse.fromPayload(new byte[]{0x01, 0x02, (byte) 0x90, 0x00});
        assertThat(ok.data()).containsExactly(0x01, 0x02);
        assertThat(ok.statusWord()).isEqualTo(0x9000);
        assertThat(ok.isOk()).isTrue();

        ApduResponse err = ApduResponse.fromPayload(new byte[]{(byte) 0x6E, 0x00});
        assertThat(err.data()).isEmpty();
        assertThat(err.statusWord()).isEqualTo(0x6E00);
        assertThat(err.isOk()).isFalse();
        assertThat(err.statusWordHex()).isEqualTo("6e00");
    }

    @Test
    void apduResponse_fromPayload_rejectsTooShort() {
        assertThatThrownBy(() -> ApduResponse.fromPayload(new byte[]{0x00}))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void responseAssembler_rejectsWrongChannel() {
        byte[] report = LedgerHidFraming.wrapCommand(0x0202, sequential(4)).get(0);
        LedgerHidFraming.ResponseAssembler assembler = new LedgerHidFraming.ResponseAssembler(CHANNEL);
        assertThatThrownBy(() -> assembler.add(report))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("channel");
    }

    private static byte[] sequential(int size) {
        byte[] out = new byte[size];
        for (int i = 0; i < size; i++) {
            out[i] = (byte) (i & 0xFF);
        }
        return out;
    }
}
