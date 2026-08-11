package com.bloxbean.cardano.yano.wallet.app;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

/**
 * Renders an asset name safely (ADR-042).
 *
 * <p>An asset name is 0–32 arbitrary bytes chosen by whoever minted the token —
 * including an attacker who minted it specifically to be shown here. This string
 * is displayed inside the dialog the user reads before signing, so it is treated
 * as hostile input, not as a label:
 *
 * <ul>
 *   <li><b>Bidi overrides are removed.</b> {@code U+202E} (right-to-left
 *       override) can visually reverse surrounding text, which in a dialog that
 *       says what leaves and what arrives is a way to invert the sentence the
 *       user is reading.</li>
 *   <li><b>Control, format and zero-width characters are removed</b>, so a name
 *       cannot inject line breaks to push the real amounts off screen, or hide
 *       characters that make two different tokens look identical.</li>
 *   <li><b>Only printable ASCII survives as text.</b> Anything else falls back to
 *       hex. This costs some legibility for non-Latin names, and buys immunity to
 *       homoglyph tokens that impersonate a well-known asset.</li>
 *   <li><b>Length is capped</b>, so a long name cannot crowd out the numbers.</li>
 * </ul>
 *
 * <p>The name is never omitted: an unnamed asset quietly leaving the wallet is
 * exactly what an attacker wants. When it cannot be shown as text it is shown as
 * hex, which is ugly and honest.
 */
final class AssetNameDisplay {

    /** Long enough for real ticker-style names, short enough not to crowd the amounts. */
    private static final int MAX_DISPLAY_CHARS = 32;

    private AssetNameDisplay() {
    }

    /**
     * A display label for one asset. Never returns null, never returns an empty
     * string, and never returns anything a terminal or label could interpret as
     * formatting.
     */
    static String of(String policyId, String assetNameHex) {
        String text = decodePrintableAscii(assetNameHex);
        if (text != null && !text.isEmpty()) {
            return text;
        }
        if (assetNameHex == null || assetNameHex.isBlank()) {
            // A nameless asset is legal; identify it by its policy so it is still
            // something the user can look up rather than a blank.
            return "(unnamed token of policy " + shortPolicy(policyId) + ")";
        }
        String hex = assetNameHex.length() > MAX_DISPLAY_CHARS
                ? assetNameHex.substring(0, MAX_DISPLAY_CHARS) + "…"
                : assetNameHex;
        return "0x" + hex;
    }

    /** First and last few characters of a policy id, for identification without noise. */
    static String shortPolicy(String policyId) {
        if (policyId == null || policyId.isBlank()) {
            return "unknown";
        }
        return policyId.length() <= 16
                ? policyId
                : policyId.substring(0, 8) + "…" + policyId.substring(policyId.length() - 8);
    }

    /**
     * Decodes the name to text only if every byte is strict UTF-8 AND every
     * resulting character is printable ASCII. Returns null when it is not safe to
     * show as text, so the caller falls back to hex.
     */
    private static String decodePrintableAscii(String assetNameHex) {
        if (assetNameHex == null || assetNameHex.isBlank()) {
            return null;
        }
        byte[] bytes;
        try {
            bytes = HexFormat.of().parseHex(assetNameHex);
        } catch (IllegalArgumentException e) {
            return null;
        }
        if (bytes.length == 0) {
            return null;
        }
        String decoded;
        try {
            CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT);
            CharBuffer chars = decoder.decode(ByteBuffer.wrap(bytes));
            decoded = chars.toString();
        } catch (CharacterCodingException e) {
            return null;
        }
        StringBuilder safe = new StringBuilder(decoded.length());
        for (int i = 0; i < decoded.length(); i++) {
            char c = decoded.charAt(i);
            // Printable ASCII only — space through tilde. This rejects C0/C1
            // controls, every bidi and zero-width format character, and every
            // non-Latin homoglyph in one test.
            if (c < 0x20 || c > 0x7E) {
                return null;
            }
            safe.append(c);
        }
        String result = safe.toString().strip();
        if (result.isEmpty()) {
            return null;
        }
        return result.length() > MAX_DISPLAY_CHARS
                ? result.substring(0, MAX_DISPLAY_CHARS) + "…"
                : result;
    }
}
