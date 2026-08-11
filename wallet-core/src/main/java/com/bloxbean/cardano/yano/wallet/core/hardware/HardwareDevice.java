package com.bloxbean.cardano.yano.wallet.core.hardware;

/**
 * A discovered hardware device (ADR-034), described only by identity — no
 * transport handle or vendor-library type crosses this SPI boundary, so
 * {@code wallet-core} stays free of {@code hid4java}/JNA. The {@code path} is
 * the OS HID path the transport re-resolves to open the device.
 *
 * @param type         device family
 * @param path         OS-specific HID device path (opaque; used to reopen)
 * @param product      USB product string (e.g. "Nano S Plus"), may be null
 * @param manufacturer USB manufacturer string (e.g. "Ledger"), may be null
 * @param serialNumber USB serial, may be null (Ledger typically omits it)
 */
public record HardwareDevice(DeviceType type, String path, String product,
                             String manufacturer, String serialNumber) {

    public HardwareDevice {
        if (type == null) {
            throw new IllegalArgumentException("device type is required");
        }
        if (path == null || path.isBlank()) {
            throw new IllegalArgumentException("device path is required");
        }
    }

    /** A short human label for the UI, e.g. "Ledger Nano S Plus". */
    public String displayName() {
        String mfr = manufacturer != null && !manufacturer.isBlank() ? manufacturer : type.name();
        String prod = product != null && !product.isBlank() ? product : "device";
        return (mfr + " " + prod).trim();
    }
}
