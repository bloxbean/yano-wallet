package com.bloxbean.cardano.yano.wallet.app;

import com.bloxbean.cardano.client.api.model.Result;
import com.bloxbean.cardano.client.api.model.Utxo;
import com.bloxbean.cardano.client.transaction.spec.Transaction;
import com.bloxbean.cardano.client.transaction.spec.TransactionBody;
import com.bloxbean.cardano.client.transaction.spec.TransactionOutput;
import com.bloxbean.cardano.client.util.HexUtil;
import com.bloxbean.cardano.yano.wallet.connector.Cip30Exception;
import com.bloxbean.cardano.yano.wallet.connector.Cip30Wallet;
import com.bloxbean.cardano.yano.wallet.core.service.WalletService;
import com.bloxbean.cardano.yano.wallet.core.wallet.StoredWallet;

import java.math.BigInteger;
import java.util.List;
import java.util.function.Supplier;

/**
 * The {@link Cip30Wallet} SPI backed by the unlocked session + node backend
 * (ADR-035, CIP30-M1). Reads the current session lazily, so the bridge can stay
 * up across lock/unlock and simply reports "not ready" while locked. Read methods
 * + submit are live; signing arrives in M2.
 */
final class WalletCip30Wallet implements Cip30Wallet {

    private final WalletBackendManager backendManager;
    private final Supplier<WalletService.Session> session;
    private final HardwareDappSigner hardwareDappSigner = new HardwareDappSigner();

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
            if (profile.isHardware()) {
                // Translate the dApp CBOR to the device stream; the Ledger shows the
                // tx for confirmation and the hash gate protects against mismatches.
                var conn = connection();
                return hardwareDappSigner.signTx(conn.backend(), conn.network(), profile,
                        txHex, partialSign);
            }
            return requireSession().signDappTx(txHex, partialSign);
        } catch (Cip30Exception e) {
            throw e;
        } catch (RuntimeException e) {
            throw Cip30Exception.internal(e.getMessage());
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
            Result<String> result = connection().backend().transactionProcessor()
                    .submitTransaction(HexUtil.decodeHexString(txHex));
            if (!result.isSuccessful()) {
                throw Cip30Exception.internal("Node rejected the transaction: " + result.getResponse());
            }
            String txHash = result.getValue();
            // A dApp that submits THROUGH the wallet gives us a reliable "this tx
            // is going on-chain" signal (unlike signTx, which a dApp may abandon),
            // so record it locally now — it shows in history immediately instead of
            // only after the node indexes the block. dApps that self-submit skip
            // this path and simply appear once confirmed (expected).
            recordPendingBestEffort(txHex, txHash);
            return txHash;
        } catch (Cip30Exception e) {
            throw e;
        } catch (Exception e) {
            throw Cip30Exception.internal("Failed to submit transaction: " + e.getMessage());
        }
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
