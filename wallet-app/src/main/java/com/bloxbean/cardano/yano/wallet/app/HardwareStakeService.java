package com.bloxbean.cardano.yano.wallet.app;

import com.bloxbean.cardano.client.address.Address;
import com.bloxbean.cardano.client.api.model.Result;
import com.bloxbean.cardano.client.api.model.Utxo;
import com.bloxbean.cardano.client.crypto.cip1852.CIP1852;
import com.bloxbean.cardano.client.transaction.spec.Transaction;
import com.bloxbean.cardano.client.transaction.spec.TransactionBody;
import com.bloxbean.cardano.client.transaction.spec.TransactionInput;
import com.bloxbean.cardano.client.transaction.spec.TransactionOutput;
import com.bloxbean.cardano.client.transaction.spec.TransactionWitnessSet;
import com.bloxbean.cardano.client.transaction.spec.Value;
import com.bloxbean.cardano.client.transaction.spec.Withdrawal;
import com.bloxbean.cardano.client.transaction.spec.cert.Certificate;
import com.bloxbean.cardano.client.transaction.spec.cert.StakeCredential;
import com.bloxbean.cardano.client.transaction.spec.cert.StakeDelegation;
import com.bloxbean.cardano.client.transaction.spec.cert.StakePoolId;
import com.bloxbean.cardano.client.transaction.spec.cert.StakeRegistration;
import com.bloxbean.cardano.client.transaction.spec.cert.VoteDelegCert;
import com.bloxbean.cardano.client.transaction.spec.cert.RegDRepCert;
import com.bloxbean.cardano.client.transaction.spec.cert.UnregDRepCert;
import com.bloxbean.cardano.client.address.Credential;
import com.bloxbean.cardano.client.transaction.spec.governance.Anchor;
import com.bloxbean.cardano.client.transaction.spec.governance.DRep;
import com.bloxbean.cardano.client.transaction.spec.governance.Vote;
import com.bloxbean.cardano.client.transaction.spec.governance.Voter;
import com.bloxbean.cardano.client.transaction.spec.governance.VoterType;
import com.bloxbean.cardano.client.transaction.spec.governance.VotingProcedure;
import com.bloxbean.cardano.client.transaction.spec.governance.VotingProcedures;
import com.bloxbean.cardano.client.transaction.spec.governance.actions.GovActionId;
import com.bloxbean.cardano.client.transaction.util.TransactionUtil;
import com.bloxbean.cardano.client.util.HexUtil;
import com.bloxbean.cardano.yano.wallet.core.config.WalletNetwork;
import com.bloxbean.cardano.yano.wallet.core.hardware.HardwareDevice;
import com.bloxbean.cardano.yano.wallet.core.wallet.StoredWallet;
import com.bloxbean.cardano.yano.wallet.hardware.ledger.LedgerBip32;
import com.bloxbean.cardano.yano.wallet.hardware.ledger.LedgerCardanoApp;
import com.bloxbean.cardano.yano.wallet.hardware.ledger.LedgerHardwareWalletService;
import com.bloxbean.cardano.yano.wallet.hardware.ledger.LedgerSignedTx;
import com.bloxbean.cardano.yano.wallet.hardware.ledger.LedgerTxInput;
import com.bloxbean.cardano.yano.wallet.hardware.ledger.LedgerTxOutput;
import com.bloxbean.cardano.yano.wallet.nodeclient.YanoNodeBackend;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Device-signed stake and governance operations for a watch-only hardware wallet
 * (ADR-034): pool delegation (with first-time registration), reward withdrawal,
 * CIP-1694 vote delegation, DRep registration, and casting votes as a DRep. Each
 * draft carries its own witness paths — the payment key always signs inputs; the
 * stake key ({@code …/2/0}) signs stake certs/withdrawals, the DRep key
 * ({@code …/3/0}) signs DRep certs and voting procedures. Certs/withdrawals/votes
 * are streamed to the device pre-serialized; the equivalent CCL objects go into
 * the submitted tx so the device tx hash matches the host's.
 */
final class HardwareStakeService {

    private static final BigInteger FEE = BigInteger.valueOf(300_000);
    private static final BigInteger STAKE_DEPOSIT = BigInteger.valueOf(2_000_000);
    private static final BigInteger DREP_DEPOSIT = BigInteger.valueOf(500_000_000);

    private final LedgerHardwareWalletService hardware = new LedgerHardwareWalletService();

    record Draft(String txHash, TransactionBody body, StoredWallet profile,
                 List<LedgerTxInput> inputs, List<LedgerTxOutput> outputs, BigInteger fee, long ttl,
                 List<byte[]> certificates, List<byte[]> withdrawals, List<byte[]> votingProcedures,
                 List<long[]> signingPaths, String summary) {
    }

    private static List<long[]> stakeSigningPaths(StoredWallet profile) {
        return List.of(LedgerBip32.paymentPath(profile.accountIndex(), 0, 0),
                LedgerBip32.stakePath(profile.accountIndex()));
    }

    private static List<long[]> drepSigningPaths(StoredWallet profile) {
        return List.of(LedgerBip32.paymentPath(profile.accountIndex(), 0, 0),
                LedgerBip32.drepPath(profile.accountIndex()));
    }

    Draft buildDelegation(YanoNodeBackend backend, WalletNetwork network,
                          StoredWallet profile, String poolId, boolean stakeRegistered) {
        byte[] accountXpub = HexUtil.decodeHexString(profile.accountXpubHex());
        byte[] stakeKeyHash = new CIP1852().getPublicKeyFromAccountPubKey(accountXpub, 2, 0).getKeyHash();
        StakeCredential stakeCredential = StakeCredential.fromKeyHash(stakeKeyHash);
        StakePoolId pool = poolId.startsWith("pool")
                ? StakePoolId.fromBech32PoolId(poolId) : StakePoolId.fromHexPoolId(poolId);
        long[] stakePath = LedgerBip32.stakePath(profile.accountIndex());

        List<Certificate> cclCerts = new ArrayList<>();
        List<byte[]> ledgerCerts = new ArrayList<>();
        BigInteger deposit = BigInteger.ZERO;
        if (!stakeRegistered) {
            cclCerts.add(new StakeRegistration(stakeCredential));
            ledgerCerts.add(LedgerCardanoApp.certStakeRegistration(stakePath));
            deposit = STAKE_DEPOSIT;
        }
        cclCerts.add(new StakeDelegation(stakeCredential, pool));
        ledgerCerts.add(LedgerCardanoApp.certStakeDelegation(stakePath, pool.getPoolKeyHash()));

        // Consume fee + deposit; the remainder returns to the account address.
        BigInteger required = FEE.add(deposit);
        Selection selection = selectUtxos(backend, profile.baseAddress(), required);
        BigInteger change = selection.total.subtract(required);

        List<TransactionOutput> outputs = List.of(
                new TransactionOutput(profile.baseAddress(), Value.fromCoin(change)));
        List<LedgerTxOutput> ledgerOutputs = List.of(
                new LedgerTxOutput(new Address(profile.baseAddress()).getBytes(), change));

        long ttl = backend.ports().status().slot() + 7200;
        TransactionBody body = TransactionBody.builder()
                .inputs(selection.inputs).outputs(outputs).fee(FEE).ttl(ttl).certs(cclCerts).build();
        String txHash = TransactionUtil.getTxHash(Transaction.builder().body(body)
                .witnessSet(TransactionWitnessSet.builder().build()).build());

        String summary = stakeRegistered ? "delegation" : "register + delegate (+₳2 deposit)";
        return new Draft(txHash, body, profile, selection.ledgerInputs, ledgerOutputs, FEE, ttl,
                ledgerCerts, List.of(), List.of(), stakeSigningPaths(profile), summary);
    }

    /**
     * Vote-delegation (CIP-1694): delegate this account's voting power to a DRep
     * (or the abstain / no-confidence pseudo-DReps). Like pool delegation it needs
     * a registered stake credential, so an unregistered account first pays the
     * ~2 ₳ registration deposit.
     */
    Draft buildVoteDelegation(YanoNodeBackend backend, WalletNetwork network,
                              StoredWallet profile, DRep drep, boolean stakeRegistered) {
        byte[] accountXpub = HexUtil.decodeHexString(profile.accountXpubHex());
        byte[] stakeKeyHash = new CIP1852().getPublicKeyFromAccountPubKey(accountXpub, 2, 0).getKeyHash();
        StakeCredential stakeCredential = StakeCredential.fromKeyHash(stakeKeyHash);
        long[] stakePath = LedgerBip32.stakePath(profile.accountIndex());

        List<Certificate> cclCerts = new ArrayList<>();
        List<byte[]> ledgerCerts = new ArrayList<>();
        BigInteger deposit = BigInteger.ZERO;
        if (!stakeRegistered) {
            cclCerts.add(new StakeRegistration(stakeCredential));
            ledgerCerts.add(LedgerCardanoApp.certStakeRegistration(stakePath));
            deposit = STAKE_DEPOSIT;
        }
        cclCerts.add(new VoteDelegCert(stakeCredential, drep));
        ledgerCerts.add(ledgerVoteDelegation(stakePath, drep));

        BigInteger required = FEE.add(deposit);
        Selection selection = selectUtxos(backend, profile.baseAddress(), required);
        BigInteger change = selection.total.subtract(required);

        List<TransactionOutput> outputs = List.of(
                new TransactionOutput(profile.baseAddress(), Value.fromCoin(change)));
        List<LedgerTxOutput> ledgerOutputs = List.of(
                new LedgerTxOutput(new Address(profile.baseAddress()).getBytes(), change));

        long ttl = backend.ports().status().slot() + 7200;
        TransactionBody body = TransactionBody.builder()
                .inputs(selection.inputs).outputs(outputs).fee(FEE).ttl(ttl).certs(cclCerts).build();
        String txHash = TransactionUtil.getTxHash(Transaction.builder().body(body)
                .witnessSet(TransactionWitnessSet.builder().build()).build());

        String target = drepTargetLabel(drep);
        String summary = stakeRegistered ? "vote → " + target
                : "register + vote → " + target + " (+₳2 deposit)";
        return new Draft(txHash, body, profile, selection.ledgerInputs, ledgerOutputs, FEE, ttl,
                ledgerCerts, List.of(), List.of(), stakeSigningPaths(profile), summary);
    }

    /** Maps a CCL DRep to the Ledger vote-delegation certificate payload. */
    private static byte[] ledgerVoteDelegation(long[] stakePath, DRep drep) {
        return switch (drep.getType()) {
            case ADDR_KEYHASH -> LedgerCardanoApp.certVoteDelegation(
                    stakePath, LedgerCardanoApp.DREP_KEY_HASH, HexUtil.decodeHexString(drep.getHash()));
            case SCRIPTHASH -> LedgerCardanoApp.certVoteDelegation(
                    stakePath, LedgerCardanoApp.DREP_SCRIPT_HASH, HexUtil.decodeHexString(drep.getHash()));
            case ABSTAIN -> LedgerCardanoApp.certVoteDelegation(
                    stakePath, LedgerCardanoApp.DREP_ABSTAIN, null);
            case NO_CONFIDENCE -> LedgerCardanoApp.certVoteDelegation(
                    stakePath, LedgerCardanoApp.DREP_NO_CONFIDENCE, null);
        };
    }

    private static String drepTargetLabel(DRep drep) {
        return switch (drep.getType()) {
            case ABSTAIN -> "abstain";
            case NO_CONFIDENCE -> "no confidence";
            default -> {
                String hash = drep.getHash();
                yield hash.length() > 12 ? hash.substring(0, 12) + "…" : hash;
            }
        };
    }

    Draft buildWithdrawal(YanoNodeBackend backend, WalletNetwork network,
                          StoredWallet profile, BigInteger withdrawable) {
        long[] stakePath = LedgerBip32.stakePath(profile.accountIndex());
        Selection selection = selectUtxos(backend, profile.baseAddress(), FEE);
        // Inputs + withdrawn rewards, minus fee, return to the account.
        BigInteger outCoin = selection.total.add(withdrawable).subtract(FEE);

        List<TransactionOutput> outputs = List.of(
                new TransactionOutput(profile.baseAddress(), Value.fromCoin(outCoin)));
        List<LedgerTxOutput> ledgerOutputs = List.of(
                new LedgerTxOutput(new Address(profile.baseAddress()).getBytes(), outCoin));

        List<Withdrawal> cclWithdrawals = List.of(new Withdrawal(profile.stakeAddress(), withdrawable));
        List<byte[]> ledgerWithdrawals = List.of(LedgerCardanoApp.withdrawal(withdrawable, stakePath));

        long ttl = backend.ports().status().slot() + 7200;
        TransactionBody body = TransactionBody.builder()
                .inputs(selection.inputs).outputs(outputs).fee(FEE).ttl(ttl).withdrawals(cclWithdrawals).build();
        String txHash = TransactionUtil.getTxHash(Transaction.builder().body(body)
                .witnessSet(TransactionWitnessSet.builder().build()).build());

        return new Draft(txHash, body, profile, selection.ledgerInputs, ledgerOutputs, FEE, ttl,
                List.of(), ledgerWithdrawals, List.of(), stakeSigningPaths(profile), "withdraw ₳" + outCoin);
    }

    /**
     * DRep registration (CIP-1694): registers this account's DRep key as a DRep,
     * locking a ~500 ₳ deposit and an optional rationale anchor. Signed by the
     * payment key (inputs) + the DRep key (the registration cert).
     */
    Draft buildDRepRegistration(YanoNodeBackend backend, WalletNetwork network,
                                StoredWallet profile, String anchorUrl, byte[] anchorHash) {
        byte[] accountXpub = HexUtil.decodeHexString(profile.accountXpubHex());
        byte[] drepKeyHash = new CIP1852().getPublicKeyFromAccountPubKey(accountXpub, 3, 0).getKeyHash();
        long[] drepPath = LedgerBip32.drepPath(profile.accountIndex());
        Anchor anchor = (anchorUrl == null || anchorUrl.isEmpty()) ? null : new Anchor(anchorUrl, anchorHash);

        List<Certificate> cclCerts = List.of(new RegDRepCert(Credential.fromKey(drepKeyHash), DREP_DEPOSIT, anchor));
        List<byte[]> ledgerCerts = List.of(
                LedgerCardanoApp.certDRepRegistration(drepPath, DREP_DEPOSIT, anchorUrl, anchorHash));

        BigInteger required = FEE.add(DREP_DEPOSIT);
        Selection selection = selectUtxos(backend, profile.baseAddress(), required);
        BigInteger change = selection.total.subtract(required);

        List<TransactionOutput> outputs = List.of(
                new TransactionOutput(profile.baseAddress(), Value.fromCoin(change)));
        List<LedgerTxOutput> ledgerOutputs = List.of(
                new LedgerTxOutput(new Address(profile.baseAddress()).getBytes(), change));

        long ttl = backend.ports().status().slot() + 7200;
        TransactionBody body = TransactionBody.builder()
                .inputs(selection.inputs).outputs(outputs).fee(FEE).ttl(ttl).certs(cclCerts).build();
        String txHash = TransactionUtil.getTxHash(Transaction.builder().body(body)
                .witnessSet(TransactionWitnessSet.builder().build()).build());

        return new Draft(txHash, body, profile, selection.ledgerInputs, ledgerOutputs, FEE, ttl,
                ledgerCerts, List.of(), List.of(), drepSigningPaths(profile), "register as DRep (+₳500 deposit)");
    }

    /**
     * DRep deregistration (CIP-1694): retires this account's DRep and reclaims the
     * locked {@code deposit}. The deposit is REFUNDED, so unlike registration this
     * selects UTxOs for the fee only and the deposit returns as change (mirrors
     * {@link #buildWithdrawal}). {@code deposit} must equal the on-chain locked amount.
     */
    Draft buildDRepDeregistration(YanoNodeBackend backend, WalletNetwork network,
                                  StoredWallet profile, BigInteger deposit) {
        byte[] accountXpub = HexUtil.decodeHexString(profile.accountXpubHex());
        byte[] drepKeyHash = new CIP1852().getPublicKeyFromAccountPubKey(accountXpub, 3, 0).getKeyHash();
        long[] drepPath = LedgerBip32.drepPath(profile.accountIndex());

        List<Certificate> cclCerts = List.of(new UnregDRepCert(Credential.fromKey(drepKeyHash), deposit));
        List<byte[]> ledgerCerts = List.of(LedgerCardanoApp.certDRepDeregistration(drepPath, deposit));

        Selection selection = selectUtxos(backend, profile.baseAddress(), FEE);
        BigInteger outCoin = selection.total.add(deposit).subtract(FEE);

        List<TransactionOutput> outputs = List.of(
                new TransactionOutput(profile.baseAddress(), Value.fromCoin(outCoin)));
        List<LedgerTxOutput> ledgerOutputs = List.of(
                new LedgerTxOutput(new Address(profile.baseAddress()).getBytes(), outCoin));

        long ttl = backend.ports().status().slot() + 7200;
        TransactionBody body = TransactionBody.builder()
                .inputs(selection.inputs).outputs(outputs).fee(FEE).ttl(ttl).certs(cclCerts).build();
        String txHash = TransactionUtil.getTxHash(Transaction.builder().body(body)
                .witnessSet(TransactionWitnessSet.builder().build()).build());

        return new Draft(txHash, body, profile, selection.ledgerInputs, ledgerOutputs, FEE, ttl,
                ledgerCerts, List.of(), List.of(), drepSigningPaths(profile), "unregister DRep (reclaim deposit)");
    }

    /**
     * Casts a vote as a DRep (CIP-1694) on a governance action. Signed by the
     * payment key (inputs) + the DRep key (the voting procedure).
     */
    Draft buildVote(YanoNodeBackend backend, WalletNetwork network, StoredWallet profile,
                    String govActionTxHash, int govActionIndex, Vote vote,
                    String anchorUrl, byte[] anchorHash) {
        byte[] accountXpub = HexUtil.decodeHexString(profile.accountXpubHex());
        byte[] drepKeyHash = new CIP1852().getPublicKeyFromAccountPubKey(accountXpub, 3, 0).getKeyHash();
        long[] drepPath = LedgerBip32.drepPath(profile.accountIndex());
        Anchor anchor = (anchorUrl == null || anchorUrl.isEmpty()) ? null : new Anchor(anchorUrl, anchorHash);

        Voter voter = new Voter(VoterType.DREP_KEY_HASH, Credential.fromKey(drepKeyHash));
        GovActionId govActionId = new GovActionId(govActionTxHash, govActionIndex);
        VotingProcedures votingProcedures = new VotingProcedures();
        votingProcedures.add(voter, govActionId, new VotingProcedure(vote, anchor));

        List<byte[]> ledgerVotes = List.of(LedgerCardanoApp.votingProcedureAsDRep(drepPath,
                HexUtil.decodeHexString(govActionTxHash), govActionIndex, ledgerVote(vote), anchorUrl, anchorHash));

        Selection selection = selectUtxos(backend, profile.baseAddress(), FEE);
        BigInteger change = selection.total.subtract(FEE);

        List<TransactionOutput> outputs = List.of(
                new TransactionOutput(profile.baseAddress(), Value.fromCoin(change)));
        List<LedgerTxOutput> ledgerOutputs = List.of(
                new LedgerTxOutput(new Address(profile.baseAddress()).getBytes(), change));

        long ttl = backend.ports().status().slot() + 7200;
        TransactionBody body = TransactionBody.builder()
                .inputs(selection.inputs).outputs(outputs).fee(FEE).ttl(ttl)
                .votingProcedures(votingProcedures).build();
        String txHash = TransactionUtil.getTxHash(Transaction.builder().body(body)
                .witnessSet(TransactionWitnessSet.builder().build()).build());

        return new Draft(txHash, body, profile, selection.ledgerInputs, ledgerOutputs, FEE, ttl,
                List.of(), List.of(), ledgerVotes, drepSigningPaths(profile),
                "vote " + vote + " on " + shortId(govActionTxHash, govActionIndex));
    }

    private static int ledgerVote(Vote vote) {
        return switch (vote) {
            case YES -> LedgerCardanoApp.VOTE_YES;
            case NO -> LedgerCardanoApp.VOTE_NO;
            case ABSTAIN -> LedgerCardanoApp.VOTE_ABSTAIN;
        };
    }

    private static String shortId(String txHash, int index) {
        String h = txHash.length() > 12 ? txHash.substring(0, 12) + "…" : txHash;
        return h + "#" + index;
    }

    String signAndSubmit(YanoNodeBackend backend, WalletNetwork network, Draft draft) {
        List<HardwareDevice> devices = hardware.enumerate();
        if (devices.isEmpty()) {
            throw new IllegalStateException("Connect and unlock your Ledger, open the Cardano app, and try again.");
        }
        // Witnesses come from the draft: payment key (inputs) + stake or DRep key.
        LedgerSignedTx signed = hardware.signTransaction(devices.get(0),
                network.networkId(), network.protocolMagic(),
                draft.inputs(), draft.outputs(), draft.fee(), draft.ttl(),
                draft.signingPaths(), draft.certificates(), draft.withdrawals(), draft.votingProcedures(),
                /* tagCborSets */ true, /* outputFormat legacy array */ 0, null);
        if (!signed.txHashHex().equals(draft.txHash())) {
            throw new IllegalStateException("Device produced a different transaction — not submitting.");
        }

        Transaction tx = Transaction.builder().body(draft.body())
                .witnessSet(HardwareSigning.witnessSet(draft.profile().accountXpubHex(), signed.witnesses()))
                .build();
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

    private static Selection selectUtxos(YanoNodeBackend backend, String address, BigInteger required) {
        List<Utxo> available = backend.utxoSupplier().getAll(address).stream()
                .filter(u -> u.getAmount().size() == 1 && "lovelace".equals(u.getAmount().get(0).getUnit()))
                .sorted(Comparator.comparing((Utxo u) -> u.getAmount().get(0).getQuantity()).reversed())
                .toList();
        List<TransactionInput> inputs = new ArrayList<>();
        List<LedgerTxInput> ledgerInputs = new ArrayList<>();
        BigInteger total = BigInteger.ZERO;
        for (Utxo u : available) {
            inputs.add(TransactionInput.builder().transactionId(u.getTxHash()).index(u.getOutputIndex()).build());
            ledgerInputs.add(new LedgerTxInput(u.getTxHash(), u.getOutputIndex()));
            total = total.add(u.getAmount().get(0).getQuantity());
            if (total.compareTo(required) >= 0) {
                break;
            }
        }
        if (total.compareTo(required) < 0) {
            throw new IllegalStateException("Not enough funds (need " + required + " lovelace, have " + total + ")");
        }
        return new Selection(inputs, ledgerInputs, total);
    }

    private record Selection(List<TransactionInput> inputs, List<LedgerTxInput> ledgerInputs, BigInteger total) {
    }
}
