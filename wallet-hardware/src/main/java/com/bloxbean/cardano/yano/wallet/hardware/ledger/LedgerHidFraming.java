package com.bloxbean.cardano.yano.wallet.hardware.ledger;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Ledger's APDU-over-HID framing (ADR-034), independent of any USB library so it
 * is unit-testable. An APDU is split into fixed-size HID reports; each report
 * carries a 2-byte channel, a 1-byte tag (0x05 = APDU), and a 2-byte sequence
 * index. The first report additionally carries the 2-byte total APDU length
 * before the payload; continuation reports carry payload only. Reports are
 * zero-padded to the report size.
 *
 * <pre>
 *   report 0 : CH CH 05 00 00  LEN LEN  &lt;payload...&gt;   (header 7 bytes)
 *   report n : CH CH 05 SS SS   &lt;payload...&gt;          (header 5 bytes)
 * </pre>
 *
 * The reassembled response payload is {@code responseData || SW1 || SW2}.
 */
public final class LedgerHidFraming {

    public static final int TAG_APDU = 0x05;
    public static final int REPORT_SIZE = 64;

    private LedgerHidFraming() {
    }

    /**
     * Splits {@code apdu} into channel-framed HID reports, each exactly
     * {@code reportSize} bytes (zero-padded).
     */
    public static List<byte[]> wrapCommand(int channel, byte[] apdu, int reportSize) {
        if (apdu == null) {
            throw new IllegalArgumentException("apdu is required");
        }
        if (reportSize <= 7) {
            throw new IllegalArgumentException("reportSize too small: " + reportSize);
        }
        List<byte[]> reports = new ArrayList<>();
        int offset = 0;
        int seq = 0;
        while (offset < apdu.length || seq == 0) {
            byte[] report = new byte[reportSize];
            report[0] = (byte) ((channel >> 8) & 0xFF);
            report[1] = (byte) (channel & 0xFF);
            report[2] = (byte) TAG_APDU;
            report[3] = (byte) ((seq >> 8) & 0xFF);
            report[4] = (byte) (seq & 0xFF);
            int pos = 5;
            if (seq == 0) {
                report[5] = (byte) ((apdu.length >> 8) & 0xFF);
                report[6] = (byte) (apdu.length & 0xFF);
                pos = 7;
            }
            int take = Math.min(apdu.length - offset, reportSize - pos);
            if (take > 0) {
                System.arraycopy(apdu, offset, report, pos, take);
                offset += take;
            }
            reports.add(report);
            seq++;
        }
        return reports;
    }

    /** Convenience for the standard 64-byte HID report size. */
    public static List<byte[]> wrapCommand(int channel, byte[] apdu) {
        return wrapCommand(channel, apdu, REPORT_SIZE);
    }

    /**
     * Stateful reassembler for response reports arriving one at a time from the
     * device. Feed reports with {@link #add}; once {@link #isComplete()} is true,
     * {@link #payload()} returns {@code responseData || SW1 || SW2}.
     */
    public static final class ResponseAssembler {
        private final int channel;
        private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        private int expectedSeq = 0;
        private int totalLength = -1;

        public ResponseAssembler(int channel) {
            this.channel = channel;
        }

        public void add(byte[] report) {
            if (report == null || report.length < 5) {
                throw new IllegalArgumentException("HID report too short");
            }
            int ch = ((report[0] & 0xFF) << 8) | (report[1] & 0xFF);
            if (ch != (channel & 0xFFFF)) {
                throw new IllegalStateException(String.format(
                        "Unexpected HID channel 0x%04x (want 0x%04x)", ch, channel & 0xFFFF));
            }
            if ((report[2] & 0xFF) != TAG_APDU) {
                throw new IllegalStateException("Unexpected HID tag 0x" + Integer.toHexString(report[2] & 0xFF));
            }
            int seq = ((report[3] & 0xFF) << 8) | (report[4] & 0xFF);
            if (seq != expectedSeq) {
                throw new IllegalStateException("Out-of-order HID report: got " + seq + " want " + expectedSeq);
            }
            int pos;
            if (seq == 0) {
                if (report.length < 7) {
                    throw new IllegalStateException("First HID report missing length header");
                }
                totalLength = ((report[5] & 0xFF) << 8) | (report[6] & 0xFF);
                pos = 7;
            } else {
                pos = 5;
            }
            int remaining = totalLength - buffer.size();
            int take = Math.min(remaining, report.length - pos);
            if (take > 0) {
                buffer.write(report, pos, take);
            }
            expectedSeq++;
        }

        public boolean isComplete() {
            return totalLength >= 0 && buffer.size() >= totalLength;
        }

        public byte[] payload() {
            if (!isComplete()) {
                throw new IllegalStateException("Response not fully assembled");
            }
            return buffer.toByteArray();
        }
    }
}
