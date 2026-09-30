package com.bloxbean.cardano.yano.wallet.core.service;

import com.bloxbean.cardano.client.api.TransactionProcessor;
import com.bloxbean.cardano.client.api.model.EvaluationResult;
import com.bloxbean.cardano.client.api.model.Result;
import com.bloxbean.cardano.client.api.model.Utxo;
import com.bloxbean.cardano.yano.wallet.core.config.WalletNetwork;
import com.bloxbean.cardano.yano.wallet.core.tx.FilePendingTransactionStore;
import com.bloxbean.cardano.yano.wallet.core.tx.PendingTransactionStatus;
import com.bloxbean.cardano.yano.wallet.core.tx.QuickAdaTxDraft;
import com.bloxbean.cardano.yano.wallet.core.wallet.FileStoredWalletRepository;
import com.bloxbean.cardano.yano.wallet.core.wallet.StoredWalletCreation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigInteger;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Which submit answers are a failed payment. Getting this wrong in the
 * "failed" direction is the expensive one: the draft is discarded, and a
 * rebuild selects other funds while the first payment may still land.
 */
class SubmitOutcomeTest {
    private static final String HASH = "ab".repeat(32);

    @TempDir
    Path tempDir;

    private FilePendingTransactionStore store;

    private WalletService.Session sessionAnswering(Result<String> answer) {
        PendingNodeAccess noNode = new PendingNodeAccess("no node in this test");
        store = new FilePendingTransactionStore(tempDir.resolve("pending-transactions.json"));
        TransactionProcessor processor = new TransactionProcessor() {
            @Override
            public Result<String> submitTransaction(byte[] cborData) {
                return answer;
            }

            @Override
            public Result<List<EvaluationResult>> evaluateTx(byte[] cbor, Set<Utxo> inputUtxos) {
                throw new UnsupportedOperationException();
            }
        };
        WalletService service = new WalletService(new FileStoredWalletRepository(tempDir, WalletNetwork.PREPROD),
                noNode, noNode, processor, store, noNode);
        StoredWalletCreation created = service.createWallet("submit", "passphrase".toCharArray());
        return service.unlock(created.wallet().id(), "passphrase".toCharArray());
    }

    private static QuickAdaTxDraft draft() {
        return new QuickAdaTxDraft(HASH, "84a0a0f5f6", "addr_from", "addr_to",
                BigInteger.valueOf(2_000_000), BigInteger.valueOf(170_000), 1000L, null, null, 1, 1);
    }

    @SuppressWarnings("unchecked")
    private static Result<String> answer(int code, String body) {
        return Result.error(body).code(code);
    }

    @Test
    void aServerErrorIsAnUnknownOutcomeNotAFailedPayment() {
        // A 5xx says nothing about whether the node took the transaction.
        WalletService.Session session = sessionAnswering(answer(503, "unavailable"));

        assertThatThrownBy(() -> session.submit(draft()))
                .isInstanceOf(WalletService.RetryableSubmitException.class)
                .hasMessageContaining("503");
        assertThat(store.find(HASH)).isEmpty();
    }

    @Test
    void aRefusalIsAFailedPayment() {
        WalletService.Session session = sessionAnswering(answer(400, "ValueNotConserved"));

        assertThatThrownBy(() -> session.submit(draft()))
                .isInstanceOf(WalletService.WalletServiceException.class)
                .isNotInstanceOf(WalletService.RetryableSubmitException.class)
                .hasMessageContaining("rejected");
        assertThat(store.find(HASH)).get()
                .extracting(tx -> tx.status()).isEqualTo(PendingTransactionStatus.FAILED);
    }
}
