package com.bloxbean.cardano.yano.wallet.app.probe;

import com.bloxbean.cardano.yano.wallet.core.config.WalletNetwork;
import com.bloxbean.cardano.yano.wallet.core.hardware.DeviceAddressService;
import com.bloxbean.cardano.yano.wallet.core.hardware.DeviceKeystore;
import com.bloxbean.cardano.yano.wallet.core.hardware.DeviceVersion;
import com.bloxbean.cardano.yano.wallet.core.hardware.HardwareDevice;
import com.bloxbean.cardano.yano.wallet.core.hardware.HardwareWalletException;
import com.bloxbean.cardano.yano.wallet.core.hardware.HardwareWalletService;
import com.bloxbean.cardano.yano.wallet.hardware.ledger.LedgerHardwareWalletService;

import java.util.List;

/**
 * HW-M1 verification harness (ADR-034): enumerate connected Ledger devices and
 * read the Cardano app version. Run with a Ledger connected + unlocked and the
 * Cardano app open:
 *
 * <pre>./gradlew :wallet-app:ledgerProbe</pre>
 *
 * This is the one HW-M1 step that needs real hardware (or the Speculos
 * emulator); the framing/protocol logic itself is unit-tested in wallet-hardware.
 */
public final class LedgerProbe {

    public static void main(String[] args) {
        HardwareWalletService service = new LedgerHardwareWalletService();

        System.out.println("Scanning for Ledger devices…");
        List<HardwareDevice> devices;
        try {
            devices = service.enumerate();
        } catch (HardwareWalletException e) {
            System.err.println("Enumeration failed: " + e.getMessage());
            System.exit(2);
            return;
        }

        if (devices.isEmpty()) {
            System.out.println("No Ledger devices found. Connect and unlock your Ledger, "
                    + "open the Cardano app, and try again.");
            return;
        }

        DeviceAddressService addresses = new DeviceAddressService();
        System.out.println("Found " + devices.size() + " Ledger device(s):");
        for (HardwareDevice device : devices) {
            System.out.println("  • " + device.displayName() + "  [path=" + device.path() + "]");
            try {
                DeviceVersion version = service.getCardanoAppVersion(device);
                System.out.println("      Cardano app version: " + version);
            } catch (HardwareWalletException e) {
                System.out.println("      Cardano app version: unavailable — " + e.getMessage());
                continue;
            }
            try {
                System.out.println("      Importing account 0 (confirm the export on your device if prompted)…");
                DeviceKeystore keystore = service.importAccount(device, 0);
                System.out.println("      Account xpub: " + keystore.accountXpubHex());
                System.out.println("      mainnet receive #0: "
                        + addresses.receiveAddress(keystore, WalletNetwork.MAINNET, 0));
                System.out.println("      preprod receive #0: "
                        + addresses.receiveAddress(keystore, WalletNetwork.PREPROD, 0));
                System.out.println("      mainnet stake addr : "
                        + addresses.stakeAddress(keystore, WalletNetwork.MAINNET));
                System.out.println();
                System.out.println("      >>> TOP UP THIS PREPROD ADDRESS <<<");
                String softAddress = addresses.receiveAddress(keystore, WalletNetwork.PREPROD, 0);
                System.out.println("      preprod receive #0: " + softAddress);
                System.out.println("      Verifying it on the device — confirm the address on the screen matches…");
                String deviceAddress = service.showReceiveAddress(device, WalletNetwork.PREPROD, 0, 0);
                boolean match = deviceAddress.equals(softAddress);
                System.out.println("      device-derived : " + deviceAddress);
                System.out.println("      on-device verification: " + (match ? "MATCH ✓ — safe to send funds here" : "MISMATCH ✗ — do NOT send"));
            } catch (HardwareWalletException e) {
                System.out.println("      Account import failed: " + e.getMessage());
            }
        }
    }

    private LedgerProbe() {
    }
}
