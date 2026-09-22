package com.bloxbean.cardano.yano.wallet.core.tx;

import com.bloxbean.cardano.client.api.TransactionProcessor;
import com.bloxbean.cardano.client.api.UtxoSupplier;
import com.bloxbean.cardano.client.address.Address;
import com.bloxbean.cardano.client.api.common.OrderEnum;
import com.bloxbean.cardano.client.api.model.Amount;
import com.bloxbean.cardano.client.api.model.EvaluationResult;
import com.bloxbean.cardano.client.api.model.ProtocolParams;
import com.bloxbean.cardano.client.api.model.Result;
import com.bloxbean.cardano.client.api.model.Utxo;
import com.bloxbean.cardano.client.common.model.Networks;
import com.bloxbean.cardano.client.transaction.spec.Transaction;
import com.bloxbean.cardano.client.util.HexUtil;
import com.bloxbean.cardano.hdwallet.Wallet;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class QuickAdaTxServiceTest {
    private static final String MNEMONIC =
            "drive useless envelope shine range ability time copper alarm museum near flee wrist "
                + "live type device meadow allow churn purity wisdom praise drop code";

    @Test
    void sendsChangeBackToSenderBaseAddressByDefault() throws Exception {
        Wallet sender = Wallet.createFromMnemonic(Networks.preprod(), MNEMONIC);
        String senderAddress = sender.getBaseAddressString(0);
        String receiverAddress = Wallet.create(Networks.preprod()).getBaseAddressString(0);
        UtxoSupplier utxoSupplier = singleUtxoSupplier(senderAddress);

        QuickAdaTxDraft draft = new QuickAdaTxService().buildSignedDraft(
                sender,
                utxoSupplier,
                this::protocolParams,
                new NoopTransactionProcessor(),
                receiverAddress,
                BigInteger.valueOf(1_000_000));

        Transaction transaction = Transaction.deserialize(HexUtil.decodeHexString(draft.cborHex()));

        assertThat(draft.fromAddress()).isEqualTo(senderAddress);
        assertThat(transaction.getBody().getOutputs())
                .anySatisfy(output -> assertThat(output.getAddress()).isEqualTo(senderAddress));
    }

    @Test
    void spendsInternalUtxoOfNonzeroAccountWithTheCorrectWitness() throws Exception {
        Wallet sender = Wallet.createFromMnemonic(Networks.mainnet(), MNEMONIC, 2);
        var internal = com.bloxbean.cardano.yano.wallet.core.wallet.WalletAddresses.account(sender, 1, 7);
        String receiver = Wallet.create(Networks.mainnet()).getBaseAddressString(0);
        QuickAdaTxDraft draft = new QuickAdaTxService().buildSignedDraft(sender,
                singleUtxoSupplier(internal.baseAddress()), this::protocolParams,
                new NoopTransactionProcessor(), receiver, BigInteger.valueOf(1_000_000));
        Transaction tx = Transaction.deserialize(HexUtil.decodeHexString(draft.cborHex()));
        assertThat(tx.getBody().getInputs()).hasSize(1);
        assertThat(tx.getBody().getInputs().getFirst().getTransactionId())
                .isEqualTo("7e1eecf7439fb5119a6762985a61c9fb3ca8158d9fc38361f0c4746430d5e0c7");
        assertThat(tx.getWitnessSet().getVkeyWitnesses()).hasSize(1);
        assertThat(tx.getWitnessSet().getVkeyWitnesses().getFirst().getVkey())
                .containsExactly(internal.publicKeyBytes());
        var witness = tx.getWitnessSet().getVkeyWitnesses().getFirst();
        assertThat(com.bloxbean.cardano.client.crypto.config.CryptoConfiguration.INSTANCE.getSigningProvider()
                .verify(witness.getSignature(), HexUtil.decodeHexString(draft.txHash()), witness.getVkey())).isTrue();
        assertThat(tx.getBody().getOutputs()).anySatisfy(output ->
                assertThat(output.getAddress()).isEqualTo(sender.getBaseAddressString(0)));
    }

    @Test
    void signsBothRolesWhenInputsShareTheSameDerivationIndex() throws Exception {
        Wallet sender = Wallet.createFromMnemonic(Networks.mainnet(), MNEMONIC, 1);
        var external = sender.getAccountAtIndex(7);
        var internal = com.bloxbean.cardano.yano.wallet.core.wallet.WalletAddresses.account(sender, 1, 7);
        Set<String> funded = Set.of(external.baseAddress(), internal.baseAddress());
        UtxoSupplier supplier = new UtxoSupplier() {
            @Override
            public List<Utxo> getPage(String address, Integer count, Integer page, OrderEnum order) {
                if (page != 0 || !funded.contains(address)) return List.of();
                return List.of(Utxo.builder().address(address)
                        .txHash((address.equals(external.baseAddress()) ? "1" : "2").repeat(64))
                        .outputIndex(0).amount(List.of(Amount.lovelace(BigInteger.valueOf(5_000_000))))
                        .build());
            }

            @Override
            public Optional<Utxo> getTxOutput(String hash, int index) { return Optional.empty(); }

            @Override
            public boolean isUsedAddress(Address address) { return funded.contains(address.toBech32()); }
        };
        QuickAdaTxDraft draft = new QuickAdaTxService().buildSignedDraft(sender, supplier,
                this::protocolParams, new NoopTransactionProcessor(),
                Wallet.create(Networks.mainnet()).getBaseAddressString(0), BigInteger.valueOf(7_000_000));
        Transaction tx = Transaction.deserialize(HexUtil.decodeHexString(draft.cborHex()));
        assertThat(tx.getBody().getInputs()).hasSize(2);
        assertThat(tx.getBody().getFee()).isGreaterThanOrEqualTo(
                BigInteger.valueOf(155381L + 44L * HexUtil.decodeHexString(draft.cborHex()).length));
        assertThat(tx.getWitnessSet().getVkeyWitnesses())
                .extracting(w -> HexUtil.encodeHexString(w.getVkey()))
                .containsExactlyInAnyOrder(HexUtil.encodeHexString(external.publicKeyBytes()),
                        HexUtil.encodeHexString(internal.publicKeyBytes()));
        for (var witness : tx.getWitnessSet().getVkeyWitnesses()) {
            assertThat(com.bloxbean.cardano.client.crypto.config.CryptoConfiguration.INSTANCE.getSigningProvider()
                    .verify(witness.getSignature(), HexUtil.decodeHexString(draft.txHash()), witness.getVkey())).isTrue();
        }
    }

    @Test
    void canDraftPaymentFromKnownChangeFundsWithoutHistory() throws Exception {
        Wallet sender = Wallet.createFromMnemonic(Networks.mainnet(), MNEMONIC);
        var internal = com.bloxbean.cardano.yano.wallet.core.wallet.WalletAddresses.account(sender, 1, 24);
        var draft = new QuickAdaTxService().buildSignedDraft(sender,
                singleUtxoSupplier(internal.baseAddress(), true), this::protocolParams,
                new NoopTransactionProcessor(), Wallet.create(Networks.mainnet()).getBaseAddressString(0),
                BigInteger.valueOf(1_000_000));
        var tx = Transaction.deserialize(HexUtil.decodeHexString(draft.cborHex()));
        assertThat(tx.getWitnessSet().getVkeyWitnesses()).hasSize(1);
        assertThat(tx.getWitnessSet().getVkeyWitnesses().getFirst().getVkey())
                .containsExactly(internal.publicKeyBytes());
    }

    private UtxoSupplier singleUtxoSupplier(String address) {
        return singleUtxoSupplier(address, false);
    }

    private UtxoSupplier singleUtxoSupplier(String address, boolean historyUnsupported) {
        return new UtxoSupplier() {
            @Override
            public List<Utxo> getPage(String queryAddress, Integer nrOfItems, Integer page, OrderEnum order) {
                if (!address.equals(queryAddress) || page != 0) {
                    return List.of();
                }
                return List.of(Utxo.builder()
                        .address(address)
                        .txHash("7e1eecf7439fb5119a6762985a61c9fb3ca8158d9fc38361f0c4746430d5e0c7")
                        .outputIndex(0)
                        .amount(List.of(Amount.lovelace(BigInteger.valueOf(5_000_000))))
                        .build());
            }

            @Override
            public Optional<Utxo> getTxOutput(String txHash, int outputIndex) {
                return Optional.empty();
            }

            @Override
            public boolean isUsedAddress(Address candidate) {
                if (historyUnsupported) throw new com.bloxbean.cardano.yano.wallet.core.service.HistoryPort
                        .HistoryNotSupportedException("No history endpoint");
                return address.equals(candidate.toBech32());
            }
        };
    }

    private ProtocolParams protocolParams() {
        return ProtocolParams.builder()
                .minFeeA(44)
                .minFeeB(155381)
                .minUtxo("1000000")
                .coinsPerUtxoSize("4312")
                .minFeeRefScriptCostPerByte(BigDecimal.valueOf(15))
                .build();
    }

    private static class NoopTransactionProcessor implements TransactionProcessor {
        @Override
        public Result<List<EvaluationResult>> evaluateTx(byte[] cbor, Set<Utxo> inputUtxos) {
            return Result.success("not evaluated").withValue(List.of());
        }

        @Override
        public Result<String> submitTransaction(byte[] cborData) {
            return Result.success("not submitted");
        }
    }
}
