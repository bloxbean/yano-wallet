package com.bloxbean.cardano.yano.wallet.core.service;

/**
 * Thrown when something asks the chain a question before the node can answer.
 *
 * <p>A managed node is usable for local work — listing, creating and restoring
 * wallets are repository operations — long before its REST API is up, and on a
 * first start that gap can be over an hour. The wallet therefore builds a
 * connection that serves the local half immediately (see
 * {@code WalletService.localOnly}) and fills in the node half when it arrives.
 *
 * <p>Every node-backed call on such a connection fails with this rather than
 * returning an empty or default answer. That is deliberate: an empty UTxO set
 * looks exactly like an empty wallet, and a default protocol-params object would
 * build a transaction with wrong fees. Loud and wrong-shaped beats quiet and
 * plausible on the money path.
 */
public class NodeNotReadyException extends RuntimeException {

    public NodeNotReadyException(String message) {
        super(message);
    }
}
