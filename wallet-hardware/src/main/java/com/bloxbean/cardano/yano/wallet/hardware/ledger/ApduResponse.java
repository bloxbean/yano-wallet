package com.bloxbean.cardano.yano.wallet.hardware.ledger;

import java.util.Arrays;

/**
 * An APDU response (ADR-034): the response data followed by a two-byte status
 * word. {@code 0x9000} means success; anything else is a device/protocol error
 * (e.g. {@code 0x6E00} wrong app, {@code 0x5515} device locked).
 *
 * @param data       response payload (never null; may be empty)
 * @param statusWord the 16-bit SW1||SW2 status word
 */
public record ApduResponse(byte[] data, int statusWord) {

    public ApduResponse {
        if (data == null) {
            data = new byte[0];
        }
    }

    public boolean isOk() {
        return statusWord == 0x9000;
    }

    /** Renders the status word as 4 hex digits, e.g. "6e00". */
    public String statusWordHex() {
        return String.format("%04x", statusWord & 0xFFFF);
    }

    /**
     * Splits a reassembled transport payload ({@code data || SW1 || SW2}) into an
     * {@link ApduResponse}.
     *
     * @throws IllegalArgumentException if fewer than the two status bytes present
     */
    public static ApduResponse fromPayload(byte[] payload) {
        if (payload == null || payload.length < 2) {
            throw new IllegalArgumentException("APDU response payload too short: "
                    + (payload == null ? "null" : payload.length));
        }
        int sw = ((payload[payload.length - 2] & 0xFF) << 8) | (payload[payload.length - 1] & 0xFF);
        byte[] body = Arrays.copyOf(payload, payload.length - 2);
        return new ApduResponse(body, sw);
    }
}
