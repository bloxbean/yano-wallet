package com.bloxbean.cardano.yano.wallet.hardware.fido;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * CTAPHID message framing (FIDO2, ADR-036 Y-M2), independent of any USB library
 * so it is unit-testable. A message (a command + payload) is split into 64-byte
 * HID reports: an INIT packet carrying the 4-byte channel id, the command
 * (high bit set), a 2-byte big-endian total length, then up to 57 payload bytes;
 * followed by CONT packets carrying the channel, a 1-byte sequence, and up to 59
 * payload bytes each.
 *
 * <pre>
 *   INIT : CID CID CID CID  (0x80|CMD)  LEN LEN  &lt;payload ≤57&gt;
 *   CONT : CID CID CID CID   SEQ         &lt;payload ≤59&gt;
 * </pre>
 *
 * Unlike the Ledger APDU transport this rides interrupt reports, and unlike the
 * YubiKey OTP path it is not feature reports — see {@code CtapHidDevice}.
 */
public final class CtapHidFraming {

    public static final int REPORT_SIZE = 64;
    public static final int INIT_HEADER = 7;
    public static final int CONT_HEADER = 5;
    public static final int BROADCAST_CID = 0xFFFFFFFF;
    private static final int INIT_CMD_BIT = 0x80;

    // CTAPHID commands (low nibble form; on the wire the INIT packet sets 0x80).
    public static final int CMD_PING = 0x01;
    public static final int CMD_MSG = 0x03;
    public static final int CMD_INIT = 0x06;
    public static final int CMD_WINK = 0x08;
    public static final int CMD_CBOR = 0x10;
    public static final int CMD_CANCEL = 0x11;
    public static final int CMD_KEEPALIVE = 0x3B;
    public static final int CMD_ERROR = 0x3F;

    private CtapHidFraming() {
    }

    /** Splits a message into 64-byte INIT+CONT reports (zero-padded). */
    public static List<byte[]> wrapCommand(int channelId, int command, byte[] payload, int reportSize) {
        if (payload == null) {
            throw new IllegalArgumentException("payload is required");
        }
        if (reportSize <= INIT_HEADER) {
            throw new IllegalArgumentException("reportSize too small: " + reportSize);
        }
        if (payload.length > 0xFFFF) {
            throw new IllegalArgumentException("payload exceeds CTAPHID max: " + payload.length);
        }
        List<byte[]> reports = new ArrayList<>();
        byte[] init = new byte[reportSize];
        putChannel(init, channelId);
        init[4] = (byte) (INIT_CMD_BIT | (command & 0x7F));
        init[5] = (byte) ((payload.length >> 8) & 0xFF);
        init[6] = (byte) (payload.length & 0xFF);
        int offset = Math.min(payload.length, reportSize - INIT_HEADER);
        System.arraycopy(payload, 0, init, INIT_HEADER, offset);
        reports.add(init);

        int seq = 0;
        while (offset < payload.length) {
            byte[] cont = new byte[reportSize];
            putChannel(cont, channelId);
            cont[4] = (byte) (seq & 0x7F);
            int take = Math.min(payload.length - offset, reportSize - CONT_HEADER);
            System.arraycopy(payload, offset, cont, CONT_HEADER, take);
            offset += take;
            seq++;
            reports.add(cont);
        }
        return reports;
    }

    /** Convenience for the standard 64-byte report size. */
    public static List<byte[]> wrapCommand(int channelId, int command, byte[] payload) {
        return wrapCommand(channelId, command, payload, REPORT_SIZE);
    }

    public static int channelId(byte[] report) {
        return ((report[0] & 0xFF) << 24) | ((report[1] & 0xFF) << 16)
                | ((report[2] & 0xFF) << 8) | (report[3] & 0xFF);
    }

    /** True for an INIT packet (byte 4 has the high bit set); false for CONT. */
    public static boolean isInitPacket(byte[] report) {
        return (report[4] & INIT_CMD_BIT) != 0;
    }

    /** The command of an INIT packet (high bit stripped). */
    public static int initCommand(byte[] report) {
        return report[4] & 0x7F;
    }

    /** The declared total payload length from an INIT packet. */
    public static int initLength(byte[] report) {
        return ((report[5] & 0xFF) << 8) | (report[6] & 0xFF);
    }

    /** The status byte of a KEEPALIVE / ERROR packet (first payload byte). */
    public static int statusByte(byte[] report) {
        return report[INIT_HEADER] & 0xFF;
    }

    private static void putChannel(byte[] report, int channelId) {
        report[0] = (byte) ((channelId >> 24) & 0xFF);
        report[1] = (byte) ((channelId >> 16) & 0xFF);
        report[2] = (byte) ((channelId >> 8) & 0xFF);
        report[3] = (byte) (channelId & 0xFF);
    }

    /**
     * Reassembles one CTAPHID response from its packets. The first packet fed in
     * must be the INIT packet (it carries the command + total length); the rest
     * are CONT packets in sequence order. KEEPALIVE/ERROR packets are separate
     * single-packet messages the caller filters out before assembling.
     */
    public static final class ResponseAssembler {
        private final int channelId;
        private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        private int command = -1;
        private int totalLength = -1;
        private int expectedSeq = 0;

        public ResponseAssembler(int channelId) {
            this.channelId = channelId;
        }

        public void add(byte[] report) {
            if (report == null || report.length < CONT_HEADER) {
                throw new IllegalArgumentException("CTAPHID report too short");
            }
            int ch = channelId(report);
            if (ch != channelId) {
                throw new IllegalStateException(String.format(
                        "Unexpected CTAPHID channel 0x%08x (want 0x%08x)", ch, channelId));
            }
            int pos;
            if (command < 0) {
                if (!isInitPacket(report)) {
                    throw new IllegalStateException("First CTAPHID packet is not an INIT packet");
                }
                if (report.length < INIT_HEADER) {
                    throw new IllegalStateException("INIT packet missing header");
                }
                command = initCommand(report);
                totalLength = initLength(report);
                pos = INIT_HEADER;
            } else {
                if (isInitPacket(report)) {
                    throw new IllegalStateException("Unexpected INIT packet mid-message");
                }
                int seq = report[4] & 0x7F;
                if (seq != expectedSeq) {
                    throw new IllegalStateException("Out-of-order CTAPHID packet: got " + seq
                            + " want " + expectedSeq);
                }
                expectedSeq++;
                pos = CONT_HEADER;
            }
            int take = Math.min(totalLength - buffer.size(), report.length - pos);
            if (take > 0) {
                buffer.write(report, pos, take);
            }
        }

        public int command() {
            return command;
        }

        public boolean isComplete() {
            return totalLength >= 0 && buffer.size() >= totalLength;
        }

        public byte[] payload() {
            if (!isComplete()) {
                throw new IllegalStateException("CTAPHID response not fully assembled");
            }
            return buffer.toByteArray();
        }
    }
}
