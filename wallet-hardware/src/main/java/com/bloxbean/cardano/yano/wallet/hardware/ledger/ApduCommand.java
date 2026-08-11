package com.bloxbean.cardano.yano.wallet.hardware.ledger;

import java.io.ByteArrayOutputStream;

/**
 * A short-form ISO-7816 APDU command (ADR-034): {@code CLA INS P1 P2 Lc data}.
 * The Cardano Ledger app keeps every command's data payload within one byte of
 * length (≤255) and chunks larger operations at the application layer, so a
 * single-byte {@code Lc} is always sufficient.
 *
 * @param cla  class byte (0xD7 for the Cardano app)
 * @param ins  instruction byte
 * @param p1   parameter 1
 * @param p2   parameter 2
 * @param data payload (never null; empty for parameterless commands)
 */
public record ApduCommand(int cla, int ins, int p1, int p2, byte[] data) {

    public ApduCommand {
        if (data == null) {
            data = new byte[0];
        }
        if (data.length > 255) {
            throw new IllegalArgumentException("APDU data exceeds 255 bytes: " + data.length);
        }
    }

    /** Serializes to {@code CLA INS P1 P2 Lc data...} (Lc omitted-as-zero when empty). */
    public byte[] serialize() {
        ByteArrayOutputStream out = new ByteArrayOutputStream(5 + data.length);
        out.write(cla & 0xFF);
        out.write(ins & 0xFF);
        out.write(p1 & 0xFF);
        out.write(p2 & 0xFF);
        out.write(data.length & 0xFF);
        out.write(data, 0, data.length);
        return out.toByteArray();
    }
}
