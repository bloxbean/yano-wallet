package com.bloxbean.cardano.yano.wallet.core.simulate;

import com.bloxbean.cardano.client.address.Address;
import com.bloxbean.cardano.client.address.AddressType;

import java.util.Collection;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Decides whether an address belongs to this wallet (ADR-042). Everything the
 * value diff reports rests on this answer, so it is deliberately narrow and
 * three-valued.
 *
 * <h2>Why the payment credential, and only the payment credential</h2>
 *
 * An address is ours when we can <em>spend</em> what sits at it, and on Cardano
 * that is decided solely by the payment part. Matching on the stake part instead
 * would be an exploitable mistake: anyone may build an address pairing their own
 * payment credential with <em>our</em> stake credential, and a wallet that called
 * that address "mine" would count an attacker's output as change coming back —
 * under-reporting exactly the drain the user is being asked to authorise.
 *
 * <p>Matching whole address strings would be wrong in the other direction: the
 * same payment key appears in a base address and an enterprise address, and a
 * string comparison would miss our own funds. That error is safe (it over-states
 * the loss) but still wrong, and it makes ordinary transactions look alarming.
 *
 * <h2>Why "unknown" exists</h2>
 *
 * An address we cannot parse must not silently become "not mine". For an input,
 * "not mine" removes value from what we believe is leaving the wallet — the
 * dangerous direction. The engine turns {@link Ownership#UNKNOWN} on an input
 * into an incomplete summary instead, and treats it as not-ours only on an
 * output, where the resulting error over-states the loss.
 *
 * <h2>The caller's obligation</h2>
 *
 * <b>The credential set must contain the payment credential of every UTxO the
 * signer is able to sign.</b> A resolvable address whose credential is missing
 * from the set classifies as {@code NOT_MINE}, and on an input that silently
 * subtracts from the reported loss — the one direction this class exists to
 * prevent. Today the wallet signs with a single account key whose credential is
 * always in the set, so the obligation holds; anything that widens what the
 * signer can authorise (more derived receive addresses, multiple accounts per
 * ADR-037) must widen this set in the same change.
 */
public final class WalletOwnership {

    /** Whether an address is ours, definitely not ours, or unclassifiable. */
    public enum Ownership {MINE, NOT_MINE, UNKNOWN}

    private final Set<String> paymentCredentials;
    private final Set<String> rewardAddresses;
    private final Set<String> certificateCredentials;

    private WalletOwnership(Set<String> paymentCredentials, Set<String> rewardAddresses,
                            Set<String> certificateCredentials) {
        this.paymentCredentials = Set.copyOf(paymentCredentials);
        this.rewardAddresses = Set.copyOf(rewardAddresses);
        this.certificateCredentials = Set.copyOf(certificateCredentials);
    }

    /**
     * Builds the predicate from the wallet's own addresses. Any address that does
     * not yield a payment credential is dropped: a credential we cannot read is
     * one we cannot match, and silently keeping a malformed entry would make
     * {@link #classify} answer NOT_MINE for funds that are ours.
     */
    public static WalletOwnership ofAddresses(Collection<String> addresses) {
        return of(addresses, List.of());
    }

    /**
     * @param rewardAddresses the account's stake addresses. Kept separate because
     *                        a reward address carries a stake credential and no
     *                        payment part, so {@link #classify} cannot see it —
     *                        yet a withdrawal from it is unambiguously ours.
     */
    public static WalletOwnership of(Collection<String> addresses, Collection<String> rewardAddresses) {
        return of(addresses, rewardAddresses, List.of());
    }

    /**
     * @param extraCertificateCredentials credential hashes (hex) that certificates
     *                                    of ours may carry beyond the stake
     *                                    credential — a DRep credential, say.
     */
    public static WalletOwnership of(Collection<String> addresses, Collection<String> rewardAddresses,
                                     Collection<String> extraCertificateCredentials) {
        Set<String> credentials = new LinkedHashSet<>();
        if (addresses != null) {
            for (String address : addresses) {
                paymentCredentialOf(address).ifPresent(credentials::add);
            }
        }
        Set<String> rewards = new LinkedHashSet<>();
        if (rewardAddresses != null) {
            for (String rewardAddress : rewardAddresses) {
                if (rewardAddress != null && !rewardAddress.isBlank()) {
                    rewards.add(rewardAddress.strip());
                }
            }
        }
        Set<String> certificateCredentials = new LinkedHashSet<>();
        for (String rewardAddress : rewards) {
            // A reward address IS a stake credential; that is the credential a
            // stake certificate names.
            try {
                new Address(rewardAddress).getDelegationCredentialHash()
                        .map(HexFormat.of()::formatHex)
                        .ifPresent(certificateCredentials::add);
            } catch (RuntimeException e) {
                // Unparseable reward address: it simply contributes nothing.
            }
        }
        if (extraCertificateCredentials != null) {
            for (String credential : extraCertificateCredentials) {
                if (credential != null && !credential.isBlank()) {
                    certificateCredentials.add(credential.strip().toLowerCase(java.util.Locale.ROOT));
                }
            }
        }
        return new WalletOwnership(credentials, rewards, certificateCredentials);
    }

    /** True when {@code rewardAddress} is one of this account's stake addresses. */
    public boolean isMyRewardAddress(String rewardAddress) {
        return rewardAddress != null && rewardAddresses.contains(rewardAddress.strip());
    }

    /**
     * True when a certificate's credential is one of ours — the stake credential
     * behind our reward addresses, or any extra credential the caller supplied
     * (a DRep credential, say).
     *
     * <p>Needed because a Conway {@code UnregCert} / {@code UnregDRepCert}
     * refunds its deposit into the transaction, which is our value re-entering
     * and can be routed anywhere. A deregistration of somebody else's credential
     * costs us nothing, so the distinction has to be made on the credential
     * rather than on the certificate's mere presence.
     */
    public boolean isMyCertificateCredential(byte[] credentialHash) {
        return credentialHash != null
                && certificateCredentials.contains(HexFormat.of().formatHex(credentialHash));
    }

    /**
     * True when no address could be resolved to a credential. The engine refuses
     * to compute a diff in that state rather than reporting that nothing of ours
     * moves — which is what an empty credential set would otherwise produce.
     */
    public boolean isEmpty() {
        return paymentCredentials.isEmpty();
    }

    public Ownership classify(String address) {
        if (address == null || address.isBlank()) {
            return Ownership.UNKNOWN;
        }
        Address parsed;
        try {
            parsed = new Address(address);
        } catch (RuntimeException e) {
            return Ownership.UNKNOWN;
        }
        if (parsed.getAddressType() == AddressType.Byron) {
            // This wallet derives CIP-1852 Shelley addresses only, so a Byron
            // address is definitively somebody else's — not merely unreadable.
            return Ownership.NOT_MINE;
        }
        Optional<byte[]> credential;
        try {
            credential = parsed.getPaymentCredentialHash();
        } catch (RuntimeException e) {
            return Ownership.UNKNOWN;
        }
        if (credential.isEmpty()) {
            return Ownership.UNKNOWN;
        }
        return paymentCredentials.contains(HexFormat.of().formatHex(credential.get()))
                ? Ownership.MINE
                : Ownership.NOT_MINE;
    }

    private static Optional<String> paymentCredentialOf(String address) {
        if (address == null || address.isBlank()) {
            return Optional.empty();
        }
        try {
            return new Address(address).getPaymentCredentialHash().map(HexFormat.of()::formatHex);
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }
}
