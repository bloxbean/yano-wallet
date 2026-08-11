package com.bloxbean.cardano.yano.wallet.hardware.yubikey;

import com.bloxbean.cardano.yano.wallet.core.hardware.HardwareWalletException;
import com.bloxbean.cardano.yano.wallet.core.vault.VaultSecondFactor;
import org.hid4java.HidDevice;
import org.hid4java.HidManager;
import org.hid4java.HidServices;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.util.Locale;

import static com.bloxbean.cardano.yano.wallet.hardware.yubikey.YubiKeyProtocol.FEATURE_REPORT_SIZE;
import static com.bloxbean.cardano.yano.wallet.hardware.yubikey.YubiKeyProtocol.REPORT_DATA_SIZE;
import static com.bloxbean.cardano.yano.wallet.hardware.yubikey.YubiKeyProtocol.SLOT_CHAL_HMAC1;
import static com.bloxbean.cardano.yano.wallet.hardware.yubikey.YubiKeyProtocol.SLOT_CHAL_HMAC2;
import static com.bloxbean.cardano.yano.wallet.hardware.yubikey.YubiKeyProtocol.USAGE;
import static com.bloxbean.cardano.yano.wallet.hardware.yubikey.YubiKeyProtocol.USAGE_PAGE;
import static com.bloxbean.cardano.yano.wallet.hardware.yubikey.YubiKeyProtocol.VENDOR_ID;

/**
 * A {@link VaultSecondFactor} backed by a YubiKey OTP slot in HMAC-SHA1
 * challenge-response mode (ADR-036 Y-M1). {@link #respond} opens the key, sends
 * the vault's challenge to the configured slot, waits (through a touch if the
 * slot requires one), and returns the 20-byte HMAC-SHA1 — which the vault mixes
 * into its Argon2id key. Holds no keys and never sees the seed or passphrase.
 *
 * <p>Lives in {@code wallet-hardware} (UI JVM only, per ADR-034). Not
 * thread-safe: one challenge-response per invocation, device opened and closed
 * each time.
 */
public final class YubiKeyChallengeResponse implements VaultSecondFactor {

    private static final Logger log = LoggerFactory.getLogger(YubiKeyChallengeResponse.class);

    private static final byte REPORT_ID = 0x00;
    private static final int DEFAULT_SLOT = 2;
    // A non-touch slot answers in a few ms; a touch slot blinks and waits for a
    // tap. Give the human time, but bound it so a forgotten key doesn't hang.
    private static final long TOUCH_TIMEOUT_MS = 15_000;
    private static final long WRITE_READY_TIMEOUT_MS = 2_000;

    @Override
    public String type() {
        return FactorDescriptor.YUBIKEY_HMAC_SHA1;
    }

    @Override
    public byte[] respond(FactorDescriptor descriptor, byte[] challenge) {
        int slotCommand = slotCommand(descriptor);
        HidDevice device = openYubiKey();
        try {
            reset(device); // clean any half-finished prior operation
            writeChallenge(device, slotCommand, challenge);
            return readDigest(device);
        } finally {
            reset(device); // clear the device's response buffer
            device.close();
        }
    }

    private static int slotCommand(FactorDescriptor descriptor) {
        int slot = descriptor == null || descriptor.slot() == null ? DEFAULT_SLOT : descriptor.slot();
        return switch (slot) {
            case 1 -> SLOT_CHAL_HMAC1;
            case 2 -> SLOT_CHAL_HMAC2;
            default -> throw new HardwareWalletException("YubiKey slot must be 1 or 2, was " + slot);
        };
    }

    private static volatile boolean darwinOpenConfigured;
    private static volatile String darwinReport = "not attempted";

    /**
     * The YubiKey OTP interface is a keyboard-type HID that macOS holds, so an
     * <em>exclusive</em> open (hidapi's default) fails with "Device not
     * initialised". hidapi can open it non-exclusively for feature reports, but
     * hid4java 0.8.0 doesn't expose the toggle — so call the bundled hidapi
     * symbol directly (its INSTANCE is the same native library hid4java uses).
     * Non-mac platforms and any linkage error fall through harmlessly.
     */
    private static void configureMacOsNonExclusiveOpen() {
        if (darwinOpenConfigured) {
            return;
        }
        if (System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("mac")) {
            try {
                org.hid4java.jna.DarwinHidApiLibrary.INSTANCE.hid_darwin_set_open_exclusive(0);
                darwinReport = "set exclusive=0 OK";
            } catch (Throwable t) {
                darwinReport = "FAILED: " + t;
                log.debug("Could not set hidapi non-exclusive open on macOS: {}", t.getMessage());
            }
        } else {
            darwinReport = "skipped (not macOS)";
        }
        darwinOpenConfigured = true;
    }

    /**
     * A human-readable inventory of every YubiKey HID collection and whether it
     * opens — for diagnosing device/permission issues without guessing.
     */
    public static String diagnostics() {
        configureMacOsNonExclusiveOpen();
        StringBuilder out = new StringBuilder("non-exclusive open: ").append(darwinReport).append('\n');
        int found = 0;
        for (HidDevice hid : hidServices().getAttachedHidDevices()) {
            if ((hid.getVendorId() & 0xFFFF) != VENDOR_ID) {
                continue;
            }
            found++;
            out.append(String.format("  pid=%04x usagePage=%04x usage=%04x iface=%d product=%s%n",
                    hid.getProductId() & 0xFFFF, hid.getUsagePage() & 0xFFFF, hid.getUsage() & 0xFFFF,
                    hid.getInterfaceNumber(), hid.getProduct()));
            out.append("      path=").append(hid.getPath()).append('\n');
            boolean opened = false;
            try {
                opened = hid.open() && hid.isOpen();
            } catch (Throwable t) {
                out.append("      open() threw: ").append(t).append('\n');
            }
            out.append("      open=").append(opened);
            if (!opened) {
                out.append("  err=").append(hid.getLastErrorMessage());
            } else {
                hid.close();
            }
            out.append('\n');
        }
        if (found == 0) {
            out.append("  (no vendor 0x1050 HID collections enumerated)\n");
        }
        return out.toString();
    }

    private HidDevice openYubiKey() {
        configureMacOsNonExclusiveOpen();
        for (HidDevice hid : hidServices().getAttachedHidDevices()) {
            // Match the OTP config collection by usage, not product id: a YubiKey 5
            // presents several HID collections (OTP, FIDO) and PIDs vary by model.
            // Mask because some platforms sign-extend the 16-bit usage values.
            if ((hid.getVendorId() & 0xFFFF) == VENDOR_ID
                    && (hid.getUsagePage() & 0xFFFF) == USAGE_PAGE
                    && (hid.getUsage() & 0xFFFF) == USAGE) {
                if (!hid.open() || !hid.isOpen()) {
                    throw new HardwareWalletException("Unable to open the YubiKey: " + hid.getLastErrorMessage()
                            + " (on macOS, grant Input Monitoring to this app).");
                }
                return hid;
            }
        }
        throw new HardwareWalletException("No YubiKey found. Plug it in and configure a slot for HMAC-SHA1 "
                + "challenge-response (ykman otp chalresp --generate 2).");
    }

    private void writeChallenge(HidDevice device, int slotCommand, byte[] challenge) {
        byte[] frame = YubiKeyProtocol.buildFrame(YubiKeyProtocol.padChallengeTo64(challenge), slotCommand);
        for (byte[] report : YubiKeyProtocol.writeReports(frame)) {
            waitForWriteReady(device);
            int written = device.sendFeatureReport(report, REPORT_ID);
            if (written < 0) {
                throw new HardwareWalletException("YubiKey write failed: " + device.getLastErrorMessage()
                        + " (on macOS, grant Input Monitoring to this app).");
            }
        }
    }

    /** Blocks until the device is ready to accept the next frame report. */
    private void waitForWriteReady(HidDevice device) {
        long deadline = System.nanoTime() + WRITE_READY_TIMEOUT_MS * 1_000_000L;
        long backoffMs = 1;
        while (true) {
            byte status = readStatus(device)[REPORT_DATA_SIZE];
            if (YubiKeyProtocol.writeFlagClear(status)) {
                return;
            }
            if (System.nanoTime() > deadline) {
                throw new HardwareWalletException("YubiKey did not become ready to accept the challenge");
            }
            backoffMs = sleep(backoffMs);
        }
    }

    private byte[] readDigest(HidDevice device) {
        ByteArrayOutputStream collected = new ByteArrayOutputStream();
        long touchDeadline = System.nanoTime() + TOUCH_TIMEOUT_MS * 1_000_000L;
        long backoffMs = 1;

        // Phase 1: wait for the response to become pending — this is where a
        // touch-required slot blinks and waits for the user's tap.
        byte[] data = readStatus(device);
        while (!YubiKeyProtocol.responsePending(data[REPORT_DATA_SIZE])) {
            if (System.nanoTime() > touchDeadline) {
                reset(device);
                throw new HardwareWalletException(YubiKeyProtocol.awaitingTouch(data[REPORT_DATA_SIZE])
                        ? "Timed out waiting for you to touch the YubiKey"
                        : "Timed out waiting for the YubiKey response");
            }
            backoffMs = sleep(backoffMs);
            data = readStatus(device);
        }
        collected.write(data, 0, REPORT_DATA_SIZE); // first 7 response bytes (seq 0)

        // Phase 2: drain 7-byte chunks until the sequence wraps back to 0 (or the
        // device stops reporting pending) — the response is then complete.
        while (collected.size() < FEATURE_REPORT_SIZE * REPORT_DATA_SIZE) { // safety bound
            data = readStatus(device);
            byte status = data[REPORT_DATA_SIZE];
            if (!YubiKeyProtocol.responsePending(status)) {
                break;
            }
            if (collected.size() > 0 && YubiKeyProtocol.sequence(status) == 0) {
                break; // sequence wrapped → done
            }
            collected.write(data, 0, REPORT_DATA_SIZE);
        }
        return YubiKeyProtocol.extractDigest(collected.toByteArray());
    }

    private byte[] readStatus(HidDevice device) {
        byte[] data = new byte[FEATURE_REPORT_SIZE];
        int read = device.getFeatureReport(data, REPORT_ID);
        if (read < 0) {
            throw new HardwareWalletException("YubiKey read failed: " + device.getLastErrorMessage()
                    + " (on macOS, grant Input Monitoring to this app).");
        }
        return data;
    }

    private void reset(HidDevice device) {
        try {
            device.sendFeatureReport(YubiKeyProtocol.resetReport(), REPORT_ID); // best-effort
        } catch (RuntimeException e) {
            log.debug("YubiKey reset report failed (ignored): {}", e.getMessage());
        }
    }

    /** Sleeps {@code backoffMs}, returning the next (doubled, capped) backoff. */
    private static long sleep(long backoffMs) {
        try {
            Thread.sleep(backoffMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new HardwareWalletException("Interrupted while talking to the YubiKey");
        }
        return Math.min(backoffMs * 2, 500);
    }

    private static HidServices hidServices() {
        try {
            return HidManager.getHidServices();
        } catch (RuntimeException e) {
            throw new HardwareWalletException(
                    "USB HID services are unavailable on this system: " + e.getMessage(), e);
        }
    }
}
