package com.bloxbean.cardano.yano.wallet.core.service;

import com.bloxbean.cardano.yano.wallet.core.config.WalletNetwork;
import com.bloxbean.cardano.yano.wallet.core.tx.FilePendingTransactionStore;
import com.bloxbean.cardano.yano.wallet.core.wallet.FileStoredWalletRepository;
import com.bloxbean.cardano.yano.wallet.core.wallet.StoredWalletCreation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The warm-up half of a managed connection: while the node starts (over an hour
 * on a first start), the wallet still has to be able to create and restore
 * wallets, and must NOT quietly answer chain questions with defaults.
 */
class LocalOnlyWalletServiceTest {

    private static final String NOT_READY = "the node is still starting";

    @TempDir
    Path tempDir;

    private WalletService localOnly() {
        return WalletService.localOnly(
                new FileStoredWalletRepository(tempDir, WalletNetwork.PREPROD),
                new FilePendingTransactionStore(tempDir.resolve("pending.json")),
                NOT_READY);
    }

    @Test
    void createsAndListsWalletsWithoutANode() {
        WalletService service = localOnly();

        StoredWalletCreation created = service.createWallet("warm-up", "passphrase".toCharArray());

        assertThat(created.mnemonic()).isNotBlank();
        assertThat(service.listWallets()).hasSize(1);
        assertThat(service.listWallets().get(0).name()).isEqualTo("warm-up");
    }

    @Test
    void chainBackedReadsFailLoudlyRatherThanLookingLikeAnEmptyWallet() {
        WalletService service = localOnly();
        StoredWalletCreation created = service.createWallet("warm-up", "passphrase".toCharArray());
        WalletService.Session session =
                service.unlock(created.wallet().id(), "passphrase".toCharArray());

        // A zero balance here would be indistinguishable from a wallet that has
        // genuinely never been funded — the reason these throw instead.
        assertThatThrownBy(session::balance)
                .isInstanceOf(NodeNotReadyException.class)
                .hasMessageContaining(NOT_READY);
    }

    @Test
    void addressDerivationStillWorks() {
        // Purely local: a user can see and share a receive address while the node
        // is starting, which is often the first thing they want.
        WalletService service = localOnly();
        StoredWalletCreation created = service.createWallet("warm-up", "passphrase".toCharArray());
        WalletService.Session session =
                service.unlock(created.wallet().id(), "passphrase".toCharArray());

        assertThat(session.addresses(1).receiveAddresses()).isNotEmpty();
    }
}
