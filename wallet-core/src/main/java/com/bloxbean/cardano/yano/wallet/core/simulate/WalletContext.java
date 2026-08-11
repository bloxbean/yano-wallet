package com.bloxbean.cardano.yano.wallet.core.simulate;

import java.math.BigInteger;

/**
 * Wallet- and chain-side facts a few risk signals need but the transaction alone
 * cannot supply (ADR-042 SIM-M3): "is this draining everything I have?" needs the
 * balance, and "has this already expired?" needs the current slot.
 *
 * <p>Both are optional. An absent value suppresses the signal that depends on it
 * rather than guessing — a wrong "this drains your whole wallet" would be as
 * damaging to trust as a missed one.
 */
public record WalletContext(BigInteger balanceLovelace, long currentSlot) {

    public static WalletContext unknown() {
        return new WalletContext(null, 0L);
    }

    public boolean hasBalance() {
        return balanceLovelace != null && balanceLovelace.signum() > 0;
    }

    public boolean hasSlot() {
        return currentSlot > 0;
    }
}
