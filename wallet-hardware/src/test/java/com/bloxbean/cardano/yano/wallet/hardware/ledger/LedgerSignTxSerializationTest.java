package com.bloxbean.cardano.yano.wallet.hardware.ledger;

import com.bloxbean.cardano.client.util.HexUtil;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pure serialization checks for the Ledger signTransaction stages (ADR-034,
 * HW-M3). These validate that the byte encoding matches the intended spec layout
 * without a device; the on-device tx-hash equality (probe) validates that the
 * layout matches the app's expectation and the host's canonical CBOR.
 */
class LedgerSignTxSerializationTest {

    @Test
    void coin_isBigEndianUint64() {
        assertThat(HexUtil.encodeHexString(LedgerCardanoApp.serializeCoin(BigInteger.valueOf(2_000_000))))
                .isEqualTo("00000000001e8480");
    }

    @Test
    void txInput_isTxHashThenIndex() {
        String txHash = "3b40265111d8bb3c3c608d95b3a0bf83461ace32d79336579a1939b3aad1c0b7";
        assertThat(HexUtil.encodeHexString(LedgerCardanoApp.serializeTxInput(new LedgerTxInput(txHash, 0))))
                .isEqualTo(txHash + "00000000");
    }

    @Test
    void txOutputBasic_thirdPartyAdaOnly_layout() {
        LedgerTxOutput output = new LedgerTxOutput(HexUtil.decodeHexString("aabbcc"), BigInteger.valueOf(2_000_000));
        String expected =
                "00"        // legacy array format
                        + "01"      // third-party destination
                        + "00000003" // address length
                        + "aabbcc"   // address
                        + "00000000001e8480" // 2_000_000 lovelace
                        + "00000000" // token bundle count
                        + "01"       // datum: NO
                        + "01";      // reference script: NO
        assertThat(HexUtil.encodeHexString(LedgerCardanoApp.serializeTxOutputBasic(output, 0)))
                .isEqualTo(expected);
    }

    @Test
    void txInit_ordinaryAdaPayment_layout() {
        // networkId 0, protocolMagic 1, 1 input, 1 output, 1 witness, no set tags.
        String expected =
                "0000000000000000" // tx-options: 8-byte uint64, no options set
                        + "00"        // networkId
                        + "00000001"  // protocolMagic
                        + "02"        // ttl present
                        + "010101010101010101" // aux,validity,mint,scriptDataHash,networkId,collOut,totColl,treasury,donation = NO
                        + "03"        // signing mode ORDINARY
                        + "00000001"  // inputs
                        + "00000001"  // outputs
                        + "00000000"  // certificates
                        + "00000000"  // withdrawals
                        + "00000000"  // collateral inputs
                        + "00000000"  // required signers
                        + "00000000"  // reference inputs
                        + "00000000"  // voting procedures
                        + "00000001"; // witnesses
        assertThat(HexUtil.encodeHexString(LedgerCardanoApp.serializeTxInit(0, 1, 1, 1, 0, 0, 0, 1, false, false)))
                .isEqualTo(expected);
    }

    @Test
    void txInit_plutusTransaction_setsModeFlagsAndCounts() {
        LedgerSignRequest request = LedgerSignRequest.builder()
                .networkId(0).protocolMagic(1)
                .signingMode(LedgerCardanoApp.SIGNING_MODE_PLUTUS)
                .inputs(java.util.List.of(new LedgerTxInput("aa".repeat(32), 0)))
                .outputs(java.util.List.of(new LedgerTxOutput(new byte[]{1, 2, 3}, BigInteger.valueOf(2_000_000))))
                .fee(BigInteger.valueOf(170_000))
                .ttl(1000L)
                .scriptDataHash(new byte[32])
                .collateralInputs(java.util.List.of(new LedgerTxInput("bb".repeat(32), 1)))
                .requiredSigners(java.util.List.of(
                        LedgerCardanoApp.requiredSignerPath(LedgerBip32.paymentPath(0, 0, 0))))
                .referenceInputs(java.util.List.of(new LedgerTxInput("cc".repeat(32), 2)))
                .totalCollateral(BigInteger.valueOf(5_000_000))
                .tagCborSets(true)
                .signingPaths(java.util.List.of(LedgerBip32.paymentPath(0, 0, 0)))
                .build();

        assertThat(HexUtil.encodeHexString(LedgerCardanoApp.serializeTxInit(request)))
                .isEqualTo("0000000000000001" // tx-options: TAG_CBOR_SETS (bit 0)
                        + "00"        // networkId
                        + "00000001"  // protocolMagic
                        + "02"        // ttl: YES
                        + "01"        // aux data: NO
                        + "01"        // validity start: NO
                        + "01"        // mint: NO
                        + "02"        // script data hash: YES
                        + "01"        // include network id: NO
                        + "01"        // collateral output: NO
                        + "02"        // total collateral: YES
                        + "01"        // treasury: NO
                        + "01"        // donation: NO
                        + "07"        // signing mode: PLUTUS
                        + "00000001"  // inputs
                        + "00000001"  // outputs
                        + "00000000"  // certificates
                        + "00000000"  // withdrawals
                        + "00000001"  // collateral inputs
                        + "00000001"  // required signers
                        + "00000001"  // reference inputs
                        + "00000000"  // voting procedures
                        + "00000001"); // witnesses
    }

    @Test
    void mintToken_amountIsSignedInt64() {
        // A burn is negative — this is why mint can't reuse the unsigned output token.
        assertThat(HexUtil.encodeHexString(LedgerCardanoApp.serializeMintToken(
                new LedgerToken(new byte[]{0x54, 0x4f, 0x4b}, BigInteger.valueOf(-5)))))
                .isEqualTo("00000003" + "544f4b" + "fffffffffffffffb");
        assertThat(HexUtil.encodeHexString(LedgerCardanoApp.serializeMintToken(
                new LedgerToken(new byte[]{0x54, 0x4f, 0x4b}, BigInteger.valueOf(7)))))
                .isEqualTo("00000003" + "544f4b" + "0000000000000007");
    }

    @Test
    void outputBasicParams_flagsDatumAndRefScriptWhenPresent() {
        LedgerTxOutput output = new LedgerTxOutput(
                new byte[]{(byte) 0xaa, (byte) 0xbb, (byte) 0xcc}, BigInteger.valueOf(2_000_000),
                java.util.List.of(), LedgerDatum.hash(new byte[32]), new byte[]{0x01, 0x02});
        String hex = HexUtil.encodeHexString(LedgerCardanoApp.serializeTxOutputBasic(output, 1));
        // format(1=Babbage map) || thirdParty || len || addr || coin || 0 groups || datum YES || script YES
        assertThat(hex).isEqualTo("01" + "01" + "00000003" + "aabbcc"
                + "00000000001e8480" + "00000000" + "02" + "02");
    }

    @Test
    void outputDatum_hashLayout() {
        byte[] hash = HexUtil.decodeHexString(
                "0011223344556677889900112233445566778899001122334455667788990011");
        assertThat(HexUtil.encodeHexString(LedgerCardanoApp.serializeOutputDatum(LedgerDatum.hash(hash))))
                .isEqualTo("00" + "0011223344556677889900112233445566778899001122334455667788990011");
    }

    @Test
    void outputDatum_inlineSmall_singleMessage() {
        byte[] datum = HexUtil.decodeHexString("d87980"); // Plutus unit constr
        assertThat(HexUtil.encodeHexString(LedgerCardanoApp.serializeOutputDatum(LedgerDatum.inline(datum))))
                .isEqualTo("01" + "00000003" + "00000003" + "d87980");
    }

    @Test
    void outputDatum_inlineLarge_firstChunkCappedAt240() {
        byte[] datum = new byte[600];
        java.util.Arrays.fill(datum, (byte) 0x5a);
        byte[] first = LedgerCardanoApp.serializeOutputDatum(LedgerDatum.inline(datum));
        // type(1) + total(4) + chunkLen(4) + 240 bytes
        assertThat(first).hasSize(1 + 4 + 4 + 240);
        assertThat(HexUtil.encodeHexString(java.util.Arrays.copyOfRange(first, 0, 9)))
                .isEqualTo("01" + "00000258" + "000000f0"); // total 600, first chunk 240
        // Follow-up chunks: 240 then 120.
        assertThat(LedgerCardanoApp.serializeChunk(datum, 240, 240)).hasSize(4 + 240);
        byte[] last = LedgerCardanoApp.serializeChunk(datum, 480, 120);
        assertThat(HexUtil.encodeHexString(java.util.Arrays.copyOfRange(last, 0, 4))).isEqualTo("00000078");
        assertThat(last).hasSize(4 + 120);
    }

    @Test
    void outputRefScript_layoutNoTypeByte() {
        byte[] script = HexUtil.decodeHexString("82015820aabb"); // 6 bytes
        assertThat(HexUtil.encodeHexString(LedgerCardanoApp.serializeOutputRefScript(script)))
                .isEqualTo("00000006" + "00000006" + "82015820aabb");
    }

    @Test
    void outputToken_rejectsNegativeAmount() {
        // Outputs are unsigned; only mint may be negative.
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> LedgerCardanoApp.serializeToken(
                        new LedgerToken(new byte[]{0x54}, BigInteger.valueOf(-1))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void requiredSigner_pathAndHashForms() {
        assertThat(HexUtil.encodeHexString(
                LedgerCardanoApp.requiredSignerPath(LedgerBip32.paymentPath(0, 0, 0))))
                .isEqualTo("00" + "05" + "8000073c" + "80000717" + "80000000" + "00000000" + "00000000");
        byte[] hash = HexUtil.decodeHexString("00112233445566778899aabbccddeeff00112233445566778899aabb");
        assertThat(HexUtil.encodeHexString(LedgerCardanoApp.requiredSignerHash(hash)))
                .isEqualTo("01" + "00112233445566778899aabbccddeeff00112233445566778899aabb");
    }

    @Test
    void stakeRegistrationCert_typeCredentialThenPath() {
        // type 0 || credType 0 (key-path) || stakePath 1852'/1815'/0'/2/0
        assertThat(HexUtil.encodeHexString(LedgerCardanoApp.certStakeRegistration(LedgerBip32.stakePath(0))))
                .isEqualTo("00" + "00" + "05" + "8000073c" + "80000717" + "80000000" + "00000002" + "00000000");
    }

    @Test
    void stakeDelegationCert_typeCredentialPathPoolHash() {
        byte[] poolHash = HexUtil.decodeHexString("00112233445566778899aabbccddeeff00112233445566778899aabb");
        assertThat(HexUtil.encodeHexString(LedgerCardanoApp.certStakeDelegation(LedgerBip32.stakePath(0), poolHash)))
                .isEqualTo("02" + "00" + "05" + "8000073c" + "80000717" + "80000000" + "00000002" + "00000000"
                        + "00112233445566778899aabbccddeeff00112233445566778899aabb");
    }

    @Test
    void voteDelegationCert_abstain() {
        // type 9 || credType 0 || stakePath || drepType 2 (abstain), no hash
        assertThat(HexUtil.encodeHexString(LedgerCardanoApp.certVoteDelegation(
                LedgerBip32.stakePath(0), LedgerCardanoApp.DREP_ABSTAIN, null)))
                .isEqualTo("09" + "00" + "05" + "8000073c" + "80000717" + "80000000" + "00000002" + "00000000"
                        + "02");
    }

    @Test
    void voteDelegationCert_noConfidence() {
        assertThat(HexUtil.encodeHexString(LedgerCardanoApp.certVoteDelegation(
                LedgerBip32.stakePath(0), LedgerCardanoApp.DREP_NO_CONFIDENCE, null)))
                .isEqualTo("09" + "00" + "05" + "8000073c" + "80000717" + "80000000" + "00000002" + "00000000"
                        + "03");
    }

    @Test
    void voteDelegationCert_keyHashDRep() {
        byte[] drepHash = HexUtil.decodeHexString("00112233445566778899aabbccddeeff00112233445566778899aabb");
        // type 9 || credType 0 || stakePath || drepType 0 (key-hash) || drepHash(28)
        assertThat(HexUtil.encodeHexString(LedgerCardanoApp.certVoteDelegation(
                LedgerBip32.stakePath(0), LedgerCardanoApp.DREP_KEY_HASH, drepHash)))
                .isEqualTo("09" + "00" + "05" + "8000073c" + "80000717" + "80000000" + "00000002" + "00000000"
                        + "00" + "00112233445566778899aabbccddeeff00112233445566778899aabb");
    }

    @Test
    void voteDelegationCert_keyHashRequires28ByteHash() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> LedgerCardanoApp.certVoteDelegation(
                        LedgerBip32.stakePath(0), LedgerCardanoApp.DREP_KEY_HASH, new byte[27]))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void anchor_absentIsOptionFlagOne() {
        assertThat(HexUtil.encodeHexString(LedgerCardanoApp.serializeAnchor(null, null))).isEqualTo("01");
        assertThat(HexUtil.encodeHexString(LedgerCardanoApp.serializeAnchor("", null))).isEqualTo("01");
    }

    @Test
    void anchor_presentIsFlagHashUrl() {
        byte[] hash = HexUtil.decodeHexString(
                "0011223344556677889900112233445566778899001122334455667788990011");
        // flag 2 || hash(32) || "ipfs://x" ascii (69 70 66 73 3a 2f 2f 78)
        assertThat(HexUtil.encodeHexString(LedgerCardanoApp.serializeAnchor("ipfs://x", hash)))
                .isEqualTo("02"
                        + "0011223344556677889900112233445566778899001122334455667788990011"
                        + "697066733a2f2f78");
    }

    @Test
    void drepRegistrationCert_typeCredentialDepositAnchor() {
        // type 16 || credType 0 || drepPath 1852'/1815'/0'/3/0 || deposit 500 ADA || no anchor
        assertThat(HexUtil.encodeHexString(LedgerCardanoApp.certDRepRegistration(
                LedgerBip32.drepPath(0), java.math.BigInteger.valueOf(500_000_000L), null, null)))
                .isEqualTo("10" + "00" + "05" + "8000073c" + "80000717" + "80000000" + "00000003" + "00000000"
                        + "000000001dcd6500" + "01");
    }

    @Test
    void votingProcedureAsDRep_voterActionVoteAnchor() {
        String govActionTxHex = "00112233445566778899aabbccddeeff00112233445566778899aabbccddeeff";
        byte[] govActionTx = HexUtil.decodeHexString(govActionTxHex);
        // voter 102 (drep key-path) || drepPath || govActionTx(32) || index 3 || YES(1) || no anchor
        assertThat(HexUtil.encodeHexString(LedgerCardanoApp.votingProcedureAsDRep(
                LedgerBip32.drepPath(0), govActionTx, 3, LedgerCardanoApp.VOTE_YES, null, null)))
                .isEqualTo("66" + "05" + "8000073c" + "80000717" + "80000000" + "00000003" + "00000000"
                        + govActionTxHex
                        + "00000003" + "01" + "01");
    }

    @Test
    void withdrawal_coinThenKeyPathCredential() {
        // coin 1_000_000 || credType 0 || stakePath
        assertThat(HexUtil.encodeHexString(LedgerCardanoApp.withdrawal(java.math.BigInteger.valueOf(1_000_000), LedgerBip32.stakePath(0))))
                .isEqualTo("00000000000f4240" + "00"
                        + "05" + "8000073c" + "80000717" + "80000000" + "00000002" + "00000000");
    }

    @Test
    void assetGroupAndToken_layout() {
        byte[] policy = HexUtil.decodeHexString("aa112233445566778899aabbccddeeff00112233445566778899aabb");
        assertThat(HexUtil.encodeHexString(LedgerCardanoApp.serializeAssetGroup(
                new LedgerAssetGroup(policy, java.util.List.of(new LedgerToken(new byte[]{0x54, 0x4f, 0x4b}, java.math.BigInteger.valueOf(7)))))))
                .isEqualTo("aa112233445566778899aabbccddeeff00112233445566778899aabb" + "00000001");
        assertThat(HexUtil.encodeHexString(LedgerCardanoApp.serializeToken(
                new LedgerToken(new byte[]{0x54, 0x4f, 0x4b}, java.math.BigInteger.valueOf(7)))))
                .isEqualTo("00000003" + "544f4b" + "0000000000000007");
    }
}
