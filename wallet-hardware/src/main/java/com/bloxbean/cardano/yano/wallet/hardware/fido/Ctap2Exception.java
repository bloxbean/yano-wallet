package com.bloxbean.cardano.yano.wallet.hardware.fido;

import com.bloxbean.cardano.yano.wallet.core.hardware.HardwareWalletException;

/**
 * A non-zero CTAP status from a FIDO2 authenticator (ADR-036 Y-M2). Carries the
 * raw status byte and maps the common ones to a readable message so the UI can
 * tell "wrong PIN" from "no credential" from "you didn't touch it".
 */
public class Ctap2Exception extends HardwareWalletException {

    // CTAP 2.1 §8.1 status codes we surface specifically.
    public static final int ERR_OPERATION_DENIED = 0x27;
    public static final int ERR_NO_CREDENTIALS = 0x2E;
    public static final int ERR_USER_ACTION_TIMEOUT = 0x2F;
    public static final int ERR_PIN_INVALID = 0x31;
    public static final int ERR_PIN_BLOCKED = 0x32;
    public static final int ERR_PIN_AUTH_BLOCKED = 0x34;
    public static final int ERR_PIN_NOT_SET = 0x35;
    public static final int ERR_PIN_REQUIRED = 0x36;
    public static final int ERR_UP_REQUIRED = 0x3B;

    private final int status;

    public Ctap2Exception(int status) {
        super(describe(status));
        this.status = status;
    }

    public int status() {
        return status;
    }

    private static String describe(int status) {
        String reason = switch (status) {
            case ERR_OPERATION_DENIED -> "the operation was denied";
            case ERR_NO_CREDENTIALS -> "this security key has no matching credential (wrong key?)";
            case ERR_USER_ACTION_TIMEOUT -> "you didn't confirm on the security key in time";
            case ERR_PIN_INVALID -> "incorrect PIN";
            case ERR_PIN_BLOCKED -> "the PIN is blocked — reset the key's FIDO2 app to recover";
            case ERR_PIN_AUTH_BLOCKED -> "too many PIN attempts — unplug and retry";
            case ERR_PIN_NOT_SET -> "no FIDO2 PIN is set on this key";
            case ERR_PIN_REQUIRED -> "a FIDO2 PIN is required";
            case ERR_UP_REQUIRED -> "you must touch the security key";
            default -> "CTAP error 0x" + Integer.toHexString(status);
        };
        return "Security key: " + reason + " (0x" + Integer.toHexString(status) + ")";
    }
}
