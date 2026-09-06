package com.bloxbean.cardano.yano.wallet.nodeclient;

import com.bloxbean.cardano.client.account.Account;
import com.bloxbean.cardano.client.api.model.Amount;
import com.bloxbean.cardano.client.backend.api.DefaultUtxoSupplier;
import com.bloxbean.cardano.client.backend.blockfrost.service.BFBackendService;
import com.bloxbean.cardano.client.common.model.Networks;
import com.bloxbean.cardano.client.function.helper.SignerProviders;
import com.bloxbean.cardano.client.quicktx.QuickTxBuilder;
import com.bloxbean.cardano.client.quicktx.Tx;
import com.bloxbean.cardano.client.transaction.spec.Asset;
import com.bloxbean.cardano.client.transaction.spec.script.ScriptPubkey;
import com.bloxbean.cardano.yano.wallet.core.service.HistoryPort.TxRef;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Opt-in: launches only its own disposable devnet; never attaches to a user's node. */
class WalletIndexLiveTest {
    private static final String MNEMONIC = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about";
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient http = HttpClient.newHttpClient();
    private Path directory;
    private Process node;
    private String base;
    private List<String> command;
    private int run;

    @Test void walletPersistsAssetsAndRecoversAfterNodeRestartCrashAndSnapshotReorg() throws Exception {
        String artifact = System.getProperty("yano.wallet.live.artifact");
        String config = System.getProperty("yano.wallet.live.config");
        assumeTrue(artifact != null && config != null, "Set wallet live artifact and devnet config paths to opt in");
        directory = Files.createTempDirectory("yano-wallet-119-live-");
        System.out.println("Wallet live evidence: " + directory);
        Account sender = new Account(Networks.testnet(), MNEMONIC, 0);
        Account receiver = new Account(Networks.testnet(), "legal winner thank year wave sausage worth useful legal winner thank yellow", 0);
        prepare(Path.of(artifact).toAbsolutePath(), Path.of(config).toAbsolutePath(), sender);
        try {
            start();
            var backend = new BFBackendService(base, "local-test");
            var supplier = new DefaultUtxoSupplier(backend.getUtxoService());
            assertThat(client().isAddressUsed(sender.baseAddress())).isTrue();
            assertThat(client().isAddressUsed(receiver.baseAddress())).isFalse();
            assertThat(history(receiver)).isEmpty();
            post("devnet/snapshot", mapper.createObjectNode().put("name", "before-transfers"));

            var policy = ScriptPubkey.createWithNewKey();
            Tx receive = new Tx().payToAddress(receiver.baseAddress(), Amount.ada(10))
                    .mintAssets(policy._1, new Asset("Wallet119", BigInteger.valueOf(100)), receiver.baseAddress())
                    .from(sender.baseAddress());
            var received = new QuickTxBuilder(backend).compose(receive)
                    .withSigner(SignerProviders.signerFrom(sender))
                    .withSigner(SignerProviders.signerFrom(policy._2.getSkey())).complete();
            assertThat(received.isSuccessful()).as(received.getResponse()).isTrue();
            awaitUtxo(supplier, receiver.baseAddress(), received.getValue());
            assertThat(history(receiver)).extracting(TxRef::txHash).containsExactly(received.getValue());
            JsonNode saved = saved();
            assertThat(saved.path("current").path("outputs").size()).isPositive();
            assertThat(saved.toString()).contains("57616c6c6574313139");
            assertThat(sender.stakeAddress()).isNotEqualTo(receiver.stakeAddress());
            long firstSeen = get("addresses/" + receiver.baseAddress() + "/first-seen").path("firstSeenSlot").asLong();
            assertThat(firstSeen).isPositive();

            stop(false);
            start();
            assertThat(history(receiver)).extracting(TxRef::txHash).containsExactly(received.getValue());
            assertThat(saved().path("current").path("outputs")).isEqualTo(saved.path("current").path("outputs"));
            Tx spend = new Tx().payToAddress(sender.baseAddress(), List.of(Amount.ada(8),
                    Amount.asset(policy._1.getPolicyId() + HexFormat.of().formatHex("Wallet119".getBytes(StandardCharsets.UTF_8)), 100)))
                    .from(receiver.baseAddress()).withChangeAddress(sender.baseAddress());
            var spent = new QuickTxBuilder(backend).compose(spend).feePayer(sender.baseAddress())
                    .withSigner(SignerProviders.signerFrom(receiver)).withSigner(SignerProviders.signerFrom(sender)).complete();
            assertThat(spent.isSuccessful()).as(spent.getResponse()).isTrue();
            awaitUtxo(supplier, sender.baseAddress(), spent.getValue());
            assertThat(supplier.getAll(receiver.baseAddress())).isEmpty();
            // A newly constructed wallet port must recover its input ownership from disk.
            assertThat(history(receiver)).extracting(TxRef::txHash).containsExactly(received.getValue(), spent.getValue());
            assertThat(saved().path("current").path("outputs").size()).isZero();
            assertThat(client().isAddressUsed(receiver.baseAddress())).isTrue();
            assertThat(get("addresses/" + receiver.baseAddress() + "/first-seen").path("firstSeenSlot").asLong()).isEqualTo(firstSeen);

            stop(true);
            start();
            assertThat(history(receiver)).extracting(TxRef::txHash).containsExactly(received.getValue(), spent.getValue());
            post("devnet/restore/before-transfers", mapper.createObjectNode());
            assertThat(history(receiver)).isEmpty();
            assertThat(saved().path("current").path("outputs").size()).isZero();
            assertThat(client().isAddressUsed(receiver.baseAddress())).isFalse();

            var replacement = new QuickTxBuilder(backend).compose(new Tx()
                    .payToAddress(receiver.baseAddress(), Amount.ada(11)).from(sender.baseAddress()))
                    .withSigner(SignerProviders.signerFrom(sender)).complete();
            assertThat(replacement.isSuccessful()).as(replacement.getResponse()).isTrue();
            awaitUtxo(supplier, receiver.baseAddress(), replacement.getValue());
            assertThat(history(receiver)).extracting(TxRef::txHash).containsExactly(replacement.getValue());
            assertThat(client().isAddressUsed(receiver.baseAddress())).isTrue();
        } finally {
            stop(false);
        }
    }

    private void prepare(Path artifact, Path config, Account sender) throws Exception {
        assertThat(artifact).isRegularFile();
        Path target = directory.resolve("config/network/devnet");
        Files.createDirectories(target);
        try (var files = Files.list(config)) {
            for (Path file : files.filter(Files::isRegularFile).toList()) Files.copy(file, target.resolve(file.getFileName()));
        }
        Path genesisFile = target.resolve("shelley-genesis.json");
        ObjectNode genesis = (ObjectNode) mapper.readTree(Files.readAllBytes(genesisFile));
        genesis.put("epochLength", 50).put("slotLength", 0.2);
        genesis.withObject("initialFunds").put(HexFormat.of().formatHex(sender.getBaseAddress().getBytes()), 1_000_000_000L);
        Files.write(genesisFile, mapper.writeValueAsBytes(genesis));
        int httpPort = freePort();
        int peerPort;
        do { peerPort = freePort(); } while (peerPort == httpPort);
        base = "http://127.0.0.1:" + httpPort + "/api/v1/";
        command = new ArrayList<>();
        boolean jar = artifact.toString().endsWith(".jar");
        command.add(jar ? Path.of(System.getProperty("java.home"), "bin/java").toString() : artifact.toString());
        command.addAll(List.of("-Xmx1g", "-Dquarkus.profile=devnet", "-Dquarkus.http.host=127.0.0.1",
                "-Dquarkus.http.port=" + httpPort, "-Dyano.server.port=" + peerPort,
                "-Dyano.scan.index.enabled=true", "-Dyano.address-first-seen.enabled=true",
                "-Dyano.history.projection.enabled=false", "-Dyano.chain.block-body-prune-depth=0",
                "-Dyano.plugins.enabled=false"));
        if (jar) command.addAll(List.of("-jar", artifact.toString()));
        Files.writeString(directory.resolve("command.txt"), String.join("\n", command));
    }

    private void start() throws Exception {
        node = new ProcessBuilder(command).directory(directory.toFile()).redirectErrorStream(true)
                .redirectOutput(directory.resolve("node-" + (++run) + ".log").toFile()).start();
        long deadline = System.nanoTime() + Duration.ofSeconds(90).toNanos();
        while (System.nanoTime() < deadline && node.isAlive()) {
            try {
                if (get("node/status").isObject() && client().isAddressUsed(new Account(Networks.testnet(), MNEMONIC, 0).baseAddress())) return;
            } catch (Exception | AssertionError notReady) { /* wait for ledger and index readiness */ }
            Thread.sleep(200);
        }
        throw new AssertionError("Devnet did not become ready; see " + directory);
    }

    private void stop(boolean crash) throws Exception {
        if (node == null || !node.isAlive()) return;
        if (crash) node.destroyForcibly(); else node.destroy();
        if (!node.waitFor(30, TimeUnit.SECONDS)) {
            node.destroyForcibly();
            assertThat(node.waitFor(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    private List<TxRef> history(Account account) {
        YanoNodePorts ports = new YanoNodePorts(client());
        ports.enableScanHistory(directory.resolve("wallet-history"));
        return ports.walletTransactions(account.stakeAddress(), account.baseAddress(), 1, 100, false);
    }
    private YanoNodeClient client() { return new YanoNodeClient(base, false); }
    private JsonNode saved() throws Exception {
        try (var files = Files.list(directory.resolve("wallet-history"))) {
            return mapper.readTree(Files.readAllBytes(files.filter(p -> p.toString().endsWith(".json")).findFirst().orElseThrow()));
        }
    }
    private void awaitUtxo(DefaultUtxoSupplier supplier, String address, String hash) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        while (System.nanoTime() < deadline) {
            if (supplier.getAll(address).stream().anyMatch(u -> u.getTxHash().equals(hash))) return;
            Thread.sleep(100);
        }
        throw new AssertionError("Transaction not applied: " + hash);
    }
    private JsonNode get(String path) throws Exception {
        var response = http.send(HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(5))
                .GET().build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return mapper.readTree(response.body());
    }
    private void post(String path, JsonNode body) throws Exception {
        var response = http.send(HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(60))
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body.toString())).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
    }
    private static int freePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) { return socket.getLocalPort(); }
    }
}
