package com.bloxbean.cardano.yano.wallet.core.service;

import com.bloxbean.cardano.client.api.ProtocolParamsSupplier;
import com.bloxbean.cardano.client.api.TransactionProcessor;
import com.bloxbean.cardano.client.api.UtxoSupplier;
import com.bloxbean.cardano.client.api.model.Amount;
import com.bloxbean.cardano.client.api.model.Result;
import com.bloxbean.cardano.client.util.HexUtil;
import com.bloxbean.cardano.yano.wallet.core.tx.PendingTransaction;
import com.bloxbean.cardano.yano.wallet.core.tx.PendingTransactionStatus;
import com.bloxbean.cardano.yano.wallet.core.tx.PendingTransactionStore;
import com.bloxbean.cardano.yano.wallet.core.tx.QuickAdaTxDraft;
import com.bloxbean.cardano.yano.wallet.core.tx.QuickAdaTxService;
import com.bloxbean.cardano.yano.wallet.core.tx.QuickTxPayment;
import com.bloxbean.cardano.yano.wallet.core.wallet.StoredWallet;
import com.bloxbean.cardano.yano.wallet.core.wallet.StoredWalletCreation;
import com.bloxbean.cardano.yano.wallet.core.wallet.StoredWalletRepository;
import com.bloxbean.cardano.yano.wallet.core.wallet.UnlockedWallet;
import com.bloxbean.cardano.yano.wallet.core.wallet.WalletAccountView;
import com.bloxbean.cardano.yano.wallet.core.wallet.WalletAddressService;
import com.bloxbean.cardano.yano.wallet.core.wallet.WalletBalance;
import com.bloxbean.cardano.yano.wallet.core.wallet.WalletBalanceService;

import java.math.BigInteger;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The ONE production money path (ADR-033): every consumer — probe, desktop
 * UI, future CIP-30 bridge — moves funds through this facade so unlock,
 * draft, submit, pending-tracking, and confirmation behave identically.
 */
public class WalletService {
    private final StoredWalletRepository repository;
    private final UtxoSupplier utxoSupplier;
    private final ProtocolParamsSupplier protocolParamsSupplier;
    private final TransactionProcessor transactionProcessor;
    private final PendingTransactionStore pendingStore;
    private final NodeStatusPort nodeStatusPort;
    private final QuickAdaTxService txService = new QuickAdaTxService();
    private final WalletBalanceService balanceService = new WalletBalanceService();
    private final WalletAddressService addressService = new WalletAddressService();

    public WalletService(StoredWalletRepository repository,
                         UtxoSupplier utxoSupplier,
                         ProtocolParamsSupplier protocolParamsSupplier,
                         TransactionProcessor transactionProcessor,
                         PendingTransactionStore pendingStore,
                         NodeStatusPort nodeStatusPort) {
        this.repository = Objects.requireNonNull(repository, "repository is required");
        this.utxoSupplier = Objects.requireNonNull(utxoSupplier, "utxoSupplier is required");
        this.protocolParamsSupplier =
                Objects.requireNonNull(protocolParamsSupplier, "protocolParamsSupplier is required");
        this.transactionProcessor = Objects.requireNonNull(transactionProcessor, "transactionProcessor is required");
        this.pendingStore = Objects.requireNonNull(pendingStore, "pendingStore is required");
        this.nodeStatusPort = Objects.requireNonNull(nodeStatusPort, "nodeStatusPort is required");
    }

    public StoredWalletRepository repository() {
        return repository;
    }

    public List<StoredWallet> listWallets() {
        return repository.list();
    }

    public Optional<StoredWallet> findWallet(String walletId) {
        return repository.find(walletId);
    }

    public StoredWalletCreation createWallet(String name, char[] passphrase) {
        return repository.createRandomWallet(name, passphrase);
    }

    public StoredWallet restoreWallet(String name, String mnemonic, char[] passphrase) {
        return repository.importMnemonic(name, mnemonic, passphrase);
    }

    /**
     * Derives the next CIP-1852 account (ADR-037) from an existing seed and stores
     * it as its own profile in the same {@code seedId} group. Every downstream
     * feature keys off the profile, so the new account is a full wallet.
     */
    public StoredWallet createAccount(String seedId, String name, char[] passphrase) {
        return repository.createAccount(seedId, name, passphrase);
    }

    /** Derives a specific account index — for accounts found by discovery (ADR-037). */
    public StoredWallet createAccountAt(String seedId, String name, char[] passphrase, int accountIndex) {
        return repository.createAccountAt(seedId, name, passphrase, accountIndex);
    }

    /**
     * Unlocks a wallet into a session that holds the derived keys in memory.
     * Callers own the session lifecycle: discard it to lock.
     */
    public Session unlock(String walletId, char[] passphrase) {
        return new Session(repository.unlock(walletId, passphrase));
    }

    /** Unlocks a factored wallet (ADR-036), resolving the challenge via the security key. */
    public Session unlock(String walletId, char[] passphrase,
                          com.bloxbean.cardano.yano.wallet.core.vault.VaultSecondFactor factor) {
        return new Session(repository.unlock(walletId, passphrase, factor));
    }

    /**
     * Opens a watch-only hardware wallet (ADR-034) into a session — no passphrase.
     * Balance, addresses, and history work off the device's account public key;
     * signing is performed on the device (wired via a separate path).
     */
    public Session unlockWatchOnly(String walletId) {
        return new Session(repository.unlockWatchOnly(walletId));
    }

    public NodeStatusPort.NodeView nodeStatus() {
        return nodeStatusPort.status();
    }

    public NodeStatusPort.TxStatusView txStatus(String txHash) {
        return nodeStatusPort.txStatus(txHash);
    }

    public List<PendingTransaction> pendingTransactions(String walletId, String networkId) {
        return pendingStore.list(walletId, networkId);
    }

    /** Drops a local pending record — call once the node's history confirms it. */
    public void forgetPending(String txHash) {
        pendingStore.remove(txHash);
    }

    /**
     * How long a submitted transaction may stay unseen on chain before the wallet
     * stops calling it pending. Generous next to a ~20s Cardano block, because
     * saying "failed" about a transaction that later lands would be worse than
     * waiting: the record is the user's only evidence it was ever sent.
     */
    public static final long PENDING_TIMEOUT_MILLIS = 5 * 60 * 1000L;

    /**
     * Marks a submitted-but-never-seen transaction as failed once
     * {@link #PENDING_TIMEOUT_MILLIS} has passed.
     *
     * <p>Without this a record can never leave the pending state: the only other
     * exit is the node's history containing its hash, so a transaction that was
     * rejected after submission, or that belonged to a devnet which has since
     * been reset, sits at the top of Recent Activity forever claiming to be in
     * flight.
     *
     * <p>Marked, not deleted — a transaction the user submitted and that did not
     * arrive is exactly the thing they should still be able to see.
     *
     * @return true if this call expired the record
     */
    public boolean expirePendingIfStale(String txHash, long nowEpochMillis) {
        return pendingStore.find(txHash)
                .filter(pending -> pending.isStale(nowEpochMillis, PENDING_TIMEOUT_MILLIS))
                .map(pending -> {
                    pendingStore.save(pending.markFailed(
                            "Not seen on chain within " + (PENDING_TIMEOUT_MILLIS / 60000) + " minutes"));
                    return true;
                })
                .orElse(false);
    }

    /**
     * Records an already-submitted payment as PENDING (for tx paths built outside
     * QuickTx, e.g. hardware-signed sends) so it appears in Recent Activity until
     * the node's history confirms it.
     */
    public void recordSubmittedPayment(StoredWallet profile, String txHash, BigInteger lovelace,
                                       BigInteger fee, String toAddress, Long ttlSlot) {
        pendingStore.save(PendingTransaction.submitted(txHash, profile.id(), profile.networkId(),
                lovelace, fee, profile.baseAddress(), toAddress, ttlSlot));
    }

    /**
     * Polls the node until the transaction lands in a block; survives
     * transient node errors and marks the pending record confirmed. Does NOT
     * touch the unlocked session/keys, so it is safe to keep running after the
     * wallet is locked. Blocks the caller — use {@link #trackConfirmation}.
     */
    public boolean awaitConfirmation(String txHash, long timeoutSeconds) {
        long deadline = System.nanoTime() + timeoutSeconds * 1_000_000_000L;
        while (System.nanoTime() < deadline) {
            try {
                NodeStatusPort.TxStatusView status = nodeStatusPort.txStatus(txHash);
                if (status.state() == NodeStatusPort.TxState.IN_BLOCK) {
                    pendingStore.find(txHash).ifPresent(pending ->
                            pendingStore.save(pending.markConfirmed(
                                    status.slot(), status.blockHeight(), status.blockHash())));
                    return true;
                }
            } catch (RuntimeException e) {
                // Transient node error during polling must not fail a submitted tx.
            }
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    /**
     * Runs {@link #awaitConfirmation} on a dedicated daemon thread so a long
     * confirmation poll never blocks the caller's executor and holds no
     * reference to the unlocked session.
     */
    public void trackConfirmation(String txHash, long timeoutSeconds) {
        Thread thread = new Thread(() -> awaitConfirmation(txHash, timeoutSeconds),
                "wallet-confirm-" + txHash.substring(0, 8));
        thread.setDaemon(true);
        thread.start();
    }

    /** An unlocked wallet bound to the service's node backend. */
    public final class Session {
        private final UnlockedWallet unlocked;

        private Session(UnlockedWallet unlocked) {
            this.unlocked = unlocked;
        }

        public StoredWallet profile() {
            return unlocked.profile();
        }

        public WalletBalance balance() {
            return balanceService.scan(unlocked.wallet(), utxoSupplier);
        }

        public WalletAccountView addresses(int receiveAddressCount) {
            return addressService.accountView(unlocked.profile(), unlocked.wallet(), receiveAddressCount);
        }

        /** Builds and signs a payment without submitting — for review/approval UIs. */
        public QuickAdaTxDraft draftPayment(String receiverAddress, BigInteger lovelace,
                                            List<Amount> nativeAssets, String message) {
            return txService.buildSignedDraft(
                    unlocked.wallet(),
                    utxoSupplier,
                    protocolParamsSupplier,
                    transactionProcessor,
                    receiverAddress,
                    lovelace,
                    nativeAssets == null ? List.of() : nativeAssets,
                    message);
        }

        public QuickAdaTxDraft draftPayments(List<QuickTxPayment> payments, String message) {
            return txService.buildSignedDraft(
                    unlocked.wallet(),
                    utxoSupplier,
                    protocolParamsSupplier,
                    transactionProcessor,
                    payments,
                    message);
        }

        /**
         * Builds and signs a delegation to a pool, registering the stake
         * address in the same transaction when it isn't registered yet.
         *
         * <p>Stake operations run through the wallet's account-0 {@code Account}:
         * CCL's hdwallet signer discovery is UTXO-driven and finds no payment
         * signer on cert-only transactions. Funds spent here must sit on the
         * primary (index 0) address — true for wallets created by this app.
         */
        public QuickAdaTxDraft draftDelegation(String poolId) {
            NodeStatusPort.AccountView account =
                    nodeStatusPort.accountInfo(unlocked.profile().stakeAddress());
            var signerAccount = unlocked.wallet().getAccountAtIndex(0);
            com.bloxbean.cardano.client.quicktx.Tx tx = new com.bloxbean.cardano.client.quicktx.Tx();
            if (!account.registered()) {
                tx.registerStakeAddress(signerAccount.baseAddress());
            }
            tx.delegateTo(signerAccount.baseAddress(), poolId);
            tx.from(signerAccount.baseAddress());
            return signStakeTx(tx, signerAccount, "delegate:" + poolId);
        }

        /**
         * Delegates this account's voting power to a DRep (CIP-1694). Vote
         * delegation needs a registered stake credential, so a first-time
         * delegation also registers the stake address (~2 ₳ refundable deposit).
         */
        public QuickAdaTxDraft draftVoteDelegation(
                com.bloxbean.cardano.client.transaction.spec.governance.DRep drep, String targetLabel) {
            NodeStatusPort.AccountView account =
                    nodeStatusPort.accountInfo(unlocked.profile().stakeAddress());
            var signerAccount = unlocked.wallet().getAccountAtIndex(0);
            com.bloxbean.cardano.client.quicktx.Tx tx = new com.bloxbean.cardano.client.quicktx.Tx();
            if (!account.registered()) {
                tx.registerStakeAddress(signerAccount.baseAddress());
            }
            tx.delegateVotingPowerTo(signerAccount.baseAddress(), drep);
            tx.from(signerAccount.baseAddress());
            return signStakeTx(tx, signerAccount, "vote:" + targetLabel);
        }

        /**
         * Registers this account's DRep key as a DRep (CIP-1694), locking a
         * ~500 ₳ deposit and an optional rationale anchor.
         */
        public QuickAdaTxDraft draftDRepRegistration(String anchorUrl, byte[] anchorHash) {
            var signerAccount = unlocked.wallet().getAccountAtIndex(0);
            com.bloxbean.cardano.client.quicktx.Tx tx = new com.bloxbean.cardano.client.quicktx.Tx();
            if (anchorUrl != null && !anchorUrl.isEmpty()) {
                tx.registerDRep(signerAccount,
                        new com.bloxbean.cardano.client.transaction.spec.governance.Anchor(anchorUrl, anchorHash));
            } else {
                tx.registerDRep(signerAccount);
            }
            tx.from(signerAccount.baseAddress());
            return signGovernanceTx(tx, signerAccount, "register-drep");
        }

        /**
         * Unregisters this account's DRep (CIP-1694), reclaiming the locked
         * {@code deposit} to the account's base address.
         */
        public QuickAdaTxDraft draftDRepDeregistration(java.math.BigInteger deposit) {
            var signerAccount = unlocked.wallet().getAccountAtIndex(0);
            com.bloxbean.cardano.client.quicktx.Tx tx = new com.bloxbean.cardano.client.quicktx.Tx();
            tx.unregisterDRep(signerAccount.drepCredential(), signerAccount.baseAddress(), deposit);
            tx.from(signerAccount.baseAddress());
            return signGovernanceTx(tx, signerAccount, "unregister-drep");
        }

        /** Casts a vote as a DRep on a governance action (CIP-1694). */
        public QuickAdaTxDraft draftVote(String govActionTxHash, int govActionIndex,
                                         com.bloxbean.cardano.client.transaction.spec.governance.Vote vote,
                                         String anchorUrl, byte[] anchorHash) {
            var signerAccount = unlocked.wallet().getAccountAtIndex(0);
            var voter = new com.bloxbean.cardano.client.transaction.spec.governance.Voter(
                    com.bloxbean.cardano.client.transaction.spec.governance.VoterType.DREP_KEY_HASH,
                    signerAccount.drepCredential());
            var govActionId = new com.bloxbean.cardano.client.transaction.spec.governance.actions.GovActionId(
                    govActionTxHash, govActionIndex);
            com.bloxbean.cardano.client.quicktx.Tx tx = new com.bloxbean.cardano.client.quicktx.Tx();
            if (anchorUrl != null && !anchorUrl.isEmpty()) {
                tx.createVote(voter, govActionId, vote,
                        new com.bloxbean.cardano.client.transaction.spec.governance.Anchor(anchorUrl, anchorHash));
            } else {
                tx.createVote(voter, govActionId, vote);
            }
            tx.from(signerAccount.baseAddress());
            return signGovernanceTx(tx, signerAccount,
                    "vote:" + vote + ":" + govActionTxHash + "#" + govActionIndex);
        }

        /**
         * Signs a dApp-provided transaction (CIP-30 signTx), returning only the
         * wallet's witness-set hex. Software wallets only (hardware = M4).
         */
        public String signDappTx(String txHex, boolean partialSign) {
            if (unlocked.profile().isHardware()) {
                throw new WalletServiceException("dApp signing with a hardware wallet isn't supported yet.");
            }
            return com.bloxbean.cardano.yano.wallet.core.tx.DappSigner.witnessSetHex(
                    unlocked.wallet().getAccountAtIndex(0), txHex, partialSign);
        }

        /** Signs data for a dApp (CIP-30 signData / CIP-8). Software wallets only. */
        public com.bloxbean.cardano.client.cip.cip30.DataSignature signDappData(byte[] addressBytes,
                                                                                byte[] payloadBytes) {
            if (unlocked.profile().isHardware()) {
                throw new WalletServiceException("dApp signing with a hardware wallet isn't supported yet.");
            }
            return com.bloxbean.cardano.yano.wallet.core.tx.DappSigner.signData(
                    unlocked.wallet().getAccountAtIndex(0), addressBytes, payloadBytes);
        }

        /** Builds and signs a withdrawal of all available rewards. */
        public QuickAdaTxDraft draftWithdrawal() {
            NodeStatusPort.AccountView account =
                    nodeStatusPort.accountInfo(unlocked.profile().stakeAddress());
            if (account.withdrawable() == null || account.withdrawable().signum() <= 0) {
                throw new WalletServiceException("No rewards available to withdraw");
            }
            var signerAccount = unlocked.wallet().getAccountAtIndex(0);
            com.bloxbean.cardano.client.quicktx.Tx tx = new com.bloxbean.cardano.client.quicktx.Tx();
            tx.withdraw(unlocked.profile().stakeAddress(), account.withdrawable());
            tx.from(signerAccount.baseAddress());
            return signStakeTx(tx, signerAccount, "withdraw:" + account.withdrawable());
        }

        /**
         * Mints {@code quantity} of a native asset under a single-signature
         * policy derived from this wallet's account-0 payment key, to the
         * wallet's own address. Useful for creating test tokens.
         */
        public QuickAdaTxDraft draftMint(String assetName, BigInteger quantity) {
            if (quantity == null || quantity.signum() <= 0) {
                throw new WalletServiceException("Mint quantity must be positive");
            }
            var account = unlocked.wallet().getAccountAtIndex(0);
            com.bloxbean.cardano.client.transaction.spec.script.ScriptPubkey policy;
            try {
                policy = com.bloxbean.cardano.client.transaction.spec.script.ScriptPubkey.create(
                        com.bloxbean.cardano.client.crypto.VerificationKey.create(account.publicKeyBytes()));
            } catch (com.bloxbean.cardano.client.exception.CborSerializationException e) {
                throw new WalletServiceException("Unable to build mint policy", e);
            }
            var asset = new com.bloxbean.cardano.client.transaction.spec.Asset(assetName, quantity);
            com.bloxbean.cardano.client.quicktx.Tx tx = new com.bloxbean.cardano.client.quicktx.Tx()
                    .mintAssets(policy, asset, account.baseAddress())
                    .from(account.baseAddress());
            return signStakeTx(tx, account, "mint:" + quantity + " " + assetName);
        }

        private QuickAdaTxDraft signStakeTx(com.bloxbean.cardano.client.quicktx.Tx tx,
                                            com.bloxbean.cardano.client.account.Account signerAccount,
                                            String summary) {
            return signComposedTx(tx, signerAccount, summary,
                    com.bloxbean.cardano.client.function.helper.SignerProviders
                            .stakeKeySignerFrom(signerAccount));
        }

        private QuickAdaTxDraft signGovernanceTx(com.bloxbean.cardano.client.quicktx.Tx tx,
                                                 com.bloxbean.cardano.client.account.Account signerAccount,
                                                 String summary) {
            return signComposedTx(tx, signerAccount, summary,
                    com.bloxbean.cardano.client.function.helper.SignerProviders
                            .drepKeySignerFrom(signerAccount));
        }

        private QuickAdaTxDraft signComposedTx(com.bloxbean.cardano.client.quicktx.Tx tx,
                                               com.bloxbean.cardano.client.account.Account signerAccount,
                                               String summary,
                                               com.bloxbean.cardano.client.function.TxSigner extraSigner) {
            var builder = new com.bloxbean.cardano.client.quicktx.QuickTxBuilder(
                    utxoSupplier, protocolParamsSupplier, transactionProcessor);
            com.bloxbean.cardano.client.transaction.spec.Transaction signed = builder.compose(tx)
                    .feePayer(signerAccount.baseAddress())
                    .withSigner(com.bloxbean.cardano.client.function.helper.SignerProviders
                            .signerFrom(signerAccount))
                    .withSigner(extraSigner)
                    .buildAndSign();
            byte[] cbor;
            try {
                cbor = signed.serialize();
            } catch (com.bloxbean.cardano.client.exception.CborSerializationException e) {
                throw new WalletServiceException("Unable to serialize stake transaction", e);
            }
            return new QuickAdaTxDraft(
                    com.bloxbean.cardano.client.transaction.util.TransactionUtil.getTxHash(signed),
                    HexUtil.encodeHexString(cbor),
                    unlocked.profile().baseAddress(),
                    summary,
                    BigInteger.ZERO,
                    signed.getBody().getFee(),
                    signed.getBody().getTtl() > 0 ? signed.getBody().getTtl() : null,
                    "",
                    "",
                    signed.getBody().getInputs() == null ? 0 : signed.getBody().getInputs().size(),
                    signed.getBody().getOutputs() == null ? 0 : signed.getBody().getOutputs().size());
        }

        /**
         * Submits a previously drafted transaction, recording it in the
         * pending store. A node rejection (the tx is invalid) marks it FAILED
         * and is terminal; a transport failure (node briefly unreachable)
         * throws {@link RetryableSubmitException} WITHOUT a pending record so
         * the caller can retry the already-signed draft.
         */
        public String submit(QuickAdaTxDraft draft) {
            PendingTransaction pending = PendingTransaction.fromDraft(
                    draft, unlocked.profile().id(), unlocked.profile().networkId());
            Result<String> result;
            try {
                result = transactionProcessor.submitTransaction(HexUtil.decodeHexString(draft.cborHex()));
            } catch (Exception e) {
                throw new RetryableSubmitException("Transaction submit failed: " + e.getMessage(), e);
            }
            if (!result.isSuccessful()) {
                pendingStore.save(pending.markFailed(String.valueOf(result.getResponse())));
                throw new WalletServiceException("Transaction rejected: " + result.getResponse());
            }
            pendingStore.save(pending.markPending(System.currentTimeMillis()));
            return draft.txHash();
        }
    }

    public static class WalletServiceException extends RuntimeException {
        public WalletServiceException(String message) {
            super(message);
        }

        public WalletServiceException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /** A transport-level submit failure; the signed draft may be retried as-is. */
    public static class RetryableSubmitException extends WalletServiceException {
        public RetryableSubmitException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
