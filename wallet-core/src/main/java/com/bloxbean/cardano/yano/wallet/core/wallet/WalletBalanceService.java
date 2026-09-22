package com.bloxbean.cardano.yano.wallet.core.wallet;

import com.bloxbean.cardano.client.api.UtxoSupplier;
import com.bloxbean.cardano.client.api.model.Utxo;
import com.bloxbean.cardano.client.common.CardanoConstants;
import com.bloxbean.cardano.hdwallet.Wallet;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Aggregates all UTxOs discovered on both Shelley payment chains. */
public class WalletBalanceService {
    public WalletBalance scan(Wallet wallet, UtxoSupplier utxoSupplier) {
        return balance(new WalletAddressScanner().scan(wallet, utxoSupplier));
    }

    public WalletBalance scan(Wallet wallet, UtxoSupplier utxoSupplier, int gapLimit, int maxAddresses) {
        return balance(new WalletAddressScanner().scan(wallet, utxoSupplier, gapLimit, maxAddresses));
    }

    /** Aggregate a completed scan; callers may retain its public derivation paths. */
    public WalletBalance balance(WalletAddressScanner.Scan scan) {
        BigInteger total = BigInteger.ZERO;
        List<WalletUtxoView> walletUtxos = new ArrayList<>();
        Map<String, BigInteger> assets = new LinkedHashMap<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (var funded : scan.fundedAddresses()) {
            String address = funded.address().toBech32();
            for (Utxo utxo : funded.utxos()) {
                if (!seen.add(utxo.getTxHash() + "#" + utxo.getOutputIndex())) continue;
                BigInteger lovelace = lovelace(utxo);
                total = total.add(lovelace);
                aggregateAssets(utxo, assets);
                walletUtxos.add(new WalletUtxoView(
                        address, utxo.getTxHash(), utxo.getOutputIndex(), lovelace, assetCount(utxo),
                        utxo.getDataHash() != null || utxo.getInlineDatum() != null,
                        utxo.getReferenceScriptHash() != null));
            }
        }
        List<WalletAssetBalance> assetBalances = assets.entrySet().stream()
                .sorted(Comparator.comparing(Map.Entry::getKey))
                .map(entry -> new WalletAssetBalance(entry.getKey(), entry.getValue()))
                .toList();
        return new WalletBalance(total, scan.addressCount(), walletUtxos.size(), walletUtxos, assetBalances, scan.warning());
    }

    private BigInteger lovelace(Utxo utxo) {
        if (utxo == null || utxo.getAmount() == null) {
            return BigInteger.ZERO;
        }
        return utxo.getAmount().stream()
                .filter(amount -> CardanoConstants.LOVELACE.equals(amount.getUnit()))
                .map(amount -> amount.getQuantity() == null ? BigInteger.ZERO : amount.getQuantity())
                .reduce(BigInteger.ZERO, BigInteger::add);
    }

    private int assetCount(Utxo utxo) {
        if (utxo == null || utxo.getAmount() == null) {
            return 0;
        }
        return (int) utxo.getAmount().stream()
                .filter(amount -> !CardanoConstants.LOVELACE.equals(amount.getUnit()))
                .count();
    }

    private void aggregateAssets(Utxo utxo, Map<String, BigInteger> assets) {
        if (utxo == null || utxo.getAmount() == null) {
            return;
        }
        utxo.getAmount().stream()
                .filter(amount -> amount.getUnit() != null)
                .filter(amount -> !CardanoConstants.LOVELACE.equals(amount.getUnit()))
                .forEach(amount -> assets.merge(
                        amount.getUnit(),
                        amount.getQuantity() == null ? BigInteger.ZERO : amount.getQuantity(),
                        BigInteger::add));
    }
}
