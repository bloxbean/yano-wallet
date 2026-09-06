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

/** Preserve node failures that CCL's default supplier otherwise turns into empty results. */
final class YanoUtxoSupplier extends DefaultUtxoSupplier {
    private final UtxoService service;
    private final YanoNodeClient client;
    private boolean searchByAddressVkh;

    YanoUtxoSupplier(UtxoService service, YanoNodeClient client) {
        super(service);
        this.service = service;
        this.client = client;
    }

    @Override
    public List<Utxo> getPage(String address, Integer count, Integer page, OrderEnum order) {
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
