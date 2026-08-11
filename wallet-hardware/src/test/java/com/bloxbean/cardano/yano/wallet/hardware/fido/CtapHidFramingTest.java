package com.bloxbean.cardano.yano.wallet.hardware.fido;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** CTAPHID INIT/CONT packet framing (ADR-036 Y-M2), verified without a device. */
class CtapHidFramingTest {

    private static final int CID = 0x11223344;

    @Test
    void wrapsShortPayloadInOneInitPacket() {
        byte[] payload = {(byte) 0xAA, (byte) 0xBB, (byte) 0xCC};
        List<byte[]> reports = CtapHidFraming.wrapCommand(CID, CtapHidFraming.CMD_CBOR, payload);

        assertThat(reports).hasSize(1);
        byte[] init = reports.get(0);
        assertThat(init).hasSize(64);
        assertThat(CtapHidFraming.channelId(init)).isEqualTo(CID);
        assertThat(CtapHidFraming.isInitPacket(init)).isTrue();
        assertThat(CtapHidFraming.initCommand(init)).isEqualTo(CtapHidFraming.CMD_CBOR);
        assertThat(CtapHidFraming.initLength(init)).isEqualTo(3);
        assertThat(init[7]).isEqualTo((byte) 0xAA);
        assertThat(init[8]).isEqualTo((byte) 0xBB);
        assertThat(init[9]).isEqualTo((byte) 0xCC);
    }

    @Test
    void splitsLongPayloadAcrossInitAndContPackets() {
        byte[] payload = new byte[200];
        for (int i = 0; i < payload.length; i++) {
            payload[i] = (byte) i;
        }
        List<byte[]> reports = CtapHidFraming.wrapCommand(CID, CtapHidFraming.CMD_CBOR, payload);

        // 57 in INIT + 59 + 59 + 25 = 200 → 1 INIT + 3 CONT
        assertThat(reports).hasSize(4);
        assertThat(CtapHidFraming.isInitPacket(reports.get(0))).isTrue();
        for (int i = 1; i < reports.size(); i++) {
            assertThat(CtapHidFraming.isInitPacket(reports.get(i))).isFalse();
            assertThat(reports.get(i)[4] & 0x7F).isEqualTo(i - 1); // SEQ 0,1,2
        }
    }

    @Test
    void wrapThenReassembleRoundTrips() {
        byte[] payload = new byte[321];
        for (int i = 0; i < payload.length; i++) {
            payload[i] = (byte) (i * 7 + 1);
        }
        List<byte[]> reports = CtapHidFraming.wrapCommand(CID, CtapHidFraming.CMD_CBOR, payload);

        CtapHidFraming.ResponseAssembler assembler = new CtapHidFraming.ResponseAssembler(CID);
        for (byte[] report : reports) {
            assertThat(assembler.isComplete()).isFalse();
            assembler.add(report);
        }
        assertThat(assembler.isComplete()).isTrue();
        assertThat(assembler.command()).isEqualTo(CtapHidFraming.CMD_CBOR);
        assertThat(assembler.payload()).containsExactly(payload);
    }

    @Test
    void emptyPayloadIsOneInitPacket() {
        List<byte[]> reports = CtapHidFraming.wrapCommand(CID, CtapHidFraming.CMD_INIT, new byte[0]);
        assertThat(reports).hasSize(1);
        assertThat(CtapHidFraming.initLength(reports.get(0))).isZero();

        CtapHidFraming.ResponseAssembler assembler = new CtapHidFraming.ResponseAssembler(CID);
        assembler.add(reports.get(0));
        assertThat(assembler.isComplete()).isTrue();
        assertThat(assembler.payload()).isEmpty();
    }

    @Test
    void rejectsWrongChannel() {
        byte[] init = CtapHidFraming.wrapCommand(CID, CtapHidFraming.CMD_CBOR, new byte[]{1}).get(0);
        CtapHidFraming.ResponseAssembler assembler =
                new CtapHidFraming.ResponseAssembler(0x55667788);
        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                () -> assembler.add(init));
    }

    @Test
    void parsesKeepaliveStatusByte() {
        // A KEEPALIVE packet: INIT-form, command 0x3B, status byte at offset 7.
        byte[] keepalive = CtapHidFraming.wrapCommand(
                CID, CtapHidFraming.CMD_KEEPALIVE, new byte[]{0x02}).get(0);
        assertThat(CtapHidFraming.initCommand(keepalive)).isEqualTo(CtapHidFraming.CMD_KEEPALIVE);
        assertThat(CtapHidFraming.statusByte(keepalive)).isEqualTo(0x02); // UPNEEDED
    }
}
