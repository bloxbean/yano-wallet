package com.bloxbean.cardano.yano.wallet.hardware.fido;

import co.nstant.in.cbor.CborDecoder;
import co.nstant.in.cbor.CborException;
import co.nstant.in.cbor.model.Array;
import co.nstant.in.cbor.model.ByteString;
import co.nstant.in.cbor.model.DataItem;
import co.nstant.in.cbor.model.NegativeInteger;
import co.nstant.in.cbor.model.SimpleValue;
import co.nstant.in.cbor.model.SimpleValueType;
import co.nstant.in.cbor.model.UnicodeString;
import co.nstant.in.cbor.model.UnsignedInteger;
import com.bloxbean.cardano.yano.wallet.core.hardware.HardwareWalletException;

import javax.crypto.KeyAgreement;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.AlgorithmParameters;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECParameterSpec;
import java.security.spec.ECPoint;
import java.security.spec.ECPublicKeySpec;
import java.util.Arrays;
import java.util.List;

/**
 * A minimal CTAP2 client for the FIDO2 {@code hmac-secret} flow (ADR-036 Y-M2):
 * {@code getInfo}, the ECDH-based clientPIN key agreement + PIN token, and
 * {@code makeCredential}/{@code getAssertion} with the hmac-secret extension.
 * Encodes with {@link Ctap2Cbor}, decodes responses with {@code co.nstant.in.cbor},
 * and does the PIN/UV crypto with {@link PinUvAuthProtocol}.
 */
public final class Ctap2Client {

    // CTAP2 commands.
    private static final int CMD_MAKE_CREDENTIAL = 0x01;
    private static final int CMD_GET_ASSERTION = 0x02;
    private static final int CMD_GET_INFO = 0x04;
    private static final int CMD_CLIENT_PIN = 0x06;
    // clientPIN subcommands.
    private static final int SUB_SET_PIN = 0x03;
    private static final int SUB_GET_KEY_AGREEMENT = 0x02;
    private static final int SUB_GET_PIN_TOKEN = 0x05;
    private static final int SUB_GET_PIN_UV_TOKEN_PERMS = 0x09;
    private static final int MIN_PIN_PADDED = 64;
    // pinUvAuthToken permissions.
    private static final int PERM_MAKE_CREDENTIAL = 0x01;
    private static final int PERM_GET_ASSERTION = 0x02;
    // authData flags.
    private static final int FLAG_AT = 0x40; // attested credential data present
    private static final int FLAG_ED = 0x80; // extension data present

    // clientDataHash is irrelevant to hmac-secret (we don't verify attestation),
    // but must be a stable 32 bytes.
    private static final byte[] CLIENT_DATA_HASH =
            PinUvAuthProtocol.sha256("yano-vault-cdh".getBytes(StandardCharsets.UTF_8));

    private final CtapHidDevice device;

    public Ctap2Client(CtapHidDevice device) {
        this.device = device;
    }

    public record Info(boolean hmacSecret, boolean clientPinSet, boolean pinUvAuthToken, int pinProtocol) {
    }

    public Info getInfo() {
        DataItem resp = decode(device.ctap2(CMD_GET_INFO, null));
        co.nstant.in.cbor.model.Map map = asMap(resp);
        boolean hmac = arrayContainsText(at(map, 2), "hmac-secret");
        co.nstant.in.cbor.model.Map options = at(map, 4) instanceof co.nstant.in.cbor.model.Map m ? m : null;
        boolean clientPin = options != null && isTrue(options.get(new UnicodeString("clientPin")));
        boolean pinUvToken = options != null && isTrue(options.get(new UnicodeString("pinUvAuthToken")));
        int proto = pickPinProtocol(at(map, 6));
        return new Info(hmac, clientPin, pinUvToken, proto);
    }

    /**
     * Sets the authenticator's FIDO2 PIN (CTAP 2.1 §6.5.5.6). Only valid when no
     * PIN is set yet. NOTE: the PIN is key-global (applies to all FIDO2 use of
     * this key) and can only be removed by a FIDO2 reset — the UI must warn.
     */
    public void setPin(char[] newPin) {
        byte[] pinBytes = new String(newPin).getBytes(StandardCharsets.UTF_8);
        if (pinBytes.length < 4 || pinBytes.length >= MIN_PIN_PADDED) {
            Arrays.fill(pinBytes, (byte) 0);
            throw new HardwareWalletException("FIDO2 PIN must be 4..63 characters");
        }
        Info info = getInfo();
        if (info.clientPinSet()) {
            Arrays.fill(pinBytes, (byte) 0);
            throw new HardwareWalletException("A FIDO2 PIN is already set on this key");
        }
        Session session = keyAgreement(info.pinProtocol());
        byte[] padded = Arrays.copyOf(pinBytes, MIN_PIN_PADDED); // zero-padded to 64
        Arrays.fill(pinBytes, (byte) 0);
        try {
            byte[] newPinEnc = session.protocol.encrypt(session.sharedSecret, padded);
            byte[] pinUvAuthParam = session.protocol.authenticate(session.sharedSecret, newPinEnc);
            byte[] request = Ctap2Cbor.map()
                    .put(0x01, Ctap2Cbor.integer(session.protocol.version()))
                    .put(0x02, Ctap2Cbor.integer(SUB_SET_PIN))
                    .put(0x03, session.platformCose)
                    .put(0x04, Ctap2Cbor.bytes(pinUvAuthParam))
                    .put(0x05, Ctap2Cbor.bytes(newPinEnc))
                    .build();
            device.ctap2(CMD_CLIENT_PIN, request); // non-zero status throws
        } finally {
            Arrays.fill(padded, (byte) 0);
        }
    }

    /**
     * Enrols a non-resident hmac-secret credential and returns its credential id.
     * {@code pin} may be null for a touch-only (non-UV) credential.
     */
    public byte[] makeHmacSecretCredential(String rpId, byte[] userId, char[] pin) {
        Info info = getInfo();
        if (!info.hmacSecret()) {
            throw new HardwareWalletException("This security key does not support hmac-secret.");
        }
        Session session = keyAgreement(info.pinProtocol());

        byte[] rp = Ctap2Cbor.map().put("id", Ctap2Cbor.text(rpId))
                .put("name", Ctap2Cbor.text("Yano Vault")).build();
        byte[] user = Ctap2Cbor.map().put("id", Ctap2Cbor.bytes(userId))
                .put("name", Ctap2Cbor.text("vault")).put("displayName", Ctap2Cbor.text("Vault")).build();
        byte[] credParams = Ctap2Cbor.array(Ctap2Cbor.map()
                .put("alg", Ctap2Cbor.integer(-7)).put("type", Ctap2Cbor.text("public-key")).build());
        byte[] extensions = Ctap2Cbor.map().put("hmac-secret", Ctap2Cbor.bool(true)).build();
        byte[] options = Ctap2Cbor.map().put("rk", Ctap2Cbor.bool(false)).build();

        Ctap2Cbor.MapBuilder request = Ctap2Cbor.map()
                .put(0x01, Ctap2Cbor.bytes(CLIENT_DATA_HASH))
                .put(0x02, rp)
                .put(0x03, user)
                .put(0x04, credParams)
                .put(0x06, extensions)
                .put(0x07, options);
        if (pin != null) {
            byte[] token = getPinToken(session, info, pin, PERM_MAKE_CREDENTIAL, rpId);
            request.put(0x08, Ctap2Cbor.bytes(session.protocol.authenticate(token, CLIENT_DATA_HASH)));
            request.put(0x09, Ctap2Cbor.integer(session.protocol.version()));
        }

        co.nstant.in.cbor.model.Map resp = asMap(decode(device.ctap2(CMD_MAKE_CREDENTIAL, request.build())));
        return parseCredentialId(bytesAt(resp, 2));
    }

    /**
     * Derives the 32-byte hmac-secret output for {@code salt} from a stored
     * credential. Deterministic for a fixed credential+salt. {@code pin} null =
     * touch-only (must match how the credential was enrolled).
     */
    public byte[] getHmacSecret(String rpId, byte[] credentialId, byte[] salt, char[] pin) {
        Info info = getInfo();
        Session session = keyAgreement(info.pinProtocol());

        byte[] saltEnc = session.protocol.encrypt(session.sharedSecret, salt);
        byte[] saltAuth = session.protocol.authenticate(session.sharedSecret, saltEnc);
        byte[] hmacExt = Ctap2Cbor.map()
                .put(1, session.platformCose)
                .put(2, Ctap2Cbor.bytes(saltEnc))
                .put(3, Ctap2Cbor.bytes(saltAuth))
                .put(4, Ctap2Cbor.integer(session.protocol.version()))
                .build();
        byte[] extensions = Ctap2Cbor.map().put("hmac-secret", hmacExt).build();
        byte[] allowList = Ctap2Cbor.array(Ctap2Cbor.map()
                .put("type", Ctap2Cbor.text("public-key"))
                .put("id", Ctap2Cbor.bytes(credentialId)).build());

        Ctap2Cbor.MapBuilder request = Ctap2Cbor.map()
                .put(0x01, Ctap2Cbor.text(rpId))
                .put(0x02, Ctap2Cbor.bytes(CLIENT_DATA_HASH))
                .put(0x03, allowList)
                .put(0x04, extensions);
        if (pin != null) {
            byte[] token = getPinToken(session, info, pin, PERM_GET_ASSERTION, rpId);
            request.put(0x06, Ctap2Cbor.bytes(session.protocol.authenticate(token, CLIENT_DATA_HASH)));
            request.put(0x07, Ctap2Cbor.integer(session.protocol.version()));
        }

        co.nstant.in.cbor.model.Map resp = asMap(decode(device.ctap2(CMD_GET_ASSERTION, request.build())));
        byte[] outputEnc = parseAssertionHmacSecret(bytesAt(resp, 2));
        byte[] output = session.protocol.decrypt(session.sharedSecret, outputEnc);
        return output.length > 32 ? Arrays.copyOf(output, 32) : output;
    }

    // --- clientPIN / ECDH ---

    private record Session(PinUvAuthProtocol protocol, byte[] sharedSecret, byte[] platformCose) {
    }

    private Session keyAgreement(int pinProtocol) {
        byte[] request = Ctap2Cbor.map()
                .put(0x01, Ctap2Cbor.integer(pinProtocol))
                .put(0x02, Ctap2Cbor.integer(SUB_GET_KEY_AGREEMENT))
                .build();
        co.nstant.in.cbor.model.Map resp = asMap(decode(device.ctap2(CMD_CLIENT_PIN, request)));
        co.nstant.in.cbor.model.Map authCose = asMap(resp.get(new UnsignedInteger(1)));
        byte[] authX = coseCoordinate(authCose, -2);
        byte[] authY = coseCoordinate(authCose, -3);

        try {
            ECParameterSpec p256 = p256Params();
            ECPublicKey authPub = (ECPublicKey) KeyFactory.getInstance("EC").generatePublic(
                    new ECPublicKeySpec(new ECPoint(new BigInteger(1, authX), new BigInteger(1, authY)), p256));

            KeyPairGenerator kpg = KeyPairGenerator.getInstance("EC");
            kpg.initialize(new ECGenParameterSpec("secp256r1"));
            KeyPair platform = kpg.generateKeyPair();

            KeyAgreement ka = KeyAgreement.getInstance("ECDH");
            ka.init(platform.getPrivate());
            ka.doPhase(authPub, true);
            byte[] z = ka.generateSecret(); // 32-byte shared X coordinate

            PinUvAuthProtocol protocol = PinUvAuthProtocol.forVersion(pinProtocol);
            byte[] sharedSecret = protocol.kdf(z);
            ECPublicKey platPub = (ECPublicKey) platform.getPublic();
            byte[] platformCose = coseKey(
                    to32(platPub.getW().getAffineX()), to32(platPub.getW().getAffineY()));
            return new Session(protocol, sharedSecret, platformCose);
        } catch (java.security.GeneralSecurityException e) {
            throw new HardwareWalletException("ECDH key agreement failed: " + e.getMessage(), e);
        }
    }

    private byte[] getPinToken(Session session, Info info, char[] pin, int permissions, String rpId) {
        byte[] pinBytes = new String(pin).getBytes(StandardCharsets.UTF_8);
        byte[] pinHash = Arrays.copyOf(PinUvAuthProtocol.sha256(pinBytes), 16);
        Arrays.fill(pinBytes, (byte) 0);
        byte[] pinHashEnc = session.protocol.encrypt(session.sharedSecret, pinHash);

        Ctap2Cbor.MapBuilder request = Ctap2Cbor.map()
                .put(0x01, Ctap2Cbor.integer(session.protocol.version()))
                .put(0x03, session.platformCose)
                .put(0x06, Ctap2Cbor.bytes(pinHashEnc));
        if (info.pinUvAuthToken()) {
            request.put(0x02, Ctap2Cbor.integer(SUB_GET_PIN_UV_TOKEN_PERMS));
            request.put(0x09, Ctap2Cbor.integer(permissions));
            request.put(0x10, Ctap2Cbor.text(rpId));
        } else {
            request.put(0x02, Ctap2Cbor.integer(SUB_GET_PIN_TOKEN));
        }
        co.nstant.in.cbor.model.Map resp = asMap(decode(device.ctap2(CMD_CLIENT_PIN, request.build())));
        return session.protocol.decrypt(session.sharedSecret, bytesAt(resp, 2));
    }

    // --- authData parsing ---

    private static byte[] parseCredentialId(byte[] authData) {
        if (authData.length < 55 || (authData[32] & FLAG_AT) == 0) {
            throw new HardwareWalletException("makeCredential response missing attested credential data");
        }
        int credIdLen = ((authData[53] & 0xFF) << 8) | (authData[54] & 0xFF);
        if (authData.length < 55 + credIdLen) {
            throw new HardwareWalletException("makeCredential credential id truncated");
        }
        return Arrays.copyOfRange(authData, 55, 55 + credIdLen);
    }

    private static byte[] parseAssertionHmacSecret(byte[] authData) {
        if (authData.length < 37 || (authData[32] & FLAG_ED) == 0) {
            throw new HardwareWalletException("getAssertion response has no extension data");
        }
        // No attested credential data in an assertion, so extensions start at 37.
        byte[] extBytes = Arrays.copyOfRange(authData, 37, authData.length);
        co.nstant.in.cbor.model.Map ext = asMap(decode(extBytes));
        DataItem value = ext.get(new UnicodeString("hmac-secret"));
        if (!(value instanceof ByteString bs)) {
            throw new HardwareWalletException("getAssertion response missing hmac-secret output");
        }
        return bs.getBytes();
    }

    // --- COSE / EC helpers ---

    private static byte[] coseKey(byte[] x, byte[] y) {
        return Ctap2Cbor.map()
                .put(1, Ctap2Cbor.integer(2))    // kty EC2
                .put(3, Ctap2Cbor.integer(-25))  // alg ECDH-ES+HKDF-256
                .put(-1, Ctap2Cbor.integer(1))   // crv P-256
                .put(-2, Ctap2Cbor.bytes(x))
                .put(-3, Ctap2Cbor.bytes(y))
                .build();
    }

    private static byte[] coseCoordinate(co.nstant.in.cbor.model.Map cose, int key) {
        DataItem item = cose.get(new NegativeInteger(key));
        if (!(item instanceof ByteString bs)) {
            throw new HardwareWalletException("COSE key missing coordinate " + key);
        }
        return bs.getBytes();
    }

    private static ECParameterSpec p256Params() throws java.security.GeneralSecurityException {
        AlgorithmParameters params = AlgorithmParameters.getInstance("EC");
        params.init(new ECGenParameterSpec("secp256r1"));
        return params.getParameterSpec(ECParameterSpec.class);
    }

    /** Big-endian, left-padded/trimmed to exactly 32 bytes. */
    private static byte[] to32(BigInteger value) {
        byte[] raw = value.toByteArray();
        if (raw.length == 32) {
            return raw;
        }
        byte[] out = new byte[32];
        if (raw.length > 32) {
            System.arraycopy(raw, raw.length - 32, out, 0, 32); // drop leading sign byte
        } else {
            System.arraycopy(raw, 0, out, 32 - raw.length, raw.length);
        }
        return out;
    }

    // --- CBOR decode helpers ---

    private static DataItem decode(byte[] cbor) {
        try {
            List<DataItem> items = CborDecoder.decode(cbor);
            if (items.isEmpty()) {
                throw new HardwareWalletException("empty CBOR response");
            }
            return items.get(0);
        } catch (CborException e) {
            throw new HardwareWalletException("CBOR decode failed: " + e.getMessage(), e);
        }
    }

    private static co.nstant.in.cbor.model.Map asMap(DataItem item) {
        if (!(item instanceof co.nstant.in.cbor.model.Map map)) {
            throw new HardwareWalletException("expected a CBOR map");
        }
        return map;
    }

    private static DataItem at(co.nstant.in.cbor.model.Map map, int key) {
        return map.get(new UnsignedInteger(key));
    }

    private static byte[] bytesAt(co.nstant.in.cbor.model.Map map, int key) {
        DataItem item = at(map, key);
        if (!(item instanceof ByteString bs)) {
            throw new HardwareWalletException("expected byte string at key " + key);
        }
        return bs.getBytes();
    }

    private static boolean arrayContainsText(DataItem item, String value) {
        if (!(item instanceof Array array)) {
            return false;
        }
        for (DataItem element : array.getDataItems()) {
            if (element instanceof UnicodeString s && value.equals(s.getString())) {
                return true;
            }
        }
        return false;
    }

    private static boolean isTrue(DataItem item) {
        return item instanceof SimpleValue sv && sv.getSimpleValueType() == SimpleValueType.TRUE;
    }

    private static int pickPinProtocol(DataItem item) {
        int best = 1;
        if (item instanceof Array array) {
            for (DataItem element : array.getDataItems()) {
                if (element instanceof UnsignedInteger n) {
                    int v = n.getValue().intValue();
                    if (v == 2) {
                        best = 2; // prefer protocol 2
                    }
                }
            }
        }
        return best;
    }
}
