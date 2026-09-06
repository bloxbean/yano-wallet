package com.bloxbean.cardano.yano.wallet.core.wallet;

import com.bloxbean.cardano.client.address.Address;
import com.bloxbean.cardano.client.api.UtxoSupplier;
import com.bloxbean.cardano.client.api.model.Utxo;
import com.bloxbean.cardano.hdwallet.Wallet;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Shared discovery for balances and software payments; each chain has its own history gap. */
public final class WalletAddressScanner {
    // A safety bound, not a successful stopping condition. Never publish a truncated balance.
    public static final int DEFAULT_MAX_ADDRESSES_PER_CHAIN = 10_000;

    public record FundedAddress(Address address, List<Utxo> utxos) {
        public FundedAddress { utxos = List.copyOf(utxos); }
    }

    public record Scan(int addressCount, List<FundedAddress> fundedAddresses) {
        public Scan { fundedAddresses = List.copyOf(fundedAddresses); }
    }

    public Scan scan(Wallet wallet, UtxoSupplier supplier) {
        Objects.requireNonNull(wallet, "wallet is required");
        int limit = Integer.getInteger("yano.wallet.scan.max-addresses-per-chain", DEFAULT_MAX_ADDRESSES_PER_CHAIN);
        return scan(wallet, supplier, wallet.getGapLimit(), limit);
    }

    public Scan scan(Wallet wallet, UtxoSupplier supplier, int gapLimit, int maxAddressesPerChain) {
        Objects.requireNonNull(wallet, "wallet is required");
        Objects.requireNonNull(supplier, "utxoSupplier is required");
        if (gapLimit <= 0 || maxAddressesPerChain <= 0) {
            throw new IllegalArgumentException("gapLimit and maxAddressesPerChain must be positive");
        }
        List<FundedAddress> funded = new ArrayList<>();
        int scanned = 0;
        for (int role = 0; role <= 1; role++) {
            int unused = 0;
            for (int index = 0; index < maxAddressesPerChain && unused < gapLimit; index++) {
                if (Thread.currentThread().isInterrupted()) {
                    throw new IncompleteScanException("Address scan interrupted; balance is unavailable");
                }
                Address address = WalletAddresses.baseAddress(wallet, role, index);
                List<Utxo> utxos = supplier.getAll(address.toBech32());
                if (utxos == null) {
                    throw new IncompleteScanException("Node did not return UTxOs; balance is unavailable");
                }
                scanned++;
                if (!utxos.isEmpty()) {
                    unused = 0;
                    funded.add(new FundedAddress(address, utxos));
                } else {
                    try {
                        unused = supplier.isUsedAddress(address) ? 0 : unused + 1;
                    } catch (RuntimeException e) {
                        throw new IncompleteScanException(
                                "Address scan incomplete: transaction history is unavailable. "
                                + "Use a node with address history enabled and retry.", e);
                    }
                }
            }
            if (unused < gapLimit) {
                throw new IncompleteScanException("Address scan incomplete: reached " + maxAddressesPerChain
                        + " addresses on the " + (role == 0 ? "receive" : "change")
                        + " chain before finding an unused gap. Increase yano.wallet.scan.max-addresses-per-chain and retry.");
            }
        }
        return new Scan(scanned, funded);
    }

    public static final class IncompleteScanException extends IllegalStateException {
        public IncompleteScanException(String message) { super(message); }
        public IncompleteScanException(String message, Throwable cause) { super(message, cause); }
    }
}
