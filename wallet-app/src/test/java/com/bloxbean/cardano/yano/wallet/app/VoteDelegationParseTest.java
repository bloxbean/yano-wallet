package com.bloxbean.cardano.yano.wallet.app;

import com.bloxbean.cardano.client.governance.GovId;
import com.bloxbean.cardano.client.transaction.spec.governance.DRep;
import com.bloxbean.cardano.client.transaction.spec.governance.DRepType;
import com.bloxbean.cardano.client.util.HexUtil;
import com.bloxbean.cardano.yano.wallet.ui.contract.WalletUiController;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The Governance screen hands the controller a raw string — a DRep id or one of
 * the standing-option sentinels. {@link DefaultWalletUiController#parseDRep} must
 * turn each into the right CCL {@link DRep} (ADR-034, CIP-1694).
 */
class VoteDelegationParseTest {

    private static final String KEY_HASH_HEX =
            "00112233445566778899aabbccddeeff00112233445566778899aabb";

    @Test
    void abstainSentinel_mapsToAbstainDRep() {
        assertThat(DefaultWalletUiController.parseDRep(WalletUiController.VOTE_ABSTAIN).getType())
                .isEqualTo(DRepType.ABSTAIN);
    }

    @Test
    void noConfidenceSentinel_mapsToNoConfidenceDRep() {
        assertThat(DefaultWalletUiController.parseDRep(WalletUiController.VOTE_NO_CONFIDENCE).getType())
                .isEqualTo(DRepType.NO_CONFIDENCE);
    }

    @Test
    void cip129DRepId_roundTripsToKeyHashDRep() {
        // Encode a key hash as a CIP-129 drep id with CCL, then parse it back.
        String drepId = GovId.drepFromKeyHash(HexUtil.decodeHexString(KEY_HASH_HEX));
        DRep drep = DefaultWalletUiController.parseDRep(drepId);
        assertThat(drep.getType()).isEqualTo(DRepType.ADDR_KEYHASH);
        assertThat(drep.getHash()).isEqualTo(KEY_HASH_HEX);
    }

    @Test
    void blankTarget_isRejected() {
        assertThatThrownBy(() -> DefaultWalletUiController.parseDRep("   "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void garbageTarget_isRejected() {
        assertThatThrownBy(() -> DefaultWalletUiController.parseDRep("not-a-drep-id"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
