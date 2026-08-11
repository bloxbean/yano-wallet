package com.bloxbean.cardano.yano.wallet.connector;

/**
 * A CIP-30 error carried back to the dApp as {@code { code, info }}. Codes follow
 * the CIP-30 APIError / TxSignError / DataSignError enums.
 */
public class Cip30Exception extends RuntimeException {

    // APIError
    public static final int INVALID_REQUEST = -1;
    public static final int INTERNAL_ERROR = -2;
    public static final int REFUSED = -3;
    public static final int ACCOUNT_CHANGE = -4;

    private final int code;

    public Cip30Exception(int code, String info) {
        super(info);
        this.code = code;
    }

    public int code() {
        return code;
    }

    public static Cip30Exception refused(String info) {
        return new Cip30Exception(REFUSED, info);
    }

    public static Cip30Exception internal(String info) {
        return new Cip30Exception(INTERNAL_ERROR, info);
    }

    public static Cip30Exception invalid(String info) {
        return new Cip30Exception(INVALID_REQUEST, info);
    }
}
