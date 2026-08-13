package com.bloxbean.cardano.yano.wallet.hardware.ledger;

import com.bloxbean.cardano.client.util.HexUtil;
import com.bloxbean.cardano.yano.wallet.core.hardware.DeviceVersion;
import com.bloxbean.cardano.yano.wallet.core.hardware.HardwareWalletException;

import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

/**
 * The Cardano Ledger application's APDU command set (ADR-034), driven over a
 * {@link LedgerTransport}. HW-M1 implements {@link #getVersion()}; the account,
 * address, and signing instructions are added in later milestones (their INS
 * codes are listed for reference).
 */
public final class LedgerCardanoApp {

    /** Class byte of the Cardano app. */
    public static final int CLA = 0xD7;

    // Instruction bytes (Cardano app). Only GET_VERSION is used in HW-M1.
    public static final int INS_GET_VERSION = 0x00;
    public static final int INS_GET_SERIAL = 0x01;
    public static final int INS_GET_EXT_PUBLIC_KEY = 0x10; // HW-M2
    public static final int INS_DERIVE_ADDRESS = 0x11;     // HW-M2
    public static final int INS_SIGN_TX = 0x21;            // HW-M3
    public static final int INS_SIGN_MSG = 0x25;           // later (CIP-8 / CIP-30)

    private final LedgerTransport transport;

    public LedgerCardanoApp(LedgerTransport transport) {
        if (transport == null) {
            throw new IllegalArgumentException("transport is required");
        }
        this.transport = transport;
    }

    /**
     * Reads the running Cardano app version. Response layout is
     * {@code major | minor | patch | flags}; {@code flags} bit 0 marks a
     * development build.
     *
     * @throws HardwareWalletException if the app is not open, the device is
     *                                 locked, or the response is malformed
     */
    public DeviceVersion getVersion() {
        ApduResponse response = transport.exchange(new ApduCommand(CLA, INS_GET_VERSION, 0x00, 0x00, new byte[0]));
        if (!response.isOk()) {
            throw new HardwareWalletException(describeStatus(response.statusWord()));
        }
        byte[] data = response.data();
        if (data.length < 3) {
            throw new HardwareWalletException("Unexpected getVersion response (" + data.length + " bytes)");
        }
        int major = data[0] & 0xFF;
        int minor = data[1] & 0xFF;
        int patch = data[2] & 0xFF;
        boolean developmentBuild = data.length >= 4 && (data[3] & 0x01) != 0;
        return new DeviceVersion(major, minor, patch, developmentBuild);
    }

    /**
     * Reads the extended public key at {@code path} (typically the account level
     * {@code 1852'/1815'/account'}). Returns the raw 64 bytes: 32-byte Ed25519
     * public key followed by the 32-byte chain code — exactly the form CCL's
     * {@code HdPublicKey.fromBytes} / {@code CIP1852.getPublicKeyFromAccountPubKey}
     * consume. The device may prompt for on-device confirmation of the export.
     *
     * <p>Single-key v7 protocol: {@code INS 0x10}, P1 {@code 0x00} (INIT), P2
     * {@code 0x00}; APDU data is the serialized path only (no "remaining keys"
     * suffix for one key).
     *
     * @throws HardwareWalletException if the app is not open, the user rejects,
     *                                 or the response is not 64 bytes
     */
    public byte[] getExtendedPublicKey(long[] path) {
        byte[] data = LedgerBip32.serialize(path);
        ApduResponse response = transport.exchange(
                new ApduCommand(CLA, INS_GET_EXT_PUBLIC_KEY, 0x00, 0x00, data));
        if (!response.isOk()) {
            throw new HardwareWalletException(describeStatus(response.statusWord()));
        }
        byte[] xpub = response.data();
        if (xpub.length != 64) {
            throw new HardwareWalletException(
                    "Unexpected extended public key length: " + xpub.length + " (want 64)");
        }
        return xpub;
    }

    // Address serialization constants.
    private static final int ADDRESS_TYPE_BASE_KEY_KEY = 0x00; // BASE_PAYMENT_KEY_STAKE_KEY
    private static final int STAKING_SOURCE_KEY_PATH = 0x22;
    private static final int P1_DERIVE_RETURN = 0x01;
    private static final int P1_DERIVE_DISPLAY = 0x02;

    /**
     * Derives a base address ({@code payment key + stake key}) and returns its
     * raw bytes ({@code header || paymentKeyHash || stakeKeyHash}) without
     * displaying it (INS 0x11, P1 0x01).
     */
    public byte[] deriveAddressBytes(int networkId, long[] spendingPath, long[] stakingPath) {
        byte[] params = serializeBaseAddressParams(networkId, spendingPath, stakingPath);
        ApduResponse response = transport.exchange(
                new ApduCommand(CLA, INS_DERIVE_ADDRESS, P1_DERIVE_RETURN, 0x00, params));
        if (!response.isOk()) {
            throw new HardwareWalletException(describeStatus(response.statusWord()));
        }
        if (response.data().length == 0) {
            throw new HardwareWalletException("Device returned an empty address");
        }
        return response.data();
    }

    /**
     * Shows the base address on the device screen for the user to verify (INS
     * 0x11, P1 0x02). The display variant returns no data — success means the
     * user approved; a non-OK status word means they rejected or an error
     * occurred.
     */
    public void displayAddress(int networkId, long[] spendingPath, long[] stakingPath) {
        byte[] params = serializeBaseAddressParams(networkId, spendingPath, stakingPath);
        ApduResponse response = transport.exchange(
                new ApduCommand(CLA, INS_DERIVE_ADDRESS, P1_DERIVE_DISPLAY, 0x00, params));
        if (!response.isOk()) {
            throw new HardwareWalletException(describeStatus(response.statusWord()));
        }
    }

    /**
     * Serializes {@code deriveAddress} parameters for a base key/key address:
     * {@code addressType(1) || networkId(1) || spendingPath || 0x22 || stakingPath}.
     * Package-visible and pure so it can be unit-tested without a device.
     */
    static byte[] serializeBaseAddressParams(int networkId, long[] spendingPath, long[] stakingPath) {
        byte[] spending = LedgerBip32.serialize(spendingPath);
        byte[] staking = LedgerBip32.serialize(stakingPath);
        byte[] out = new byte[2 + spending.length + 1 + staking.length];
        int at = 0;
        out[at++] = (byte) ADDRESS_TYPE_BASE_KEY_KEY;
        out[at++] = (byte) (networkId & 0xFF);
        System.arraycopy(spending, 0, out, at, spending.length);
        at += spending.length;
        out[at++] = (byte) STAKING_SOURCE_KEY_PATH;
        System.arraycopy(staking, 0, out, at, staking.length);
        return out;
    }

    // --- signTransaction (INS 0x21) ---
    private static final int STAGE_INIT = 0x01;
    private static final int STAGE_INPUTS = 0x02;
    private static final int STAGE_OUTPUTS = 0x03;
    private static final int STAGE_FEE = 0x04;
    private static final int STAGE_TTL = 0x05;
    private static final int STAGE_CERTIFICATES = 0x06;
    private static final int STAGE_WITHDRAWALS = 0x07;
    private static final int STAGE_VOTING_PROCEDURES = 0x13;
    private static final int STAGE_AUX_DATA = 0x08;
    private static final int STAGE_CONFIRM = 0x0a;
    private static final int STAGE_WITNESSES = 0x0f;
    // Plutus / full-body stages (ADR-035 M4).
    private static final int STAGE_VALIDITY_INTERVAL_START = 0x09;
    private static final int STAGE_MINT = 0x0b;
    private static final int STAGE_SCRIPT_DATA_HASH = 0x0c;
    private static final int STAGE_COLLATERAL_INPUTS = 0x0d;
    private static final int STAGE_REQUIRED_SIGNERS = 0x0e;
    private static final int STAGE_TOTAL_COLLATERAL = 0x10;
    private static final int STAGE_REFERENCE_INPUTS = 0x11;
    private static final int STAGE_COLLATERAL_OUTPUT = 0x12;
    private static final int STAGE_TREASURY = 0x15;
    private static final int STAGE_DONATION = 0x16;
    /** Auxiliary-data sub-type: a bare metadata hash (CIP-20 message, etc.). */
    private static final int AUX_DATA_ARBITRARY_HASH = 0x00;
    private static final int OUTPUT_P2_BASIC_DATA = 0x30;
    private static final int OUTPUT_P2_ASSET_GROUP = 0x31;
    private static final int OUTPUT_P2_TOKEN = 0x32;
    private static final int OUTPUT_P2_CONFIRM = 0x33;
    // Output datum / reference script stream in chunks (Plutus outputs).
    private static final int OUTPUT_P2_DATUM = 0x34;
    private static final int OUTPUT_P2_DATUM_CHUNK = 0x35;
    private static final int OUTPUT_P2_SCRIPT = 0x36;
    private static final int OUTPUT_P2_SCRIPT_CHUNK = 0x37;
    // The MINT stage mirrors OUTPUTS: a count, then asset groups + tokens.
    private static final int MINT_P2_INIT = 0x30;
    private static final int MINT_P2_ASSET_GROUP = 0x31;
    private static final int MINT_P2_TOKEN = 0x32;
    private static final int MINT_P2_CONFIRM = 0x33;
    /** Required-signer discriminators. */
    public static final int REQUIRED_SIGNER_PATH = 0x00;
    public static final int REQUIRED_SIGNER_HASH = 0x01;
    // Certificate types (classic; Conway variants start at 7).
    public static final int CERT_STAKE_REGISTRATION = 0x00;
    public static final int CERT_STAKE_DEREGISTRATION = 0x01;
    public static final int CERT_STAKE_DELEGATION = 0x02;
    /**
     * Conway registration/deregistration. Distinct device certificate types from
     * the legacy 0/1 because they carry an explicit deposit, and they happen to
     * match the CBOR certificate types of the same name.
     */
    public static final int CERT_STAKE_REGISTRATION_CONWAY = 0x07;
    public static final int CERT_STAKE_DEREGISTRATION_CONWAY = 0x08;
    /** Conway vote-delegation certificate (CIP-1694): delegate voting power to a DRep. */
    public static final int CERT_VOTE_DELEGATION = 0x09;
    /** Credential encoded as a BIP32 key path. */
    /**
     * Credential wire types, as ledgerjs {@code utils/serialize.ts#serializeCredential}
     * writes them — the byte IS the {@code CredentialType} enum value.
     *
     * <p>Verified against the reference rather than inferred: the v8 interaction
     * path has its OWN {@code CredentialWireType} with different numbers
     * ({@code KEY_HASH=0, SCRIPT_HASH=1, KEY_PATH=2}), and the DRep target uses a
     * third enum again ({@code DREP_KEY_HASH=0, DREP_SCRIPT_HASH=1}). Three enums,
     * three orderings, one byte on the wire — do not pattern-match between them.
     */
    private static final int CREDENTIAL_KEY_PATH = 0x00;
    public static final int CREDENTIAL_SCRIPT_HASH = 0x01;
    public static final int CREDENTIAL_KEY_HASH = 0x02;
    // DRep target discriminators used by serializeDRep.
    public static final int DREP_KEY_HASH = 0x00;
    public static final int DREP_SCRIPT_HASH = 0x01;
    public static final int DREP_ABSTAIN = 0x02;
    public static final int DREP_NO_CONFIDENCE = 0x03;
    /** Conway DRep-registration / deregistration certificates (CIP-1694). */
    public static final int CERT_DREP_REGISTRATION = 0x10;   // 16
    public static final int CERT_DREP_DEREGISTRATION = 0x11; // 17
    // Voter discriminators used by serializeVoter (voting procedures).
    public static final int VOTER_DREP_KEY_HASH = 0x02;
    public static final int VOTER_DREP_KEY_PATH = 102;
    // Vote choices.
    public static final int VOTE_NO = 0x00;
    public static final int VOTE_YES = 0x01;
    public static final int VOTE_ABSTAIN = 0x02;
    /**
     * Signing modes. ORDINARY covers plain payments and key-path certs/withdrawals.
     * PLUTUS is required once a tx carries script evaluation (collateral, script
     * data hash, required signers, …); its only device-side restriction is that it
     * must not contain a pool-registration certificate.
     */
    public static final int SIGNING_MODE_ORDINARY = 0x03;
    public static final int SIGNING_MODE_PLUTUS = 0x07;
    private static final int OUTPUT_DESTINATION_THIRD_PARTY = 0x01;
    /** Legacy array output format {@code [address, value]} (Babbage map is 0x01). */
    private static final int OUTPUT_FORMAT_ARRAY_LEGACY = 0x00;
    /** Conway tx-options bit 0: encode inputs/certs as CBOR-tagged (258) sets. */
    private static final long OPTION_TAG_CBOR_SETS = 1L;

    /**
     * Signs an ordinary transaction (ADR-034): payments, native-asset outputs,
     * stake certificates, and reward withdrawals. Streams the tx to the device
     * (init → [aux] → inputs → outputs[+assets] → fee → ttl → certs → withdrawals
     * → confirm), the user approves on-screen, and the device returns the tx id
     * plus one Ed25519 witness per {@code signingPaths} entry. The returned
     * {@code txHashHex} MUST equal the host's blake2b-256 of the tx body — that
     * equality is the correctness gate.
     *
     * @param signingPaths  BIP32 paths to witness (payment for inputs, stake for
     *                      certs/withdrawals)
     * @param certificates  pre-serialized certificate payloads (see the cert* helpers)
     * @param withdrawals   pre-serialized withdrawal payloads (see {@link #withdrawal})
     * @param tagCborSets   whether the device tags sets (Conway); must match host CBOR
     * @param outputFormat  Ledger output format byte (0 = legacy array)
     */
    public LedgerSignedTx signTransaction(int networkId, long protocolMagic,
                                          List<LedgerTxInput> inputs, List<LedgerTxOutput> outputs,
                                          BigInteger fee, long ttl, List<long[]> signingPaths,
                                          List<byte[]> certificates, List<byte[]> withdrawals,
                                          boolean tagCborSets, int outputFormat, byte[] auxiliaryDataHash) {
        return signTransaction(networkId, protocolMagic, inputs, outputs, fee, ttl, signingPaths,
                certificates, withdrawals, List.of(), tagCborSets, outputFormat, auxiliaryDataHash);
    }

    /**
     * Streaming sign with Conway voting procedures. {@code votingProcedures} are
     * pre-serialized voter-vote payloads (see {@link #votingProcedureAsDRep}); they
     * stream after withdrawals, and the DRep witness path must appear in
     * {@code signingPaths}. Everything else matches the base overload.
     */
    public LedgerSignedTx signTransaction(int networkId, long protocolMagic,
                                          List<LedgerTxInput> inputs, List<LedgerTxOutput> outputs,
                                          BigInteger fee, long ttl, List<long[]> signingPaths,
                                          List<byte[]> certificates, List<byte[]> withdrawals,
                                          List<byte[]> votingProcedures,
                                          boolean tagCborSets, int outputFormat, byte[] auxiliaryDataHash) {
        return signTransaction(LedgerSignRequest.builder()
                .networkId(networkId).protocolMagic(protocolMagic)
                .inputs(inputs).outputs(outputs).fee(fee).ttl(ttl)
                .certificates(certificates).withdrawals(withdrawals).votingProcedures(votingProcedures)
                .auxiliaryDataHash(auxiliaryDataHash)
                .tagCborSets(tagCborSets).outputFormat(outputFormat)
                .signingPaths(signingPaths)
                .build());
    }

    /**
     * Streams a full transaction body — including the Plutus fields (mint, script
     * data hash, collateral, required signers, reference inputs, treasury/donation)
     * — in the exact order the device's state machine expects, then confirms and
     * collects one witness per signing path.
     *
     * <p>The returned {@code txHashHex} MUST equal the host's blake2b-256 of the tx
     * body. That equality is the correctness gate: if the request didn't reproduce
     * the host's CBOR exactly, the hashes differ and the caller must not submit.
     */
    public LedgerSignedTx signTransaction(LedgerSignRequest r) {
        exchangeStage(STAGE_INIT, 0x00, serializeTxInit(r));

        // Modern apps take auxiliary data before the body.
        if (r.auxiliaryDataHash() != null) {
            if (r.auxiliaryDataHash().length != 32) {
                throw new HardwareWalletException("auxiliaryDataHash must be 32 bytes");
            }
            byte[] auxPayload = new byte[1 + 32];
            auxPayload[0] = (byte) AUX_DATA_ARBITRARY_HASH;
            System.arraycopy(r.auxiliaryDataHash(), 0, auxPayload, 1, 32);
            exchangeStage(STAGE_AUX_DATA, 0x00, auxPayload);
        }

        for (LedgerTxInput input : r.inputs()) {
            exchangeStage(STAGE_INPUTS, 0x00, serializeTxInput(input));
        }
        for (LedgerTxOutput output : r.outputs()) {
            streamOutput(STAGE_OUTPUTS, output, r.outputFormat());
        }
        exchangeStage(STAGE_FEE, 0x00, serializeCoin(r.fee()));
        if (r.ttl() != null) {
            exchangeStage(STAGE_TTL, 0x00, uint64(BigInteger.valueOf(r.ttl())));
        }
        for (byte[] cert : r.certificates()) {
            exchangeStage(STAGE_CERTIFICATES, 0x00, cert);
        }
        for (byte[] wd : r.withdrawals()) {
            exchangeStage(STAGE_WITHDRAWALS, 0x00, wd);
        }
        if (r.validityIntervalStart() != null) {
            exchangeStage(STAGE_VALIDITY_INTERVAL_START, 0x00,
                    uint64(BigInteger.valueOf(r.validityIntervalStart())));
        }
        if (!r.mint().isEmpty()) {
            streamMint(r.mint());
        }
        if (r.scriptDataHash() != null) {
            if (r.scriptDataHash().length != 32) {
                throw new HardwareWalletException("scriptDataHash must be 32 bytes");
            }
            exchangeStage(STAGE_SCRIPT_DATA_HASH, 0x00, r.scriptDataHash());
        }
        for (LedgerTxInput input : r.collateralInputs()) {
            exchangeStage(STAGE_COLLATERAL_INPUTS, 0x00, serializeTxInput(input));
        }
        for (byte[] signer : r.requiredSigners()) {
            exchangeStage(STAGE_REQUIRED_SIGNERS, 0x00, signer);
        }
        if (r.collateralOutput() != null) {
            streamOutput(STAGE_COLLATERAL_OUTPUT, r.collateralOutput(), r.outputFormat());
        }
        if (r.totalCollateral() != null) {
            exchangeStage(STAGE_TOTAL_COLLATERAL, 0x00, serializeCoin(r.totalCollateral()));
        }
        for (LedgerTxInput input : r.referenceInputs()) {
            exchangeStage(STAGE_REFERENCE_INPUTS, 0x00, serializeTxInput(input));
        }
        for (byte[] vote : r.votingProcedures()) {
            exchangeStage(STAGE_VOTING_PROCEDURES, 0x00, vote);
        }
        if (r.treasury() != null) {
            exchangeStage(STAGE_TREASURY, 0x00, serializeCoin(r.treasury()));
        }
        if (r.donation() != null) {
            exchangeStage(STAGE_DONATION, 0x00, serializeCoin(r.donation()));
        }

        byte[] txHash = exchangeStage(STAGE_CONFIRM, 0x00, new byte[0]);
        if (txHash.length != 32) {
            throw new HardwareWalletException("Unexpected tx hash length: " + txHash.length);
        }
        List<LedgerWitness> witnesses = new ArrayList<>();
        for (long[] path : r.signingPaths()) {
            byte[] signature = exchangeStage(STAGE_WITNESSES, 0x00, LedgerBip32.serialize(path));
            if (signature.length != 64) {
                throw new HardwareWalletException("Unexpected witness signature length: " + signature.length);
            }
            witnesses.add(new LedgerWitness(path, signature));
        }
        return new LedgerSignedTx(HexUtil.encodeHexString(txHash), witnesses);
    }

    /**
     * An output (or the collateral output): basic params, asset groups/tokens,
     * then — for Plutus outputs — the datum and reference script (first message
     * carries up to {@link #OUTPUT_CHUNK_SIZE} bytes, the rest follow as chunk
     * messages), then confirm.
     */
    private void streamOutput(int stage, LedgerTxOutput output, int defaultFormat) {
        int outputFormat = output.format() != null ? output.format() : defaultFormat;
        exchangeStage(stage, OUTPUT_P2_BASIC_DATA, serializeTxOutputBasic(output, outputFormat));
        for (LedgerAssetGroup group : output.assets()) {
            exchangeStage(stage, OUTPUT_P2_ASSET_GROUP, serializeAssetGroup(group));
            for (LedgerToken token : group.tokens()) {
                exchangeStage(stage, OUTPUT_P2_TOKEN, serializeToken(token));
            }
        }
        if (output.datum() != null) {
            exchangeStage(stage, OUTPUT_P2_DATUM, serializeOutputDatum(output.datum()));
            if (output.datum().type() == LedgerDatum.TYPE_INLINE) {
                streamRemainingChunks(stage, OUTPUT_P2_DATUM_CHUNK, output.datum().bytes());
            }
        }
        if (output.referenceScript() != null) {
            exchangeStage(stage, OUTPUT_P2_SCRIPT, serializeOutputRefScript(output.referenceScript()));
            streamRemainingChunks(stage, OUTPUT_P2_SCRIPT_CHUNK, output.referenceScript());
        }
        exchangeStage(stage, OUTPUT_P2_CONFIRM, new byte[0]);
    }

    /** Sends everything after the first chunk (which rode in the DATUM/SCRIPT message). */
    private void streamRemainingChunks(int stage, int p2, byte[] data) {
        for (int from = OUTPUT_CHUNK_SIZE; from < data.length; from += OUTPUT_CHUNK_SIZE) {
            int length = Math.min(OUTPUT_CHUNK_SIZE, data.length - from);
            exchangeStage(stage, p2, serializeChunk(data, from, length));
        }
    }

    /** Mint mirrors OUTPUTS but its token amounts are signed (a burn is negative). */
    private void streamMint(List<LedgerAssetGroup> mint) {
        ByteArrayOutputStream init = new ByteArrayOutputStream();
        writeUint32(init, mint.size());
        exchangeStage(STAGE_MINT, MINT_P2_INIT, init.toByteArray());
        for (LedgerAssetGroup group : mint) {
            exchangeStage(STAGE_MINT, MINT_P2_ASSET_GROUP, serializeAssetGroup(group));
            for (LedgerToken token : group.tokens()) {
                exchangeStage(STAGE_MINT, MINT_P2_TOKEN, serializeMintToken(token));
            }
        }
        exchangeStage(STAGE_MINT, MINT_P2_CONFIRM, new byte[0]);
    }

    /**
     * Stake-registration certificate payload:
     * {@code type(0) || credentialType(0=key-path) || stakePath}. The Cardano app
     * (multisig-capable, v7) serializes the stake credential via serializeCredential,
     * which prefixes the credential-type byte before the path.
     */
    public static byte[] certStakeRegistration(long[] stakePath) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(CERT_STAKE_REGISTRATION);
        out.write(CREDENTIAL_KEY_PATH);
        out.writeBytes(LedgerBip32.serialize(stakePath));
        return out.toByteArray();
    }

    /**
     * Stake-delegation certificate payload:
     * {@code type(2) || credentialType(0=key-path) || stakePath || poolKeyHash(28)}.
     */
    public static byte[] certStakeDelegation(long[] stakePath, byte[] poolKeyHash) {
        if (poolKeyHash == null || poolKeyHash.length != 28) {
            throw new IllegalArgumentException("poolKeyHash must be 28 bytes");
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(CERT_STAKE_DELEGATION);
        out.write(CREDENTIAL_KEY_PATH);
        out.writeBytes(LedgerBip32.serialize(stakePath));
        out.writeBytes(poolKeyHash);
        return out.toByteArray();
    }

    /**
     * Vote-delegation certificate payload (CIP-1694):
     * {@code type(9) || credentialType(0) || stakePath || serializeDRep(drep)}.
     * The DRep target is {@code drepType} followed by a 28-byte hash for
     * key-hash/script-hash targets, or nothing for abstain/no-confidence.
     */
    public static byte[] certVoteDelegation(long[] stakePath, int drepType, byte[] drepHash) {
        boolean needsHash = drepType == DREP_KEY_HASH || drepType == DREP_SCRIPT_HASH;
        if (needsHash && (drepHash == null || drepHash.length != 28)) {
            throw new IllegalArgumentException("DRep hash must be 28 bytes for key-hash/script-hash targets");
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(CERT_VOTE_DELEGATION);
        out.write(CREDENTIAL_KEY_PATH);
        out.writeBytes(LedgerBip32.serialize(stakePath));
        out.write(drepType);
        if (needsHash) {
            out.writeBytes(drepHash);
        }
        return out.toByteArray();
    }

    /**
     * DRep-registration certificate (CIP-1694):
     * {@code type(16) || credentialType(0) || drepPath || coin(deposit) || anchor}.
     * The DRep credential is this account's DRep key ({@code …/3/0}).
     */
    public static byte[] certDRepRegistration(long[] drepPath, BigInteger deposit,
                                              String anchorUrl, byte[] anchorHash) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(CERT_DREP_REGISTRATION);
        out.write(CREDENTIAL_KEY_PATH);
        out.writeBytes(LedgerBip32.serialize(drepPath));
        out.writeBytes(serializeCoin(deposit));
        out.writeBytes(serializeAnchor(anchorUrl, anchorHash));
        return out.toByteArray();
    }

    /**
     * DRep-deregistration certificate (reclaims the deposit):
     * {@code type(17) || credentialType(0) || drepPath || coin(deposit)}.
     */
    public static byte[] certDRepDeregistration(long[] drepPath, BigInteger deposit) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(CERT_DREP_DEREGISTRATION);
        out.write(CREDENTIAL_KEY_PATH);
        out.writeBytes(LedgerBip32.serialize(drepPath));
        out.writeBytes(serializeCoin(deposit));
        return out.toByteArray();
    }

    /**
     * A single DRep voting procedure (one vote per message):
     * {@code voter(102 || drepPath) || govActionTxHash(32) || govActionIndex(4) ||
     * vote(1) || anchor}. {@code vote} is {@link #VOTE_NO}/{@link #VOTE_YES}/{@link #VOTE_ABSTAIN}.
     */
    public static byte[] votingProcedureAsDRep(long[] drepPath, byte[] govActionTxHash,
                                               long govActionIndex, int vote,
                                               String anchorUrl, byte[] anchorHash) {
        if (govActionTxHash == null || govActionTxHash.length != 32) {
            throw new IllegalArgumentException("govActionTxHash must be 32 bytes");
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(VOTER_DREP_KEY_PATH);
        out.writeBytes(LedgerBip32.serialize(drepPath));
        out.writeBytes(govActionTxHash);
        writeUint32(out, govActionIndex);
        out.write(vote);
        out.writeBytes(serializeAnchor(anchorUrl, anchorHash));
        return out.toByteArray();
    }

    /** Anchor: {@code optionFlag(1=absent/2=present)}, and when present {@code hash(32) || url(ascii)}. */
    static byte[] serializeAnchor(String url, byte[] hash) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        if (url == null || url.isEmpty()) {
            out.write(optionFlag(false));
            return out.toByteArray();
        }
        if (hash == null || hash.length != 32) {
            throw new IllegalArgumentException("Anchor hash must be 32 bytes when an anchor url is present");
        }
        out.write(optionFlag(true));
        out.writeBytes(hash);
        out.writeBytes(url.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        return out.toByteArray();
    }

    /**
     * A stake credential owned by someone else — a script, or a key we do not
     * derive. {@code hash} is the 28-byte script or key hash.
     *
     * <p>This is what a dApp's certificates and withdrawals carry. The wallet's own
     * staking flows use the key-path form instead, because there the credential IS
     * a path we can derive and the device shows the user a path it recognises.
     */
    public static byte[] credentialFromHash(int credentialType, byte[] hash) {
        if (credentialType != CREDENTIAL_SCRIPT_HASH && credentialType != CREDENTIAL_KEY_HASH) {
            throw new IllegalArgumentException("Not a hash credential type: " + credentialType);
        }
        if (hash == null || hash.length != 28) {
            throw new IllegalArgumentException("credential hash must be 28 bytes");
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(credentialType);
        out.writeBytes(hash);
        return out.toByteArray();
    }

    /** The key-path credential form, for credentials this wallet derives. */
    public static byte[] credentialFromPath(long[] stakePath) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(CREDENTIAL_KEY_PATH);
        out.writeBytes(LedgerBip32.serialize(stakePath));
        return out.toByteArray();
    }

    /**
     * Conway stake registration/deregistration over an arbitrary credential:
     * {@code type(7|8) || credential || coin(deposit)}.
     */
    public static byte[] certConwayRegistration(boolean register, byte[] credential,
                                                BigInteger deposit) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(register ? CERT_STAKE_REGISTRATION_CONWAY : CERT_STAKE_DEREGISTRATION_CONWAY);
        out.writeBytes(credential);
        out.writeBytes(uint64(deposit));
        return out.toByteArray();
    }

    /** Legacy stake registration/deregistration: {@code type(0|1) || credential}. */
    public static byte[] certLegacyRegistration(boolean register, byte[] credential) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(register ? CERT_STAKE_REGISTRATION : CERT_STAKE_DEREGISTRATION);
        out.writeBytes(credential);
        return out.toByteArray();
    }

    /** Stake delegation over an arbitrary credential: {@code type(2) || credential || poolKeyHash(28)}. */
    public static byte[] certDelegation(byte[] credential, byte[] poolKeyHash) {
        if (poolKeyHash == null || poolKeyHash.length != 28) {
            throw new IllegalArgumentException("poolKeyHash must be 28 bytes");
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(CERT_STAKE_DELEGATION);
        out.writeBytes(credential);
        out.writeBytes(poolKeyHash);
        return out.toByteArray();
    }

    /** Withdrawal over an arbitrary credential: {@code coin(8) || credential}. */
    public static byte[] withdrawalFromCredential(BigInteger amount, byte[] credential) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(uint64(amount));
        out.writeBytes(credential);
        return out.toByteArray();
    }

    /** Withdrawal payload: {@code coin(8) || credentialType(0=key-path) || stakePath}. */
    public static byte[] withdrawal(BigInteger amount, long[] stakePath) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(uint64(amount));
        out.write(CREDENTIAL_KEY_PATH);
        out.writeBytes(LedgerBip32.serialize(stakePath));
        return out.toByteArray();
    }

    /** Asset group: {@code policyId(28) || tokenCount(4)}. */
    static byte[] serializeAssetGroup(LedgerAssetGroup group) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(group.policyId());
        writeUint32(out, group.tokens().size());
        return out.toByteArray();
    }

    /** Output token: {@code assetNameLen(4) || assetName || amount(8, unsigned)}. */
    static byte[] serializeToken(LedgerToken token) {
        if (token.amount().signum() < 0) {
            throw new IllegalArgumentException(
                    "output token amount must be >= 0 (a negative amount is a mint burn)");
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeUint32(out, token.assetName().length);
        out.writeBytes(token.assetName());
        out.writeBytes(uint64(token.amount()));
        return out.toByteArray();
    }

    /**
     * Mint token: same layout as {@link #serializeToken} but the amount is a
     * <em>signed</em> int64 — minting is positive, burning negative.
     */
    static byte[] serializeMintToken(LedgerToken token) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeUint32(out, token.assetName().length);
        out.writeBytes(token.assetName());
        out.writeBytes(int64(token.amount()));
        return out.toByteArray();
    }

    /** Required signer the device holds a key for: {@code type(0) || path}. */
    public static byte[] requiredSignerPath(long[] path) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(REQUIRED_SIGNER_PATH);
        out.writeBytes(LedgerBip32.serialize(path));
        return out.toByteArray();
    }

    /** Required signer given only as a key hash: {@code type(1) || hash(28)}. */
    public static byte[] requiredSignerHash(byte[] keyHash) {
        if (keyHash == null || keyHash.length != 28) {
            throw new IllegalArgumentException("required signer key hash must be 28 bytes");
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(REQUIRED_SIGNER_HASH);
        out.writeBytes(keyHash);
        return out.toByteArray();
    }

    /** Signed 8-byte big-endian (two's complement) — mint amounts may be negative. */
    static byte[] int64(BigInteger value) {
        long v = value.longValueExact();
        byte[] out = new byte[8];
        for (int i = 7; i >= 0; i--) {
            out[i] = (byte) (v & 0xFF);
            v >>= 8;
        }
        return out;
    }

    private byte[] exchangeStage(int p1, int p2, byte[] data) {
        ApduResponse response = transport.exchange(new ApduCommand(CLA, INS_SIGN_TX, p1, p2, data));
        if (!response.isOk()) {
            throw new HardwareWalletException(describeStatus(response.statusWord()));
        }
        return response.data();
    }

    /** INIT payload for an ordinary tx with no certs/withdrawals/mint/collateral (Conway-aware app). */
    /** Legacy ordinary-payment shape; delegates to the full request form. */
    static byte[] serializeTxInit(int networkId, long protocolMagic, int numInputs, int numOutputs,
                                  int numCerts, int numWithdrawals, int numVotingProcedures,
                                  int numWitnesses, boolean tagCborSets, boolean auxiliaryDataPresent) {
        return serializeTxInit(LedgerSignRequest.builder()
                .networkId(networkId).protocolMagic(protocolMagic)
                .inputs(repeat(numInputs, new LedgerTxInput("00".repeat(32), 0)))
                .outputs(repeat(numOutputs, new LedgerTxOutput(new byte[]{0}, BigInteger.ZERO)))
                .certificates(repeat(numCerts, new byte[0]))
                .withdrawals(repeat(numWithdrawals, new byte[0]))
                .votingProcedures(repeat(numVotingProcedures, new byte[0]))
                .signingPaths(repeat(numWitnesses, new long[0]))
                .ttl(1L)
                .auxiliaryDataHash(auxiliaryDataPresent ? new byte[32] : null)
                .tagCborSets(tagCborSets)
                .build());
    }

    private static <T> List<T> repeat(int count, T value) {
        List<T> list = new ArrayList<>(Math.max(0, count));
        for (int i = 0; i < count; i++) {
            list.add(value);
        }
        return list;
    }

    /**
     * The INIT message: tx options, network, the presence flag for every optional
     * body field, the signing mode, and a count for every repeated field. The
     * device uses these to know exactly what to expect and how to re-encode.
     */
    static byte[] serializeTxInit(LedgerSignRequest r) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        // Conway tx-options: an 8-byte (uint64 big-endian) bitfield, not a 1-byte
        // flag; 0 when no options are set. TAG_CBOR_SETS is bit 0.
        out.writeBytes(uint64(BigInteger.valueOf(r.tagCborSets() ? OPTION_TAG_CBOR_SETS : 0L)));
        out.write(r.networkId() & 0xFF);
        writeUint32(out, r.protocolMagic());
        out.write(optionFlag(r.ttl() != null));
        out.write(optionFlag(r.auxiliaryDataHash() != null));
        out.write(optionFlag(r.validityIntervalStart() != null));
        out.write(optionFlag(!r.mint().isEmpty()));
        out.write(optionFlag(r.scriptDataHash() != null));
        out.write(optionFlag(false));                       // include network id
        out.write(optionFlag(r.collateralOutput() != null));
        out.write(optionFlag(r.totalCollateral() != null));
        out.write(optionFlag(r.treasury() != null));
        out.write(optionFlag(r.donation() != null));
        out.write(r.signingMode());
        writeUint32(out, r.inputs().size());
        writeUint32(out, r.outputs().size());
        writeUint32(out, r.certificates().size());
        writeUint32(out, r.withdrawals().size());
        writeUint32(out, r.collateralInputs().size());
        writeUint32(out, r.requiredSigners().size());
        writeUint32(out, r.referenceInputs().size());
        writeUint32(out, r.votingProcedures().size());
        writeUint32(out, r.signingPaths().size());
        return out.toByteArray();
    }

    /** {@code txHash(32) || outputIndex(4, big-endian)}. */
    static byte[] serializeTxInput(LedgerTxInput input) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(HexUtil.decodeHexString(input.txHashHex()));
        writeUint32(out, input.index());
        return out.toByteArray();
    }

    /**
     * Basic output params for a third-party output:
     * {@code format(1) || destinationType(1) || addressLen(4) || address ||
     * coin(8) || assetGroups(4) || datumFlag(1) || refScriptFlag(1)}. Asset
     * groups/tokens — and, when flagged, the datum and reference script — follow
     * as separate OUTPUTS sub-messages.
     */
    static byte[] serializeTxOutputBasic(LedgerTxOutput output, int outputFormat) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(outputFormat & 0xFF);
        out.write(OUTPUT_DESTINATION_THIRD_PARTY);
        writeUint32(out, output.addressBytes().length);
        out.writeBytes(output.addressBytes());
        out.writeBytes(serializeCoin(output.coin()));
        writeUint32(out, output.assets().size());
        out.write(optionFlag(output.datum() != null));
        out.write(optionFlag(output.referenceScript() != null));
        return out.toByteArray();
    }

    /** Inline datums and reference scripts stream to the device in chunks of this many bytes. */
    static final int OUTPUT_CHUNK_SIZE = 240;

    /**
     * The first DATUM message: {@code type(1) || hash(32)} for a datum hash, or
     * {@code type(1) || totalSize(4) || chunkSize(4) || firstChunk} for an inline
     * datum (remaining bytes follow as DATUM_CHUNK messages).
     */
    static byte[] serializeOutputDatum(LedgerDatum datum) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(datum.type());
        if (datum.type() == LedgerDatum.TYPE_HASH) {
            out.writeBytes(datum.bytes());
            return out.toByteArray();
        }
        byte[] bytes = datum.bytes();
        int firstChunk = Math.min(bytes.length, OUTPUT_CHUNK_SIZE);
        writeUint32(out, bytes.length);
        writeUint32(out, firstChunk);
        out.write(bytes, 0, firstChunk);
        return out.toByteArray();
    }

    /**
     * The first SCRIPT message: {@code totalSize(4) || chunkSize(4) || firstChunk}
     * (no type byte; remaining bytes follow as SCRIPT_CHUNK messages).
     */
    static byte[] serializeOutputRefScript(byte[] script) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int firstChunk = Math.min(script.length, OUTPUT_CHUNK_SIZE);
        writeUint32(out, script.length);
        writeUint32(out, firstChunk);
        out.write(script, 0, firstChunk);
        return out.toByteArray();
    }

    /** A follow-up chunk message: {@code chunkSize(4) || chunk}. */
    static byte[] serializeChunk(byte[] data, int from, int length) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeUint32(out, length);
        out.write(data, from, length);
        return out.toByteArray();
    }

    static byte[] serializeCoin(BigInteger coin) {
        return uint64(coin);
    }

    private static int optionFlag(boolean included) {
        return included ? 2 : 1;
    }

    private static void writeUint32(ByteArrayOutputStream out, long value) {
        out.write((int) ((value >> 24) & 0xFF));
        out.write((int) ((value >> 16) & 0xFF));
        out.write((int) ((value >> 8) & 0xFF));
        out.write((int) (value & 0xFF));
    }

    private static byte[] uint64(BigInteger value) {
        byte[] out = new byte[8];
        BigInteger v = value;
        for (int i = 7; i >= 0; i--) {
            out[i] = v.and(BigInteger.valueOf(0xFF)).byteValue();
            v = v.shiftRight(8);
        }
        return out;
    }

    /**
     * Maps Cardano-app status words to actionable messages.
     *
     * <p>The 0x6Exx range is the app's own, and the distinction inside it is the
     * one that matters when something goes wrong: whether the USER declined,
     * whether the device's policy declined, or whether the host sent something the
     * device could not parse. Those are three different bugs, and reporting them
     * all as a raw hex code — as this did — costs a debugging round every time.
     * Values from ledgerjs {@code errors/deviceStatusError.ts}.
     */
    static String describeStatus(int statusWord) {
        return switch (statusWord) {
            case 0x6E00, 0x6D00 -> "Open the Cardano app on your Ledger and try again";
            case 0x5515, 0x6E11 -> "Unlock your Ledger and try again";
            case 0x6982 -> "Device rejected the request (security status not satisfied)";
            case 0x6985, 0x5001, 0x6E09 -> "Request was rejected on the device";
            case 0x6E10 -> "The Ledger's policy refused this transaction — it may contain something "
                    + "the device will not sign without different settings";
            case 0x6E07 -> "The Ledger could not read part of this transaction (invalid data). This "
                    + "is a wallet-side translation problem, not something you can fix on the device "
                    + "— please report the transaction.";
            case 0x6E08 -> "The Ledger rejected a derivation path in this transaction";
            case 0x6E12 -> "This transaction uses an address type the Ledger does not support";
            case 0x6E01 -> "The Ledger rejected the request header (malformed APDU)";
            case 0x6E04 -> "The Ledger is still completing a previous call — try again";
            default -> "Device returned error status 0x" + String.format("%04x", statusWord & 0xFFFF);
        };
    }
}
