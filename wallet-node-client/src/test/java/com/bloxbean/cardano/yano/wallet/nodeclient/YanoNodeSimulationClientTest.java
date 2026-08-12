package com.bloxbean.cardano.yano.wallet.nodeclient;

import com.bloxbean.cardano.yano.wallet.core.simulate.ResolvedOutput;
import com.bloxbean.cardano.yano.wallet.core.simulate.ScriptEvaluation;
import com.bloxbean.cardano.yano.wallet.core.simulate.SimulationCapabilities;
import com.bloxbean.cardano.yano.wallet.core.simulate.SimulationCapabilities.Support;
import com.bloxbean.cardano.yano.wallet.core.simulate.TxSimulationException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.math.BigInteger;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * ADR-042 SIM-M0/SIM-M2: the node client's simulation surface.
 *
 * <p>The load-bearing case is {@link #missingRouteIsNotAMissingUtxo()}: both a
 * node that does not serve the route and a node reporting "no such transaction"
 * answer 404, and confusing them would report "your node is too old" as "this
 * input is not yours" — silently under-reporting a loss.
 */
class YanoNodeSimulationClientTest {

    private static final String POLICY = "0f5560dbc05282e05507aedb02d823d9d9f0805037bc4b8a24e6c1b1";
    private static final String NAME_HEX = "4d494e";   // "MIN"

    private static final String OUTPUT_JSON = """
            {
              "tx_hash": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa1", "output_index": 0,
              "address": "addr_test1qq_owner",
              "amount": [
                {"unit": "lovelace", "quantity": "4500000"},
                {"unit": "%s%s", "quantity": "340"}
              ],
              "data_hash": null, "inline_datum": null,
              "script_ref": null, "reference_script_hash": null,
              "block": "beef"
            }
            """.formatted(POLICY, NAME_HEX);

    /** The {@code /txs/{hash}/utxos} path, which is what the client resolves through. */
    private static String utxosOf(String txHash) {
        return "/api/v1/txs/" + txHash + "/utxos";
    }

    /** The envelope both backends wrap their outputs in. */
    private static String txUtxos(String... outputs) {
        return "{\"hash\":\"aa11\",\"inputs\":[],\"outputs\":[" + String.join(",", outputs) + "]}";
    }

    private static final String EVAL_SUCCESS_JSON = """
            {"type":"jsonwsp/response","version":"1.0","servicename":"ogmios","methodname":"EvaluateTx",
             "result":{"EvaluationResult":{"spend:0":{"memory":1700,"steps":476468},
                                           "mint:1":{"memory":900,"steps":120000}}}}
            """;

    private static String evalFailure(String message) {
        return """
                {"type":"jsonwsp/response","version":"1.0","servicename":"ogmios","methodname":"EvaluateTx",
                 "result":{"EvaluationFailure":{"message":"%s"}}}
                """.formatted(message);
    }

    private StubYanoNode node;
    private YanoNodeClient client;

    @BeforeEach
    void setUp() throws IOException {
        node = new StubYanoNode();
        client = new YanoNodeClient(node.baseUrl());
    }

    @AfterEach
    void tearDown() {
        node.close();
    }

    // ---- input resolution --------------------------------------------------

    @Test
    void resolvesAnOutputReferenceWithItsAssets() {
        node.on(utxosOf("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa1"), txUtxos(OUTPUT_JSON));

        ResolvedOutput output = client.getUtxo("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa1", 0);

        assertThat(output).isNotNull();
        assertThat(output.address()).isEqualTo("addr_test1qq_owner");
        assertThat(output.lovelace()).isEqualTo(new BigInteger("4500000"));
        assertThat(output.assets()).singleElement().satisfies(asset -> {
            assertThat(asset.policyId()).isEqualTo(POLICY);
            assertThat(asset.assetNameHex()).isEqualTo(NAME_HEX);
            assertThat(asset.quantity()).isEqualTo(BigInteger.valueOf(340));
        });
        assertThat(output.hasDatum()).isFalse();
        assertThat(output.hasReferenceScript()).isFalse();
    }

    @Test
    void aJsonBodied404MeansTheTransactionIsNotOnChain() {
        // A Yano node's answer for a transaction it has never seen.
        node.on(utxosOf("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa1"),
                req -> new StubYanoNode.Response(404, "application/json",
                        "{\"error\":\"Transaction not found\"}"));

        assertThat(client.getUtxo("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa1", 0)).isNull();
    }

    @Test
    void anRfc7807BodiedNotFoundStillMeansTheOutputIsAbsent() {
        // yaci-store says the same thing as application/problem+json. Reading
        // "404 with a body" as "this node has no such route" made the wallet
        // declare a perfectly capable DevKit incapable of looking up inputs.
        node.on(utxosOf("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa1"),
                req -> new StubYanoNode.Response(404, "application/problem+json",
                        "{\"type\":\"about:blank\",\"title\":\"Not Found\",\"status\":404,"
                                + "\"detail\":\"Transaction not found\"}"));

        assertThat(client.getUtxo("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa1", 0))
                .isNull();
    }

    @Test
    void anEmptyBodied404IsAlsoJustAnAbsentTransaction() {
        node.on(utxosOf("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa1"),
                req -> new StubYanoNode.Response(404, "application/json", ""));

        assertThat(client.getUtxo("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa1", 0)).isNull();
    }

    @Test
    void aProblemJsonNotFoundLeavesTheProbeReportingTheNodeCapable() {
        node.on(utxosOf(zeros()),
                req -> new StubYanoNode.Response(404, "application/problem+json",
                        "{\"status\":404,\"detail\":\"Transaction not found\"}"));

        assertThat(client.probeSimulationCapabilities().utxoLookup())
                .isEqualTo(Support.AVAILABLE);
    }

    @Test
    void missingRouteIsNotAMissingUtxo() {
        // A server with no such route answers with its generic HTML error page —
        // that, not merely "has a body", is what distinguishes it from a handler
        // reporting a miss. This MUST NOT be read as "the output is not yours".
        node.on(utxosOf("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa1"),
                req -> new StubYanoNode.Response(404, "text/html",
                        "<html><body><h1>Resource not found</h1></body></html>"));
        assertThatThrownBy(() -> client.getUtxo("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa1", 0))
                .isInstanceOf(TxSimulationException.class)
                .hasMessageContaining("does not serve");
    }

    @Test
    void aNodeErrorIsNeverReportedAsAResolvedOutput() {
        node.on(utxosOf("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa1"),
                req -> new StubYanoNode.Response(503, "application/json",
                        "{\"error\":\"UTXO state disabled\"}"));

        assertThatThrownBy(() -> client.getUtxo("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa1", 0))
                .isInstanceOf(TxSimulationException.class)
                .hasMessageContaining("503");
    }

    @Test
    void aYaciStoreEnvelopeResolvesIdenticallyToAYanoOne() {
        // Verbatim from a DevKit's yaci-store: the extra keys a Yano node omits
        // (policy_id, asset_name, stake_address, inline_datum_json) and quantities
        // as JSON strings. Choosing the route where both backends agree is what
        // retired the owner_addr/amounts fallback — so this pins that they DO
        // agree, rather than leaving it as an assumption. Reading only one
        // backend's spelling once made every input on a DevKit fail to resolve,
        // which the user saw as "the effect on your wallet cannot be computed".
        node.on(utxosOf("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa1"), """
                {"hash":"aa11","inputs":[],"outputs":[
                  {"tx_hash":"aa11","output_index":0,
                   "address":"addr_test1qq_owner",
                   "stake_address":"stake_test1uz_owner",
                   "amount":[{"unit":"lovelace","policy_id":null,"asset_name":"lovelace",
                              "quantity":"2828603"},
                             {"unit":"%s%s","policy_id":"%s","asset_name":"MIN","quantity":"340"}],
                   "data_hash":null,"inline_datum":null,"inline_datum_json":null,
                   "script_ref":null,"reference_script_hash":null}]}
                """.formatted(POLICY, NAME_HEX, POLICY));

        ResolvedOutput output = client.getUtxo(
                "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa1", 0);

        assertThat(output.address()).isEqualTo("addr_test1qq_owner");
        assertThat(output.lovelace()).isEqualTo(new BigInteger("2828603"));
        assertThat(output.assets()).singleElement().satisfies(asset -> {
            assertThat(asset.policyId()).isEqualTo(POLICY);
            assertThat(asset.assetNameHex()).isEqualTo(NAME_HEX);
            assertThat(asset.quantity()).isEqualTo(BigInteger.valueOf(340));
        });
    }

    @Test
    void theRequestedOutputIsPickedByItsDeclaredIndexNotItsPosition() {
        // THE case this route introduces. It answers with every output of the
        // transaction, so the wallet has to pick one — and nothing promises the
        // list is ordered or complete. Taking outputs[index] on a list that is
        // neither resolves a different address holding a different amount, while
        // still reporting the summary as complete: the confident-smaller-loss
        // outcome ADR-042 exists to prevent.
        node.on(utxosOf("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa1"), txUtxos(
                """
                {"output_index":2,"address":"addr_attacker",
                 "amount":[{"unit":"lovelace","quantity":"1"}]}""",
                """
                {"output_index":0,"address":"addr_mine",
                 "amount":[{"unit":"lovelace","quantity":"9000000"}]}"""));

        ResolvedOutput output = client.getUtxo(
                "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa1", 0);

        assertThat(output.address()).isEqualTo("addr_mine");
        assertThat(output.lovelace()).isEqualTo(new BigInteger("9000000"));
    }

    @Test
    void anIndexTheTransactionDoesNotHaveIsUnresolvedRatherThanWrong() {
        node.on(utxosOf("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa1"), txUtxos("""
                {"output_index":0,"address":"addr_mine",
                 "amount":[{"unit":"lovelace","quantity":"9000000"}]}"""));

        assertThat(client.getUtxo("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa1", 7))
                .isNull();
    }

    @Test
    void anOutputThatDoesNotDeclareItsIndexIsRefusedNotGuessed() {
        node.on(utxosOf("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa1"), txUtxos("""
                {"address":"addr_mine","amount":[{"unit":"lovelace","quantity":"9000000"}]}"""));

        assertThatThrownBy(() ->
                client.getUtxo("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa1", 0))
                .isInstanceOf(TxSimulationException.class)
                .hasMessageContaining("output_index");
    }

    @Test
    void aDeadNodeIsAnErrorNotAnUnownedInput() {
        // The other half of "could not ask is never not-yours": infrastructure
        // failure, not a 404.
        node.close();

        assertThatThrownBy(() -> client.getUtxo("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa1", 0))
                .isInstanceOf(TxSimulationException.class);
    }

    @Test
    void aMalformed200IsRejectedRatherThanSilentlyUndercounted() {
        // Every one of these, read leniently, would remove value from "what
        // leaves my wallet" while still reporting the summary as complete — the
        // confident-smaller-loss case ADR-042 calls the worst outcome. Each entry
        // declares output_index 0 so that what is under test is the reading of the
        // entry, not the selecting of it.
        record Case(String label, String body) {
        }
        List.of(
                new Case("empty body", ""),
                new Case("not an object", "[]"),
                new Case("no outputs key", "{\"hash\":\"aa11\",\"inputs\":[]}"),
                new Case("outputs not an array", "{\"hash\":\"aa11\",\"outputs\":{}}"),
                new Case("no address", txUtxos("""
                        {"output_index":0,"amount":[{"unit":"lovelace","quantity":"1000000"}]}""")),
                new Case("blank address", txUtxos("""
                        {"output_index":0,"address":"  ",
                         "amount":[{"unit":"lovelace","quantity":"1000000"}]}""")),
                new Case("no amount list", txUtxos("""
                        {"output_index":0,"address":"addr_test1qq"}""")),
                new Case("amount not an array", txUtxos("""
                        {"output_index":0,"address":"addr_test1qq","amount":{"unit":"lovelace"}}""")),
                new Case("short asset unit", txUtxos("""
                        {"output_index":0,"address":"addr_test1qq",
                         "amount":[{"unit":"abcd","quantity":"5"}]}""")),
                new Case("unreadable quantity", txUtxos("""
                        {"output_index":0,"address":"addr_test1qq",
                         "amount":[{"unit":"lovelace","quantity":"1.5e6"}]}""")),
                new Case("owner_addr only — the non-standard spelling is no longer read", txUtxos("""
                        {"output_index":0,"owner_addr":"addr_test1qq",
                         "amounts":[{"unit":"lovelace","quantity":"1000000"}]}"""))
        ).forEach(testCase -> {
            node.on(utxosOf("bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb2"), testCase.body());
            assertThatThrownBy(() -> client.getUtxo("bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb2", 0))
                    .as(testCase.label())
                    .isInstanceOf(TxSimulationException.class);
        });
    }

    @Test
    void duplicateLovelaceEntriesSumRatherThanOverwrite() {
        node.on(utxosOf("ccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc3"), txUtxos("""
                {"output_index":0,"address":"addr_test1qq","amount":[
                  {"unit":"lovelace","quantity":"1000000"},
                  {"unit":"lovelace","quantity":"500000"}]}"""));

        assertThat(client.getUtxo("ccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc3", 0).lovelace()).isEqualTo(new BigInteger("1500000"));
    }

    @Test
    void quantitiesBeyondLongAreCarriedExactly() {
        String huge = "123456789012345678901234567890";
        node.on(utxosOf("ddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd4"), txUtxos("""
                {"output_index":0,"address":"addr_test1qq","amount":[
                  {"unit":"lovelace","quantity":"1000000"},
                  {"unit":"%s%s","quantity":"%s"}]}""".formatted(POLICY, NAME_HEX, huge)));

        assertThat(client.getUtxo("ddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd4", 0).assets())
                .singleElement()
                .satisfies(asset -> assertThat(asset.quantity()).isEqualTo(new BigInteger(huge)));
    }

    @Test
    void aTxHashIsNeverInterpolatedIntoTheUrlUnchecked() {
        assertThatThrownBy(() -> client.getUtxo("aa11/../status", 0))
                .isInstanceOf(TxSimulationException.class)
                .hasMessageContaining("Not a transaction hash");
        assertThatThrownBy(() -> client.getUtxo("ZZ".repeat(32), 0))
                .isInstanceOf(TxSimulationException.class);
    }

    @Test
    void aGenuineScriptFailureMentioningAvailabilityIsStillAFailure() {
        // An attacker-influenced validator trace must not be able to downgrade
        // "this WILL fail and burn your collateral" to "could not verify".
        node.on("/api/v1/utils/txs/evaluate",
                evalFailure("validator error: the requested datum is not available"));

        assertThat(client.evaluateTx("84a4").outcome()).isEqualTo(ScriptEvaluation.Outcome.FAILURE);
    }

    @Test
    void nodeInfrastructureErrorsAreNotReportedAsFailedTransactions() {
        // These are the node being unable to check. Reporting them as FAILURE
        // would tell the user their transaction fails when nothing ever ran.
        List.of("Failed to resolve current slot from runtime",
                "Cannot resolve SlotConfig zeroTime: no valid genesis timestamp is available",
                "Protocol version not found or invalid in protocol-param.json",
                "Transaction evaluation is not available")
                .forEach(message -> {
                    node.on("/api/v1/utils/txs/evaluate", evalFailure(message));
                    assertThat(client.evaluateTx("84a4").outcome())
                            .as(message)
                            .isEqualTo(ScriptEvaluation.Outcome.UNAVAILABLE);
                });
    }

    @Test
    void datumAndReferenceScriptFlagsAreReported() {
        // reference_script_hash without script_ref on purpose: a Yano node's
        // tx-utxos DTO has no script_ref field at all, so the reference-script flag
        // has to survive on the hash alone.
        node.on(utxosOf("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa1"), txUtxos("""
                {"tx_hash":"aa11","output_index":1,"address":"addr_test1_script",
                 "amount":[{"unit":"lovelace","quantity":"2000000"}],
                 "data_hash":"d00d","inline_datum":null,
                 "reference_script_hash":"5c1b"}"""));

        ResolvedOutput output = client.getUtxo("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa1", 1);

        assertThat(output.hasDatum()).isTrue();
        assertThat(output.hasReferenceScript()).isTrue();
    }

    // ---- script evaluation -------------------------------------------------

    @Test
    void parsesExUnitsPerRedeemer() {
        node.on("/api/v1/utils/txs/evaluate", EVAL_SUCCESS_JSON);

        ScriptEvaluation evaluation = client.evaluateTx("84a4");

        assertThat(evaluation.outcome()).isEqualTo(ScriptEvaluation.Outcome.SUCCESS);
        assertThat(evaluation.costs()).hasSize(2);
        assertThat(evaluation.costs()).anySatisfy(cost -> {
            assertThat(cost.tag()).isEqualTo("spend");
            assertThat(cost.index()).isZero();
            assertThat(cost.memory()).isEqualTo(1700);
            assertThat(cost.steps()).isEqualTo(476468);
        });
        assertThat(evaluation.costs()).anySatisfy(cost -> {
            assertThat(cost.tag()).isEqualTo("mint");
            assertThat(cost.index()).isEqualTo(1);
        });
    }

    @Test
    void evaluationFailureArrivesAsHttp200AndIsReadFromTheBody() {
        node.on("/api/v1/utils/txs/evaluate", evalFailure("validation failed for script spend:0"));

        ScriptEvaluation evaluation = client.evaluateTx("84a4");

        assertThat(evaluation.outcome()).isEqualTo(ScriptEvaluation.Outcome.FAILURE);
        assertThat(evaluation.message()).contains("spend:0");
    }

    @Test
    void anUninitialisedEvaluatorIsUnavailableNotAFailedTransaction() {
        // The node says this when tx-evaluation is off or protocol params are
        // missing. Nothing was run, so the transaction has NOT been shown to fail.
        node.on("/api/v1/utils/txs/evaluate",
                evalFailure("Script evaluation not initialized. Ensure tx-evaluation is enabled"));

        ScriptEvaluation evaluation = client.evaluateTx("84a4");

        assertThat(evaluation.outcome()).isEqualTo(ScriptEvaluation.Outcome.UNAVAILABLE);
    }

    @Test
    void anAbsentEvaluateRouteIsUnavailableRatherThanAnException() {
        // No handler → default 404. The user still needs a prompt, so this must
        // be a renderable outcome, not a thrown error.
        ScriptEvaluation evaluation = client.evaluateTx("84a4");

        assertThat(evaluation.outcome()).isEqualTo(ScriptEvaluation.Outcome.UNAVAILABLE);
    }

    // ---- capability probe --------------------------------------------------

    @Test
    void probeReportsBothCapabilitiesOnACapableNode() {
        node.on(utxosOf(zeros()),
                req -> new StubYanoNode.Response(404, "application/json", ""));
        node.on("/api/v1/utils/txs/evaluate", evalFailure("CBOR deserialization failed"));
        node.on("/api/v1/node/config", "{\"protocolMagic\":42,\"version\":\"0.1.0-pre12\"}");

        SimulationCapabilities capabilities = client.probeSimulationCapabilities();

        assertThat(capabilities.utxoLookup()).isEqualTo(Support.AVAILABLE);
        assertThat(capabilities.scriptEvaluation()).isEqualTo(Support.AVAILABLE);
        assertThat(capabilities.canSimulateFully()).isTrue();
        assertThat(capabilities.limitation()).isNull();
        assertThat(capabilities.nodeVersion()).isEqualTo("0.1.0-pre12");
    }

    @Test
    void probeReportsAnOlderNodeAsUnavailableWithAPlainLanguageReason() {
        // A node without these routes answers with its HTML error page.
        node.on(utxosOf(zeros()), req -> new StubYanoNode.Response(404,
                "text/html", "<html><body>Resource not found</body></html>"));
        SimulationCapabilities capabilities = client.probeSimulationCapabilities();

        assertThat(capabilities.utxoLookup()).isEqualTo(Support.UNAVAILABLE);
        assertThat(capabilities.scriptEvaluation()).isEqualTo(Support.UNAVAILABLE);
        assertThat(capabilities.canResolveInputs()).isFalse();
        assertThat(capabilities.limitation())
                .contains("cannot look up transaction inputs")
                .doesNotContain("404");
    }

    @Test
    void evaluationCanBeOffWhileInputResolutionWorks() {
        node.on(utxosOf(zeros()),
                req -> new StubYanoNode.Response(404, "application/json", ""));
        node.on("/api/v1/utils/txs/evaluate",
                evalFailure("Script evaluation not initialized. Ensure tx-evaluation is enabled"));

        SimulationCapabilities capabilities = client.probeSimulationCapabilities();

        assertThat(capabilities.canResolveInputs()).isTrue();
        assertThat(capabilities.canSimulateFully()).isFalse();
        assertThat(capabilities.limitation()).contains("cannot evaluate Plutus scripts");
    }

    @Test
    void aDisabledUtxoIndexIsReportedAsSuch() {
        node.on(utxosOf(zeros()), req -> new StubYanoNode.Response(503,
                "application/json", "{\"error\":\"UTXO state disabled\"}"));

        SimulationCapabilities capabilities = client.probeSimulationCapabilities();

        assertThat(capabilities.utxoLookup()).isEqualTo(Support.UNAVAILABLE);
        assertThat(capabilities.limitation()).contains("UTxO index is switched off");
    }

    @Test
    void anUnreachableNodeIsUnknownNotUnavailable() throws IOException {
        // Written off vs. could-not-tell: a node that hiccups once must not be
        // permanently downgraded.
        StubYanoNode dead = new StubYanoNode();
        String deadUrl = dead.baseUrl();
        dead.close();
        YanoNodeClient deadClient = new YanoNodeClient(deadUrl);

        SimulationCapabilities capabilities = deadClient.probeSimulationCapabilities();

        assertThat(capabilities.utxoLookup()).isEqualTo(Support.UNKNOWN);
        assertThat(capabilities.scriptEvaluation()).isEqualTo(Support.UNKNOWN);
        assertThat(capabilities.limitation()).contains("Could not confirm");
    }

    @Test
    void versionIsCarriedForMessagesButNeverDrivesTheDecision() {
        // A locally built node newer than the pinned release reporting an OLDER
        // version must still be reported as capable — capability comes from the
        // probe, the version string only decorates the message.
        node.on(utxosOf(zeros()),
                req -> new StubYanoNode.Response(404, "application/json", ""));
        node.on("/api/v1/utils/txs/evaluate", evalFailure("CBOR deserialization failed"));
        node.on("/api/v1/node/config", "{\"version\":\"0.1.0-pre11\"}");

        SimulationCapabilities capabilities = client.probeSimulationCapabilities();

        assertThat(capabilities.canSimulateFully()).isTrue();
        assertThat(capabilities.nodeVersion()).isEqualTo("0.1.0-pre11");
    }

    @Test
    void anAbsentVersionEndpointIsNotAnError() {
        assertThat(client.nodeVersionOrNull()).isNull();
    }

    private static String zeros() {
        return "0".repeat(64);
    }
}
