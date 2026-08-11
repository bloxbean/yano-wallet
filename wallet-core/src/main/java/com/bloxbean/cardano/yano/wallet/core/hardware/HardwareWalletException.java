package com.bloxbean.cardano.yano.wallet.core.hardware;

/**
 * Raised when a hardware-device operation fails (ADR-034): device not found,
 * transport error, app not open, unsupported app version, user rejection, or a
 * protocol/status-word error. The message is safe to surface to the user.
 */
public class HardwareWalletException extends RuntimeException {

    public HardwareWalletException(String message) {
        super(message);
    }

    public HardwareWalletException(String message, Throwable cause) {
        super(message, cause);
    }
}
