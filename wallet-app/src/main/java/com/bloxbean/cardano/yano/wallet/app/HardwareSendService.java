package com.bloxbean.cardano.yano.wallet.app;

import com.bloxbean.cardano.client.address.Address;
import com.bloxbean.cardano.client.api.model.Amount;
import com.bloxbean.cardano.client.api.model.Result;
import com.bloxbean.cardano.client.api.model.Utxo;
import com.bloxbean.cardano.client.cip.cip20.MessageMetadata;
import com.bloxbean.cardano.client.crypto.cip1852.CIP1852;
import com.bloxbean.cardano.client.transaction.spec.Asset;
import com.bloxbean.cardano.client.transaction.spec.AuxiliaryData;
import com.bloxbean.cardano.client.transaction.spec.MultiAsset;
import com.bloxbean.cardano.client.transaction.spec.Transaction;
import com.bloxbean.cardano.client.transaction.spec.TransactionBody;
import com.bloxbean.cardano.client.transaction.spec.TransactionInput;
import com.bloxbean.cardano.client.transaction.spec.TransactionOutput;
import com.bloxbean.cardano.client.transaction.spec.TransactionWitnessSet;
import com.bloxbean.cardano.client.transaction.spec.Value;
import com.bloxbean.cardano.client.transaction.spec.VkeyWitness;
import com.bloxbean.cardano.client.transaction.util.TransactionUtil;
import com.bloxbean.cardano.client.util.HexUtil;
import com.bloxbean.cardano.yano.wallet.core.config.WalletNetwork;
import com.bloxbean.cardano.yano.wallet.core.hardware.HardwareDevice;
import com.bloxbean.cardano.yano.wallet.core.wallet.StoredWallet;
import com.bloxbean.cardano.yano.wallet.hardware.ledger.LedgerAssetGroup;
import com.bloxbean.cardano.yano.wallet.hardware.ledger.LedgerHardwareWalletService;
import com.bloxbean.cardano.yano.wallet.hardware.ledger.LedgerSignedTx;
import com.bloxbean.cardano.yano.wallet.hardware.ledger.LedgerToken;
import com.bloxbean.cardano.yano.wallet.hardware.ledger.LedgerTxInput;
import com.bloxbean.cardano.yano.wallet.hardware.ledger.LedgerTxOutput;
import com.bloxbean.cardano.yano.wallet.nodeclient.YanoNodeBackend;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Device-signed ADA payments for a watch-only hardware wallet (ADR-034). Builds
 * an unsigned transaction from the account's UTXOs, signs it on the device
 * (reusing the verified {@code signAdaPayment} path), and submits it through the
 * node. First cut: ADA only, inputs from the account's receive-0 address (one
 * signing key → one witness); native assets and certificates come later.
 */
final class HardwareSendService {

    /** Conservative flat fee — safely above the min fee for a small tx. */
    private static final BigInteger FEE = BigInteger.valueOf(200_000);
    /** Below this, change is dust; fold it into the fee rather than emit an output. */
    private static final BigInteger MIN_CHANGE = BigInteger.valueOf(1_000_000);

    private final LedgerHardwareWalletService hardware = new LedgerHardwareWalletService();

    /** An unsigned hardware payment, held between draft and device-confirm. */
    record Draft(String txHash, String toAddress, BigInteger amount, BigInteger fee,
                 List<LedgerTxInput> inputs, List<LedgerTxOutput> outputs, long ttl,
                 TransactionBody body, StoredWallet profile,
                 AuxiliaryData auxiliaryData, byte[] auxiliaryDataHash) {
    }

    Draft buildPayment(YanoNodeBackend backend, WalletNetwork network,
                       StoredWallet profile, String toAddress, BigInteger lovelace, String memo) {
        List<Utxo> available = backend.utxoSupplier().getAll(profile.baseAddress()).stream()
                .filter(u -> u.getAmount().size() == 1 && "lovelace".equals(u.getAmount().get(0).getUnit()))
                .sorted(Comparator.comparing((Utxo u) -> u.getAmount().get(0).getQuantity()).reversed())
                .toList();

        List<Utxo> selected = new ArrayList<>();
        BigInteger total = BigInteger.ZERO;
        BigInteger target = lovelace.add(FEE);
        for (Utxo u : available) {
            selected.add(u);
            total = total.add(u.getAmount().get(0).getQuantity());
            if (total.compareTo(target) >= 0) {
                break;
            }
        }
        if (total.compareTo(target) < 0) {
            throw new IllegalStateException("Not enough funds (need "
                    + target + " lovelace, have " + total + ")");
        }

        BigInteger change = total.subtract(lovelace).subtract(FEE);
        BigInteger fee = FEE;
        List<TransactionOutput> outputs = new ArrayList<>();
        List<LedgerTxOutput> ledgerOutputs = new ArrayList<>();
        outputs.add(new TransactionOutput(toAddress, Value.fromCoin(lovelace)));
        ledgerOutputs.add(new LedgerTxOutput(new Address(toAddress).getBytes(), lovelace));
        if (change.compareTo(MIN_CHANGE) >= 0) {
            outputs.add(new TransactionOutput(profile.baseAddress(), Value.fromCoin(change)));
            ledgerOutputs.add(new LedgerTxOutput(new Address(profile.baseAddress()).getBytes(), change));
        } else {
            fee = fee.add(change); // dust change → absorb into the fee (no dust output)
        }

        List<TransactionInput> inputs = new ArrayList<>();
        List<LedgerTxInput> ledgerInputs = new ArrayList<>();
        for (Utxo u : selected) {
            inputs.add(TransactionInput.builder().transactionId(u.getTxHash()).index(u.getOutputIndex()).build());
            ledgerInputs.add(new LedgerTxInput(u.getTxHash(), u.getOutputIndex()));
        }

        // CIP-20 memo → auxiliary data. The device is sent only the hash (it folds
        // it into the body it hashes); the full metadata is attached at submit.
        AuxiliaryData auxiliaryData = null;
        byte[] auxiliaryDataHash = null;
        if (memo != null && !memo.isBlank()) {
            auxiliaryData = AuxiliaryData.builder()
                    .metadata(MessageMetadata.create().add(memo.trim()))
                    .build();
            try {
                auxiliaryDataHash = auxiliaryData.getAuxiliaryDataHash();
            } catch (Exception e) {
                throw new IllegalStateException("Unable to build memo metadata: " + e.getMessage(), e);
            }
        }

        long ttl = backend.ports().status().slot() + 7200;
        TransactionBody.TransactionBodyBuilder bodyBuilder = TransactionBody.builder()
                .inputs(inputs).outputs(outputs).fee(fee).ttl(ttl);
        if (auxiliaryDataHash != null) {
            bodyBuilder.auxiliaryDataHash(auxiliaryDataHash);
        }
        TransactionBody body = bodyBuilder.build();
        String txHash = TransactionUtil.getTxHash(Transaction.builder().body(body)
                .witnessSet(TransactionWitnessSet.builder().build()).build());

        return new Draft(txHash, toAddress, lovelace, fee, ledgerInputs, ledgerOutputs, ttl, body, profile,
                auxiliaryData, auxiliaryDataHash);
    }

    /**
     * Builds a native-asset send (first cut): the recipient gets min-ADA + the
     * token, change returns the leftover. Scoped to a single-asset UTXO (ADA +
     * this token) so there is no multi-policy ordering to reconcile.
     */
    Draft buildTokenPayment(YanoNodeBackend backend, WalletNetwork network, StoredWallet profile,
                            String toAddress, String unit, BigInteger sendAmount, String memo) {
        String policyHex = unit.substring(0, 56);
        String nameHex = unit.length() > 56 ? unit.substring(56) : "";
        byte[] policyId = HexUtil.decodeHexString(policyHex);
        byte[] assetName = nameHex.isEmpty() ? new byte[0] : HexUtil.decodeHexString(nameHex);
        String cclName = "0x" + nameHex;
        BigInteger recipientAda = BigInteger.valueOf(1_500_000);

        Utxo utxo = backend.utxoSupplier().getAll(profile.baseAddress()).stream()
                .filter(u -> u.getAmount().stream()
                        .allMatch(a -> "lovelace".equals(a.getUnit()) || unit.equals(a.getUnit())))
                .filter(u -> u.getAmount().stream()
                        .anyMatch(a -> unit.equals(a.getUnit()) && a.getQuantity().compareTo(sendAmount) >= 0))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "No suitable UTXO holding this token (ADA + single token) at your address."));
        BigInteger utxoAda = amountOf(utxo, "lovelace");
        BigInteger utxoToken = amountOf(utxo, unit);
        BigInteger changeAda = utxoAda.subtract(recipientAda).subtract(FEE);
        if (changeAda.signum() < 0) {
            throw new IllegalStateException("The token UTXO's ADA is too low to cover min-ADA + fee.");
        }
        BigInteger leftover = utxoToken.subtract(sendAmount);
        if (leftover.signum() > 0 && changeAda.compareTo(BigInteger.valueOf(1_000_000)) < 0) {
            throw new IllegalStateException("Not enough ADA left for the token change output.");
        }

        List<TransactionOutput> outputs = new ArrayList<>();
        List<LedgerTxOutput> ledgerOutputs = new ArrayList<>();
        outputs.add(new TransactionOutput(toAddress,
                new Value(recipientAda, List.of(new MultiAsset(policyHex, List.of(new Asset(cclName, sendAmount)))))));
        ledgerOutputs.add(new LedgerTxOutput(new Address(toAddress).getBytes(), recipientAda,
                List.of(new LedgerAssetGroup(policyId, List.of(new LedgerToken(assetName, sendAmount))))));
        if (leftover.signum() > 0) {
            outputs.add(new TransactionOutput(profile.baseAddress(),
                    new Value(changeAda, List.of(new MultiAsset(policyHex, List.of(new Asset(cclName, leftover)))))));
            ledgerOutputs.add(new LedgerTxOutput(new Address(profile.baseAddress()).getBytes(), changeAda,
                    List.of(new LedgerAssetGroup(policyId, List.of(new LedgerToken(assetName, leftover))))));
        } else {
            outputs.add(new TransactionOutput(profile.baseAddress(), Value.builder().coin(changeAda).build()));
            ledgerOutputs.add(new LedgerTxOutput(new Address(profile.baseAddress()).getBytes(), changeAda));
        }

        AuxiliaryData auxiliaryData = null;
        byte[] auxiliaryDataHash = null;
        if (memo != null && !memo.isBlank()) {
            auxiliaryData = AuxiliaryData.builder().metadata(MessageMetadata.create().add(memo.trim())).build();
            try {
                auxiliaryDataHash = auxiliaryData.getAuxiliaryDataHash();
            } catch (Exception e) {
                throw new IllegalStateException("Unable to build memo metadata: " + e.getMessage(), e);
            }
        }

        List<TransactionInput> inputs = List.of(
                TransactionInput.builder().transactionId(utxo.getTxHash()).index(utxo.getOutputIndex()).build());
        List<LedgerTxInput> ledgerInputs = List.of(new LedgerTxInput(utxo.getTxHash(), utxo.getOutputIndex()));

        long ttl = backend.ports().status().slot() + 7200;
        TransactionBody.TransactionBodyBuilder bodyBuilder = TransactionBody.builder()
                .inputs(inputs).outputs(outputs).fee(FEE).ttl(ttl);
        if (auxiliaryDataHash != null) {
            bodyBuilder.auxiliaryDataHash(auxiliaryDataHash);
        }
        TransactionBody body = bodyBuilder.build();
        String txHash = TransactionUtil.getTxHash(Transaction.builder().body(body)
                .witnessSet(TransactionWitnessSet.builder().build()).build());
        return new Draft(txHash, toAddress, sendAmount, FEE, ledgerInputs, ledgerOutputs, ttl, body, profile,
                auxiliaryData, auxiliaryDataHash);
    }

    private static BigInteger amountOf(Utxo utxo, String unit) {
        return utxo.getAmount().stream()
                .filter(a -> unit.equals(a.getUnit()))
                .map(Amount::getQuantity)
                .findFirst()
                .orElse(BigInteger.ZERO);
    }

    /** Signs the draft on the device and submits it; returns the tx hash. */
    String signAndSubmit(YanoNodeBackend backend, WalletNetwork network, Draft draft) {
        List<HardwareDevice> devices = hardware.enumerate();
        if (devices.isEmpty()) {
            throw new IllegalStateException("Connect and unlock your Ledger, open the Cardano app, and try again.");
        }
        HardwareDevice device = devices.get(0);
        int accountIndex = draft.profile().accountIndex();

        LedgerSignedTx signed = hardware.signAdaPayment(device, accountIndex,
                network.networkId(), network.protocolMagic(),
                draft.inputs(), draft.outputs(), draft.fee(), draft.ttl(),
                /* tagCborSets */ true, /* outputFormat legacy array */ 0, draft.auxiliaryDataHash());
        if (!signed.txHashHex().equals(draft.txHash())) {
            throw new IllegalStateException("Device produced a different transaction — not submitting.");
        }

        Transaction.TransactionBuilder txBuilder = Transaction.builder().body(draft.body())
                .witnessSet(HardwareSigning.witnessSet(draft.profile().accountXpubHex(), signed.witnesses()));
        if (draft.auxiliaryData() != null) {
            txBuilder.auxiliaryData(draft.auxiliaryData());
        }
        Transaction tx = txBuilder.build();
        try {
            Result<String> result = backend.transactionProcessor().submitTransaction(tx.serialize());
            if (!result.isSuccessful()) {
                throw new IllegalStateException("Node rejected the transaction: " + result.getResponse());
            }
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to submit: " + e.getMessage(), e);
        }
        return draft.txHash();
    }
}
