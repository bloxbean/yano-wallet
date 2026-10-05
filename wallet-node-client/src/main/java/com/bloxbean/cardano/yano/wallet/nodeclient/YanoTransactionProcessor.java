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
            boolean definite = result.code() >= 400 && result.code() < 500;
            if (normalized.contains("mempool admission failed (conflict)")
                    || normalized.contains("regular input is already claimed by")) {
                // Claimed by this very transaction: it is in the mempool already.
                // pre16 answers a duplicate 200 before it checks conflicts, so this
                // guards other builds — recorded as a conflict it would be marked
                // failed and its real TTL lost.
                if (PendingInputs.claimant(reason).filter(hash::equalsIgnoreCase).isPresent()) {
                    return submitted(hash, "Transaction is already in the mempool");
                }
                boolean recorded = pendingInputs.recordConflict(hash, reason);
                if (!recorded && definite) pendingInputs.release(hash);
                throw new MempoolConflictException(reason);
            }
            if (definite) {
                if (alreadyInBlock(hash, reason)) return submitted(hash, "Transaction is already on chain");
                pendingInputs.release(hash);
            }
        }
        return result;
    }

    /**
     * Whether a refused transaction is refused because it has already landed. A
     * resend after an unknown outcome gets exactly that when the first attempt
     * went through: its inputs are spent — by itself. Recorded as a failure, the
     * draft is discarded and a rebuild selects other funds, paying twice. A
     * resend still in the mempool needs no check; the node answers it 200.
     *
     * <p>Asked on every definite refusal, not only on resends: the reservation a
     * resend would be recognised by is gone once {@link PendingInputs#refresh}
     * sees the transaction in a block, which is exactly this case.
     */
    private boolean alreadyInBlock(String hash, String reason) throws ApiException {
        try {
            return "in_block".equals(client.getTxStatus(hash).status());
        } catch (NodeClientException unanswered) {
            // A refusal that cannot be told apart from "already on chain" has an
            // unknown outcome: keep the reservation and report it as a transport
            // failure, so the caller keeps the draft instead of marking it failed.
            throw new ApiException("The node refused the transaction (" + reason
                    + "), but whether it is already on chain could not be checked: "
                    + unanswered.getMessage(), unanswered);
        }
    }

    @SuppressWarnings("unchecked")
    private static Result<String> submitted(String hash, String response) {
        return Result.success(response).withValue(hash).code(200);
    }
}
