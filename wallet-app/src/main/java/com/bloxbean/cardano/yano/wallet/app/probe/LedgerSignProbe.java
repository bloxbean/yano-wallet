package com.bloxbean.cardano.yano.wallet.app.probe;

import com.bloxbean.cardano.client.address.Address;
import com.bloxbean.cardano.client.transaction.spec.Transaction;
import com.bloxbean.cardano.client.transaction.spec.TransactionBody;
import com.bloxbean.cardano.client.transaction.spec.TransactionInput;
import com.bloxbean.cardano.client.transaction.spec.TransactionOutput;
import com.bloxbean.cardano.client.transaction.spec.TransactionWitnessSet;
import com.bloxbean.cardano.client.transaction.spec.Value;
import com.bloxbean.cardano.client.transaction.util.TransactionUtil;
import com.bloxbean.cardano.client.util.HexUtil;
import com.bloxbean.cardano.yano.wallet.core.config.WalletNetwork;
import com.bloxbean.cardano.yano.wallet.core.hardware.DeviceAddressService;
import com.bloxbean.cardano.yano.wallet.core.hardware.DeviceKeystore;
import com.bloxbean.cardano.yano.wallet.core.hardware.HardwareDevice;
import com.bloxbean.cardano.yano.wallet.hardware.ledger.LedgerHardwareWalletService;
import com.bloxbean.cardano.yano.wallet.hardware.ledger.LedgerSignedTx;
import com.bloxbean.cardano.yano.wallet.hardware.ledger.LedgerTxInput;
import com.bloxbean.cardano.yano.wallet.hardware.ledger.LedgerTxOutput;

import java.math.BigInteger;
import java.util.List;

/**
 * HW-M3 signing validation harness (ADR-034). Builds a small self-send ADA
 * payment for the device's own account, computes the host tx hash with CCL,
 * streams the tx to the device for approval, and asserts the device-computed tx
 * hash equals the host's. That equality proves the Ledger signTx serialization
 * matches the host's canonical CBOR — so the returned witness is valid. No funds
 * or node needed: the device signs offline and the UTXO need not exist.
 *
 * <pre>./gradlew :wallet-app:ledgerSignProbe</pre>
 */
public final class LedgerSignProbe {

    // A dummy input (UTXO existence is irrelevant to signing / the tx hash).
    private static final String DUMMY_INPUT_TX =
            "3b40265111d8bb3c3c608d95b3a0bf83461ace32d79336579a1939b3aad1c0b7";

    public static void main(String[] args) {
        LedgerHardwareWalletService service = new LedgerHardwareWalletService();
        List<HardwareDevice> devices = service.enumerate();
        if (devices.isEmpty()) {
            System.out.println("No Ledger devices found. Connect + unlock and open the Cardano app.");
            return;
        }
        HardwareDevice device = devices.get(0);
        WalletNetwork network = WalletNetwork.PREPROD;

        DeviceKeystore keystore = service.importAccount(device, 0);
        String ownAddress = new DeviceAddressService().receiveAddress(keystore, network, 0);

        BigInteger coin = BigInteger.valueOf(1_000_000);
        BigInteger fee = BigInteger.valueOf(170_000);
        long ttl = 1_000_000L;

        // Host side: build the identical tx body with CCL and hash it.
        TransactionBody body = TransactionBody.builder()
                .inputs(List.of(TransactionInput.builder().transactionId(DUMMY_INPUT_TX).index(0).build()))
                .outputs(List.of(new TransactionOutput(ownAddress, Value.fromCoin(coin))))
                .fee(fee)
                .ttl(ttl)
                .build();
        Transaction tx = Transaction.builder().body(body)
                .witnessSet(TransactionWitnessSet.builder().build()).build();
        String hostTxHash = TransactionUtil.getTxHash(tx);

        System.out.println("Streaming a self-send tx to the device — review the amount/fee and approve on screen…");
        LedgerSignedTx signed = service.signAdaPayment(device, 0,
                network.networkId(), network.protocolMagic(),
                List.of(new LedgerTxInput(DUMMY_INPUT_TX, 0)),
                List.of(new LedgerTxOutput(new Address(ownAddress).getBytes(), coin)),
                fee, ttl, /* tagCborSets */ true, /* outputFormat=legacy array */ 0, /* auxDataHash */ null);

        System.out.println("  host   tx hash: " + hostTxHash);
        System.out.println("  device tx hash: " + signed.txHashHex());
        boolean match = hostTxHash.equals(signed.txHashHex());
        System.out.println("  result: " + (match
                ? "TX HASH MATCH ✓ — serialization correct, witness is valid"
                : "MISMATCH ✗ — streamed structure differs from host CBOR (adjust set-tags / output format)"));
        System.out.println("  witness: " + HexUtil.encodeHexString(signed.witnesses().get(0).signature()));
    }

    private LedgerSignProbe() {
    }
}
