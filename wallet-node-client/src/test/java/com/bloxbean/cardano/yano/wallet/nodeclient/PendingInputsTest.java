package com.bloxbean.cardano.yano.wallet.nodeclient;

import com.bloxbean.cardano.client.address.Address;
import com.bloxbean.cardano.client.api.common.OrderEnum;
import com.bloxbean.cardano.client.api.model.Utxo;
import com.bloxbean.cardano.client.api.model.Amount;
import com.bloxbean.cardano.client.api.model.ProtocolParams;
import com.bloxbean.cardano.client.common.model.Networks;
import com.bloxbean.cardano.hdwallet.Wallet;
import com.bloxbean.cardano.yano.wallet.core.tx.QuickAdaTxService;
import com.bloxbean.cardano.yano.wallet.core.wallet.WalletAddresses;
import com.bloxbean.cardano.client.transaction.spec.Transaction;
import com.bloxbean.cardano.client.transaction.spec.TransactionBody;
import com.bloxbean.cardano.client.transaction.spec.TransactionInput;
import com.bloxbean.cardano.client.transaction.spec.TransactionWitnessSet;
import com.bloxbean.cardano.yano.wallet.core.config.WalletNetwork;
import com.bloxbean.cardano.yano.wallet.core.service.MempoolConflictException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigInteger;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HexFormat;
import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.*;

class PendingInputsTest {
    static final String ADDRESS = "addr_test1qzx9hu8j4ah3auytk0mwcupd69hpc52t0cw39a65ndrah86djs784u92a3m5w475w3w35tyd6v3qumkze80j8a6h5tuqq5xe8y";
    static final String HASH = "11".repeat(32);
    static final String ROUTE = "/api/v1/addresses/" + ADDRESS + "/utxos";
    @TempDir Path directory;

    static byte[] tx(int count) throws Exception {
        return Transaction.builder().body(TransactionBody.builder()
                        .inputs(IntStream.range(0, count).mapToObj(i -> new TransactionInput(HASH, i)).toList())
                        .outputs(List.of()).fee(BigInteger.valueOf(200000)).ttl(1000).build())
                .witnessSet(new TransactionWitnessSet()).build().serialize();
    }

    static String utxos(int first, int end) {
        return IntStream.range(first, end).mapToObj(i -> """
                {"tx_hash":"%s","output_index":%d,"address":"%s",
                "amount":[{"unit":"lovelace","quantity":"10000000"},
                {"unit":"%s","quantity":"20"}]}
                """.formatted(HASH, i, ADDRESS, "ab".repeat(28) + "746f6b656e"))
                .collect(java.util.stream.Collectors.joining(",", "[", "]"));
    }

    static void status(StubYanoNode node, long slot, int lag) {
        node.on("/api/v1/status", """
                {"chain":{"slot":%d,"blockNumber":10},
                 "utxo":{"enabled":true,"lagBlocks":%d,"lastAppliedBlock":10}}
                """.formatted(slot, lag));
    }

    @Test void submittedInputsAreExcludedAcrossPagesButConfirmedAndDevkitViewsAreUnchanged() throws Exception {
        try (var node = new StubYanoNode()) {
            status(node, 100, 0);
            node.on(ROUTE, r -> StubYanoNode.Response.json(r.path().contains("page=1&")
                    ? utxos(0, 100) : r.path().contains("page=2&") ? utxos(100, 102) : "[]"));
            node.on("/api/v1/tx/submit", "\"" + HASH + "\"");
            var backend = YanoNodeBackend.connect(WalletNetwork.PREPROD, node.baseUrl());
            backend.persistPendingInputs(directory.resolve("pending-inputs.json"));
            assertThat(backend.transactionProcessor().submitTransaction(tx(100)).isSuccessful()).isTrue();
            assertThat(backend.selectionUtxoSupplier().getAll(ADDRESS)).extracting(Utxo::getOutputIndex)
                    .containsExactly(100, 101);
            assertThat(backend.selectionUtxoSupplier().getPage(ADDRESS, 100, 0, OrderEnum.asc))
                    .extracting(Utxo::getOutputIndex).containsExactly(100, 101);
            assertThat(backend.selectionUtxoSupplier().getPage(ADDRESS, 100, 1, OrderEnum.asc)).isEmpty();
            assertThat(backend.selectionUtxoSupplier().getAll(ADDRESS).getFirst().getAmount()).hasSize(2);
            assertThat(backend.selectionUtxoSupplier().getTxOutput(HASH, 0)).isEmpty();
            assertThat(backend.utxoSupplier().getAll(ADDRESS)).hasSize(102);
            var restarted = YanoNodeBackend.connect(WalletNetwork.PREPROD, node.baseUrl());
            restarted.persistPendingInputs(directory.resolve("pending-inputs.json"));
            assertThat(restarted.selectionUtxoSupplier().getAll(ADDRESS)).hasSize(2);
            var devkit = YanoNodeBackend.connect(WalletNetwork.YACI_DEVKIT, node.baseUrl());
            assertThat(devkit.selectionUtxoSupplier()).isSameAs(devkit.utxoSupplier());
            assertThat(devkit.selectionUtxoSupplier().getAll(ADDRESS)).hasSize(102);
            assertThat(node.requests()).anyMatch(r -> r.path().contains("include_mempool=true"));
        }
    }

    @Test void credentialSelectionFiltersInputsAndPreservesOrder() throws Exception {
        try (var node = new StubYanoNode()) {
            status(node, 100, 0);
            String credential = HexFormat.of().formatHex(new Address(ADDRESS).getPaymentCredentialHash().orElseThrow());
            node.on("/api/v1/credentials/" + credential + "/utxos", r ->
                    StubYanoNode.Response.json(r.path().contains("page=1&") ? utxos(0, 2) : "[]"));
            node.on("/api/v1/tx/submit", "\"" + HASH + "\"");
            var backend = YanoNodeBackend.connect(WalletNetwork.PREPROD, node.baseUrl());
            backend.transactionProcessor().submitTransaction(tx(1));
            backend.selectionUtxoSupplier().setSearchByAddressVkh(true);
            assertThat(backend.selectionUtxoSupplier().getPage(ADDRESS, 2, 0, OrderEnum.desc))
                    .extracting(Utxo::getOutputIndex).containsExactly(1);
            assertThat(node.requests().stream().filter(r -> r.path().contains("/credentials/")))
                    .allMatch(r -> r.path().contains("count=2") && r.path().contains("order=desc"));
        }
    }

    @Test void realAdaAndTokenDraftsSpendPendingChangeFromOverlay() throws Exception {
        try (var node = new StubYanoNode()) {
            status(node, 100, 0);
            Wallet wallet = Wallet.createFromMnemonic(Networks.preprod(),
                    "drive useless envelope shine range ability time copper alarm museum near flee wrist "
                            + "live type device meadow allow churn purity wisdom praise drop code");
            for (int role = 0; role < 2; role++) {
                for (int index = 0; index < 22; index++) {
                    String address = WalletAddresses.baseAddress(wallet, role, index).toBech32();
                    node.on("/api/v1/addresses/" + address + "/transactions", "[]");
                    node.on("/api/v1/addresses/" + address + "/utxos", "[]");
                }
            }
            String sender = wallet.getBaseAddressString(0);
            node.on("/api/v1/addresses/" + sender + "/utxos", r -> StubYanoNode.Response.json(
                    !r.path().contains("page=1&") ? "[]" : r.path().contains("include_mempool=true")
                            ? utxos(0, 1).replace(HASH, "22".repeat(32)).replace(ADDRESS, sender)
                                    .replace("\"amount\":", "\"block\":null,\"amount\":")
                            : utxos(0, 1).replace(ADDRESS, sender)));
            node.on("/api/v1/tx/submit", "\"" + HASH + "\"");
            var backend = YanoNodeBackend.connect(WalletNetwork.PREPROD, node.baseUrl());
            backend.transactionProcessor().submitTransaction(tx(1));
            var params = ProtocolParams.builder().minFeeA(44).minFeeB(155381).minUtxo("1000000")
                    .coinsPerUtxoSize("4312").minFeeRefScriptCostPerByte(BigDecimal.valueOf(15)).build();
            for (boolean token : List.of(false, true)) {
                var draft = new QuickAdaTxService().buildSignedDraft(wallet, backend.selectionUtxoSupplier(),
                        () -> params, backend.transactionProcessor(), ADDRESS, BigInteger.valueOf(2_000_000),
                        token ? List.of(new Amount("ab".repeat(28) + "746f6b656e", BigInteger.ONE)) : List.of(), null);
                Transaction built = Transaction.deserialize(HexFormat.of().parseHex(draft.cborHex()));
                assertThat(built.getBody().getInputs()).hasSize(1);
                assertThat(built.getBody().getInputs().getFirst().getIndex()).isEqualTo(0);
                assertThat(built.getBody().getInputs().getFirst().getTransactionId()).isEqualTo("22".repeat(32));
            }
            assertThat(backend.utxoSupplier().getAll(sender)).extracting(Utxo::getTxHash).containsExactly(HASH);
        }
    }

    @Test void definiteRejectionReleasesInputsAndConflictIsNeverAutomaticallyRetried() throws Exception {
        try (var node = new StubYanoNode()) {
            node.on("/api/v1/tx/submit", r -> new StubYanoNode.Response(400, "text/plain",
                    "Mempool admission failed (CONFLICT): regular input is already claimed by other"));
            node.on(ROUTE, r -> StubYanoNode.Response.json(r.path().contains("page=1&") ? utxos(0, 1) : "[]"));
            var backend = YanoNodeBackend.connect(WalletNetwork.PREPROD, node.baseUrl());
            assertThatThrownBy(() -> backend.transactionProcessor().submitTransaction(tx(1)))
                    .isInstanceOf(MempoolConflictException.class).hasMessageContaining("Rebuild");
            assertThat(backend.selectionUtxoSupplier().getAll(ADDRESS)).hasSize(1);
            assertThat(node.requests().stream().filter(r -> r.path().contains("tx/submit"))).hasSize(1);
        }
    }

    @Test void uncertainSubmissionStaysReservedUntilExpiryAndLagDoesNotReleaseIt() throws Exception {
        try (var node = new StubYanoNode()) {
            status(node, 100, 0);
            node.on("/api/v1/tx/submit", r -> new StubYanoNode.Response(503, "text/plain", "unavailable"));
            node.on(ROUTE, r -> StubYanoNode.Response.json(r.path().contains("page=1&") ? utxos(0, 1) : "[]"));
            var backend = YanoNodeBackend.connect(WalletNetwork.PREPROD, node.baseUrl());
            assertThat(backend.transactionProcessor().submitTransaction(tx(1)).isSuccessful()).isFalse();
            assertThat(backend.selectionUtxoSupplier().getAll(ADDRESS)).isEmpty();
            assertThatThrownBy(() -> backend.transactionProcessor().submitTransaction(tx(1)))
                    .isInstanceOf(MempoolConflictException.class);
            assertThat(node.requests().stream().filter(r -> r.path().contains("tx/submit"))).hasSize(1);
            status(node, 1001, 2);
            assertThat(backend.selectionUtxoSupplier().getAll(ADDRESS)).isEmpty();
            status(node, 1001, 0);
            assertThat(backend.selectionUtxoSupplier().getAll(ADDRESS)).hasSize(1);
        }
    }

    @Test void rebuildExcludesNodeReportedConflictEvenWhenOriginalSubmissionWasNotTracked() throws Exception {
        try (var node = new StubYanoNode()) {
            String owner = "70".repeat(32);
            status(node, 100, 0);
            node.on(ROUTE, r -> StubYanoNode.Response.json(r.path().contains("page=1&") ? utxos(0, 2) : "[]"));
            node.on("/api/v1/tx/submit", r -> new StubYanoNode.Response(400, "application/json",
                    "{\"error\":\"Failed to submit transaction: Mempool admission failed (CONFLICT): "
                            + "regular input is already claimed by " + owner + ": " + HASH + "#0\"}"));
            Path file = directory.resolve("pending-inputs.json");
            var backend = YanoNodeBackend.connect(WalletNetwork.PREPROD, node.baseUrl());
            backend.persistPendingInputs(file);
            assertThatThrownBy(() -> backend.transactionProcessor().submitTransaction(tx(2)))
                    .isInstanceOf(MempoolConflictException.class);
            assertThat(backend.selectionUtxoSupplier().getAll(ADDRESS)).extracting(Utxo::getOutputIndex)
                    .containsExactly(1); // Other inputs from the rejected draft are released.
            assertThat(backend.utxoSupplier().getAll(ADDRESS)).hasSize(2);
            var restarted = YanoNodeBackend.connect(WalletNetwork.PREPROD, node.baseUrl());
            restarted.persistPendingInputs(file);
            status(node, 1001, 0); // Rejected draft's TTL must not unlock the other transaction's input.
            var available = restarted.selectionUtxoSupplier().getAll(ADDRESS);
            assertThat(available).extracting(Utxo::getOutputIndex).containsExactly(1);
            Transaction rebuilt = Transaction.deserialize(tx(1));
            rebuilt.getBody().setInputs(List.of(new TransactionInput(available.getFirst().getTxHash(),
                    available.getFirst().getOutputIndex())));
            rebuilt.getBody().setTtl(2000);
            node.on("/api/v1/tx/submit", "\"" + HASH + "\"");
            assertThat(restarted.transactionProcessor().submitTransaction(rebuilt.serialize()).isSuccessful()).isTrue();
            assertThat(node.requests().stream().filter(r -> r.path().contains("tx/submit"))).hasSize(2);
            node.on("/api/v1/txs/" + owner, "{\"hash\":\"" + owner + "\",\"slot\":1001,\"block_height\":10}");
            node.on(ROUTE, "[]");
            assertThat(restarted.selectionUtxoSupplier().getAll(ADDRESS)).isEmpty();
            assertThat(Files.readString(file)).doesNotContain(owner);
        }
    }

    @Test void devkitConflictResponseDoesNotEnableYanoTrackingOrChangeItsUtxos() throws Exception {
        try (var node = new StubYanoNode()) {
            node.on(ROUTE, r -> StubYanoNode.Response.json(r.path().contains("page=1&") ? utxos(0, 2) : "[]"));
            node.on("/api/v1/tx/submit", r -> new StubYanoNode.Response(400, "application/json",
                    "{\"error\":\"Mempool admission failed (CONFLICT): regular input is already claimed by "
                            + "70".repeat(32) + ": " + HASH + "#0\"}"));
            var devkit = YanoNodeBackend.connect(WalletNetwork.YACI_DEVKIT, node.baseUrl());
            Path file = directory.resolve("devkit-pending-inputs.json");
            devkit.persistPendingInputs(file);
            assertThat(devkit.transactionProcessor().submitTransaction(tx(1)).isSuccessful()).isFalse();
            assertThat(devkit.selectionUtxoSupplier()).isSameAs(devkit.utxoSupplier());
            assertThat(devkit.selectionUtxoSupplier().getAll(ADDRESS)).extracting(Utxo::getOutputIndex)
                    .containsExactly(0, 1);
            assertThat(file).doesNotExist();
            assertThat(node.requests()).allMatch(r -> r.path().startsWith(ROUTE)
                    || r.path().equals("/api/v1/tx/submit"));
        }
    }

    @Test void lookupAndStorageFailuresAreExplicit() throws Exception {
        try (var node = new StubYanoNode()) {
            status(node, 100, 0);
            node.on("/api/v1/tx/submit", "\"" + HASH + "\"");
            node.on(ROUTE, r -> r.path().contains("page=1&") ? StubYanoNode.Response.json(utxos(0, 100))
                    : new StubYanoNode.Response(500, "text/plain", "broken index"));
            var backend = YanoNodeBackend.connect(WalletNetwork.PREPROD, node.baseUrl());
            node.on("/api/v1/utxos/" + HASH + "/0", r -> new StubYanoNode.Response(500, "text/plain", "unavailable"));
            assertThatThrownBy(() -> backend.selectionUtxoSupplier().getTxOutput(HASH, 0))
                    .isInstanceOf(NodeClientException.class);
            backend.transactionProcessor().submitTransaction(tx(100));
            assertThatThrownBy(() -> backend.selectionUtxoSupplier().getAll(ADDRESS))
                    .isInstanceOf(NodeClientException.class);
            node.on("/api/v1/status", r -> new StubYanoNode.Response(500, "text/plain", "unavailable"));
            assertThatThrownBy(() -> backend.selectionUtxoSupplier().getAll(ADDRESS)).isInstanceOf(NodeClientException.class);
            Path corrupt = directory.resolve("corrupt.json");
            Files.writeString(corrupt, "broken");
            assertThatThrownBy(() -> YanoNodeBackend.connect(WalletNetwork.PREPROD, node.baseUrl())
                    .persistPendingInputs(corrupt)).isInstanceOf(java.io.UncheckedIOException.class);
        }
    }

    @Test void confirmationRefreshClearsPersistedReservationsEvenWithoutTtl() throws Exception {
        try (var node = new StubYanoNode()) {
            status(node, 100, 0);
            Transaction transaction = Transaction.deserialize(tx(1));
            transaction.getBody().setTtl(0);
            PendingInputs inputs = new PendingInputs();
            Path file = directory.resolve("pending-inputs.json");
            inputs.persistAt(file);
            String hash = inputs.reserve(transaction.serialize());
            inputs.refresh(new YanoNodeClient(node.baseUrl()));
            assertThat(inputs.snapshot()).containsExactly(HASH + "#0");
            node.on("/api/v1/txs/" + hash, "{\"hash\":\"" + hash + "\",\"block_height\":10,\"slot\":100}");
            inputs.refresh(new YanoNodeClient(node.baseUrl()));
            assertThat(inputs.snapshot()).isEmpty();
            PendingInputs restarted = new PendingInputs();
            restarted.persistAt(file);
            assertThat(restarted.snapshot()).isEmpty();
        }
    }
}
