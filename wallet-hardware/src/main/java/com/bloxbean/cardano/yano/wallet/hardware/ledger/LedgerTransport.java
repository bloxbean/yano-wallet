package com.bloxbean.cardano.yano.wallet.hardware.ledger;

import com.bloxbean.cardano.yano.wallet.core.hardware.HardwareWalletException;
import org.hid4java.HidDevice;

/**
 * APDU exchange over an opened Ledger HID device (ADR-034): frames a command
 * with {@link LedgerHidFraming}, writes the 64-byte reports, then reads and
 * reassembles the response. Owns the {@link HidDevice} lifetime — {@link #close}
 * closes it. Not thread-safe; drive from a single device thread.
 */
public final class LedgerTransport implements AutoCloseable {

    /** Any fixed value works as long as command and response channels match. */
    static final int DEFAULT_CHANNEL = 0x0101;
    private static final byte HID_REPORT_ID = 0x00;
    private static final int READ_TIMEOUT_MS = 60_000;

    private final HidDevice device;
    private final int channel;

    public LedgerTransport(HidDevice device) {
        this(device, DEFAULT_CHANNEL);
    }

    public LedgerTransport(HidDevice device, int channel) {
        if (device == null) {
            throw new IllegalArgumentException("device is required");
        }
        this.device = device;
        this.channel = channel;
    }

    /** Sends one APDU and returns the device's response. */
    public ApduResponse exchange(ApduCommand command) {
        byte[] apdu = command.serialize();
        for (byte[] report : LedgerHidFraming.wrapCommand(channel, apdu)) {
            int written = device.write(report, report.length, HID_REPORT_ID);
            if (written < 0) {
                throw new HardwareWalletException("Failed to write to device: " + device.getLastErrorMessage());
            }
        }

        LedgerHidFraming.ResponseAssembler assembler = new LedgerHidFraming.ResponseAssembler(channel);
        while (!assembler.isComplete()) {
            byte[] report = new byte[LedgerHidFraming.REPORT_SIZE];
            int read = device.read(report, READ_TIMEOUT_MS);
            if (read < 0) {
                throw new HardwareWalletException("Failed to read from device: " + device.getLastErrorMessage());
            }
            if (read == 0) {
                throw new HardwareWalletException("Timed out waiting for the device");
            }
            assembler.add(report);
        }
        return ApduResponse.fromPayload(assembler.payload());
    }

    @Override
    public void close() {
        if (device.isOpen()) {
            device.close();
        }
    }
}
