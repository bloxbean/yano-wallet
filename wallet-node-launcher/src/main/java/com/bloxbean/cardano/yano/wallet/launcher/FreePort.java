package com.bloxbean.cardano.yano.wallet.launcher;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ServerSocket;

/**
 * Finds a free local TCP port for the managed node, so it never collides with
 * a default Yano (7070/13337) or unrelated software (e.g. Docker on 8090).
 */
public final class FreePort {
    private FreePort() {
    }

    /** An ephemeral free port. There is an inherent (small) TOCTOU window before the node binds it. */
    public static int find() {
        try (ServerSocket socket = new ServerSocket(0)) {
            socket.setReuseAddress(true);
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new UncheckedIOException("Unable to allocate a free port for the managed node", e);
        }
    }
}
