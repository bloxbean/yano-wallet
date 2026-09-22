package com.bloxbean.cardano.yano.wallet.nodeclient;

import com.bloxbean.cardano.client.address.Address;
import com.bloxbean.cardano.client.api.common.OrderEnum;
import com.bloxbean.cardano.client.api.model.Utxo;
import com.bloxbean.cardano.yano.wallet.core.config.WalletNetwork;
import org.junit.jupiter.api.Test;

import java.util.HexFormat;

import static com.bloxbean.cardano.yano.wallet.nodeclient.PendingInputsTest.*;
import static org.assertj.core.api.Assertions.*;

class YanoMempoolViewTest {
    @Test void overlayPagesIncludePendingOutputsAndExcludeInputsUnknownToLocalTracker() throws Exception {
        try (var node = new StubYanoNode()) {
            String pending = "22".repeat(32);
            node.on(ROUTE, r -> {
                if (!r.path().contains("include_mempool=true")) {
                    return StubYanoNode.Response.json(r.path().contains("page=1&") ? utxos(0, 1) : "[]");
                }
                String rows = r.path().contains("page=1&") ? utxos(0, 100)
                        : r.path().contains("page=2&") ? utxos(100, 101) : "[]";
                return StubYanoNode.Response.json(rows.replace(HASH, pending)
                        .replace("\"amount\":", "\"block\":null,\"amount\":"));
            });
            var backend = YanoNodeBackend.connect(WalletNetwork.PREPROD, node.baseUrl());
            assertThat(backend.selectionUtxoSupplier().getAll(ADDRESS)).hasSize(101)
                    .allMatch(u -> pending.equals(u.getTxHash()));
            assertThat(backend.utxoSupplier().getAll(ADDRESS)).extracting(Utxo::getTxHash).containsExactly(HASH);
            assertThat(node.requests().stream().filter(r -> r.path().contains("include_mempool")))
                    .hasSize(3).allMatch(r -> r.path().contains("count=100&order=asc&include_mempool=true"));
        }
    }

    @Test void credentialsPreservePageCountAndOrderAndSingleOutputUsesOverlay() throws Exception {
        try (var node = new StubYanoNode()) {
            String credential = HexFormat.of().formatHex(new Address(ADDRESS).getPaymentCredentialHash().orElseThrow());
            String route = "/api/v1/credentials/" + credential + "/utxos";
            node.on(route, utxos(5, 6));
            node.on("/api/v1/utxos/" + HASH + "/5", utxos(5, 6).substring(1, utxos(5, 6).length() - 1));
            var backend = YanoNodeBackend.connect(WalletNetwork.PREPROD, node.baseUrl());
            backend.selectionUtxoSupplier().setSearchByAddressVkh(true);
            assertThat(backend.selectionUtxoSupplier().getPage(ADDRESS, 2, 3, OrderEnum.desc))
                    .extracting(Utxo::getOutputIndex).containsExactly(5);
            assertThat(node.requests().getFirst().path()).isEqualTo(route + "?page=4&count=2&order=desc&include_mempool=true");
            assertThat(backend.selectionUtxoSupplier().getTxOutput(HASH, 5)).isPresent();
            assertThat(backend.selectionUtxoSupplier().getTxOutput(HASH, 6)).isEmpty();
            assertThat(node.requests()).allMatch(r -> r.path().contains("include_mempool=true"));
        }
    }

    @Test void unavailableAndMalformedOverlayNeverFallBackToConfirmedListings() throws Exception {
        try (var node = new StubYanoNode()) {
            var backend = YanoNodeBackend.connect(WalletNetwork.PREPROD, node.baseUrl());
            for (int code : new int[]{404, 503}) {
                node.on(ROUTE, r -> new StubYanoNode.Response(code, "application/json", "{\"error\":\"unavailable\"}"));
                assertThatThrownBy(() -> backend.selectionUtxoSupplier().getAll(ADDRESS)).isInstanceOf(NodeClientException.class);
            }
            node.on(ROUTE, "[{}]");
            assertThatThrownBy(() -> backend.selectionUtxoSupplier().getAll(ADDRESS)).isInstanceOf(NodeClientException.class);
            node.on(ROUTE, utxos(0, 1).replace("10000000", "not-a-number"));
            assertThatThrownBy(() -> backend.selectionUtxoSupplier().getAll(ADDRESS)).isInstanceOf(NodeClientException.class);
            node.on("/api/v1/utxos/" + HASH + "/5", utxos(0, 1).substring(1, utxos(0, 1).length() - 1));
            assertThatThrownBy(() -> backend.selectionUtxoSupplier().getTxOutput(HASH, 5)).isInstanceOf(NodeClientException.class);
            assertThat(node.requests()).allMatch(r -> r.path().contains("include_mempool=true"));
        }
    }
}
