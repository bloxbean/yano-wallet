package com.bloxbean.cardano.yano.wallet.connector;

import com.bloxbean.cardano.client.api.model.Utxo;

import java.util.List;

/**
 * What the CIP-30 bridge needs from an unlocked wallet (ADR-035). Implemented by
 * the wallet app over its session + node backend; the bridge module itself holds
 * no wallet state. Addresses are bech32 strings — the bridge encodes them to the
 * CIP-30 hex/CBOR wire format.
 */
public interface Cip30Wallet {

    /** True when a wallet is unlocked and connected to a node — i.e. can serve requests. */
    boolean isReady();

    /** CIP-30 network id: 0 = testnet, 1 = mainnet. */
    int networkId();

    /** The wallet's payment (base) addresses that have been used, bech32. */
    List<String> usedAddresses();

    /** Derived-but-unused payment addresses, bech32 (may be empty). */
    List<String> unusedAddresses();

    /** The address change should return to, bech32. */
    String changeAddress();

    /** The wallet's reward (stake) addresses, bech32. */
    List<String> rewardAddresses();

    /** All UTxOs controlled by the wallet (the bridge encodes them to CIP-30 CBOR). */
    List<Utxo> utxos();

    /**
     * Signs a dApp-provided transaction (hex CBOR) and returns the witness-set hex.
     * {@code partialSign} tolerates required signers the wallet doesn't hold. (M2)
     */
    String signTx(String txHex, boolean partialSign);

    /** CIP-8 data signing of {@code payloadHex} by the key for {@code signerAddress}. (M2) */
    DataSignature signData(String signerAddress, String payloadHex);

    /** Submits a signed transaction (hex CBOR) via the node; returns the tx hash. */
    String submitTx(String txHex);

    /** CIP-30 DataSignature: hex COSE_Sign1 signature + hex COSE_Key. */
    record DataSignature(String signature, String key) {
    }
}
