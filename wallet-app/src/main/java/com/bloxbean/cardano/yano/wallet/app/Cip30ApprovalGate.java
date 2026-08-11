package com.bloxbean.cardano.yano.wallet.app;

import com.bloxbean.cardano.yano.wallet.connector.Cip30Approvals;
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

    Cip30ApprovalGate(Cip30AllowlistStore allowlist, Cip30Prompt prompt, TxEffectSummariser summariser) {
        this.allowlist = allowlist;
        this.prompt = prompt;
        this.summariser = summariser;
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
        // Simulation is best-effort and bounded; it never decides for the user and
        // never blocks the prompt from appearing.
        TxEffectView effect = summariser.summarise(txHex);
        return prompt.confirmSign(origin, effect);
    }

    @Override
    public boolean confirmSignData(String origin, String address) {
        return prompt.confirmSignData(origin, address);
    }
}
