package com.bloxbean.cardano.yano.wallet.hardware.fido;

import org.bouncycastle.crypto.digests.SHA256Digest;
import org.bouncycastle.crypto.generators.HKDFBytesGenerator;
import org.bouncycastle.crypto.params.HKDFParameters;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;

/**
 * CTAP2 PIN/UV auth protocol primitives (ADR-036 Y-M2), versions 1 and 2
 * (CTAP 2.1 §6.5.6). Given the ECDH shared-point X coordinate {@code Z},
 * {@link #kdf} derives the shared secret; {@link #encrypt}/{@link #decrypt}/
 * {@link #authenticate} are the platform-side primitives used for the PIN-token
 * exchange and the hmac-secret salt. Only the platform side is implemented.
 *
 * <ul>
 *   <li><b>v1</b>: sharedSecret = SHA-256(Z); AES-256-CBC zero-IV; HMAC-SHA-256
 *       truncated to 16 bytes.</li>
 *   <li><b>v2</b>: sharedSecret = HKDF(Z)→(hmacKey‖aesKey); AES-256-CBC with a
 *       random IV prepended; full HMAC-SHA-256.</li>
 * </ul>
 */
public interface PinUvAuthProtocol {

    int version();

    /** Derives the shared secret from the ECDH shared-point X coordinate. */
    byte[] kdf(byte[] z);

    byte[] encrypt(byte[] sharedSecret, byte[] plaintext);

    byte[] decrypt(byte[] sharedSecret, byte[] ciphertext);

    byte[] authenticate(byte[] key, byte[] message);

    static PinUvAuthProtocol forVersion(int version) {
        return switch (version) {
            case 1 -> new V1();
            case 2 -> new V2(new SecureRandom());
            default -> throw new IllegalArgumentException("Unsupported PIN/UV auth protocol: " + version);
        };
    }

    // --- shared crypto helpers ---

    static byte[] sha256(byte[] input) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(input);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    static byte[] hmacSha256(byte[] key, byte[] message) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(message);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    static byte[] aesCbc(int cipherMode, byte[] key, byte[] iv, byte[] data) {
        try {
            Cipher cipher = Cipher.getInstance("AES/CBC/NoPadding");
            cipher.init(cipherMode, new SecretKeySpec(key, "AES"), new IvParameterSpec(iv));
            return cipher.doFinal(data);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("AES-CBC failed: " + e.getMessage(), e);
        }
    }

    static byte[] hkdfSha256(byte[] ikm, byte[] salt, byte[] info, int length) {
        HKDFBytesGenerator generator = new HKDFBytesGenerator(new SHA256Digest());
        generator.init(new HKDFParameters(ikm, salt, info));
        byte[] out = new byte[length];
        generator.generateBytes(out, 0, length);
        return out;
    }

    /** PIN/UV auth protocol one. */
    final class V1 implements PinUvAuthProtocol {
        private static final byte[] ZERO_IV = new byte[16];

        @Override
        public int version() {
            return 1;
        }

        @Override
        public byte[] kdf(byte[] z) {
            return sha256(z);
        }

        @Override
        public byte[] encrypt(byte[] sharedSecret, byte[] plaintext) {
            return aesCbc(Cipher.ENCRYPT_MODE, sharedSecret, ZERO_IV, plaintext);
        }

        @Override
        public byte[] decrypt(byte[] sharedSecret, byte[] ciphertext) {
            return aesCbc(Cipher.DECRYPT_MODE, sharedSecret, ZERO_IV, ciphertext);
        }

        @Override
        public byte[] authenticate(byte[] key, byte[] message) {
            return Arrays.copyOf(hmacSha256(key, message), 16); // truncated
        }
    }

    /** PIN/UV auth protocol two. */
    final class V2 implements PinUvAuthProtocol {
        private static final byte[] HKDF_SALT = new byte[32];
        private static final byte[] HMAC_INFO = "CTAP2 HMAC key".getBytes(StandardCharsets.US_ASCII);
        private static final byte[] AES_INFO = "CTAP2 AES key".getBytes(StandardCharsets.US_ASCII);

        private final SecureRandom random;

        V2(SecureRandom random) {
            this.random = random;
        }

        @Override
        public int version() {
            return 2;
        }

        @Override
        public byte[] kdf(byte[] z) {
            byte[] hmacKey = hkdfSha256(z, HKDF_SALT, HMAC_INFO, 32);
            byte[] aesKey = hkdfSha256(z, HKDF_SALT, AES_INFO, 32);
            byte[] combined = new byte[64];
            System.arraycopy(hmacKey, 0, combined, 0, 32);
            System.arraycopy(aesKey, 0, combined, 32, 32);
            return combined;
        }

        @Override
        public byte[] encrypt(byte[] sharedSecret, byte[] plaintext) {
            byte[] iv = new byte[16];
            random.nextBytes(iv);
            byte[] ciphertext = aesCbc(Cipher.ENCRYPT_MODE, aesKey(sharedSecret), iv, plaintext);
            byte[] out = new byte[iv.length + ciphertext.length];
            System.arraycopy(iv, 0, out, 0, iv.length);
            System.arraycopy(ciphertext, 0, out, iv.length, ciphertext.length);
            return out;
        }

        @Override
        public byte[] decrypt(byte[] sharedSecret, byte[] ciphertext) {
            if (ciphertext.length < 16) {
                throw new IllegalArgumentException("v2 ciphertext missing IV");
            }
            byte[] iv = Arrays.copyOfRange(ciphertext, 0, 16);
            byte[] body = Arrays.copyOfRange(ciphertext, 16, ciphertext.length);
            return aesCbc(Cipher.DECRYPT_MODE, aesKey(sharedSecret), iv, body);
        }

        @Override
        public byte[] authenticate(byte[] key, byte[] message) {
            // key may be the 64-byte sharedSecret (use its HMAC half) or a token.
            byte[] hmacKey = key.length == 64 ? Arrays.copyOfRange(key, 0, 32) : key;
            return hmacSha256(hmacKey, message);
        }

        private static byte[] aesKey(byte[] sharedSecret) {
            return sharedSecret.length == 64 ? Arrays.copyOfRange(sharedSecret, 32, 64) : sharedSecret;
        }
    }
}
