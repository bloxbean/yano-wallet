package com.bloxbean.cardano.yano.wallet.nodeclient;

import com.bloxbean.cardano.client.address.Address;
import com.bloxbean.cardano.client.api.UtxoSupplier;
import com.bloxbean.cardano.client.api.common.OrderEnum;
import com.bloxbean.cardano.client.api.exception.ApiException;
import com.bloxbean.cardano.client.api.exception.ApiRuntimeException;
import com.bloxbean.cardano.client.api.model.Utxo;
import com.bloxbean.cardano.client.backend.api.DefaultUtxoSupplier;
import com.bloxbean.cardano.client.backend.api.UtxoService;

import java.util.List;
import java.util.ArrayList;
import java.util.Optional;
import java.util.Set;

/** Preserve node failures that CCL's default supplier otherwise turns into empty results. */
final class YanoUtxoSupplier extends DefaultUtxoSupplier {
    private final UtxoService service;
    private final YanoNodeClient client;
    private boolean searchByAddressVkh;
    private final PendingInputs pendingInputs;

    YanoUtxoSupplier(UtxoService service, YanoNodeClient client) {
        this(service, client, null);
    }

    YanoUtxoSupplier(UtxoService service, YanoNodeClient client, PendingInputs pendingInputs) {
        super(service);
        this.service = service;
        this.client = client;
        this.pendingInputs = pendingInputs;
    }

    @Override
    public List<Utxo> getPage(String address, Integer count, Integer page, OrderEnum order) {
        int size = count == null ? UtxoSupplier.DEFAULT_NR_OF_ITEMS_TO_FETCH : count;
        int number = page == null ? 0 : page;
        if (size <= 0 || number < 0) throw new IllegalArgumentException("Invalid UTxO page/count");
        Set<String> blocked = blockedInputs();
        if (blocked.isEmpty()) return sourcePage(address, size, number, order);
        // Paginate AFTER filtering: a wholly reserved server page must not end CCL's getAll loop.
        long skip = Math.multiplyExact((long) number, size);
        List<Utxo> selected = new ArrayList<>();
        for (int sourcePage = 0; ; sourcePage++) {
            List<Utxo> outputs = sourcePage(address, size, sourcePage, order);
            if (outputs.isEmpty()) return selected;
            for (Utxo output : outputs) {
                if (blocked.contains(output.getTxHash() + "#" + output.getOutputIndex())) continue;
                if (skip > 0) { skip--; continue; }
                selected.add(output);
                if (selected.size() == size) return selected;
            }
        }
    }

    @Override
    public List<Utxo> getAll(String address) {
        Set<String> blocked = blockedInputs();
        List<Utxo> result = new ArrayList<>();
        for (int page = 0; ; page++) {
            List<Utxo> outputs = sourcePage(address, DEFAULT_NR_OF_ITEMS_TO_FETCH, page, OrderEnum.asc);
            if (outputs.isEmpty()) return result;
            outputs.stream().filter(u -> !blocked.contains(u.getTxHash() + "#" + u.getOutputIndex()))
                    .forEach(result::add);
        }
    }

    @Override
    public Optional<Utxo> getTxOutput(String txHash, int outputIndex) {
        if (blockedInputs().contains(txHash + "#" + outputIndex)) return Optional.empty();
        if (pendingInputs == null) return super.getTxOutput(txHash, outputIndex);
        return client.getSelectionOutput(txHash, outputIndex);
    }

    private Set<String> blockedInputs() {
        if (pendingInputs == null || pendingInputs.isEmpty()) return Set.of();
        pendingInputs.refresh(client);
        return pendingInputs.snapshot();
    }

    private List<Utxo> sourcePage(String address, Integer count, Integer page, OrderEnum order) {
        if (pendingInputs != null) {
            return client.getSelectionUtxos(address, searchByAddressVkh, count, page + 1,
                    order == null ? OrderEnum.asc : order);
        }
        String key = searchByAddressVkh
                ? new Address(address).getBech32VerificationKeyHash().orElseThrow() : address;
        try {
            var result = service.getUtxos(key, count == null ? UtxoSupplier.DEFAULT_NR_OF_ITEMS_TO_FETCH : count,
                    page == null ? 1 : page + 1, order == null ? OrderEnum.asc : order);
            if (result != null && result.code() == 404) return List.of();
            if (result == null || !result.isSuccessful() || result.getValue() == null) {
                throw new IllegalStateException("UTxO lookup failed (HTTP "
                        + (result == null ? "unknown" : result.code()) + "); balance is unavailable");
            }
            return result.getValue();
        } catch (ApiException e) {
            throw new ApiRuntimeException(e);
        }
    }

    @Override
    public boolean isUsedAddress(Address address) {
        return client.isAddressUsed(address.toBech32());
    }

    @Override
    public void setSearchByAddressVkh(boolean flag) {
        searchByAddressVkh = flag;
    }
}
