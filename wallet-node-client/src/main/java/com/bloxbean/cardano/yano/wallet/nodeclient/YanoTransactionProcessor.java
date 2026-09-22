package com.bloxbean.cardano.yano.wallet.nodeclient;

import com.bloxbean.cardano.client.backend.api.DefaultTransactionProcessor;
import com.bloxbean.cardano.client.backend.api.TransactionService;
import com.bloxbean.cardano.client.api.model.Result;
import com.bloxbean.cardano.client.api.exception.ApiException;
import com.bloxbean.cardano.yano.wallet.core.service.MempoolConflictException;

import java.util.Locale;

/** Retains the node rejection as terminal; never retries a conflicting signed transaction. */
final class YanoTransactionProcessor extends DefaultTransactionProcessor {
    private final PendingInputs pendingInputs;
    private final YanoNodeClient client;

    YanoTransactionProcessor(TransactionService service, PendingInputs pendingInputs, YanoNodeClient client) {
        super(service);
        this.pendingInputs = pendingInputs;
        this.client = client;
    }

    @Override
    public Result<String> submitTransaction(byte[] cbor) throws ApiException {
        pendingInputs.refresh(client);
        String hash = pendingInputs.reserve(cbor);
        // Transport failures and 5xx responses have an unknown outcome. Keep reservations until TTL.
        Result<String> result = super.submitTransaction(cbor);
        if (result != null && !result.isSuccessful()) {
            String reason = String.valueOf(result.getResponse());
            String normalized = reason.toLowerCase(Locale.ROOT);
            if (normalized.contains("mempool admission failed (conflict)")
                    || normalized.contains("regular input is already claimed by")) {
                boolean recorded = pendingInputs.recordConflict(hash, reason);
                if (!recorded && result.code() >= 400 && result.code() < 500) pendingInputs.release(hash);
                throw new MempoolConflictException(reason);
            }
            if (result.code() >= 400 && result.code() < 500) pendingInputs.release(hash);
        }
        return result;
    }
}
