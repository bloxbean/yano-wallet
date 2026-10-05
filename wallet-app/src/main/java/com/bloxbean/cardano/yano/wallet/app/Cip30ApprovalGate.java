package com.bloxbean.cardano.yano.wallet.app;

import com.bloxbean.cardano.yano.wallet.connector.Cip30Approvals;
import com.bloxbean.cardano.yano.wallet.connector.Cip30Exception;
import com.bloxbean.cardano.yano.wallet.core.tx.DappSignerSearch;
import com.bloxbean.cardano.yano.wallet.ui.contract.Cip30Prompt;
import com.bloxbean.cardano.yano.wallet.ui.contract.TxEffectView;

/**
 * Bridges the connector's {@link Cip30Approvals} SPI to the persisted allowlist +
 * the UI {@link Cip30Prompt} (ADR-035), and is where a transaction is simulated
 * before the user is asked to approve it (ADR-042).
 *
 * <p>A connect prompt that the user grants is remembered so the site connects
 * silently next time; every signature still prompts, and every prompt shows what
 * the transaction actually does.
 */
final class Cip30ApprovalGate implements Cip30Approvals {

    private final Cip30AllowlistStore allowlist;
    private final Cip30Prompt prompt;
    private final TxEffectSummariser summariser;
    private final WalletCip30Wallet wallet;

    Cip30ApprovalGate(Cip30AllowlistStore allowlist, Cip30Prompt prompt, TxEffectSummariser summariser,
                      WalletCip30Wallet wallet) {
        this.allowlist = allowlist;
        this.prompt = prompt;
        this.summariser = summariser;
        this.wallet = wallet;
    }

    @Override
    public boolean isConnected(String origin) {
        return allowlist.isAllowed(origin);
    }

    @Override
    public boolean confirmConnect(String origin) {
        if (allowlist.isAllowed(origin)) {
            return true;
        }
        boolean granted = prompt.confirmConnect(origin);
        if (granted) {
            allowlist.allow(origin);
        }
        return granted;
    }

    @Override
    public boolean confirmSign(String origin, String txHex, boolean partialSign) {
        // Key discovery fails closed if input ownership cannot be resolved.
        // The separate effect simulation is best-effort and visibly reports its limits.
        var review = wallet.reviewSigning(txHex, partialSign, DappSignerSearch.DEFAULT_LIMIT);
        if (review.plan() != null && !review.plan().unmatched().isEmpty()
                && prompt.confirmExtendedSignerSearch(origin, review.plan().description())) {
            var extended = wallet.reviewSigning(txHex, partialSign, DappSignerSearch.EXTENDED_LIMIT);
            if (extended.session() != review.session() || extended.connection() != review.connection())
                throw Cip30Exception.refused("Account or network changed during signer search. Retry the request.");
            review = extended;
        }
        if (review.plan() != null) {
            if (!review.plan().hasSigners())
                throw Cip30Exception.refused("No matching signing keys found. " + review.plan().description());
            if (!partialSign && !review.plan().unmatched().isEmpty())
                throw Cip30Exception.refused("Cannot fully sign within this search. " + review.plan().description());
        }
        TxEffectView effect = summariser.summarise(txHex);
        String paths = review.plan() == null ? "Hardware wallet: verify signing on the device."
                : review.plan().description();
        if (!prompt.confirmSign(origin, effect, paths)) return false;
        wallet.approveSigning(review);
        return true;
    }

    @Override
    public boolean confirmSignData(String origin, String address) {
        return false; // A payload-bound review is required by this implementation.
    }

    @Override
    public boolean confirmSignData(String origin, String address, String payload) {
        var review = wallet.reviewData(address, payload, DappSignerSearch.DEFAULT_LIMIT);
        if (!review.plan().hasSigner() && prompt.confirmExtendedSignerSearch(origin, review.plan().description())) {
            var extended = wallet.reviewData(address, payload, DappSignerSearch.EXTENDED_LIMIT);
            if (extended.session() != review.session() || extended.connection() != review.connection())
                throw Cip30Exception.refused("Account or network changed during signer search. Retry the request.");
            review = extended;
        }
        if (!review.plan().hasSigner()) throw Cip30Exception.refused("No matching data signing key. " + review.plan().description());
        if (!prompt.confirmSignData(origin, address, review.plan().description(), review.plan().payload())) return false;
        wallet.approveData(review);
        return true;
    }
}
