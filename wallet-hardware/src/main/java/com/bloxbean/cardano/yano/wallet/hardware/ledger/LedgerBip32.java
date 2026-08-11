package com.bloxbean.cardano.yano.wallet.hardware.ledger;

/**
 * CIP-1852 BIP32 path helpers and the Ledger APDU path encoding (ADR-034).
 *
 * <p>A path is serialized as {@code [count(1)] [element(4, big-endian)]...} with
 * hardened elements carrying the {@code 0x80000000} bit — the encoding the
 * Cardano Ledger app expects (matching {@code ledgerjs} {@code path_to_buf}).
 *
 * <p>Only the <em>account</em> level ({@code 1852'/1815'/account'}) is requested
 * from the device; payment (role 0/1) and stake (role 2) child keys are derived
 * in software from the returned account public key. Those child levels are
 * non-hardened, so the derivation is possible from the public key alone and
 * yields exactly the keys the device would derive for the full path.
 */
public final class LedgerBip32 {

    /** Hardened-derivation offset. */
    public static final long HARDENED = 0x80000000L;
    /** CIP-1852 purpose for Shelley (1852'). Byron used 44'. */
    public static final long PURPOSE_SHELLEY = 1852L | HARDENED;
    /** Cardano coin type (1815', Ada Lovelace's birth year). */
    public static final long COIN_TYPE_ADA = 1815L | HARDENED;

    private LedgerBip32() {
    }

    /** The account-level path {@code 1852'/1815'/account'}. */
    public static long[] accountPath(int accountIndex) {
        return new long[]{PURPOSE_SHELLEY, COIN_TYPE_ADA, hardened(accountIndex)};
    }

    /**
     * A full payment path {@code 1852'/1815'/account'/role/index} (role 0
     * external, 1 change). Role and index are non-hardened.
     */
    public static long[] paymentPath(int accountIndex, int role, int index) {
        if (index < 0) {
            throw new IllegalArgumentException("index must be >= 0");
        }
        return new long[]{PURPOSE_SHELLEY, COIN_TYPE_ADA, hardened(accountIndex),
                role & 0xFFFFFFFFL, index & 0xFFFFFFFFL};
    }

    /** The stake path {@code 1852'/1815'/account'/2/0}. */
    public static long[] stakePath(int accountIndex) {
        return new long[]{PURPOSE_SHELLEY, COIN_TYPE_ADA, hardened(accountIndex), 2L, 0L};
    }

    /** The DRep path {@code 1852'/1815'/account'/3/0} (CIP-1694 governance key). */
    public static long[] drepPath(int accountIndex) {
        return new long[]{PURPOSE_SHELLEY, COIN_TYPE_ADA, hardened(accountIndex), 3L, 0L};
    }

    private static long hardened(int accountIndex) {
        if (accountIndex < 0) {
            throw new IllegalArgumentException("accountIndex must be >= 0");
        }
        return (accountIndex & 0xFFFFFFFFL) | HARDENED;
    }

    /** Encodes a path as the Cardano Ledger app's APDU path payload. */
    public static byte[] serialize(long[] path) {
        if (path == null || path.length == 0 || path.length > 10) {
            throw new IllegalArgumentException("path must have 1..10 elements");
        }
        byte[] out = new byte[1 + 4 * path.length];
        out[0] = (byte) path.length;
        for (int i = 0; i < path.length; i++) {
            long element = path[i] & 0xFFFFFFFFL;
            int at = 1 + i * 4;
            out[at] = (byte) ((element >> 24) & 0xFF);
            out[at + 1] = (byte) ((element >> 16) & 0xFF);
            out[at + 2] = (byte) ((element >> 8) & 0xFF);
            out[at + 3] = (byte) (element & 0xFF);
        }
        return out;
    }
}
