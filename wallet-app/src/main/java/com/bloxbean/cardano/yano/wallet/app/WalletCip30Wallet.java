package com.bloxbean.cardano.yano.wallet.app;

import com.bloxbean.cardano.client.api.model.Result;
import com.bloxbean.cardano.client.api.model.Utxo;
import com.bloxbean.cardano.client.transaction.spec.Transaction;
import com.bloxbean.cardano.client.transaction.spec.TransactionBody;
import com.bloxbean.cardano.client.transaction.spec.TransactionInput;
import com.bloxbean.cardano.client.transaction.spec.TransactionOutput;
import com.bloxbean.cardano.client.util.HexUtil;
import com.bloxbean.cardano.yano.wallet.connector.Cip30Exception;
import com.bloxbean.cardano.yano.wallet.connector.Cip30Wallet;
import com.bloxbean.cardano.yano.wallet.core.service.WalletService;
import com.bloxbean.cardano.yano.wallet.core.wallet.StoredWallet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigInteger;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;

/**
 * The {@link Cip30Wallet} SPI backed by the unlocked session + node backend
 * (ADR-035, CIP30-M1). Reads the current session lazily, so the bridge can stay
 * up across lock/unlock and simply reports "not ready" while locked. Read methods
 * + submit are live; signing arrives in M2.
 */
final class WalletCip30Wallet implements Cip30Wallet {

    /**
     * Every dApp request the wallet answers, and every rejection the node gives
     * back. Without this a failed dApp flow leaves nothing behind: the error goes
     * to the dApp, which usually collapses it to "failed to sign or submit", and
     * the wallet's own log shows the connection and nothing else. Diagnosing the
     * 2026-08-13 CIP-113 failure took several rounds of inference that one line of
     * this would have replaced.
     */
    private static final Logger log = LoggerFactory.getLogger(WalletCip30Wallet.class);

    /**
     * How long {@link #submitTx} will wait for a parent transaction to reach a
     * block before giving up — see {@link #chainedParentAwaitingABlock}. Sized for
     * one block plus slack (~20s on every network the wallet supports); the dApp
     * sees a slow promise, and each CIP-30 connection has its own thread, so a wait
     * here cannot hold up other calls.
     *
     * <p>REMOVE THIS WORKAROUND once bloxbean/yano#66 ships: when the node's
     * mempool resolves inputs created by other mempool transactions, chained
     * submits succeed first time and everything below becomes dead weight that
     * silently swallows 45 seconds on a genuinely missing input.
     */
    private static final Duration CHAIN_WAIT = Duration.ofSeconds(45);
    private static final Duration CHAIN_POLL = Duration.ofSeconds(2);

    private final WalletBackendManager backendManager;
    private final Supplier<WalletService.Session> session;
    private final HardwareDappSigner hardwareDappSigner = new HardwareDappSigner();
    /**
     * Transaction hashes this wallet submitted, newest last — the evidence that a
     * missing input is one we are about to create rather than one that never
     * existed. Bounded because a long-lived session must not accumulate them.
     */
    final Map<String, Boolean> submitted =
            java.util.Collections.synchronizedMap(new LinkedHashMap<>(16, 0.75f, false) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
                    return size() > 64;
                }
            });

    WalletCip30Wallet(WalletBackendManager backendManager, Supplier<WalletService.Session> session) {
        this.backendManager = backendManager;
        this.session = session;
    }

    @Override
    public boolean isReady() {
        return session.get() != null && backendManager.active() != null;
    }

    @Override
    public int networkId() {
        return connection().network().networkId();
    }

    @Override
    public List<String> usedAddresses() {
        return List.of(profile().baseAddress());
    }

    @Override
    public List<String> unusedAddresses() {
        return List.of();
    }

    @Override
    public String changeAddress() {
        return profile().baseAddress();
    }

    @Override
    public List<String> rewardAddresses() {
        return List.of(profile().stakeAddress());
    }

    @Override
    public List<Utxo> utxos() {
        return connection().backend().utxoSupplier().getAll(profile().baseAddress());
    }

    @Override
    public String signTx(String txHex, boolean partialSign) {
        try {
            StoredWallet profile = profile();
            log.info("CIP-30 signTx: {} bytes, partialSign={}, tx {}",
                    txHex.length() / 2, partialSign, txHashOrUnknown(txHex));
            if (profile.isHardware()) {
                // Translate the dApp CBOR to the device stream; the Ledger shows the
                // tx for confirmation and the hash gate protects against mismatches.
                var conn = connection();
                return hardwareDappSigner.signTx(conn.backend(), conn.network(), profile,
                        txHex, partialSign);
            }
            String witnessSet = requireSession().signDappTx(txHex, partialSign);
            log.info("CIP-30 signTx: returning {} bytes of witnesses for tx {}",
                    witnessSet.length() / 2, txHashOrUnknown(txHex));
            return witnessSet;
        } catch (Cip30Exception e) {
            log.warn("CIP-30 signTx refused for tx {}: {}", txHashOrUnknown(txHex), e.getMessage());
            throw e;
        } catch (RuntimeException e) {
            log.warn("CIP-30 signTx failed for tx {}: {}", txHashOrUnknown(txHex), e.toString());
            throw Cip30Exception.internal(e.getMessage());
        }
    }

    /** For log lines only — never let a logging concern break a dApp call. */
    private static String txHashOrUnknown(String txHex) {
        try {
            return com.bloxbean.cardano.client.transaction.util.TransactionUtil
                    .getTxHash(HexUtil.decodeHexString(txHex));
        } catch (Exception e) {
            return "<unreadable>";
        }
    }

    @Override
    public DataSignature signData(String signerAddress, String payloadHex) {
        try {
            var sig = requireSession().signDappData(
                    HexUtil.decodeHexString(signerAddress), HexUtil.decodeHexString(payloadHex));
            return new DataSignature(sig.signature(), sig.key());
        } catch (Cip30Exception e) {
            throw e;
        } catch (RuntimeException e) {
            throw Cip30Exception.internal(e.getMessage());
        }
    }

    private WalletService.Session requireSession() {
        WalletService.Session s = session.get();
        if (s == null) {
            throw Cip30Exception.internal("Wallet is locked.");
        }
        return s;
    }

    @Override
    public String submitTx(String txHex) {
        try {
            return submitOnce(txHex);
        } catch (Cip30Exception first) {
            TransactionInput parent = chainedParentAwaitingABlock(txHex, first);
            if (parent == null) {
                log.warn("CIP-30 submitTx failed for tx {} (not a chained-parent wait): {}",
                        txHashOrUnknown(txHex), first.getMessage());
                throw first;
            }
            log.info("CIP-30 submitTx: waiting up to {}s for parent {}#{} to reach a block",
                    CHAIN_WAIT.toSeconds(), parent.getTransactionId(), parent.getIndex());
            if (!awaitOnChain(parent)) {
                log.warn("CIP-30 submitTx: parent {}#{} did not land in {}s; returning the original error: {}",
                        parent.getTransactionId(), parent.getIndex(), CHAIN_WAIT.toSeconds(),
                        first.getMessage());
                throw first;
            }
            log.info("CIP-30 submitTx: parent landed, resubmitting tx {}", txHashOrUnknown(txHex));
            // The parent is on chain now, so the input the node could not see
            // exists. Resubmitting the same bytes is idempotent — same
            // transaction, same hash — so the worst case is the identical error.
            return submitOnce(txHex);
        } catch (Exception e) {
            throw Cip30Exception.internal("Failed to submit transaction: " + e.getMessage());
        }
    }

    private String submitOnce(String txHex) {
        Result<String> result;
        try {
            result = connection().backend().transactionProcessor()
                    .submitTransaction(HexUtil.decodeHexString(txHex));
        } catch (Exception e) {
            throw Cip30Exception.internal("Failed to submit transaction: " + e.getMessage());
        }
        if (!result.isSuccessful()) {
            // The node's own words, in full. This is the line that says WHY, and
            // the dApp almost never shows it to the user.
            log.warn("CIP-30 submitTx: node rejected tx {} (code {}): {}",
                    txHashOrUnknown(txHex), result.code(), result.getResponse());
            throw Cip30Exception.internal("Node rejected the transaction: " + result.getResponse());
        }
        log.info("CIP-30 submitTx: node accepted tx {}", result.getValue());
        String txHash = result.getValue();
        // A dApp that submits THROUGH the wallet gives us a reliable "this tx
        // is going on-chain" signal (unlike signTx, which a dApp may abandon),
        // so record it locally now — it shows in history immediately instead of
        // only after the node indexes the block. dApps that self-submit skip
        // this path and simply appear once confirmed (expected).
        recordPendingBestEffort(txHex, txHash);
        submitted.put(txHash, Boolean.TRUE);
        return txHash;
    }

    /**
     * The input this transaction is waiting on, when the submit failed only
     * because a parent transaction has not reached a block yet — or {@code null}
     * when that is not what happened.
     *
     * <p>Yano's mempool admission resolves inputs against the persisted UTxO set
     * alone, so a transaction spending an output of another transaction still in
     * the mempool is refused with {@code UtxoNotFound} for the ~20s until the
     * parent is included (bloxbean/yano#66). cardano-node accepts these, so a dApp
     * that submits a chained pair back to back works everywhere except here. That
     * is what broke a CIP-113 token registration on 2026-08-13: the setup
     * transaction landed and the one spending its output was rejected.
     *
     * <p>Deliberately narrow. Both conditions must hold — the node said it could
     * not find a UTxO, <em>and</em> this transaction spends an output of something
     * this wallet itself submitted moments ago. Any other rejection, including a
     * genuinely missing input, returns immediately as before.
     */
    // Package-private: the decision of WHEN to retry is the part worth pinning;
    // the polling around it is mechanical.
    TransactionInput chainedParentAwaitingABlock(String txHex, Cip30Exception failure) {
        String message = failure.getMessage() == null ? "" : failure.getMessage().toLowerCase(Locale.ROOT);
        if (!message.contains("utxonotfound") && !message.contains("not found")) {
            return null;
        }
        try {
            TransactionBody body = Transaction.deserialize(HexUtil.decodeHexString(txHex)).getBody();
            List<TransactionInput> candidates = new ArrayList<>();
            if (body.getInputs() != null) {
                candidates.addAll(body.getInputs());
            }
            if (body.getCollateral() != null) {
                candidates.addAll(body.getCollateral());
            }
            if (body.getReferenceInputs() != null) {
                candidates.addAll(body.getReferenceInputs());
            }
            for (TransactionInput input : candidates) {
                if (submitted.containsKey(input.getTransactionId())) {
                    return input;
                }
            }
        } catch (Exception e) {
            return null; // unreadable CBOR is not a chaining problem
        }
        return null;
    }

    /** Polls until the outpoint resolves, or the budget runs out. */
    private boolean awaitOnChain(TransactionInput input) {
        long deadline = System.nanoTime() + CHAIN_WAIT.toNanos();
        while (System.nanoTime() < deadline) {
            try {
                if (connection().backend().nodeClient()
                        .getUtxo(input.getTransactionId(), input.getIndex()) != null) {
                    return true;
                }
            } catch (RuntimeException e) {
                // Could not ask — try again until the budget is spent.
            }
            try {
                Thread.sleep(CHAIN_POLL.toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    /**
     * Records a just-submitted dApp tx in the local pending store (net coin sent
     * to non-wallet outputs + fee, decoded from the signed CBOR) and starts the
     * shared confirmation tracker. Strictly best-effort: the tx is already on the
     * node, so a bookkeeping failure must never surface to the dApp.
     */
    private void recordPendingBestEffort(String txHex, String txHash) {
        try {
            StoredWallet profile = profile();
            Transaction tx = Transaction.deserialize(HexUtil.decodeHexString(txHex));
            TransactionBody body = tx.getBody();
            BigInteger fee = body.getFee() != null ? body.getFee() : BigInteger.ZERO;
            Long ttl = body.getTtl() > 0 ? body.getTtl() : null;

            String myAddress = profile.baseAddress();
            BigInteger sentToOthers = BigInteger.ZERO;
            String counterparty = null;
            if (body.getOutputs() != null) {
                for (TransactionOutput out : body.getOutputs()) {
                    if (myAddress.equals(out.getAddress())) {
                        continue; // change back to us — not "sent"
                    }
                    if (out.getValue() != null && out.getValue().getCoin() != null) {
                        sentToOthers = sentToOthers.add(out.getValue().getCoin());
                    }
                    if (counterparty == null) {
                        counterparty = out.getAddress();
                    }
                }
            }

            WalletService service = connection().service();
            service.recordSubmittedPayment(profile, txHash, sentToOthers, fee, counterparty, ttl);
            service.trackConfirmation(txHash, 120);
        } catch (Exception e) {
            // Includes CborDeserializationException — the tx is already on-chain,
            // so a decode/bookkeeping failure must never surface to the dApp.
            System.err.println("CIP-30: could not record pending dApp tx " + txHash + ": " + e.getMessage());
        }
    }

    private StoredWallet profile() {
        WalletService.Session s = session.get();
        if (s == null) {
            throw Cip30Exception.internal("Wallet is locked.");
        }
        return s.profile();
    }

    private WalletBackendManager.ActiveConnection connection() {
        WalletBackendManager.ActiveConnection conn = backendManager.active();
        if (conn == null) {
            throw Cip30Exception.internal("Not connected to a node.");
        }
        return conn;
    }
}
