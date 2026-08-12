package com.bloxbean.cardano.yano.wallet.nodeclient;

import com.bloxbean.cardano.yano.wallet.core.service.HistoryPort;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.math.BigInteger;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * ADR-038 §4: the path/capability map for the Blockfrost flavor.
 *
 * <p>yaci-store is Blockfrost-compatible for the money path but is NOT a Yano
 * node, and two routes the wallet depends on simply do not exist on it. Both
 * were verified against a live Yaci DevKit (its OpenAPI document lists 142
 * routes; neither of these is among them):
 *
 * <ul>
 *   <li>{@code /accounts/{stake}/transactions} — absent. Asking anyway 404s, and
 *       the history screen shows an empty list as though the wallet had never
 *       transacted.</li>
 *   <li>{@code /governance/dreps/{drepId}} — absent. Asking anyway 404s, which
 *       the wallet reads as "not registered" — so a registered DRep would be
 *       offered registration again.</li>
 * </ul>
 *
 * <p>Both failures are silent, which is what makes them worth pinning: nothing
 * errors, the wallet just quietly says something untrue.
 */
class BlockfrostFlavorPathTest {

    private static final String STAKE = "stake_test1ur96ax323q0xq0lnn5mwnpa6e7u8a6vq28ez93078m7n2rq9emzd3";
    private static final String ADDRESS = "addr_test1qq_wallet";
    private static final String DREP = "drep1yfmk9nxq";

    private StubYanoNode node;

    @BeforeEach
    void setUp() throws IOException {
        node = new StubYanoNode();
    }

    @AfterEach
    void tearDown() {
        node.close();
    }

    private YanoNodePorts ports(boolean blockfrostFlavor) {
        return new YanoNodePorts(new YanoNodeClient(node.baseUrl(), blockfrostFlavor));
    }

    private static String addressTxs() {
        return """
                [{"tx_hash":"aa11","block_height":42,"block_time":1700000000,"slot":123}]
                """;
    }

    // ---- history -----------------------------------------------------------

    @Test
    void aYanoNodeIsAskedForAccountHistory() {
        node.on("/api/v1/accounts/" + STAKE + "/transactions", addressTxs());

        List<HistoryPort.TxRef> history = ports(false).walletTransactions(STAKE, ADDRESS, 1, 10, true);

        assertThat(history).singleElement()
                .satisfies(tx -> assertThat(tx.txHash()).isEqualTo("aa11"));
    }

    @Test
    void yaciStoreIsAskedForAddressHistoryInstead() {
        // Only the ADDRESS route is served — exactly like a real DevKit, where
        // the account route does not exist. Routing to the account route would
        // silently produce an empty history.
        node.on("/api/v1/addresses/" + ADDRESS + "/transactions", addressTxs());

        List<HistoryPort.TxRef> history = ports(true).walletTransactions(STAKE, ADDRESS, 1, 10, true);

        assertThat(history).singleElement()
                .satisfies(tx -> assertThat(tx.txHash()).isEqualTo("aa11"));
        assertThat(node.requests())
                .noneMatch(request -> request.path().contains("/accounts/"));
    }

    @Test
    void aMissingHistoryRouteIsReportedNotSwallowed() {
        // Nothing registered → 404. History must degrade loudly enough for the UI
        // to say "unavailable" rather than render a convincing empty list.
        assertThatThrownBy(() -> ports(true).walletTransactions(STAKE, ADDRESS, 1, 10, true))
                .isInstanceOf(HistoryPort.HistoryUnavailableException.class);
    }

    // ---- DRep --------------------------------------------------------------

    @Test
    void aYanoNodeIsAskedForTheDRepUnderGovernance() {
        node.on("/api/v1/governance/dreps/" + DREP, """
                {"drep_id":"%s","active":true,"retired":false,"expired":false,
                 "registered_epoch":480,"deposit":"500000000"}
                """.formatted(DREP));

        YanoNodeClient.DRepInfo drep = new YanoNodeClient(node.baseUrl(), false).getDRepInfo(DREP);

        assertThat(drep).isNotNull();
        assertThat(drep.active()).isTrue();
        assertThat(drep.registeredEpoch()).isEqualTo(480);
        assertThat(drep.deposit()).isEqualTo(new BigInteger("500000000"));
    }

    @Test
    void yaciStoreIsAskedUnderGovernanceStateAndItsStatusIsMapped() {
        // yaci-store's DRepDetailsDto: a `status` string instead of the flag
        // trio, and no registration epoch at all.
        node.on("/api/v1/governance-state/dreps/" + DREP, """
                {"drep_id":"%s","drep_hash":"abcd","deposit":"500000000",
                 "status":"ACTIVE","voting_power":"0","registration_slot":900,"drep_type":"KEY_HASH"}
                """.formatted(DREP));

        YanoNodeClient.DRepInfo drep = new YanoNodeClient(node.baseUrl(), true).getDRepInfo(DREP);

        assertThat(drep).isNotNull();
        assertThat(drep.active()).isTrue();
        assertThat(drep.retired()).isFalse();
        assertThat(drep.deposit()).isEqualTo(new BigInteger("500000000"));
        assertThat(node.requests()).noneMatch(request -> request.path().contains("/governance/dreps/"));
    }

    @Test
    void aRetiredOrExpiredDRepIsRecognisedFromItsStatus() {
        node.on("/api/v1/governance-state/dreps/retired", """
                {"drep_id":"retired","deposit":"0","status":"RETIRED"}""");
        node.on("/api/v1/governance-state/dreps/expired", """
                {"drep_id":"expired","deposit":"0","status":"EXPIRED"}""");

        YanoNodeClient client = new YanoNodeClient(node.baseUrl(), true);

        assertThat(client.getDRepInfo("retired").retired()).isTrue();
        assertThat(client.getDRepInfo("retired").active()).isFalse();
        assertThat(client.getDRepInfo("expired").expired()).isTrue();
        assertThat(client.getDRepInfo("expired").active()).isFalse();
    }

    @Test
    void anUnregisteredDRepIsNullOnBothFlavors() {
        // 404 here genuinely means "no such DRep" — the route exists on each
        // flavor, which is the whole point of choosing the right one.
        assertThat(new YanoNodeClient(node.baseUrl(), true).getDRepInfo(DREP)).isNull();
        assertThat(new YanoNodeClient(node.baseUrl(), false).getDRepInfo(DREP)).isNull();
    }
}
