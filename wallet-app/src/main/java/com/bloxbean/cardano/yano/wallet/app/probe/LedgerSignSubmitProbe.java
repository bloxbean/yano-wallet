package com.bloxbean.cardano.yano.wallet.app.probe;

import com.bloxbean.cardano.client.address.Address;
import com.bloxbean.cardano.client.api.model.Amount;
import com.bloxbean.cardano.client.api.model.Result;
import com.bloxbean.cardano.client.api.model.Utxo;
import com.bloxbean.cardano.client.crypto.cip1852.CIP1852;
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
import com.bloxbean.cardano.yano.wallet.core.hardware.DeviceAddressService;
import com.bloxbean.cardano.yano.wallet.core.hardware.DeviceKeystore;
import com.bloxbean.cardano.yano.wallet.core.hardware.HardwareDevice;
import com.bloxbean.cardano.yano.wallet.core.service.NodeStatusPort;
import com.bloxbean.cardano.yano.wallet.hardware.ledger.LedgerHardwareWalletService;
import com.bloxbean.cardano.yano.wallet.hardware.ledger.LedgerSignedTx;
import com.bloxbean.cardano.yano.wallet.hardware.ledger.LedgerTxInput;
import com.bloxbean.cardano.yano.wallet.hardware.ledger.LedgerTxOutput;
import com.bloxbean.cardano.yano.wallet.nodeclient.YanoNodeBackend;

import java.math.BigInteger;
import java.util.List;

/**
 * HW-M3 end-to-end (ADR-034): builds a real preprod self-send from the Ledger
 * account's UTXOs, signs it on the device, attaches the witness, and submits it
 * through a Yano node. The account's preprod address must be funded and the node
 * synced.
 *
 * <pre>./gradlew :wallet-app:ledgerSignSubmitProbe -PnodeUrl=http://localhost:7070/api/v1/</pre>
 */
public final class LedgerSignSubmitProbe {

    private static final BigInteger FEE = BigInteger.valueOf(250_000);

    public static void main(String[] args) throws Exception {
        String nodeUrl = args.length > 0 ? args[0] : "http://localhost:7070/api/v1/";
        WalletNetwork network = WalletNetwork.PREPROD;
        System.out.println("Node: " + nodeUrl + "  network: " + network.id());

        LedgerHardwareWalletService service = new LedgerHardwareWalletService();
        List<HardwareDevice> devices = service.enumerate();
        if (devices.isEmpty()) {
            System.out.println("No Ledger devices found. Connect + unlock and open the Cardano app.");
            return;
        }
        HardwareDevice device = devices.get(0);

        YanoNodeBackend backend = YanoNodeBackend.connect(network, nodeUrl);
        DeviceKeystore keystore = service.importAccount(device, 0);
        String address = new DeviceAddressService().receiveAddress(keystore, network, 0);
        System.out.println("Account address: " + address);

        // Pick an ADA-only UTXO at the account's receive-0 address.
        Utxo utxo = backend.utxoSupplier().getAll(address).stream()
                .filter(u -> u.getAmount().size() == 1 && "lovelace".equals(u.getAmount().get(0).getUnit()))
                .findFirst()
                .orElse(null);
        if (utxo == null) {
            System.out.println("No ADA-only UTXO found at the address. Fund it (preprod faucet) and let the node sync.");
            return;
        }
        BigInteger inputValue = utxo.getAmount().get(0).getQuantity();
        BigInteger outCoin = inputValue.subtract(FEE);
        long ttl = backend.ports().status().slot() + 7200;
        System.out.println("Spending UTXO " + utxo.getTxHash() + "#" + utxo.getOutputIndex()
                + " (" + inputValue + " lovelace); self-send " + outCoin + ", fee " + FEE + ", ttl " + ttl);

        // Host tx body + hash.
        TransactionBody body = TransactionBody.builder()
                .inputs(List.of(TransactionInput.builder()
                        .transactionId(utxo.getTxHash()).index(utxo.getOutputIndex()).build()))
                .outputs(List.of(new TransactionOutput(address, Value.fromCoin(outCoin))))
                .fee(FEE)
                .ttl(ttl)
                .build();
        String hostTxHash = TransactionUtil.getTxHash(
                Transaction.builder().body(body).witnessSet(TransactionWitnessSet.builder().build()).build());

        System.out.println("Review the amount/fee on your Ledger and approve…");
        LedgerSignedTx signed = service.signAdaPayment(device, 0, network.networkId(), network.protocolMagic(),
                List.of(new LedgerTxInput(utxo.getTxHash(), utxo.getOutputIndex())),
                List.of(new LedgerTxOutput(new Address(address).getBytes(), outCoin)),
                FEE, ttl, true, 0, null);
        if (!signed.txHashHex().equals(hostTxHash)) {
            System.out.println("ABORT: device tx hash " + signed.txHashHex() + " != host " + hostTxHash);
            return;
        }

        // Attach the device witness and submit.
        byte[] paymentPubKey = new CIP1852()
                .getPublicKeyFromAccountPubKey(HexUtil.decodeHexString(keystore.accountXpubHex()), 0, 0)
                .getKeyData();
        Transaction tx = Transaction.builder().body(body)
                .witnessSet(TransactionWitnessSet.builder()
                        .vkeyWitnesses(List.of(new VkeyWitness(paymentPubKey, signed.witnesses().get(0).signature())))
                        .build())
                .build();

        Result<String> result = backend.transactionProcessor().submitTransaction(tx.serialize());
        if (!result.isSuccessful()) {
            System.out.println("SUBMIT FAILED: " + result.getResponse());
            return;
        }
        System.out.println("SUBMITTED ✓ tx: " + hostTxHash);
        awaitConfirmation(backend.ports(), hostTxHash);
    }

    private static void awaitConfirmation(NodeStatusPort ports, String txHash) throws InterruptedException {
        System.out.println("Waiting for confirmation…");
        for (int i = 0; i < 40; i++) {
            Thread.sleep(3000);
            try {
                NodeStatusPort.TxStatusView status = ports.txStatus(txHash);
                if (status.state() == NodeStatusPort.TxState.IN_BLOCK) {
                    System.out.println("CONFIRMED ✓ in block " + status.blockHeight() + " (slot " + status.slot() + ")");
                    return;
                }
            } catch (RuntimeException ignored) {
                // transient — keep polling
            }
        }
        System.out.println("Not yet confirmed after ~2 min; check a preprod explorer for tx " + txHash);
    }

    private LedgerSignSubmitProbe() {
    }
}
