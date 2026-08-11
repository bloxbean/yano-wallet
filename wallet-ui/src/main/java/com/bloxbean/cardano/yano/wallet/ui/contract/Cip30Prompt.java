package com.bloxbean.cardano.yano.wallet.ui.contract;

/**
 * The UI half of the CIP-30 consent flow (ADR-035, ADR-042): a modal that asks the
 * user to connect a dApp or approve a signature. Implemented by the UI with a
 * JavaFX dialog; called from a bridge worker thread, so implementations must
 * marshal to the UI thread and block until the user decides.
 */
public interface Cip30Prompt {

    /** Ask the user to connect {@code origin} (e.g. "https://app.example"). */
    boolean confirmConnect(String origin);

    /**
     * Ask the user to approve a transaction from {@code origin}, showing what it
     * will actually do to their wallet (ADR-042) rather than a free-text summary.
     *
     * <p>Implementations must present {@link TxEffectView#completeness()} as
     * prominently as the amounts: an unverified transaction is not a safe one.
     */
    boolean confirmSign(String origin, TxEffectView effect);

    /**
     * Ask the user to approve a CIP-8 data signature from {@code origin}. Data
     * signing moves no value and has no effect to simulate, so it deliberately
     * does not take a {@link TxEffectView} it could not fill.
     */
    boolean confirmSignData(String origin, String address);
}
