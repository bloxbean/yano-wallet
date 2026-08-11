package com.bloxbean.cardano.yano.wallet.core.hardware;

import java.util.List;

/**
 * SPI for talking to a hardware wallet (ADR-034). Implemented per device family
 * in {@code wallet-hardware} (Ledger first); the wallet app depends only on this
 * interface, so no {@code hid4java}/JNA or device-protocol type leaks into the
 * rest of the wallet.
 *
 * <p>HW-M1 covers discovery and version gating ({@link #enumerate()} and
 * {@link #getCardanoAppVersion}). Account import ({@code getExtendedPublicKey}),
 * on-device address verification ({@code deriveAddress}), and transaction
 * signing are added in later milestones as this interface grows.
 *
 * <p>Implementations open the device only for the duration of a call and must be
 * safe to invoke from a single dedicated device thread (the USB-HID transport is
 * not concurrent).
 */
public interface HardwareWalletService {

    /** The device family this implementation handles. */
    DeviceType deviceType();

    /**
     * Lists the currently attached devices of this family. Never null; empty
     * when none are connected. Does not require the Cardano app to be open.
     */
    List<HardwareDevice> enumerate();

    /**
     * Opens {@code device} and reads the version of the Cardano app it is
     * running. The Cardano app must be open on the device.
     *
     * @throws HardwareWalletException if the device is unreachable, the Cardano
     *                                 app is not open, or the protocol errors
     */
    DeviceVersion getCardanoAppVersion(HardwareDevice device);

    /**
     * Reads the account-level extended public key ({@code 1852'/1815'/account'})
     * and returns a watch-only {@link DeviceKeystore}. The seed never leaves the
     * device; only public key material is returned. The device may prompt for
     * on-device confirmation.
     *
     * @throws HardwareWalletException if the app is not open, the user rejects,
     *                                 or the protocol errors
     */
    DeviceKeystore importAccount(HardwareDevice device, int accountIndex);

    /**
     * Derives the external receive address at
     * {@code 1852'/1815'/accountIndex'/0/index} on the device and asks the device
     * to <b>display</b> it for the user to verify against the wallet, defeating
     * address-swap malware. Returns the bech32 address the device derived (which
     * the caller can also compare to the software-derived address).
     *
     * @throws HardwareWalletException if the app is not open, the user rejects,
     *                                 or the protocol errors
     */
    String showReceiveAddress(HardwareDevice device,
                              com.bloxbean.cardano.yano.wallet.core.config.WalletNetwork network,
                              int accountIndex, int index);
}
