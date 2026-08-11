package com.bloxbean.cardano.yano.wallet.core.simulate;

import com.bloxbean.cardano.client.address.AddressProvider;
import com.bloxbean.cardano.client.address.Credential;
import com.bloxbean.cardano.client.common.model.Networks;
import com.bloxbean.cardano.client.transaction.spec.Asset;
import com.bloxbean.cardano.client.transaction.spec.MultiAsset;
import com.bloxbean.cardano.client.transaction.spec.Transaction;
import com.bloxbean.cardano.client.transaction.spec.TransactionBody;
import com.bloxbean.cardano.client.transaction.spec.TransactionInput;
import com.bloxbean.cardano.client.transaction.spec.TransactionOutput;
import com.bloxbean.cardano.client.transaction.spec.TransactionWitnessSet;
import com.bloxbean.cardano.client.transaction.spec.Value;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.Executor;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ADR-042 SIM-M5: verification.
 *
 * <p>The other tests check the engine against cases I thought of, which means
 * they share my assumptions. These check it against the <em>ledger's</em>
 * arithmetic instead, over randomly generated transactions:
 *
 * <pre>  Σinputs + Σwithdrawals = Σoutputs + fee</pre>
 *
 * Partitioning that identity by ownership gives a value the engine never
 * computes and cannot fudge:
 *
 * <pre>  walletNet + strangersNet = −fee</pre>
 *
 * where {@code strangersNet} is summed independently from the same transaction.
 * If the diff drops an input, double-counts an output, mishandles a withdrawal
 * or loses an asset, this identity breaks — regardless of whether I anticipated
 * that particular mistake.
 *
 * <p>The second property is the security invariant itself, stated as something
 * that must hold for every transaction rather than for one example: degrading
 * what the node can tell us must never shrink the loss we report as verified.
 *
 * <h2>What this is not</h2>
 *
 * The ADR asks for replay of real preprod/mainnet transactions. That needs the
 * transaction's CBOR, and the pinned node serves no {@code /txs/{hash}/cbor}
 * (verified against a running node — it 404s, as does {@code /blocks/{hash}/txs}).
 * Until the node exposes raw transaction bytes, chain replay cannot be done from
 * the wallet's own node, and doing it from a third-party API is exactly the
 * trust dependency ADR-041 exists to avoid. These properties are the strongest
 * check available offline.
 */
class TxEffectConservationTest {

    private static final int TRANSACTIONS = 300;
    private static final BigInteger ADA = BigInteger.valueOf(1_000_000);
    private static final Executor DIRECT = Runnable::run;
    private static final String POLICY = "0f5560dbc05282e05507aedb02d823d9d9f0805037bc4b8a24e6c1b1";
    private static final List<String> ASSET_NAMES = List.of("4d494e", "484f534b59", "");

    private static byte[] credential(int seed) {
        byte[] bytes = new byte[28];
        for (int i = 0; i < bytes.length; i++) {
            bytes[i] = (byte) (seed + i);
        }
        return bytes;
    }

    private final String myAddress = AddressProvider.getBaseAddress(
            Credential.fromKey(credential(1)), Credential.fromKey(credential(9)),
            Networks.testnet()).toBech32();
    private final String theirAddress = AddressProvider.getBaseAddress(
            Credential.fromKey(credential(40)), Credential.fromKey(credential(70)),
            Networks.testnet()).toBech32();
    private final String myRewardAddress = AddressProvider.getRewardAddress(
            Credential.fromKey(credential(9)), Networks.testnet()).toBech32();

    private final WalletOwnership ownership =
            WalletOwnership.of(List.of(myAddress), List.of(myRewardAddress));

    /** A transaction plus the resolved inputs it was generated from. */
    private record Generated(String txHex, Map<String, ResolvedOutput> resolved,
                             List<TransactionInput> inputs, TransactionBody body,
                             BigInteger myWithdrawal) {
    }

    private static final class MapPort implements TxSimulationPort {
        private final Map<String, ResolvedOutput> resolved;
        private final java.util.Set<String> broken = new java.util.HashSet<>();

        MapPort(Map<String, ResolvedOutput> resolved) {
            this.resolved = resolved;
        }

        @Override
        public SimulationCapabilities capabilities() {
            return new SimulationCapabilities(SimulationCapabilities.Support.AVAILABLE,
                    SimulationCapabilities.Support.UNAVAILABLE, null, null);
        }

        @Override
        public ResolvedOutput resolveOutput(String txHash, int outputIndex) {
            String key = txHash + "#" + outputIndex;
            if (broken.contains(key)) {
                return null;
            }
            return resolved.get(key);
        }

        @Override
        public ScriptEvaluation evaluate(String txHex) {
            return ScriptEvaluation.unavailable("not part of this property");
        }
    }

    // ---- generation ---------------------------------------------------------

    private Generated generate(Random random) {
        int inputCount = 1 + random.nextInt(4);
        int outputCount = 1 + random.nextInt(4);

        List<TransactionInput> inputs = new ArrayList<>();
        Map<String, ResolvedOutput> resolved = new HashMap<>();
        BigInteger inputLovelace = BigInteger.ZERO;
        Map<String, BigInteger> inputAssets = new HashMap<>();

        for (int i = 0; i < inputCount; i++) {
            String hash = HexFormat.of().formatHex(new byte[]{(byte) i, (byte) random.nextInt(200)})
                    .repeat(16).substring(0, 64);
            TransactionInput in = TransactionInput.builder().transactionId(hash).index(i).build();
            inputs.add(in);
            String owner = random.nextBoolean() ? myAddress : theirAddress;
            BigInteger lovelace = ADA.multiply(BigInteger.valueOf(1 + random.nextInt(50)));
            List<AssetQuantity> assets = new ArrayList<>();
            if (random.nextInt(3) == 0) {
                String name = ASSET_NAMES.get(random.nextInt(ASSET_NAMES.size()));
                BigInteger quantity = BigInteger.valueOf(1 + random.nextInt(1000));
                assets.add(new AssetQuantity(POLICY, name, quantity));
                inputAssets.merge(POLICY + name, quantity, BigInteger::add);
            }
            resolved.put(hash + "#" + i, new ResolvedOutput(owner, lovelace, assets, false, false));
            inputLovelace = inputLovelace.add(lovelace);
        }

        // A withdrawal from our own reward account, sometimes.
        BigInteger myWithdrawal = random.nextInt(4) == 0
                ? ADA.multiply(BigInteger.valueOf(1 + random.nextInt(20)))
                : BigInteger.ZERO;

        BigInteger fee = ADA.divide(BigInteger.valueOf(2 + random.nextInt(4)));
        BigInteger distributable = inputLovelace.add(myWithdrawal).subtract(fee);

        // Split the available value across outputs so the ledger identity holds
        // exactly — this is what makes the generated transaction well-formed.
        List<TransactionOutput> outputs = new ArrayList<>();
        BigInteger remaining = distributable;
        for (int i = 0; i < outputCount; i++) {
            boolean last = i == outputCount - 1;
            BigInteger amount = last ? remaining
                    : remaining.divide(BigInteger.valueOf(outputCount - i));
            remaining = remaining.subtract(amount);
            String owner = random.nextBoolean() ? myAddress : theirAddress;
            outputs.add(TransactionOutput.builder()
                    .address(owner)
                    .value(Value.builder().coin(amount).build())
                    .build());
        }
        // All input assets land on the first output (no mint/burn generated), so
        // asset conservation must hold too.
        if (!inputAssets.isEmpty()) {
            List<Asset> assetList = new ArrayList<>();
            inputAssets.forEach((unit, quantity) -> assetList.add(Asset.builder()
                    .name("0x" + unit.substring(POLICY.length()))
                    .value(quantity)
                    .build()));
            TransactionOutput first = outputs.get(0);
            first.setValue(Value.builder()
                    .coin(first.getValue().getCoin())
                    .multiAssets(List.of(MultiAsset.builder().policyId(POLICY).assets(assetList).build()))
                    .build());
        }

        TransactionBody.TransactionBodyBuilder body = TransactionBody.builder()
                .inputs(inputs).outputs(outputs).fee(fee);
        if (myWithdrawal.signum() > 0) {
            body.withdrawals(List.of(new com.bloxbean.cardano.client.transaction.spec.Withdrawal(
                    myRewardAddress, myWithdrawal)));
        }
        TransactionBody built = body.build();
        try {
            String hex = HexFormat.of().formatHex(Transaction.builder()
                    .body(built).witnessSet(new TransactionWitnessSet()).build().serialize());
            return new Generated(hex, resolved, inputs, built, myWithdrawal);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** The other side of the identity, summed independently of the engine. */
    private BigInteger strangersNet(Generated generated) {
        BigInteger net = BigInteger.ZERO;
        for (TransactionOutput output : generated.body().getOutputs()) {
            if (!myAddress.equals(output.getAddress())) {
                net = net.add(output.getValue().getCoin());
            }
        }
        for (TransactionInput input : generated.inputs()) {
            ResolvedOutput resolved = generated.resolved().get(
                    input.getTransactionId() + "#" + input.getIndex());
            if (!myAddress.equals(resolved.address())) {
                net = net.subtract(resolved.lovelace());
            }
        }
        return net;
    }

    // ---- the properties -----------------------------------------------------

    @Test
    void theDiffObeysLedgerValueConservation() {
        Random random = new Random(20260810L);   // fixed seed: a failure is reproducible
        for (int i = 0; i < TRANSACTIONS; i++) {
            Generated generated = generate(random);
            TxEffect effect = new TxEffectEngine(new MapPort(generated.resolved()), DIRECT)
                    .analyse(generated.txHex(), ownership);

            assertThat(effect.completeness())
                    .as("every input was resolvable in transaction %d", i)
                    .isEqualTo(TxEffect.Completeness.COMPLETE);
            assertThat(effect.lovelaceDelta().add(strangersNet(generated)))
                    .as("wallet net + strangers net must equal −fee (transaction %d)", i)
                    .isEqualTo(generated.body().getFee().negate());
        }
    }

    @Test
    void everyAssetInTheTransactionIsAccountedForExactly() {
        // Deliberately driven from the units present in the TRANSACTION, not from
        // the deltas the engine chose to report. Iterating the engine's own output
        // makes a dropped asset pass vacuously — and an unnamed token silently
        // leaving the wallet is precisely the case ADR-042 says an attacker wants
        // invisible.
        Random random = new Random(981L);
        for (int i = 0; i < TRANSACTIONS; i++) {
            Generated generated = generate(random);
            TxEffect effect = new TxEffectEngine(new MapPort(generated.resolved()), DIRECT)
                    .analyse(generated.txHex(), ownership);

            Map<String, BigInteger> reported = new HashMap<>();
            for (TxEffect.AssetDelta delta : effect.assetDeltas()) {
                reported.merge(delta.policyId() + "|" + delta.assetNameHex(), delta.quantity(),
                        BigInteger::add);
            }

            for (String unit : unitsIn(generated)) {
                String[] parts = unit.split("\\|", -1);
                BigInteger expected = myAssetNet(generated, parts[0], parts[1]);
                BigInteger actual = reported.getOrDefault(unit, BigInteger.ZERO);
                assertThat(actual)
                        .as("asset '%s' of transaction %d: engine said %s, transaction says %s",
                                parts[1], i, actual, expected)
                        .isEqualTo(expected);
            }
            // And nothing invented: every reported unit must exist in the transaction.
            assertThat(reported.keySet()).isSubsetOf(unitsIn(generated));
        }
    }

    /** Every {@code policy|name} unit appearing anywhere in the transaction. */
    private java.util.Set<String> unitsIn(Generated generated) {
        java.util.Set<String> units = new java.util.LinkedHashSet<>();
        for (ResolvedOutput resolved : generated.resolved().values()) {
            for (AssetQuantity asset : resolved.assets()) {
                units.add(asset.policyId() + "|" + asset.assetNameHex());
            }
        }
        for (TransactionOutput output : generated.body().getOutputs()) {
            if (output.getValue().getMultiAssets() == null) {
                continue;
            }
            for (MultiAsset multiAsset : output.getValue().getMultiAssets()) {
                for (Asset asset : multiAsset.getAssets()) {
                    units.add(multiAsset.getPolicyId() + "|"
                            + HexFormat.of().formatHex(asset.getNameAsBytes()));
                }
            }
        }
        return units;
    }

    /** What the wallet's balance of one asset truly changes by, summed from the transaction. */
    private BigInteger myAssetNet(Generated generated, String policyId, String assetNameHex) {
        BigInteger net = BigInteger.ZERO;
        for (TransactionOutput output : generated.body().getOutputs()) {
            if (!myAddress.equals(output.getAddress()) || output.getValue().getMultiAssets() == null) {
                continue;
            }
            for (MultiAsset multiAsset : output.getValue().getMultiAssets()) {
                if (!policyId.equals(multiAsset.getPolicyId())) {
                    continue;
                }
                for (Asset asset : multiAsset.getAssets()) {
                    if (assetNameHex.equals(HexFormat.of().formatHex(asset.getNameAsBytes()))) {
                        net = net.add(asset.getValue());
                    }
                }
            }
        }
        for (TransactionInput input : generated.inputs()) {
            ResolvedOutput resolved = generated.resolved().get(
                    input.getTransactionId() + "#" + input.getIndex());
            if (!myAddress.equals(resolved.address())) {
                continue;
            }
            for (AssetQuantity asset : resolved.assets()) {
                if (policyId.equals(asset.policyId()) && assetNameHex.equals(asset.assetNameHex())) {
                    net = net.subtract(asset.quantity());
                }
            }
        }
        return net;
    }

    private BigInteger strangersAssetNet(Generated generated, String policyId, String assetNameHex) {
        BigInteger net = BigInteger.ZERO;
        for (TransactionOutput output : generated.body().getOutputs()) {
            if (myAddress.equals(output.getAddress()) || output.getValue().getMultiAssets() == null) {
                continue;
            }
            for (MultiAsset multiAsset : output.getValue().getMultiAssets()) {
                if (!policyId.equals(multiAsset.getPolicyId())) {
                    continue;
                }
                for (Asset asset : multiAsset.getAssets()) {
                    if (assetNameHex.equals(HexFormat.of().formatHex(asset.getNameAsBytes()))) {
                        net = net.add(asset.getValue());
                    }
                }
            }
        }
        for (TransactionInput input : generated.inputs()) {
            ResolvedOutput resolved = generated.resolved().get(
                    input.getTransactionId() + "#" + input.getIndex());
            if (myAddress.equals(resolved.address())) {
                continue;
            }
            for (AssetQuantity asset : resolved.assets()) {
                if (policyId.equals(asset.policyId()) && assetNameHex.equals(asset.assetNameHex())) {
                    net = net.subtract(asset.quantity());
                }
            }
        }
        return net;
    }

    @Test
    void losingAnInputNeverShrinksAVerifiedLoss() {
        // The governing invariant of ADR-042, as a property over every generated
        // transaction rather than one example: whatever the node fails to tell
        // us, the wallet must not respond by claiming a smaller, confident loss.
        Random random = new Random(4242L);
        for (int i = 0; i < TRANSACTIONS; i++) {
            Generated generated = generate(random);
            TxEffectEngine engine = new TxEffectEngine(new MapPort(generated.resolved()), DIRECT);
            TxEffect baseline = engine.analyse(generated.txHex(), ownership);

            for (TransactionInput input : generated.inputs()) {
                MapPort degraded = new MapPort(generated.resolved());
                degraded.broken.add(input.getTransactionId() + "#" + input.getIndex());
                TxEffect result = new TxEffectEngine(degraded, DIRECT)
                        .analyse(generated.txHex(), ownership);

                assertThat(result.completeness())
                        .as("an unresolvable input must degrade the summary (transaction %d)", i)
                        .isEqualTo(TxEffect.Completeness.INCOMPLETE);
                if (result.lovelaceDelta().compareTo(baseline.lovelaceDelta()) > 0) {
                    // A smaller loss is only ever allowed alongside INCOMPLETE,
                    // which the assertion above already established.
                    assertThat(result.limitation())
                            .as("a smaller number must come with a stated reason (transaction %d)", i)
                            .isNotBlank();
                }
            }
        }
    }

    @Test
    void aWalletThatOwnsNothingSeesNoMovement() {
        // Sanity anchor for the identity: with none of the inputs or outputs
        // ours, the diff is exactly zero — not "unknown", and not the fee.
        Random random = new Random(7L);
        WalletOwnership stranger = WalletOwnership.ofAddresses(List.of(
                AddressProvider.getBaseAddress(Credential.fromKey(credential(200)),
                        Credential.fromKey(credential(210)), Networks.testnet()).toBech32()));

        for (int i = 0; i < 50; i++) {
            Generated generated = generate(random);
            TxEffect effect = new TxEffectEngine(new MapPort(generated.resolved()), DIRECT)
                    .analyse(generated.txHex(), stranger);

            assertThat(effect.lovelaceDelta()).as("transaction %d", i).isEqualTo(BigInteger.ZERO);
            assertThat(effect.assetDeltas()).isEmpty();
        }
    }
}
