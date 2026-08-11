package com.bloxbean.cardano.yano.wallet.nodeclient;

public class NodeClientException extends RuntimeException {
    public NodeClientException(String message) {
        super(message);
    }

    public NodeClientException(String message, Throwable cause) {
        super(message, cause);
    }
}
