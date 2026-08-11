package com.bloxbean.cardano.yano.wallet.connector;

import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;

/**
 * The CIP-30 desktop bridge (ADR-035): a WebSocket server bound to loopback only
 * that the companion extension connects to. Each text frame is a JSON request
 * {@code {id, method, params, origin}}; the reply is {@code {id, result}} or
 * {@code {id, error:{code, info}}}. All wallet access + consent goes through the
 * injected SPIs — this class is pure transport + framing.
 */
public final class Cip30BridgeServer {

    private static final Logger log = LoggerFactory.getLogger(Cip30BridgeServer.class);

    public static final int DEFAULT_PORT = 27428;

    private final Cip30Rpc rpc;
    private final WebSocketServer server;
    private volatile boolean started;

    public Cip30BridgeServer(int port, Cip30Wallet wallet, Cip30Approvals approvals) {
        this.rpc = new Cip30Rpc(new Cip30Dispatcher(wallet, approvals));
        this.server = new WebSocketServer(new InetSocketAddress("127.0.0.1", port)) {
            @Override
            public void onOpen(WebSocket conn, ClientHandshake handshake) {
                log.debug("CIP-30 dApp bridge: client connected");
            }

            @Override
            public void onClose(WebSocket conn, int code, String reason, boolean remote) {
                log.debug("CIP-30 dApp bridge: client disconnected ({})", reason);
            }

            @Override
            public void onMessage(WebSocket conn, String message) {
                conn.send(rpc.handle(message));
            }

            @Override
            public void onError(WebSocket conn, Exception ex) {
                log.warn("CIP-30 dApp bridge error: {}", ex.getMessage());
            }

            @Override
            public void onStart() {
                log.info("CIP-30 dApp bridge listening on 127.0.0.1:{}", port);
            }
        };
        this.server.setReuseAddr(true);
        this.server.setDaemon(true);
    }

    public Cip30BridgeServer(Cip30Wallet wallet, Cip30Approvals approvals) {
        this(DEFAULT_PORT, wallet, approvals);
    }

    public synchronized void start() {
        if (started) {
            return;
        }
        started = true;
        server.start();
    }

    /** The bound port (useful when constructed with port 0 for tests). */
    public int port() {
        return server.getPort();
    }

    public synchronized void stop() {
        if (!started) {
            return;
        }
        started = false;
        try {
            server.stop(1000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

}
