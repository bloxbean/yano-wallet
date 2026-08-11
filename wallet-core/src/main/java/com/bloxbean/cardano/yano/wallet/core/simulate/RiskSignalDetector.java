package com.bloxbean.cardano.yano.wallet.core.simulate;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

/**
 * Derives ADR-042's risk signals from a computed effect (SIM-M3).
 *
 * <h2>These inform; they do not authorise</h2>
 *
 * Every signal here is a heuristic. A transaction with no signals is not thereby
 * safe — it may simply be a well-formed drain the user chose to approve — and a
 * transaction with several is usually just an ordinary DeFi interaction. The
 * value is in naming what is happening in words, not in scoring it.
 *
 * <p>Two rules keep them useful rather than noisy:
 *
 * <ul>
 *   <li><b>Every signal states its reason.</b> "Asset leaving" alone teaches the
 *       user nothing; the reason is what lets them judge.</li>
 *   <li><b>A signal that cannot be computed is not raised.</b> Guessing at the
 *       drain heuristic without knowing the balance would produce false alarms,
 *       and a warning the user learns to dismiss is worse than none.</li>
 * </ul>
 */
final class RiskSignalDetector {

    /** Above this share of the balance, an outgoing transaction is "most of what you have". */
    private static final int DRAIN_PERCENT = 95;

    /** ~30 days at one slot per second: beyond this a validity window is odd enough to mention. */
    private static final long FAR_FUTURE_SLOTS = 30L * 24 * 60 * 60;

    private RiskSignalDetector() {
    }

    static List<RiskSignal> detect(TxEffect effect, WalletContext context) {
        List<RiskSignal> signals = new ArrayList<>();
        TxFacts facts = effect.facts();

        // Ordered most-consequential first: the prompt renders them in sequence.

        if (effect.scriptOutcome() == TxEffect.ScriptOutcome.FAILED) {
            signals.add(new RiskSignal(RiskSignal.Kind.SCRIPT_FAILURE, RiskSignal.Severity.CRITICAL,
                    "This transaction will fail",
                    "Your node ran its scripts and they did not pass. Submitting it would waste the fee"
                            + (facts.collateralPresent() ? " and forfeit the collateral below." : ".")));
        }

        if (effect.scriptOutcome() == TxEffect.ScriptOutcome.COULD_NOT_VERIFY && facts.hasRedeemers()) {
            signals.add(new RiskSignal(RiskSignal.Kind.SCRIPT_FAILURE, RiskSignal.Severity.WARNING,
                    "Its scripts were not checked",
                    "This transaction runs smart-contract code that your node could not evaluate."
                            + (facts.collateralPresent()
                            ? " If that code fails on-chain, the collateral below is forfeited."
                            : " Whether it succeeds on-chain is unknown.")));
        }

        if (effect.completeness() != TxEffect.Completeness.COMPLETE) {
            signals.add(new RiskSignal(RiskSignal.Kind.INCOMPLETE_SUMMARY, RiskSignal.Severity.WARNING,
                    "Not fully checked",
                    effect.limitation() != null ? effect.limitation()
                            : "Part of this transaction could not be verified, so it may do more than is shown."));
        }

        if (isDrain(effect, context)) {
            signals.add(new RiskSignal(RiskSignal.Kind.TOTAL_VALUE_DRAIN, RiskSignal.Severity.CRITICAL,
                    "Empties your wallet",
                    "This sends out essentially your entire ADA balance. Legitimate requests rarely need"
                            + " everything you hold."));
        }

        for (TxEffect.AssetDelta delta : effect.assetDeltas()) {
            if (delta.isOutgoing()) {
                signals.add(new RiskSignal(RiskSignal.Kind.ASSET_LEAVING, RiskSignal.Severity.WARNING,
                        "A token leaves your wallet",
                        "Tokens can be worth far more than the ADA in the same transaction, and this one"
                                + " does not come back."));
                break;   // one signal for the class; the amounts are listed separately
            }
        }

        if (!facts.mint().isEmpty()) {
            signals.add(new RiskSignal(RiskSignal.Kind.MINT_OR_BURN, RiskSignal.Severity.WARNING,
                    mintTitle(facts.mint()),
                    "This transaction creates or destroys tokens. If you did not ask to mint or burn"
                            + " anything, that is worth questioning."));
        }

        if (facts.collateralPresent() && facts.collateralLovelace().signum() > 0) {
            signals.add(new RiskSignal(RiskSignal.Kind.COLLATERAL_AT_RISK, RiskSignal.Severity.INFO,
                    "Collateral is committed",
                    "If this transaction's scripts fail on-chain, the collateral is taken instead of"
                            + " being returned."));
        }

        if (facts.scriptOutputCount() > 0) {
            signals.add(new RiskSignal(RiskSignal.Kind.UNKNOWN_SCRIPT_OUTPUT, RiskSignal.Severity.INFO,
                    "Funds go to a smart contract",
                    "Value in this transaction goes to an address controlled by code rather than by a key."
                            + " Getting it back depends on that contract behaving as its authors intended."));
        }

        if (facts.datumOutputCount() > 0) {
            signals.add(new RiskSignal(RiskSignal.Kind.DATUM_BEARING_OUTPUT, RiskSignal.Severity.INFO,
                    "An output carries data the wallet cannot read",
                    "The attached datum decides how a contract treats those funds, and this wallet"
                            + " cannot interpret it."));
        }

        for (String certificate : facts.certificates()) {
            signals.add(new RiskSignal(RiskSignal.Kind.CERTIFICATE, RiskSignal.Severity.WARNING,
                    "Changes your staking or governance setup",
                    certificateReason(certificate)));
        }

        for (TxFacts.Withdrawal withdrawal : facts.withdrawals()) {
            if (withdrawal.mine()) {
                signals.add(new RiskSignal(RiskSignal.Kind.WITHDRAWAL, RiskSignal.Severity.WARNING,
                        "Withdraws your staking rewards",
                        "Cardano always withdraws the entire reward balance. The amount above already"
                                + " counts it as leaving; check where it ends up."));
            }
        }

        addValidityWindowSignal(signals, facts, context);
        return List.copyOf(signals);
    }

    private static boolean isDrain(TxEffect effect, WalletContext context) {
        // Requires a known balance AND a complete summary: raising "empties your
        // wallet" off numbers we already said we could not verify would be a
        // guess presented as an alarm.
        if (!context.hasBalance() || effect.completeness() != TxEffect.Completeness.COMPLETE) {
            return false;
        }
        if (effect.lovelaceDelta().signum() >= 0) {
            return false;
        }
        BigInteger leaving = effect.lovelaceDelta().negate();
        BigInteger threshold = context.balanceLovelace()
                .multiply(BigInteger.valueOf(DRAIN_PERCENT))
                .divide(BigInteger.valueOf(100));
        return leaving.compareTo(threshold) >= 0;
    }

    private static void addValidityWindowSignal(List<RiskSignal> signals, TxFacts facts, WalletContext context) {
        if (!context.hasSlot()) {
            return;                              // no clock, no claim
        }
        long ttl = facts.ttl();
        if (ttl > 0 && ttl < context.currentSlot()) {
            signals.add(new RiskSignal(RiskSignal.Kind.VALIDITY_WINDOW, RiskSignal.Severity.WARNING,
                    "Already expired",
                    "This transaction's validity window has passed, so the network will reject it."
                            + " Signing it achieves nothing, but the signature is still yours."));
            return;
        }
        if (ttl > context.currentSlot() + FAR_FUTURE_SLOTS) {
            signals.add(new RiskSignal(RiskSignal.Kind.VALIDITY_WINDOW, RiskSignal.Severity.INFO,
                    "Stays valid for an unusually long time",
                    "This transaction can still be submitted far in the future, so a signature you give"
                            + " now could be used long after you have forgotten it."));
        }
    }

    private static String mintTitle(List<TxFacts.AssetDelta> mint) {
        boolean mints = mint.stream().anyMatch(asset -> asset.quantity().signum() > 0);
        boolean burns = mint.stream().anyMatch(asset -> asset.quantity().signum() < 0);
        if (mints && burns) {
            return "Creates and destroys tokens";
        }
        return burns ? "Destroys tokens" : "Creates new tokens";
    }

    private static String certificateReason(String certificate) {
        String kind = certificate == null ? "" : certificate;
        // Ordered most specific first: several CCL class names match more than one
        // of these tests, and a wrong-but-confident description of a certificate
        // is its own small version of the problem this whole feature addresses.
        if (kind.contains("PoolRetirement")) {
            return "This retires a stake pool. Its deposit is refunded to the pool's reward account at"
                    + " the retirement epoch, not to the outputs of this transaction.";
        }
        if (kind.contains("PoolRegistration")) {
            return "This registers or updates a stake pool, which locks a pool deposit.";
        }
        if (kind.contains("Genesis") || kind.contains("MoveInstantaneous")) {
            return "This is a protocol-level certificate that an ordinary wallet transaction has no"
                    + " reason to contain.";
        }
        if (kind.contains("StakeVoteDeleg")) {
            return "This changes both which stake pool your funds are delegated to and who votes"
                    + " with them.";
        }
        if (kind.contains("Dereg") || kind.contains("Unreg")) {
            // Legacy StakeDeregistration carries no amount, so the engine cannot
            // price its refund; Conway unregistrations do carry one and ARE
            // counted. Either way, saying where the refund goes is worth a line.
            return "This retires a registration and reclaims its deposit. Check the outputs to see"
                    + " where that refund goes.";
        }
        if (kind.contains("Vote") || kind.contains("DRep")) {
            return "This changes who votes with your stake in Cardano governance.";
        }
        if (kind.contains("Deleg")) {
            return "This changes which stake pool your funds are delegated to.";
        }
        if (kind.contains("Reg")) {
            return "This registers an account, which locks a deposit until it is retired.";
        }
        return "This transaction includes a certificate (" + kind + ") that changes account state.";
    }
}
