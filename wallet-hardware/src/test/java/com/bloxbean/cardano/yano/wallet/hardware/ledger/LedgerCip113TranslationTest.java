package com.bloxbean.cardano.yano.wallet.hardware.ledger;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The real CIP-113 programmable-token transactions, translated for a Ledger (E4).
 *
 * <p>These are the two transactions a user actually signed with a software wallet
 * on 2026-08-13, and the pair a hardware wallet refused outright because the
 * translator had no way to express their certificates and withdrawal. They are the
 * useful fixtures precisely because they are awkward: certificates over SCRIPT
 * credentials with Conway deposits, and a withdrawal from a script reward account —
 * not the key-path forms the wallet's own staking flows produce.
 *
 * <p>What this cannot prove is that a device accepts the bytes. The payload layouts
 * are taken from ledgerjs rather than invented, and {@code HardwareDappSigner}
 * gates on the transaction hash the device computes, so a wrong translation refuses
 * rather than producing a bad signature — but the acceptance test is a person with
 * a Ledger.
 */
class LedgerCip113TranslationTest {

    private static final long[] PAYMENT_PATH = LedgerBip32.paymentPath(0, 0, 0);
    private static final long[] STAKE_PATH = LedgerBip32.stakePath(0);
    /** The wallet that signed these; its payment key hash is the Reg tx's required signer. */
    private static final byte[] PAYMENT_KEY_HASH =
            HexFormat.of().parseHex("c7252730673b7a0fb9dfb68615c4431e821991f7dbe02298e9bd6b65");

    private static byte[] fixture(String name) {
        try (InputStream in = LedgerCip113TranslationTest.class
                .getResourceAsStream("/cip113/" + name)) {
            return HexFormat.of().parseHex(
                    new String(in.readAllBytes(), StandardCharsets.UTF_8).strip());
        } catch (Exception e) {
            throw new IllegalStateException("missing fixture " + name, e);
        }
    }

    private static LedgerTxTranslator.Context context(Set<String> ownedInputs) {
        return new LedgerTxTranslator.Context(0, 2, ownedInputs, PAYMENT_KEY_HASH,
                PAYMENT_PATH, null, STAKE_PATH);
    }

    @Test
    void theInitTransactionsConwayCertificatesTranslate() {
        // Two reg_certs (CBOR type 7) over SCRIPT credentials, 2 ADA deposit each.
        // Device payload: type(7) || credentialType(1=script hash) || hash(28) || coin(8).
        LedgerSignRequest request = LedgerTxTranslator.translate(fixture("init-tx.hex"),
                context(Set.of("f26a96a12b7aed2a868f2e249a6e100d2d671c414314593d55be5d497daac949#1")));

        assertThat(request.certificates()).hasSize(2);
        for (byte[] cert : request.certificates()) {
            assertThat(cert[0]).as("Conway stake registration").isEqualTo((byte) 0x07);
            assertThat(cert[1]).as("script-hash credential").isEqualTo((byte) 0x01);
            assertThat(cert).as("type + credType + 28-byte hash + 8-byte deposit").hasSize(1 + 1 + 28 + 8);
            // deposit 2_000_000 in the trailing uint64
            assertThat(cert[cert.length - 3] & 0xFF).isEqualTo(0x1E);
            assertThat(cert[cert.length - 2] & 0xFF).isEqualTo(0x84);
            assertThat(cert[cert.length - 1] & 0xFF).isEqualTo(0x80);
        }
        assertThat(request.signingMode())
                .as("collateral + script data hash present")
                .isEqualTo(LedgerCardanoApp.SIGNING_MODE_PLUTUS);
    }

    @Test
    void theRegTransactionsScriptWithdrawalTranslates() {
        // Zero withdrawal from reward account f09839a0df… — the f0 header means a
        // SCRIPT credential, so it is the validator's, not ours.
        LedgerSignRequest request = LedgerTxTranslator.translate(fixture("reg-tx.hex"),
                context(Set.of()));

        assertThat(request.withdrawals()).hasSize(1);
        byte[] w = request.withdrawals().get(0);
        assertThat(w).as("coin(8) + credType + 28-byte hash").hasSize(8 + 1 + 28);
        assertThat(w[8]).as("script-hash credential").isEqualTo((byte) 0x01);
        assertThat(HexFormat.of().formatHex(w, 9, 37))
                .isEqualTo("9839a0df1337fcf55aae2b56f91a2a7f520fd28a60a0ce73883872ab");
    }

    @Test
    void theRegTransactionSignsWithThePaymentKeyOnly() {
        // Its required_signers names our payment key; the withdrawal is a script's,
        // so our stake key must NOT be dragged in. Note ownership cannot be proven
        // from inputs here — the Reg tx spends the Init tx's not-yet-on-chain
        // output — so required_signers is what makes this translatable at all.
        LedgerSignRequest request = LedgerTxTranslator.translate(fixture("reg-tx.hex"),
                context(Set.of()));

        assertThat(request.signingPaths()).hasSize(1);
        assertThat(request.signingPaths().get(0)).isEqualTo(PAYMENT_PATH);
    }
}
