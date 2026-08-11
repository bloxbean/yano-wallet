package com.bloxbean.cardano.yano.wallet.app;

import com.bloxbean.cardano.client.api.model.Utxo;
import com.bloxbean.cardano.client.common.cbor.CborSerializationUtil;
import com.bloxbean.cardano.client.crypto.cip1852.CIP1852;
import com.bloxbean.cardano.client.transaction.spec.TransactionWitnessSet;
import com.bloxbean.cardano.client.transaction.util.TransactionUtil;
import com.bloxbean.cardano.client.util.HexUtil;
import com.bloxbean.cardano.yano.wallet.core.config.WalletNetwork;
import com.bloxbean.cardano.yano.wallet.core.hardware.HardwareDevice;
import com.bloxbean.cardano.yano.wallet.core.wallet.StoredWallet;
import com.bloxbean.cardano.yano.wallet.hardware.ledger.LedgerBip32;
import com.bloxbean.cardano.yano.wallet.hardware.ledger.LedgerHardwareWalletService;
import com.bloxbean.cardano.yano.wallet.hardware.ledger.LedgerSignRequest;
import com.bloxbean.cardano.yano.wallet.hardware.ledger.LedgerSignedTx;
import com.bloxbean.cardano.yano.wallet.hardware.ledger.LedgerTxTranslator;
import com.bloxbean.cardano.yano.wallet.nodeclient.YanoNodeBackend;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * CIP-30 {@code signTx} for hardware wallets (ADR-035 M4c): translate the dApp's
 * transaction CBOR into the device's structured signing stream, sign on-device
 * (the user confirms on the Ledger's screen), and return only the wallet's
 * witnesses as witness-set CBOR.
 *
 * <p>The correctness gate: the device recomputes the tx hash from the translated
 * stream, and we compare it against the hash of the dApp's <em>original</em> body
 * bytes. Any translation slip means the hashes differ and we abort — a wrong
 * signature can never leave this method.
 */
final class HardwareDappSigner {

    private final LedgerHardwareWalletService hardware = new LedgerHardwareWalletService();

    String signTx(YanoNodeBackend backend, WalletNetwork network, StoredWallet profile,
                  String txHex, boolean partialSign) {
        byte[] txCbor = HexUtil.decodeHexString(txHex);
        byte[] accountXpub = HexUtil.decodeHexString(profile.accountXpubHex());
        byte[] paymentKeyHash = new CIP1852().getPublicKeyFromAccountPubKey(accountXpub, 0, 0).getKeyHash();

        // Which inputs are ours: the wallet's live UTxO set keyed txHash#index.
        Set<String> ownedInputs = new HashSet<>();
        for (Utxo utxo : backend.utxoSupplier().getAll(profile.baseAddress())) {
            ownedInputs.add(utxo.getTxHash() + "#" + utxo.getOutputIndex());
        }

        LedgerSignRequest request = LedgerTxTranslator.translate(txCbor,
                new LedgerTxTranslator.Context(network.networkId(), network.protocolMagic(),
                        ownedInputs, paymentKeyHash,
                        LedgerBip32.paymentPath(profile.accountIndex(), 0, 0)));

        List<HardwareDevice> devices = hardware.enumerate();
        if (devices.isEmpty()) {
            throw new IllegalStateException("Connect and unlock your Ledger, open the Cardano app, and try again.");
        }
        LedgerSignedTx signed = hardware.signTransaction(devices.get(0), request);

        // Hash gate against the ORIGINAL body bytes — not a re-encoding.
        String expectedHash = TransactionUtil.getTxHash(txCbor);
        if (!signed.txHashHex().equals(expectedHash)) {
            throw new IllegalStateException("The device computed a different transaction than this dApp sent"
                    + " — refusing to sign. (This protects you from encoding mismatches.)");
        }

        TransactionWitnessSet witnessSet =
                HardwareSigning.witnessSet(profile.accountXpubHex(), signed.witnesses());
        try {
            return HexUtil.encodeHexString(CborSerializationUtil.serialize(witnessSet.serialize()));
        } catch (Exception e) {
            throw new IllegalStateException("Failed to encode witness set: " + e.getMessage(), e);
        }
    }
}
