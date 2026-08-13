package com.bloxbean.cardano.yano.wallet.core.simulate;

import com.bloxbean.cardano.client.transaction.spec.Asset;
import com.bloxbean.cardano.client.transaction.spec.MultiAsset;
import com.bloxbean.cardano.client.transaction.spec.Transaction;
import com.bloxbean.cardano.client.transaction.spec.TransactionBody;
import com.bloxbean.cardano.client.transaction.spec.TransactionInput;
import com.bloxbean.cardano.client.transaction.spec.TransactionOutput;
import com.bloxbean.cardano.client.transaction.spec.Value;
import com.bloxbean.cardano.client.transaction.spec.Withdrawal;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;


/**
 * Computes what a transaction does to this wallet (ADR-042 SIM-M1/M2/M3), from
 * the transaction's own CBOR plus input resolution against the user's node.
 *
 * <h2>The one invariant</h2>
 *
 * Errors are never allowed to make the loss look smaller. Concretely:
 *
 * <ul>
 *   <li>An <b>input</b> is either resolved <em>and</em> classified, or the whole
 *       summary is incomplete. It is never quietly treated as somebody else's,
 *       because that would subtract from what we report as leaving.</li>
 *   <li>An <b>output</b> we cannot classify is treated as not ours, which
 *       over-states the loss. That direction is safe, so one unreadable output
 *       degrades a number rather than aborting the summary.</li>
 * </ul>
 *
 * <p>The engine performs no I/O of its own beyond the {@link TxSimulationPort}
 * and holds no wallet secrets; it is a pure function of (transaction, ownership,
 * node answers) and is tested as one.
 */
public final class TxEffectEngine {

    private final TxSimulationPort port;
    private final Executor executor;

    /**
     * @param executor runs input resolutions concurrently. A transaction with
     *                 twenty inputs cannot afford twenty sequential round trips
     *                 inside a signing prompt's deadline.
     */
    public TxEffectEngine(TxSimulationPort port, Executor executor) {
        this.port = Objects.requireNonNull(port, "port is required");
        this.executor = Objects.requireNonNull(executor, "executor is required");
    }

    public TxEffect analyse(String txHex, WalletOwnership ownership) {
        return analyse(txHex, ownership, WalletContext.unknown());
    }

    /**
     * @param context wallet balance and current slot, for the risk signals that
     *                need them (SIM-M3). {@link WalletContext#unknown()} simply
     *                suppresses those signals rather than guessing.
     */
    public TxEffect analyse(String txHex, WalletOwnership ownership, WalletContext context) {
        Transaction tx;
        try {
            tx = Transaction.deserialize(HexFormat.of().parseHex(normaliseHex(txHex)));
        } catch (Exception e) {
            return TxEffect.undecodable("This transaction could not be decoded, so what it does cannot be shown.");
        }
        TransactionBody body = tx == null ? null : tx.getBody();
        if (body == null) {
            return TxEffect.undecodable("This transaction has no body, so what it does cannot be shown.");
        }
        // Redeemers are what the evaluator actually runs; the script-data hash is
        // the body-level corroboration. Either one means "this transaction has
        // scripts", so neither alone may be trusted to mean it does not.
        boolean hasWitnessRedeemers = hasRedeemers(tx);
        boolean hasRedeemers = hasWitnessRedeemers || body.getScriptDataHash() != null;
        if (ownership == null || ownership.isEmpty()) {
            // With no credentials to match, everything would classify as "not
            // mine" and the diff would confidently report that nothing of ours
            // moves. Refusing is the only honest answer.
            return new TxEffect(TxEffect.Completeness.INCOMPLETE,
                    "The wallet's own addresses were unavailable, so the effect on your balance could not be computed.",
                    BigInteger.ZERO, feeOf(body), List.of(), TxEffect.ScriptOutcome.COULD_NOT_VERIFY, null,
                    List.of(), factsOf(body, hasRedeemers, List.of(), 0, BigInteger.ZERO, false), List.of())
                    .withRisks(List.of(new RiskSignal(RiskSignal.Kind.INCOMPLETE_SUMMARY,
                            RiskSignal.Severity.WARNING, "Not checked",
                            "The wallet's own addresses were unavailable, so nothing here was verified.")));
        }

        SimulationCapabilities capabilities = capabilities();
        if (!capabilities.canResolveInputs()) {
            return new TxEffect(TxEffect.Completeness.INCOMPLETE, capabilities.limitation(),
                    BigInteger.ZERO, feeOf(body), List.of(), TxEffect.ScriptOutcome.COULD_NOT_VERIFY, null,
                    List.of(), factsOf(body, hasRedeemers, List.of(),
                            orEmpty(body.getInputs()).size(), BigInteger.ZERO, false), List.of())
                    .withRisks(List.of(new RiskSignal(RiskSignal.Kind.INCOMPLETE_SUMMARY,
                            RiskSignal.Severity.WARNING, "Not checked",
                            capabilities.limitation() != null ? capabilities.limitation()
                                    : "Your node cannot check transactions, so nothing here was verified.")));
        }

        // ---- resolve every outpoint the node will need -----------------------
        //
        // Spending inputs drive the value diff. Collateral is priced for the
        // "at risk" figure. Reference inputs affect neither — but the node's
        // evaluator resolves all three (TransactionEvaluationService), and a
        // reference input it cannot find comes back as an EvaluationFailure that
        // says nothing about the scripts. So they are resolved here purely to
        // know whether a script verdict can be trusted.
        List<TransactionInput> inputs = orEmpty(body.getInputs());
        List<TransactionInput> collateral = orEmpty(body.getCollateral());
        List<TransactionInput> references = orEmpty(body.getReferenceInputs());
        Map<String, Resolution> resolved =
                resolveAll(concat(concat(inputs, collateral), references));

        // ---- what leaves: our resolved inputs --------------------------------
        Ledger spent = new Ledger();
        int unresolved = 0;
        for (TransactionInput input : inputs) {
            Resolution resolution = resolved.get(key(input));
            if (resolution == null || !resolution.isResolved()) {
                unresolved++;                       // could be ours — see class javadoc
                continue;
            }
            WalletOwnership.Ownership owner = ownership.classify(resolution.output().address());
            if (owner == WalletOwnership.Ownership.MINE) {
                spent.addLovelace(resolution.output().lovelace());
                for (AssetQuantity asset : resolution.output().assets()) {
                    spent.addAsset(asset.policyId(), asset.assetNameHex(), asset.quantity());
                }
            } else if (owner == WalletOwnership.Ownership.UNKNOWN) {
                // Resolved, but we cannot say whose it is. Counting it as
                // somebody else's would shrink the reported loss.
                unresolved++;
            }
        }

        // ---- what comes back: outputs paying us ------------------------------
        Ledger returned = new Ledger();
        for (TransactionOutput output : orEmpty(body.getOutputs())) {
            // UNKNOWN counts as not-ours here: it over-states the loss, which is
            // the safe direction, so one odd output never aborts the summary.
            if (ownership.classify(output.getAddress()) != WalletOwnership.Ownership.MINE) {
                continue;
            }
            Value value = output.getValue();
            if (value == null) {
                continue;
            }
            returned.addLovelace(value.getCoin());
            for (MultiAsset multiAsset : orEmpty(value.getMultiAssets())) {
                for (Asset asset : orEmpty(multiAsset.getAssets())) {
                    returned.addAsset(multiAsset.getPolicyId(), hexNameOf(asset), asset.getValue());
                }
            }
        }

        // ---- withdrawals: rewards are ours, and they enter here ---------------
        //
        // Value conservation on Cardano is
        //     Σinputs + Σwithdrawals + refunds = Σoutputs + fee + deposits
        // so a withdrawal from OUR reward account is our value entering the
        // transaction, exactly like an input — and the protocol withdraws the
        // entire reward balance, never part of it.
        //
        // Treating it as a mere annotation is a drain: routed to a foreign
        // output it lands in neither `spent` nor `returned`, and a wallet holding
        // 10 ADA of rewards would be told a transaction taking all of them
        // "leaves ₳2". Counting it on the spent side makes both cases right —
        // claiming rewards to ourselves then nets out against the outputs and
        // costs only the fee, rather than reading as a windfall.
        List<TxFacts.Withdrawal> withdrawals = new ArrayList<>();
        for (Withdrawal withdrawal : orEmpty(body.getWithdrawals())) {
            String rewardAddress = withdrawal.getRewardAddress();
            BigInteger amount = withdrawal.getCoin() == null ? BigInteger.ZERO : withdrawal.getCoin();
            boolean mine = ownership.isMyRewardAddress(rewardAddress);
            if (mine) {
                spent.addLovelace(amount);
            }
            withdrawals.add(new TxFacts.Withdrawal(rewardAddress, amount, mine));
        }

        // ---- deposit refunds re-enter exactly like withdrawals ----------------
        // A Conway unregistration refunds its deposit into this transaction. That
        // deposit was ours, so if the refund is routed to a stranger's output we
        // lose it — and it appears in neither `spent` nor `returned` unless it is
        // counted here. At DRep scale that is 500 ADA moving invisibly.
        for (var certificate : orEmpty(body.getCerts())) {
            BigInteger refund = refundToUs(certificate, ownership);
            if (refund.signum() > 0) {
                spent.addLovelace(refund);
            }
        }

        Ledger net = returned.minus(spent);

        // Collateral is the loss when scripts fail, so an unpriced collateral
        // input is not a smaller "at risk" figure — it is an unknown one, and it
        // degrades the summary exactly like an unresolved spending input.
        BigInteger collateralLovelace = BigInteger.ZERO;
        int unpricedCollateral = 0;
        for (TransactionInput input : collateral) {
            Resolution resolution = resolved.get(key(input));
            if (resolution != null && resolution.isResolved()) {
                collateralLovelace = collateralLovelace.add(resolution.output().lovelace());
            } else {
                unpricedCollateral++;
            }
        }

        // The node resolves reference inputs too; one it cannot see turns a
        // perfectly good transaction into an EvaluationFailure.
        int unresolvedReferences = 0;
        for (TransactionInput input : references) {
            Resolution resolution = resolved.get(key(input));
            if (resolution == null || !resolution.isResolved()) {
                unresolvedReferences++;
            }
        }

        TxEffect.Completeness completeness = unresolved == 0 && unpricedCollateral == 0
                ? TxEffect.Completeness.COMPLETE
                : TxEffect.Completeness.INCOMPLETE;
        String limitation = unresolved > 0 ? unresolvedMessage(unresolved)
                : unpricedCollateral > 0
                ? "Some of this transaction's collateral could not be checked, so the amount at risk"
                + " if its scripts fail may be larger than shown."
                : null;

        TxFacts facts = factsOf(body, hasRedeemers, withdrawals, unresolved,
                collateralLovelace, !collateral.isEmpty());

        TxEffect effect = new TxEffect(completeness, limitation, net.lovelace(), feeOf(body),
                net.assetDeltas(), TxEffect.ScriptOutcome.NO_SCRIPTS, null, List.of(), facts, List.of());
        boolean everyOutpointKnown = unresolved == 0 && unpricedCollateral == 0 && unresolvedReferences == 0;
        effect = evaluateScripts(effect, normaliseHex(txHex), capabilities,
                everyOutpointKnown, hasWitnessRedeemers);
        // Signals are derived last: several of them (a failing script, an
        // incomplete summary) depend on everything above being settled.
        return effect.withRisks(RiskSignalDetector.detect(effect, context));
    }

    /**
     * The script-outcome state machine (ADR-042 SIM-M2). Its whole job is to keep
     * two very different statements apart:
     *
     * <ul>
     *   <li><b>"Your scripts fail"</b> — a reason not to sign: the transaction
     *       will fail on-chain, wasting the fee and burning collateral.</li>
     *   <li><b>"I could not check"</b> — no information at all, and not a reason
     *       to sign or to refuse.</li>
     * </ul>
     *
     * <p>Only a node that evaluated <em>this exact transaction with every input
     * known</em> can produce the first. In particular a transaction spending an
     * output that is not yet on-chain — a chained dApp transaction — comes back
     * as a failure from a node that simply cannot see the input, and reporting
     * that as "your scripts fail" would be a confident lie (ADR-042 limit 3).
     */
    private TxEffect evaluateScripts(TxEffect effect, String txHex, SimulationCapabilities capabilities,
                                     boolean everyOutpointKnown, boolean hasWitnessRedeemers) {
        if (!effect.facts().hasRedeemers()) {
            return effect;                                  // nothing to evaluate
        }
        // A verdict — "they pass" or "they fail" — may only come from the node
        // having run THIS transaction with every outpoint it needs. Two ways that
        // fails silently, both of which the node reports as an ordinary
        // EvaluationFailure indistinguishable from a real script error:
        //
        //  1. An outpoint it cannot see. Spending inputs are the obvious case,
        //     but the evaluator also resolves collateral and reference inputs, so
        //     a chained transaction referencing a sibling's output would be
        //     reported as "your scripts fail" when nothing was wrong.
        //  2. Redeemers not attached yet. A partially built transaction can carry
        //     the script-data hash while the dApp adds redeemers after signing;
        //     evaluating it either errors or "succeeds" with nothing run, and
        //     both readings would be a confident statement about scripts that
        //     never executed.
        if (!everyOutpointKnown) {
            return effect.withScript(TxEffect.ScriptOutcome.COULD_NOT_VERIFY,
                    "Some of the outputs this transaction depends on are unknown to your node,"
                            + " so its scripts could not be run.",
                    List.of());
        }
        if (!hasWitnessRedeemers) {
            return effect.withScript(TxEffect.ScriptOutcome.COULD_NOT_VERIFY,
                    "This transaction's scripts are not attached yet, so they could not be run.",
                    List.of());
        }
        if (!capabilities.scriptEvaluation().isAvailable()) {
            return effect.withScript(TxEffect.ScriptOutcome.COULD_NOT_VERIFY,
                    capabilities.limitation(), List.of());
        }
        ScriptEvaluation evaluation;
        try {
            evaluation = port.evaluate(txHex);
        } catch (RuntimeException e) {
            return effect.withScript(TxEffect.ScriptOutcome.COULD_NOT_VERIFY,
                    "Your node could not be reached to check this transaction's scripts.", List.of());
        }
        if (evaluation == null) {
            return effect.withScript(TxEffect.ScriptOutcome.COULD_NOT_VERIFY, null, List.of());
        }
        return switch (evaluation.outcome()) {
            case SUCCESS -> effect.withScript(TxEffect.ScriptOutcome.SUCCESS, null, evaluation.costs());
            case FAILURE -> effect.withScript(TxEffect.ScriptOutcome.FAILED, evaluation.message(), List.of());
            case UNAVAILABLE -> effect.withScript(TxEffect.ScriptOutcome.COULD_NOT_VERIFY,
                    evaluation.message(), List.of());
        };
    }

    private SimulationCapabilities capabilities() {
        try {
            return port.capabilities();
        } catch (RuntimeException e) {
            return new SimulationCapabilities(SimulationCapabilities.Support.UNKNOWN,
                    SimulationCapabilities.Support.UNKNOWN, null, null);
        }
    }

    private static String unresolvedMessage(int unresolved) {
        return unresolved == 1
                ? "One of this transaction's inputs could not be checked, so the amounts below may understate what leaves your wallet."
                : unresolved + " of this transaction's inputs could not be checked, so the amounts below may understate what leaves your wallet.";
    }

    /** Resolves every distinct output reference concurrently, tolerating individual failures. */
    private Map<String, Resolution> resolveAll(List<TransactionInput> references) {
        Map<String, CompletableFuture<Resolution>> pending = new LinkedHashMap<>();
        for (TransactionInput reference : references) {
            pending.computeIfAbsent(key(reference), key -> CompletableFuture.supplyAsync(
                    () -> resolveOne(reference), executor));
        }
        Map<String, Resolution> resolved = new LinkedHashMap<>();
        pending.forEach((key, future) -> {
            try {
                resolved.put(key, future.join());
            } catch (RuntimeException e) {
                resolved.put(key, Resolution.failed());
            }
        });
        return resolved;
    }

    private Resolution resolveOne(TransactionInput reference) {
        try {
            ResolvedOutput output = port.resolveOutput(
                    reference.getTransactionId(), reference.getIndex());
            // A null is the node saying "not in the UTxO set" — an answer, but not
            // one that tells us whose it was, so it still leaves us unable to
            // classify. Both land in the same place: unresolved.
            return output == null ? Resolution.failed() : Resolution.of(output);
        } catch (RuntimeException e) {
            return Resolution.failed();
        }
    }

    private TxFacts factsOf(TransactionBody body, boolean hasRedeemers,
                            List<TxFacts.Withdrawal> withdrawals, int unresolved,
                            BigInteger collateralLovelace, boolean collateralPresent) {
        List<TxFacts.AssetDelta> mint = new ArrayList<>();
        for (MultiAsset multiAsset : orEmpty(body.getMint())) {
            for (Asset asset : orEmpty(multiAsset.getAssets())) {
                mint.add(new TxFacts.AssetDelta(multiAsset.getPolicyId(), hexNameOf(asset), asset.getValue()));
            }
        }
        List<String> certificates = new ArrayList<>();
        for (var certificate : orEmpty(body.getCerts())) {
            certificates.add(certificate.getClass().getSimpleName());
        }
        int outputs = orEmpty(body.getOutputs()).size();
        int scriptOutputs = 0;
        int datumOutputs = 0;
        for (TransactionOutput output : orEmpty(body.getOutputs())) {
            if (isScriptAddress(output.getAddress())) {
                scriptOutputs++;
            }
            if (output.getDatumHash() != null || output.getInlineDatum() != null) {
                datumOutputs++;
            }
        }
        return new TxFacts(mint, certificates, withdrawals, collateralLovelace, collateralPresent,
                body.getTtl(), body.getValidityStartInterval(), outputs,
                orEmpty(body.getInputs()).size(), unresolved, scriptOutputs, datumOutputs,
                hasRedeemers);
    }

    /**
     * The deposit a certificate refunds into this transaction, when the credential
     * being retired is ours. Zero otherwise — including for the legacy
     * {@code StakeDeregistration}, which carries no amount (the refund is the
     * protocol's key deposit, which the wallet cannot price without protocol
     * parameters). That case is disclosed through the certificate risk signal
     * instead, which says the refund is not reflected in the amounts.
     */
    private static BigInteger refundToUs(com.bloxbean.cardano.client.transaction.spec.cert.Certificate certificate,
                                         WalletOwnership ownership) {
        if (certificate instanceof com.bloxbean.cardano.client.transaction.spec.cert.UnregCert unreg) {
            var credential = unreg.getStakeCredential();
            if (credential != null && ownership.isMyCertificateCredential(credential.getHash())) {
                return unreg.getCoin() == null ? BigInteger.ZERO : unreg.getCoin();
            }
        }
        if (certificate instanceof com.bloxbean.cardano.client.transaction.spec.cert.UnregDRepCert unreg) {
            var credential = unreg.getDrepCredential();
            if (credential != null && ownership.isMyCertificateCredential(credential.getBytes())) {
                return unreg.getCoin() == null ? BigInteger.ZERO : unreg.getCoin();
            }
        }
        return BigInteger.ZERO;
    }

    private static boolean hasRedeemers(Transaction tx) {
        var witnessSet = tx.getWitnessSet();
        return witnessSet != null
                && witnessSet.getRedeemers() != null
                && !witnessSet.getRedeemers().isEmpty();
    }

    private static boolean isScriptAddress(String address) {
        if (address == null || address.isBlank()) {
            return false;
        }
        try {
            return new com.bloxbean.cardano.client.address.Address(address).isScriptHashInPaymentPart();
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static String hexNameOf(Asset asset) {
        byte[] name = asset.getNameAsBytes();
        return name == null ? "" : HexFormat.of().formatHex(name);
    }

    private static BigInteger feeOf(TransactionBody body) {
        return body.getFee() == null ? BigInteger.ZERO : body.getFee();
    }

    private static String key(TransactionInput input) {
        return input.getTransactionId() + "#" + input.getIndex();
    }

    private static List<TransactionInput> concat(List<TransactionInput> a, List<TransactionInput> b) {
        List<TransactionInput> all = new ArrayList<>(a);
        all.addAll(b);
        return all;
    }

    private static <T> List<T> orEmpty(Collection<T> values) {
        return values == null ? List.of() : new ArrayList<>(values);
    }

    private static String normaliseHex(String hex) {
        if (hex == null) {
            throw new IllegalArgumentException("txHex is required");
        }
        String trimmed = hex.strip();
        return trimmed.startsWith("0x") ? trimmed.substring(2) : trimmed;
    }

    /** Either a resolved output, or the fact that we could not get one. */
    private record Resolution(ResolvedOutput output) {
        static Resolution of(ResolvedOutput output) {
            return new Resolution(output);
        }

        static Resolution failed() {
            return new Resolution(null);
        }

        boolean isResolved() {
            return output != null;
        }
    }

    /** Running per-unit totals; ADA is kept apart so it can never collide with an asset. */
    private static final class Ledger {
        private BigInteger lovelace = BigInteger.ZERO;
        private final Map<String, BigInteger> assets = new LinkedHashMap<>();
        private final Map<String, String[]> assetKeys = new LinkedHashMap<>();

        void addLovelace(BigInteger amount) {
            if (amount != null) {
                lovelace = lovelace.add(amount);
            }
        }

        void addAsset(String policyId, String assetNameHex, BigInteger quantity) {
            if (policyId == null || quantity == null) {
                return;
            }
            String name = assetNameHex == null ? "" : assetNameHex;
            String unit = policyId + name;
            assets.merge(unit, quantity, BigInteger::add);
            assetKeys.putIfAbsent(unit, new String[]{policyId, name});
        }

        BigInteger lovelace() {
            return lovelace;
        }

        Ledger minus(Ledger other) {
            Ledger result = new Ledger();
            result.lovelace = this.lovelace.subtract(other.lovelace);
            this.assets.forEach((unit, quantity) -> {
                String[] parts = this.assetKeys.get(unit);
                result.addAsset(parts[0], parts[1], quantity);
            });
            other.assets.forEach((unit, quantity) -> {
                String[] parts = other.assetKeys.get(unit);
                result.addAsset(parts[0], parts[1], quantity.negate());
            });
            return result;
        }

        List<TxEffect.AssetDelta> assetDeltas() {
            List<TxEffect.AssetDelta> deltas = new ArrayList<>();
            assets.forEach((unit, quantity) -> {
                if (quantity.signum() != 0) {          // a net-zero asset is not a change
                    String[] parts = assetKeys.get(unit);
                    deltas.add(new TxEffect.AssetDelta(parts[0], parts[1], quantity));
                }
            });
            return deltas;
        }
    }
}
