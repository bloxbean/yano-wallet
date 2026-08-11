package com.bloxbean.cardano.yano.wallet.app;

import com.bloxbean.cardano.yano.wallet.core.service.WalletService;
import com.bloxbean.cardano.yano.wallet.core.simulate.TxEffect;
import com.bloxbean.cardano.yano.wallet.core.simulate.TxEffectEngine;
import com.bloxbean.cardano.yano.wallet.core.simulate.TxFacts;
import com.bloxbean.cardano.yano.wallet.core.simulate.WalletContext;
import com.bloxbean.cardano.yano.wallet.core.simulate.WalletOwnership;
import com.bloxbean.cardano.yano.wallet.core.wallet.StoredWallet;
import com.bloxbean.cardano.yano.wallet.ui.contract.TxEffectView;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

/**
 * Turns a dApp's raw transaction into the summary shown before signing
 * (ADR-042), under a hard deadline.
 *
 * <h2>Why the deadline is the whole design</h2>
 *
 * This runs on the CIP-30 bridge worker thread, immediately before a blocking
 * modal. A node that is slow, wedged or mid-restart must produce a degraded
 * prompt — "could not verify" — and never a wallet that appears frozen. So the
 * simulation gets {@value #BUDGET_SECONDS} seconds total, after which whatever it
 * was doing is abandoned and the user is told plainly that nothing was verified.
 *
 * <p>Two thread pools, deliberately. The engine resolves inputs concurrently on
 * {@code resolvers}; the whole analysis runs on {@code supervisor}. Sharing one
 * bounded pool would let the outer task occupy the only thread while waiting for
 * inner tasks that can never be scheduled — a deadlock that would present as
 * exactly the frozen wallet this class exists to prevent.
 */
final class TxEffectSummariser {

    private static final int BUDGET_SECONDS = 3;
    private static final int RESOLVER_THREADS = 8;

    private final WalletBackendManager backendManager;
    private final Supplier<WalletService.Session> session;
    private final ExecutorService supervisor;
    private final ExecutorService resolvers;

    TxEffectSummariser(WalletBackendManager backendManager, Supplier<WalletService.Session> session) {
        this.backendManager = backendManager;
        this.session = session;
        this.supervisor = Executors.newCachedThreadPool(daemonFactory("yano-sim-supervisor"));
        this.resolvers = Executors.newFixedThreadPool(RESOLVER_THREADS, daemonFactory("yano-sim-resolver"));
    }

    private static ThreadFactory daemonFactory(String name) {
        return runnable -> {
            // Daemon: a simulation in flight must never keep the wallet alive at
            // shutdown.
            Thread thread = new Thread(runnable, name);
            thread.setDaemon(true);
            return thread;
        };
    }

    /** Never throws: every failure becomes a degraded, legible view. */
    TxEffectView summarise(String txHex) {
        Future<TxEffect> analysis;
        try {
            WalletBackendManager.ActiveConnection connection = backendManager.active();
            if (connection == null) {
                return degraded(txHex, "The wallet is not connected to a node, so this transaction was not checked.");
            }
            WalletOwnership ownership = ownership();
            TxEffectEngine engine = new TxEffectEngine(connection.backend().ports(), resolvers);
            // Gathered on the supervisor thread, inside the deadline: a slow node
            // must cost a couple of risk signals, never the whole prompt.
            analysis = supervisor.submit(() -> engine.analyse(txHex, ownership, context(connection)));
        } catch (RuntimeException e) {
            return degraded(txHex, "This transaction could not be checked: " + e.getMessage());
        }
        try {
            return toView(analysis.get(BUDGET_SECONDS, TimeUnit.SECONDS), txHex);
        } catch (TimeoutException e) {
            analysis.cancel(true);
            return degraded(txHex, "Checking this transaction took too long, so it has not been verified."
                    + " Treat it as unchecked.");
        } catch (InterruptedException e) {
            analysis.cancel(true);
            Thread.currentThread().interrupt();
            return degraded(txHex, "Checking this transaction was interrupted, so it has not been verified.");
        } catch (Exception e) {
            return degraded(txHex, "This transaction could not be checked, so it has not been verified.");
        }
    }

    /**
     * Balance and chain tip for the signals that need them (SIM-M3). Best-effort
     * by design: {@link WalletContext#unknown()} suppresses those two signals
     * rather than guessing, and a node hiccup here must not cost the user the
     * value diff they actually came for.
     */
    private WalletContext context(WalletBackendManager.ActiveConnection connection) {
        WalletService.Session current = session.get();
        BigInteger balance = null;
        long slot = 0L;
        try {
            if (current != null) {
                balance = current.balance().lovelace();
            }
        } catch (RuntimeException e) {
            balance = null;
        }
        try {
            slot = connection.service().nodeStatus().slot();
        } catch (RuntimeException e) {
            slot = 0L;
        }
        return new WalletContext(balance, slot);
    }

    /**
     * The addresses whose funds count as ours.
     *
     * <p>One base address plus one stake address, because that is exactly what
     * this wallet's signer can authorise: {@code DappSigner} signs with the
     * account's single payment key, and its stake key for certificates and
     * withdrawals. {@link WalletOwnership} requires the set to cover everything
     * the signer can sign — so whenever the signer gains reach (more derived
     * receive addresses, multiple accounts per ADR-037), this method must grow
     * with it, or inputs at the new credentials would be quietly treated as
     * somebody else's and shrink the reported loss.
     */
    private WalletOwnership ownership() {
        WalletService.Session current = session.get();
        if (current == null) {
            return WalletOwnership.ofAddresses(List.of());
        }
        StoredWallet profile = current.profile();
        List<String> addresses = new ArrayList<>();
        if (profile.baseAddress() != null) {
            addresses.add(profile.baseAddress());
        }
        List<String> rewardAddresses = new ArrayList<>();
        if (profile.stakeAddress() != null) {
            rewardAddresses.add(profile.stakeAddress());
        }
        // The DRep credential so a DRep unregistration's 500 ADA refund is
        // recognised as ours re-entering the transaction rather than value that
        // silently moves outside the diff.
        List<String> certificateCredentials = new ArrayList<>();
        String drepCredential = drepCredentialHex(profile.drepId());
        if (drepCredential != null) {
            certificateCredentials.add(drepCredential);
        }
        return WalletOwnership.of(addresses, rewardAddresses, certificateCredentials);
    }

    /** The credential hash behind a bech32 DRep id, or null if it cannot be read. */
    private static String drepCredentialHex(String drepId) {
        if (drepId == null || drepId.isBlank()) {
            return null;
        }
        try {
            byte[] bytes = com.bloxbean.cardano.client.crypto.Bech32.decode(drepId.strip()).data;
            if (bytes == null || bytes.length < 28) {
                return null;
            }
            // CIP-129 ids carry a one-byte header ahead of the 28-byte hash.
            byte[] hash = java.util.Arrays.copyOfRange(bytes, bytes.length - 28, bytes.length);
            return java.util.HexFormat.of().formatHex(hash);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** The honest zero-information prompt: no numbers, one plain reason. */
    static TxEffectView degraded(String txHex, String reason) {
        return new TxEffectView(TxEffectView.Completeness.INCOMPLETE, reason,
                0L, 0L, List.of(), TxEffectView.ScriptOutcome.COULD_NOT_VERIFY, null,
                0, 0L, 0L, List.of(),
                0, 0, 0, 0L, List.of(), List.of(), List.of(), txHex);
    }

    static TxEffectView toView(TxEffect effect, String txHex) {
        TxFacts facts = effect.facts();
        return new TxEffectView(
                switch (effect.completeness()) {
                    case COMPLETE -> TxEffectView.Completeness.COMPLETE;
                    case INCOMPLETE -> TxEffectView.Completeness.INCOMPLETE;
                    case UNDECODABLE -> TxEffectView.Completeness.UNDECODABLE;
                },
                effect.limitation(),
                toLong(effect.lovelaceDelta()),
                toLong(effect.fee()),
                effect.assetDeltas().stream()
                        .map(delta -> new TxEffectView.AssetChange(
                                AssetNameDisplay.of(delta.policyId(), delta.assetNameHex()),
                                delta.policyId(), delta.assetNameHex(),
                                delta.quantity().toString(), delta.isOutgoing()))
                        .toList(),
                switch (effect.scriptOutcome()) {
                    case NO_SCRIPTS -> TxEffectView.ScriptOutcome.NO_SCRIPTS;
                    case SUCCESS -> TxEffectView.ScriptOutcome.SUCCESS;
                    case FAILED -> TxEffectView.ScriptOutcome.FAILED;
                    case COULD_NOT_VERIFY -> TxEffectView.ScriptOutcome.COULD_NOT_VERIFY;
                },
                effect.scriptMessage(),
                effect.scriptCosts().size(),
                effect.scriptCosts().stream().mapToLong(c -> c.memory()).sum(),
                effect.scriptCosts().stream().mapToLong(c -> c.steps()).sum(),
                effect.risks().stream()
                        .map(risk -> new TxEffectView.RiskItem(
                                switch (risk.severity()) {
                                    case INFO -> TxEffectView.Severity.INFO;
                                    case WARNING -> TxEffectView.Severity.WARNING;
                                    case CRITICAL -> TxEffectView.Severity.CRITICAL;
                                },
                                risk.title(), risk.reason()))
                        .toList(),
                facts.outputCount(),
                facts.inputCount(),
                facts.unresolvedInputCount(),
                toLong(facts.collateralLovelace()),
                facts.certificates(),
                facts.withdrawals().stream()
                        .map(w -> new TxEffectView.WithdrawalItem(
                                w.rewardAddress(), toLong(w.lovelace()), w.mine()))
                        .toList(),
                facts.mint().stream()
                        .map(mint -> new TxEffectView.AssetChange(
                                AssetNameDisplay.of(mint.policyId(), mint.assetNameHex()),
                                mint.policyId(), mint.assetNameHex(),
                                mint.quantity().toString(), mint.quantity().signum() < 0))
                        .toList(),
                txHex);
    }

    /**
     * Saturates rather than throwing. Lovelace cannot legitimately exceed a long
     * (max supply is 4.5e16), so an out-of-range value means a malformed
     * transaction — and a clamped extreme number in the prompt is better than an
     * exception that would replace the whole summary with nothing.
     */
    private static long toLong(BigInteger value) {
        if (value == null) {
            return 0L;
        }
        if (value.bitLength() >= 64) {
            return value.signum() < 0 ? Long.MIN_VALUE : Long.MAX_VALUE;
        }
        return value.longValue();
    }

    void close() {
        supervisor.shutdownNow();
        resolvers.shutdownNow();
    }
}
