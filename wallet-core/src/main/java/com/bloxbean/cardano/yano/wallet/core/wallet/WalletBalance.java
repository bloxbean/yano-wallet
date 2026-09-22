package com.bloxbean.cardano.yano.wallet.core.wallet;

import java.math.BigInteger;
import java.util.List;

public record WalletBalance(
        BigInteger lovelace,
        int addressCount,
        int utxoCount,
        List<WalletUtxoView> utxos,
        List<WalletAssetBalance> assets,
        String scanWarning) {

    public boolean complete() { return scanWarning == null; }

    public WalletBalance(BigInteger lovelace, int addressCount, int utxoCount,
                         List<WalletUtxoView> utxos, List<WalletAssetBalance> assets) {
        this(lovelace, addressCount, utxoCount, utxos, assets, null);
    }

    public WalletBalance {
        lovelace = lovelace == null ? BigInteger.ZERO : lovelace;
        utxos = utxos == null ? List.of() : List.copyOf(utxos);
        assets = assets == null ? List.of() : List.copyOf(assets);
    }

    public WalletBalance(
            BigInteger lovelace,
            int addressCount,
            int utxoCount,
            List<WalletUtxoView> utxos) {
        this(lovelace, addressCount, utxoCount, utxos, List.of());
    }
}
