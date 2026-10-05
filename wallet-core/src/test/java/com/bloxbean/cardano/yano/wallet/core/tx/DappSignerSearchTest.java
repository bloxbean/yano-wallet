package com.bloxbean.cardano.yano.wallet.core.tx;

import com.bloxbean.cardano.client.account.Account;
import com.bloxbean.cardano.client.address.Address;
import com.bloxbean.cardano.client.api.UtxoSupplier;
import com.bloxbean.cardano.client.api.common.OrderEnum;
import com.bloxbean.cardano.client.api.model.Utxo;
import com.bloxbean.cardano.client.common.cbor.CborSerializationUtil;
import com.bloxbean.cardano.client.common.model.Networks;
import com.bloxbean.cardano.client.crypto.config.CryptoConfiguration;
import com.bloxbean.cardano.client.transaction.TransactionSigner;
import com.bloxbean.cardano.client.transaction.spec.*;
import com.bloxbean.cardano.client.transaction.util.TransactionUtil;
import com.bloxbean.cardano.client.util.HexUtil;
import com.bloxbean.cardano.hdwallet.Wallet;
import com.bloxbean.cardano.yano.wallet.core.wallet.WalletAddresses;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.*;

import static org.assertj.core.api.Assertions.*;

/** Cryptographic/discovery tests, not claims of ledger acceptance for fabricated transactions. */
class DappSignerSearchTest {
    private static final String MNEMONIC = "test walk nut penalty hip pave soap entry language right filter choice";
    private final Wallet wallet = Wallet.createFromMnemonic(Networks.testnet(), MNEMONIC, 1);
    private final Inputs inputs = new Inputs();

    static class Inputs implements UtxoSupplier {
        final Map<String, Utxo> outputs = new HashMap<>();
        final Set<String> resolved = new HashSet<>();
        public List<Utxo> getPage(String address, Integer count, Integer page, OrderEnum order) {
            throw new AssertionError("Signing must not scan address history or balances");
        }
        public boolean isUsedAddress(Address address) { throw new AssertionError("No gap scan for signing"); }
        public Optional<Utxo> getTxOutput(String hash, int index) {
            resolved.add(hash + "#" + index);
            return Optional.ofNullable(outputs.get(hash + "#" + index));
        }
        void add(TransactionInput input, String address) {
            outputs.put(input.getTransactionId() + "#" + input.getIndex(), Utxo.builder()
                    .txHash(input.getTransactionId()).outputIndex(input.getIndex()).address(address).build());
        }
    }

    private Account key(int role, int index) { return WalletAddresses.account(wallet, role, index); }
    private byte[] hash(int role, int index) { return key(role, index).hdKeyPair().getPublicKey().getKeyHash(); }
    private Transaction tx(byte[]... signers) {
        return Transaction.builder().body(TransactionBody.builder().inputs(List.of())
                .outputs(List.of(new TransactionOutput(wallet.getBaseAddressString(0),
                        Value.builder().coin(BigInteger.valueOf(2_000_000)).build())))
                .fee(BigInteger.valueOf(200_000)).requiredSigners(Arrays.asList(signers)).build())
                .witnessSet(TransactionWitnessSet.builder().build()).build();
    }
    private DappSignerSearch.Plan find(Transaction tx, int limit) throws Exception {
        return DappSignerSearch.transaction(wallet, tx.serializeToHex(), inputs, List.of(), limit);
    }
    private List<VkeyWitness> verify(String original, String witnesses) throws Exception {
        var result = TransactionWitnessSet.deserialize((co.nstant.in.cbor.model.Map)
                CborSerializationUtil.deserialize(HexUtil.decodeHexString(witnesses))).getVkeyWitnesses();
        byte[] digest = HexUtil.decodeHexString(TransactionUtil.getTxHash(HexUtil.decodeHexString(original)));
        for (var w : result) assertThat(CryptoConfiguration.INSTANCE.getSigningProvider()
                .verify(w.getSignature(), digest, w.getVkey())).isTrue();
        return result;
    }

    @Test void dataSearchChecksKnownPathsAndRejectsWrongAccountNetworkAndMalformedAddress() {
        byte[] addr = new Address(key(1, 123).enterpriseAddress()).getBytes();
        assertThat(DappSignerSearch.data(wallet, addr, new byte[32], List.of(new DappSignerSearch.KeyPath(1, 123)), 30).paymentPath())
                .isEqualTo(new DappSignerSearch.KeyPath(1, 123));
        assertThat(DappSignerSearch.data(wallet, addr, new byte[32], List.of(), 50).hasSigner()).isFalse();
        var another = Wallet.createFromMnemonic(Networks.testnet(), MNEMONIC, 2);
        assertThat(DappSignerSearch.data(another, new Address(key(0, 1).enterpriseAddress()).getBytes(), new byte[32], List.of(), 50).hasSigner()).isFalse();
        addr[0] = 0x61;
        assertThatThrownBy(() -> DappSignerSearch.data(wallet, addr, new byte[32], List.of(), 30)).hasMessageContaining("another network");
        addr[0] = 0x70;
        assertThatThrownBy(() -> DappSignerSearch.data(wallet, addr, new byte[32], List.of(), 30)).hasMessageContaining("key payment");
        assertThatThrownBy(() -> DappSignerSearch.data(wallet, new byte[1], new byte[32], List.of(), 30)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void findsUnusedReceive012AndReturnsAllThreeVerifiableSignatures() throws Exception {
        var tx = tx(hash(0, 0), hash(0, 1), hash(0, 2));
        var plan = find(tx, 30);
        assertThat(plan.paymentPaths()).containsExactly(new DappSignerSearch.KeyPath(0, 0),
                new DappSignerSearch.KeyPath(0, 1), new DappSignerSearch.KeyPath(0, 2));
        assertThat(plan.unmatched()).isEmpty();
        assertThat(plan.stake()).isFalse();
        var keys = plan.paymentPaths().stream().map(p -> key(p.role(), p.index()).hdKeyPair()).toList();
        assertThat(verify(tx.serializeToHex(), DappSigner.witnessSetHex(keys, tx.serializeToHex()))).hasSize(3);
    }

    @Test void bothChainsRespect30And50ExclusiveBounds() throws Exception {
        var tx = tx(hash(0, 29), hash(1, 29), hash(0, 30), hash(1, 30), hash(0, 49), hash(1, 49), hash(0, 50), hash(1, 50));
        var initial = find(tx, 30);
        assertThat(initial.paymentPaths()).hasSize(2);
        assertThat(initial.unmatched()).hasSize(6);
        var extended = find(tx, 50);
        assertThat(extended.paymentPaths()).hasSize(6);
        assertThat(extended.unmatched()).containsExactly(HexUtil.encodeHexString(hash(0, 50)), HexUtil.encodeHexString(hash(1, 50)));
    }

    @Test void knownPathsBeyondWindowAreCheckedFirstWithoutScanningInterveningIndexes() throws Exception {
        var known = new DappSignerSearch.KeyPath(1, 123);
        var plan = DappSignerSearch.transaction(wallet, tx(hash(1, 123), hash(0, 2)).serializeToHex(),
                inputs, List.of(known, known), 30);
        assertThat(plan.paymentPaths()).containsExactly(known, new DappSignerSearch.KeyPath(0, 2));
    }

    @Test void neverSearchesAnotherHdAccountAndNeverAddsUnrelatedIndexZero() throws Exception {
        var other = Wallet.createFromMnemonic(Networks.testnet(), MNEMONIC, 2);
        byte[] foreign = other.getAccountAtIndex(0).hdKeyPair().getPublicKey().getKeyHash();
        var plan = find(tx(hash(1, 2), foreign), 50);
        assertThat(plan.paymentPaths()).containsExactly(new DappSignerSearch.KeyPath(1, 2));
        assertThat(plan.unmatched()).containsExactly(HexUtil.encodeHexString(foreign));
    }

    @Test void matchesSpendingAndCollateralButNotReferenceInputsOrOutputs() throws Exception {
        var spend = new TransactionInput("aa".repeat(32), 0);
        var collateral = new TransactionInput("bb".repeat(32), 1);
        var reference = new TransactionInput("cc".repeat(32), 2);
        inputs.add(spend, key(0, 2).baseAddress());
        inputs.add(collateral, key(1, 3).baseAddress());
        var tx = tx();
        tx.getBody().setInputs(List.of(spend));
        tx.getBody().setCollateral(List.of(collateral));
        tx.getBody().setReferenceInputs(List.of(reference));
        var plan = find(tx, 30);
        assertThat(plan.paymentPaths()).containsExactly(new DappSignerSearch.KeyPath(0, 2), new DappSignerSearch.KeyPath(1, 3));
        assertThat(inputs.resolved).containsExactlyInAnyOrder("aa".repeat(32) + "#0", "bb".repeat(32) + "#1");
    }

    @Test void failsClosedOnMissingOrMismatchedInputResolution() throws Exception {
        var input = new TransactionInput("aa".repeat(32), 0);
        var tx = tx(hash(0, 0));
        tx.getBody().setInputs(List.of(input));
        assertThatThrownBy(() -> find(tx, 30)).hasMessageContaining("Cannot resolve");
        inputs.outputs.put("aa".repeat(32) + "#0", Utxo.builder().txHash("bb".repeat(32))
                .outputIndex(0).address(key(0, 0).baseAddress()).build());
        assertThatThrownBy(() -> find(tx, 30)).hasMessageContaining("identity mismatch");
    }

    @Test void preservesExistingValidWitnessesAndDoesNotReturnThemAgain() throws Exception {
        var tx = tx(hash(0, 0), hash(0, 1));
        String signed = HexUtil.encodeHexString(TransactionSigner.INSTANCE.sign(tx.serialize(), key(0, 0).hdKeyPair()));
        var plan = DappSignerSearch.transaction(wallet, signed, inputs, List.of(), 30);
        assertThat(plan.paymentPaths()).containsExactly(new DappSignerSearch.KeyPath(0, 1));
        String witnesses = DappSigner.witnessSetHex(List.of(key(0, 0).hdKeyPair(), key(0, 1).hdKeyPair(), key(0, 1).hdKeyPair()), signed);
        assertThat(verify(signed, witnesses)).hasSize(1);
    }

    @Test void invalidExistingWitnessCannotSuppressTheRealSigner() throws Exception {
        var tx = tx(hash(0, 0));
        tx.getWitnessSet().setVkeyWitnesses(List.of(new VkeyWitness(key(0, 0).publicKeyBytes(), new byte[64])));
        assertThatThrownBy(() -> find(tx, 30)).hasMessageContaining("Invalid pre-existing");
    }

    @Test void stakeOnlyRequestDoesNotAddPaymentWitnesses() throws Exception {
        var stake = key(0, 0).stakeHdKeyPair();
        var plan = find(tx(stake.getPublicKey().getKeyHash()), 30);
        assertThat(plan.stake()).isTrue();
        assertThat(plan.paymentPaths()).isEmpty();
        assertThat(plan.unmatched()).isEmpty();
    }

    @Test void multiKeySignaturesPreserveIndefiniteCborBody() throws Exception {
        String original = Cip113Fixtures.REG_TX;
        String witnesses = DappSigner.witnessSetHex(List.of(key(0, 0).hdKeyPair(), key(0, 1).hdKeyPair(), key(1, 2).hdKeyPair()), original);
        assertThat(verify(original, witnesses)).hasSize(3);
    }

    @Test void rejectsUnboundedSearchAndMalformedTransactions() throws Exception {
        assertThatThrownBy(() -> find(tx(hash(0, 1)), 51)).hasMessageContaining("30 or 50");
        assertThatThrownBy(() -> DappSignerSearch.transaction(wallet, "00", inputs, List.of(), 30)).hasMessageContaining("Invalid transaction");
        assertThatThrownBy(() -> DappSignerSearch.transaction(wallet, "00".repeat(65537), inputs, List.of(), 30)).hasMessageContaining("size limit");
    }
}
