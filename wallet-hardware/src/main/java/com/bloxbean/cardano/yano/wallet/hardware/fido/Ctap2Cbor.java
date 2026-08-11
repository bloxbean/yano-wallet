package com.bloxbean.cardano.yano.wallet.hardware.fido;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Minimal CTAP2 <em>canonical</em> CBOR encoder (ADR-036 Y-M2). CTAP2 requires
 * definite-length items, shortest-form integers, and map keys sorted
 * length-first then bytewise (CTAP 2.1 §6). Only the small fixed set of types
 * the client sends is supported — integers, byte/text strings, arrays, maps.
 * Responses are decoded separately with a permissive decoder.
 *
 * <p>Hand-rolled rather than delegated to a generic CBOR library so the
 * canonical key ordering is guaranteed regardless of a library's default map
 * ordering.
 */
public final class Ctap2Cbor {

    private Ctap2Cbor() {
    }

    /** A signed integer (major type 0 for &gt;=0, major type 1 for &lt;0), shortest form. */
    public static byte[] integer(long value) {
        return value >= 0 ? typeAndValue(0, value) : typeAndValue(1, -1 - value);
    }

    public static byte[] bytes(byte[] value) {
        return concat(typeAndValue(2, value.length), value);
    }

    public static byte[] text(String value) {
        byte[] utf8 = value.getBytes(StandardCharsets.UTF_8);
        return concat(typeAndValue(3, utf8.length), utf8);
    }

    /** CBOR boolean (major type 7 simple value): true=0xf5, false=0xf4. */
    public static byte[] bool(boolean value) {
        return new byte[]{(byte) (value ? 0xF5 : 0xF4)};
    }

    /** An array of already-encoded items. */
    public static byte[] array(List<byte[]> items) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(typeAndValue(4, items.size()));
        for (byte[] item : items) {
            out.writeBytes(item);
        }
        return out.toByteArray();
    }

    public static byte[] array(byte[]... items) {
        return array(List.of(items));
    }

    public static MapBuilder map() {
        return new MapBuilder();
    }

    /**
     * Builds a canonical CBOR map. Keys may be integers or text; on {@link #build}
     * the entries are sorted by encoded-key bytes (shorter first, then unsigned
     * bytewise) as CTAP2 requires.
     */
    public static final class MapBuilder {
        private final List<byte[][]> entries = new ArrayList<>();

        public MapBuilder put(int key, byte[] encodedValue) {
            entries.add(new byte[][]{integer(key), encodedValue});
            return this;
        }

        public MapBuilder put(String key, byte[] encodedValue) {
            entries.add(new byte[][]{text(key), encodedValue});
            return this;
        }

        public boolean isEmpty() {
            return entries.isEmpty();
        }

        public byte[] build() {
            entries.sort(Comparator.comparing(e -> e[0], CANONICAL_KEY));
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            out.writeBytes(typeAndValue(5, entries.size()));
            for (byte[][] entry : entries) {
                out.writeBytes(entry[0]);
                out.writeBytes(entry[1]);
            }
            return out.toByteArray();
        }
    }

    /** CTAP2 canonical map-key order: shorter encoding first, then unsigned bytewise. */
    static final Comparator<byte[]> CANONICAL_KEY = (a, b) -> {
        if (a.length != b.length) {
            return Integer.compare(a.length, b.length);
        }
        for (int i = 0; i < a.length; i++) {
            int diff = (a[i] & 0xFF) - (b[i] & 0xFF);
            if (diff != 0) {
                return diff;
            }
        }
        return 0;
    };

    private static byte[] typeAndValue(int majorType, long value) {
        int mt = majorType << 5;
        if (value < 0) {
            throw new IllegalArgumentException("argument must be non-negative");
        }
        if (value < 24) {
            return new byte[]{(byte) (mt | value)};
        }
        if (value < 0x100L) {
            return new byte[]{(byte) (mt | 24), (byte) value};
        }
        if (value < 0x10000L) {
            return new byte[]{(byte) (mt | 25), (byte) (value >> 8), (byte) value};
        }
        if (value < 0x100000000L) {
            return new byte[]{(byte) (mt | 26),
                    (byte) (value >> 24), (byte) (value >> 16), (byte) (value >> 8), (byte) value};
        }
        return new byte[]{(byte) (mt | 27),
                (byte) (value >> 56), (byte) (value >> 48), (byte) (value >> 40), (byte) (value >> 32),
                (byte) (value >> 24), (byte) (value >> 16), (byte) (value >> 8), (byte) value};
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] out = new byte[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }
}
