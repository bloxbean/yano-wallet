package com.bloxbean.cardano.yano.wallet.hardware.ledger;

import com.bloxbean.cardano.client.util.HexUtil;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pure-serialization checks for the Ledger derive-address protocol (ADR-034):
 * path encoding and base-address parameter layout, validated against
 * hand-computed byte vectors (no device needed). 1852' = 0x8000073c,
 * 1815' = 0x80000717, 0' = 0x80000000.
 */
class LedgerAddressParamsTest {

    @Test
    void accountPath_serializesToThreeHardenedElements() {
        assertThat(HexUtil.encodeHexString(LedgerBip32.serialize(LedgerBip32.accountPath(0))))
                .isEqualTo("03" + "8000073c" + "80000717" + "80000000");
    }

    @Test
    void paymentPath_hasNonHardenedRoleAndIndex() {
        assertThat(HexUtil.encodeHexString(LedgerBip32.serialize(LedgerBip32.paymentPath(0, 0, 5))))
                .isEqualTo("05" + "8000073c" + "80000717" + "80000000" + "00000000" + "00000005");
    }

    @Test
    void stakePath_isRoleTwoIndexZero() {
        assertThat(HexUtil.encodeHexString(LedgerBip32.serialize(LedgerBip32.stakePath(0))))
                .isEqualTo("05" + "8000073c" + "80000717" + "80000000" + "00000002" + "00000000");
    }

    @Test
    void baseAddressParams_matchLedgerLayout() {
        // addressType(0=base key/key) | networkId(1) | spendingPath | 0x22(key-path) | stakingPath
        String expected =
                "00" + "01"
                        + "05" + "8000073c" + "80000717" + "80000000" + "00000000" + "00000000"
                        + "22"
                        + "05" + "8000073c" + "80000717" + "80000000" + "00000002" + "00000000";

        byte[] params = LedgerCardanoApp.serializeBaseAddressParams(
                1, LedgerBip32.paymentPath(0, 0, 0), LedgerBip32.stakePath(0));

        assertThat(HexUtil.encodeHexString(params)).isEqualTo(expected);
    }
}
