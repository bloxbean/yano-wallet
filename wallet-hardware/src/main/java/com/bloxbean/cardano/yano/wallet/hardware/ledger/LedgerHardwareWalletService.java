package com.bloxbean.cardano.yano.wallet.hardware.ledger;

import com.bloxbean.cardano.client.address.Address;
import com.bloxbean.cardano.client.util.HexUtil;
import com.bloxbean.cardano.yano.wallet.core.config.WalletNetwork;
import com.bloxbean.cardano.yano.wallet.core.hardware.DeviceKeystore;
import com.bloxbean.cardano.yano.wallet.core.hardware.DeviceType;
import com.bloxbean.cardano.yano.wallet.core.hardware.DeviceVersion;
import com.bloxbean.cardano.yano.wallet.core.hardware.HardwareDevice;
import com.bloxbean.cardano.yano.wallet.core.hardware.HardwareWalletException;
import com.bloxbean.cardano.yano.wallet.core.hardware.HardwareWalletService;
import org.hid4java.HidDevice;
import org.hid4java.HidManager;
import org.hid4java.HidServices;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Ledger implementation of {@link HardwareWalletService} (ADR-034). Enumerates
 * attached Ledger devices over USB-HID via {@code hid4java} and reads the
 * Cardano app version. Later milestones extend this with account import,
 * address verification, and signing.
 *
 * <p>Only the APDU HID interface is used (Ledger exposes it as USB usage page
 * {@code 0xFFA0}); other interfaces of the same device are ignored.
 */
public final class LedgerHardwareWalletService implements HardwareWalletService {

    private static final Logger log = LoggerFactory.getLogger(LedgerHardwareWalletService.class);

    /** Ledger USB vendor id. */
    static final int LEDGER_VENDOR_ID = 0x2C97;
    /** USB usage page of the generic-HID APDU interface on Ledger devices. */
    static final int APDU_USAGE_PAGE = 0xFFA0;

    @Override
    public DeviceType deviceType() {
        return DeviceType.LEDGER;
    }

    @Override
    public List<HardwareDevice> enumerate() {
        List<HidDevice> ledgerHids = new ArrayList<>();
        for (HidDevice hid : hidServices().getAttachedHidDevices()) {
            if (hid.getVendorId() == LEDGER_VENDOR_ID) {
                ledgerHids.add(hid);
            }
        }
        // Prefer the APDU interface (usage page 0xFFA0). On platforms where the
        // usage page is unavailable (e.g. Linux without udev permissions it can
        // read back as 0), fall back to every Ledger interface so a connected
        // device is still discovered rather than silently hidden.
        boolean anyApduInterface = ledgerHids.stream().anyMatch(LedgerHardwareWalletService::isApduInterface);

        List<HardwareDevice> devices = new ArrayList<>();
        Set<String> seenPaths = new HashSet<>();
        for (HidDevice hid : ledgerHids) {
            if (anyApduInterface && !isApduInterface(hid)) {
                continue;
            }
            String path = hid.getPath();
            if (path == null || !seenPaths.add(path)) {
                continue;
            }
            devices.add(new HardwareDevice(DeviceType.LEDGER, path,
                    hid.getProduct(), hid.getManufacturer(), blankToNull(hid.getSerialNumber())));
        }
        return devices;
    }

    @Override
    public DeviceVersion getCardanoAppVersion(HardwareDevice device) {
        requireLedger(device);
        HidDevice hid = openByPath(device.path());
        try (LedgerTransport transport = new LedgerTransport(hid)) {
            return new LedgerCardanoApp(transport).getVersion();
        }
    }

    @Override
    public DeviceKeystore importAccount(HardwareDevice device, int accountIndex) {
        requireLedger(device);
        HidDevice hid = openByPath(device.path());
        try (LedgerTransport transport = new LedgerTransport(hid)) {
            byte[] accountXpub = new LedgerCardanoApp(transport)
                    .getExtendedPublicKey(LedgerBip32.accountPath(accountIndex));
            return new DeviceKeystore(DeviceType.LEDGER, accountIndex,
                    HexUtil.encodeHexString(accountXpub), null);
        }
    }

    @Override
    public String showReceiveAddress(HardwareDevice device, WalletNetwork network,
                                     int accountIndex, int index) {
        requireLedger(device);
        HidDevice hid = openByPath(device.path());
        try (LedgerTransport transport = new LedgerTransport(hid)) {
            LedgerCardanoApp app = new LedgerCardanoApp(transport);
            long[] spendingPath = LedgerBip32.paymentPath(accountIndex, 0, index);
            long[] stakingPath = LedgerBip32.stakePath(accountIndex);
            int networkId = network.networkId();

            // Read the device's own address bytes (for a programmatic match)...
            byte[] addressBytes = app.deriveAddressBytes(networkId, spendingPath, stakingPath);
            String bech32 = new Address(addressBytes).toBech32();
            // ...then show it on the device screen for the user to approve.
            app.displayAddress(networkId, spendingPath, stakingPath);
            return bech32;
        }
    }

    /**
     * Signs an ordinary ADA-only payment on the device (ADR-034, HW-M3 first
     * cut). Ledger-specific (not yet on the SPI) — used by the sign probe to
     * validate the streaming protocol against the host tx hash before wiring it
     * into the wallet money path. {@code tagCborSets}/{@code outputFormat} must
     * match the host's canonical CBOR (see {@link LedgerCardanoApp#signTransaction}).
     */
    /**
     * General ordinary-tx signing (ADR-034): payments, native assets, stake
     * certificates, and withdrawals. Opens the device, streams the tx, and
     * returns the tx hash + one witness per signing path.
     */
    public LedgerSignedTx signTransaction(HardwareDevice device, int networkId, long protocolMagic,
                                          List<LedgerTxInput> inputs, List<LedgerTxOutput> outputs,
                                          BigInteger fee, long ttl, List<long[]> signingPaths,
                                          List<byte[]> certificates, List<byte[]> withdrawals,
                                          boolean tagCborSets, int outputFormat, byte[] auxiliaryDataHash) {
        return signTransaction(device, networkId, protocolMagic, inputs, outputs, fee, ttl,
                signingPaths, certificates, withdrawals, List.of(), tagCborSets, outputFormat, auxiliaryDataHash);
    }

    /** Sign with Conway voting procedures (CIP-1694); see the base overload. */
    public LedgerSignedTx signTransaction(HardwareDevice device, int networkId, long protocolMagic,
                                          List<LedgerTxInput> inputs, List<LedgerTxOutput> outputs,
                                          BigInteger fee, long ttl, List<long[]> signingPaths,
                                          List<byte[]> certificates, List<byte[]> withdrawals,
                                          List<byte[]> votingProcedures,
                                          boolean tagCborSets, int outputFormat, byte[] auxiliaryDataHash) {
        requireLedger(device);
        HidDevice hid = openByPath(device.path());
        try (LedgerTransport transport = new LedgerTransport(hid)) {
            return new LedgerCardanoApp(transport).signTransaction(
                    networkId, protocolMagic, inputs, outputs, fee, ttl, signingPaths, certificates,
                    withdrawals, votingProcedures, tagCborSets, outputFormat, auxiliaryDataHash);
        }
    }

    /** Full-body signing (ADR-035 M4, incl. Plutus): streams a complete {@link LedgerSignRequest}. */
    public LedgerSignedTx signTransaction(HardwareDevice device, LedgerSignRequest request) {
        requireLedger(device);
        HidDevice hid = openByPath(device.path());
        try (LedgerTransport transport = new LedgerTransport(hid)) {
            return new LedgerCardanoApp(transport).signTransaction(request);
        }
    }

    /** A simple payment: one payment-key witness, no certs/withdrawals. */
    public LedgerSignedTx signAdaPayment(HardwareDevice device, int accountIndex,
                                         int networkId, long protocolMagic,
                                         List<LedgerTxInput> inputs, List<LedgerTxOutput> outputs,
                                         BigInteger fee, long ttl,
                                         boolean tagCborSets, int outputFormat, byte[] auxiliaryDataHash) {
        return signTransaction(device, networkId, protocolMagic, inputs, outputs, fee, ttl,
                List.of(LedgerBip32.paymentPath(accountIndex, 0, 0)), List.of(), List.of(),
                tagCborSets, outputFormat, auxiliaryDataHash);
    }

    private HidDevice openByPath(String path) {
        for (HidDevice hid : hidServices().getAttachedHidDevices()) {
            if (path.equals(hid.getPath())) {
                if (!hid.open() || !hid.isOpen()) {
                    throw new HardwareWalletException(
                            "Unable to open the device: " + hid.getLastErrorMessage());
                }
                return hid;
            }
        }
        throw new HardwareWalletException("Device is no longer connected");
    }

    private static boolean isApduInterface(HidDevice hid) {
        // Ledger reports the APDU interface as usage page 0xFFA0. Mask because
        // some platforms surface the value as a sign-extended short.
        return (hid.getUsagePage() & 0xFFFF) == APDU_USAGE_PAGE;
    }

    private static void requireLedger(HardwareDevice device) {
        if (device == null || device.type() != DeviceType.LEDGER) {
            throw new HardwareWalletException("Not a Ledger device");
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static HidServices hidServices() {
        try {
            return HidManager.getHidServices();
        } catch (RuntimeException e) {
            log.debug("HID services unavailable", e);
            throw new HardwareWalletException(
                    "USB HID services are unavailable on this system: " + e.getMessage(), e);
        }
    }
}
