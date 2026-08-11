package com.bloxbean.cardano.yano.wallet.hardware.yubikey;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The YubiKey OTP-slot HMAC-SHA1 challenge-response wire protocol (ADR-036 Y-M1),
 * as pure functions — no device I/O — so the framing and CRC are unit-testable
 * without hardware. {@link YubiKeyChallengeResponse} drives these over USB-HID.
 *
 * <p>Model: a 70-byte frame = 64-byte padded challenge + 1 slot-command byte +
 * 2 CRC bytes (little-endian) + 3 zero filler. It is split into 8-byte HID
 * feature reports (7 data bytes + a sequence/flag byte); all-zero middle reports
 * are skipped. The device answers with 20 HMAC-SHA1 bytes + a 2-byte CRC, read
 * back over successive feature reports and validated against the CRC residual.
 *
 * <p>Constants and algorithm from Yubico's {@code yubikey-personalization}
 * (ykdef.h, ykcore.c), {@code yubico-c} (ykcrc.c) and {@code python-yubico}.
 */
final class YubiKeyProtocol {

    private YubiKeyProtocol() {
    }

    // USB identity of the YubiKey OTP config (keyboard-usage) HID collection.
    static final int VENDOR_ID = 0x1050;
    static final int USAGE_PAGE = 0x0001; // Generic Desktop
    static final int USAGE = 0x0006;      // Keyboard

    // Slot commands: HMAC-SHA1 challenge-response for slot 1 / slot 2.
    static final int SLOT_CHAL_HMAC1 = 0x30;
    static final int SLOT_CHAL_HMAC2 = 0x38;

    // Status/flag bits carried in the last byte of every 8-byte report.
    static final int SLOT_WRITE_FLAG = 0x80;
    static final int RESP_PENDING_FLAG = 0x40;
    static final int RESP_TIMEOUT_WAIT_FLAG = 0x20;
    static final int SEQ_MASK = 0x1f;
    static final int DUMMY_REPORT_WRITE = 0x8f; // reset/abort the slot state

    static final int PAYLOAD_SIZE = 64;         // padded challenge
    static final int FRAME_SIZE = 70;           // payload + slot + crc + filler
    static final int REPORT_DATA_SIZE = 7;      // data bytes per 8-byte feature report
    static final int FEATURE_REPORT_SIZE = 8;
    static final int DIGEST_SIZE = 20;          // HMAC-SHA1 output
    static final int CRC_OK_RESIDUAL = 0xF0B8;  // crc16(data ‖ its-CRC) for a valid frame

    /**
     * Expands a challenge to the fixed 64-byte payload. Variable-input slots (the
     * ykman default) strip trailing {@code 0x00}, so when the challenge itself
     * ends in {@code 0x00} we pad with {@code 0xFF} instead — no real byte is
     * lost, the result stays deterministic, and it matches {@code ykman otp
     * calculate}. For a random 32-byte challenge the flip essentially never fires.
     */
    static byte[] padChallengeTo64(byte[] challenge) {
        if (challenge.length == 0 || challenge.length > PAYLOAD_SIZE) {
            throw new IllegalArgumentException("challenge must be 1.." + PAYLOAD_SIZE + " bytes");
        }
        byte[] payload = new byte[PAYLOAD_SIZE];
        System.arraycopy(challenge, 0, payload, 0, challenge.length);
        if (challenge.length < PAYLOAD_SIZE) {
            byte pad = challenge[challenge.length - 1] == 0 ? (byte) 0xFF : 0x00;
            Arrays.fill(payload, challenge.length, PAYLOAD_SIZE, pad);
        }
        return payload;
    }

    /** Builds the 70-byte challenge frame for the given slot command. */
    static byte[] buildFrame(byte[] paddedChallenge, int slotCommand) {
        if (paddedChallenge.length != PAYLOAD_SIZE) {
            throw new IllegalArgumentException("payload must be " + PAYLOAD_SIZE + " bytes");
        }
        byte[] frame = new byte[FRAME_SIZE];
        System.arraycopy(paddedChallenge, 0, frame, 0, PAYLOAD_SIZE);
        frame[64] = (byte) slotCommand;
        int crc = crc16(paddedChallenge, PAYLOAD_SIZE);
        frame[65] = (byte) (crc & 0xFF);        // little-endian on the wire
        frame[66] = (byte) ((crc >>> 8) & 0xFF);
        // frame[67..69] stay zero (filler)
        return frame;
    }

    /**
     * Splits a frame into the 8-byte feature reports to WRITE: 7 data bytes plus
     * a {@code SLOT_WRITE_FLAG | seq} byte. All-zero middle reports (seq 1..8) are
     * skipped — but seq 0 and the final seq 9 are always sent.
     */
    static List<byte[]> writeReports(byte[] frame) {
        List<byte[]> reports = new ArrayList<>();
        int seqCount = FRAME_SIZE / REPORT_DATA_SIZE; // 10
        for (int seq = 0; seq < seqCount; seq++) {
            byte[] report = new byte[FEATURE_REPORT_SIZE];
            boolean allZero = true;
            for (int i = 0; i < REPORT_DATA_SIZE; i++) {
                report[i] = frame[seq * REPORT_DATA_SIZE + i];
                if (report[i] != 0) {
                    allZero = false;
                }
            }
            if (allZero && seq > 0 && seq < seqCount - 1) {
                continue; // skip empty middle report
            }
            report[REPORT_DATA_SIZE] = (byte) (SLOT_WRITE_FLAG | seq);
            reports.add(report);
        }
        return reports;
    }

    static boolean responsePending(byte status) {
        return (status & RESP_PENDING_FLAG) != 0;
    }

    static boolean awaitingTouch(byte status) {
        return (status & RESP_TIMEOUT_WAIT_FLAG) != 0;
    }

    static int sequence(byte status) {
        return status & SEQ_MASK;
    }

    static boolean writeFlagClear(byte status) {
        return (status & SLOT_WRITE_FLAG) == 0;
    }

    /**
     * Validates the collected response ({@code >= 22} bytes: 20 HMAC + 2 CRC)
     * against the CRC residual and returns the 20-byte digest.
     */
    static byte[] extractDigest(byte[] collected) {
        if (collected.length < DIGEST_SIZE + 2) {
            throw new IllegalStateException("YubiKey response too short (" + collected.length + " bytes)");
        }
        if (crc16(collected, DIGEST_SIZE + 2) != CRC_OK_RESIDUAL) {
            throw new IllegalStateException("YubiKey response failed its CRC check");
        }
        return Arrays.copyOf(collected, DIGEST_SIZE);
    }

    /** A reset ("dummy write") report that aborts any pending slot operation. */
    static byte[] resetReport() {
        byte[] report = new byte[FEATURE_REPORT_SIZE];
        report[REPORT_DATA_SIZE] = (byte) DUMMY_REPORT_WRITE;
        return report;
    }

    /** CRC-16 (ISO-13239 / CCITT reflected, init 0xFFFF, poly 0x8408, no final xor). */
    static int crc16(byte[] buf, int len) {
        int crc = 0xFFFF;
        for (int i = 0; i < len; i++) {
            crc ^= (buf[i] & 0xFF);
            for (int b = 0; b < 8; b++) {
                int lsb = crc & 1;
                crc >>>= 1;
                if (lsb != 0) {
                    crc ^= 0x8408;
                }
            }
        }
        return crc & 0xFFFF;
    }
}
