package com.bloxbean.cardano.yano.wallet.hardware.fido;

import com.bloxbean.cardano.yano.wallet.core.hardware.HardwareWalletException;
import org.hid4java.HidDevice;
import org.hid4java.HidManager;
import org.hid4java.HidServices;

import java.security.SecureRandom;
import java.util.Arrays;

/**
 * CTAPHID transport to a FIDO2 authenticator over USB-HID (ADR-036 Y-M2). Opens
 * the FIDO interface (usage page 0xF1D0), performs the INIT nonce→channel
 * handshake, and exchanges CTAP2 CBOR messages — handling KEEPALIVE frames while
 * the key waits for a touch. Uses interrupt reports (write/read), unlike the
 * YubiKey OTP path's feature reports. Not thread-safe; one exchange at a time.
 */
public final class CtapHidDevice implements AutoCloseable {

    private static final int FIDO_USAGE_PAGE = 0xF1D0;
    private static final int FIDO_USAGE = 0x01;
    private static final byte REPORT_ID = 0x00;
    private static final int READ_TIMEOUT_MS = 1_000;
    // A make/get with user verification waits for a touch (and maybe a PIN gesture).
    private static final long EXCHANGE_TIMEOUT_MS = 60_000;

    private final HidDevice device;
    private final int channelId;
    private volatile Runnable onTouchNeeded;
    private volatile boolean touchAnnounced;

    private CtapHidDevice(HidDevice device, int channelId) {
        this.device = device;
        this.channelId = channelId;
    }

    /** Opens the first attached FIDO2 authenticator and allocates a channel. */
    public static CtapHidDevice open() {
        HidDevice hid = findFidoInterface();
        if (!hid.open() || !hid.isOpen()) {
            throw new HardwareWalletException("Unable to open the security key: " + hid.getLastErrorMessage());
        }
        try {
            int cid = initChannel(hid);
            return new CtapHidDevice(hid, cid);
        } catch (RuntimeException e) {
            hid.close();
            throw e;
        }
    }

    /** Called (once) when the key signals it is waiting for the user's touch. */
    public void onTouchNeeded(Runnable callback) {
        this.onTouchNeeded = callback;
    }

    /**
     * Sends a CTAP2 command (command byte + canonical-CBOR params) and returns
     * the response CBOR map bytes. Throws {@link Ctap2Exception} on a non-zero
     * CTAP status.
     */
    public byte[] ctap2(int ctapCommand, byte[] cborParams) {
        byte[] payload = new byte[1 + (cborParams == null ? 0 : cborParams.length)];
        payload[0] = (byte) ctapCommand;
        if (cborParams != null) {
            System.arraycopy(cborParams, 0, payload, 1, cborParams.length);
        }
        touchAnnounced = false;
        byte[] response = transceive(CtapHidFraming.CMD_CBOR, payload);
        if (response.length == 0) {
            throw new HardwareWalletException("Empty CTAP2 response");
        }
        int status = response[0] & 0xFF;
        if (status != 0x00) {
            throw new Ctap2Exception(status);
        }
        return Arrays.copyOfRange(response, 1, response.length);
    }

    private byte[] transceive(int command, byte[] payload) {
        for (byte[] report : CtapHidFraming.wrapCommand(channelId, command, payload)) {
            int written = device.write(report, CtapHidFraming.REPORT_SIZE, REPORT_ID);
            if (written < 0) {
                throw new HardwareWalletException("CTAPHID write failed: " + device.getLastErrorMessage());
            }
        }
        return receive(channelId);
    }

    private byte[] receive(int expectedChannel) {
        long deadline = System.nanoTime() + EXCHANGE_TIMEOUT_MS * 1_000_000L;
        CtapHidFraming.ResponseAssembler assembler = null;
        while (System.nanoTime() < deadline) {
            byte[] packet = new byte[CtapHidFraming.REPORT_SIZE];
            int read = device.read(packet, READ_TIMEOUT_MS);
            if (read < 0) {
                throw new HardwareWalletException("CTAPHID read failed: " + device.getLastErrorMessage());
            }
            if (read == 0) {
                continue; // no frame yet — the key may be waiting for a touch
            }
            if (CtapHidFraming.channelId(packet) != expectedChannel) {
                continue; // a frame for another channel
            }
            if (assembler == null) {
                if (!CtapHidFraming.isInitPacket(packet)) {
                    continue; // stray continuation
                }
                int cmd = CtapHidFraming.initCommand(packet);
                if (cmd == CtapHidFraming.CMD_KEEPALIVE) {
                    if (CtapHidFraming.statusByte(packet) == 0x02) {
                        announceTouch();
                    }
                    continue;
                }
                if (cmd == CtapHidFraming.CMD_ERROR) {
                    throw new HardwareWalletException("CTAPHID error 0x"
                            + Integer.toHexString(CtapHidFraming.statusByte(packet)));
                }
                assembler = new CtapHidFraming.ResponseAssembler(expectedChannel);
            }
            assembler.add(packet);
            if (assembler.isComplete()) {
                return assembler.payload();
            }
        }
        throw new HardwareWalletException("Timed out waiting for the security key");
    }

    private void announceTouch() {
        if (!touchAnnounced && onTouchNeeded != null) {
            touchAnnounced = true;
            onTouchNeeded.run();
        }
    }

    private static int initChannel(HidDevice device) {
        byte[] nonce = new byte[8];
        RandomHolder.INSTANCE.nextBytes(nonce);
        for (byte[] report : CtapHidFraming.wrapCommand(CtapHidFraming.BROADCAST_CID, CtapHidFraming.CMD_INIT, nonce)) {
            if (device.write(report, CtapHidFraming.REPORT_SIZE, REPORT_ID) < 0) {
                throw new HardwareWalletException("CTAPHID INIT write failed: " + device.getLastErrorMessage());
            }
        }
        // The INIT reply comes back on the broadcast channel: echoed nonce (8) +
        // new 4-byte channel id + version/caps. Re-read if the nonce doesn't match
        // (a reply to someone else's concurrent INIT).
        long deadline = System.nanoTime() + 3_000_000_000L;
        while (System.nanoTime() < deadline) {
            byte[] reply = receiveBroadcastInit(device);
            if (reply.length >= 17 && Arrays.equals(Arrays.copyOfRange(reply, 0, 8), nonce)) {
                return ((reply[8] & 0xFF) << 24) | ((reply[9] & 0xFF) << 16)
                        | ((reply[10] & 0xFF) << 8) | (reply[11] & 0xFF);
            }
        }
        throw new HardwareWalletException("Security key did not answer the CTAPHID INIT handshake");
    }

    private static byte[] receiveBroadcastInit(HidDevice device) {
        CtapHidFraming.ResponseAssembler assembler =
                new CtapHidFraming.ResponseAssembler(CtapHidFraming.BROADCAST_CID);
        long deadline = System.nanoTime() + 2_000_000_000L;
        while (System.nanoTime() < deadline) {
            byte[] packet = new byte[CtapHidFraming.REPORT_SIZE];
            int read = device.read(packet, READ_TIMEOUT_MS);
            if (read <= 0) {
                continue;
            }
            if (CtapHidFraming.channelId(packet) != CtapHidFraming.BROADCAST_CID) {
                continue;
            }
            if (assembler.command() < 0 && (!CtapHidFraming.isInitPacket(packet)
                    || CtapHidFraming.initCommand(packet) != CtapHidFraming.CMD_INIT)) {
                continue;
            }
            assembler.add(packet);
            if (assembler.isComplete()) {
                return assembler.payload();
            }
        }
        throw new HardwareWalletException("Timed out on the CTAPHID INIT handshake");
    }

    private static HidDevice findFidoInterface() {
        for (HidDevice hid : hidServices().getAttachedHidDevices()) {
            if ((hid.getUsagePage() & 0xFFFF) == FIDO_USAGE_PAGE && (hid.getUsage() & 0xFFFF) == FIDO_USAGE) {
                return hid;
            }
        }
        throw new HardwareWalletException("No FIDO2 security key found. Plug one in and try again.");
    }

    private static HidServices hidServices() {
        try {
            return HidManager.getHidServices();
        } catch (RuntimeException e) {
            throw new HardwareWalletException(
                    "USB HID services are unavailable on this system: " + e.getMessage(), e);
        }
    }

    @Override
    public void close() {
        if (device.isOpen()) {
            device.close();
        }
    }

    private static final class RandomHolder {
        private static final SecureRandom INSTANCE = new SecureRandom();
    }
}
