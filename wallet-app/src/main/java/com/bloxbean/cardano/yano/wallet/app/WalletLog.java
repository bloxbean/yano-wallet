package com.bloxbean.cardano.yano.wallet.app;

import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;

/**
 * Writes the wallet's own diagnostics to {@code <dataDir>/wallet.log}.
 *
 * <p>The managed node has always had {@code node.log}, but the WALLET's output
 * went only to stderr — which a packaged macOS {@code .app} or Windows launcher
 * discards. So any failure before the node starts (no node jar found, a connect
 * that threw, an unreadable vault) left nothing behind at all: the user saw a
 * message in a dialog, closed it, and the reason was gone. That is precisely the
 * class of bug a released build produces and a developer cannot reproduce.
 *
 * <p>Implemented by teeing {@code System.out} and {@code System.err} rather than
 * by configuring a logging backend, because it has to capture three different
 * sources at once: slf4j-simple (which writes to stderr), the direct
 * {@code System.err.println} calls scattered through the connector paths, and
 * stack traces from threads that die. Swapping in a logging framework would
 * catch only the first.
 *
 * <p>Never throws. A wallet that cannot open its log must still start.
 */
final class WalletLog {

    /** Rotate at 5 MB so a long sync's chatter cannot fill a user's disk. */
    private static final long MAX_BYTES = 5L * 1024 * 1024;

    private WalletLog() {
    }

    static Path install(Path dataDirRoot) {
        Path logFile = dataDirRoot.resolve("wallet.log");
        try {
            Files.createDirectories(dataDirRoot);
            rotateIfLarge(logFile);
            PrintStream file = new PrintStream(
                    new FileOutputStream(logFile.toFile(), true), true, StandardCharsets.UTF_8);
            System.setOut(new PrintStream(tee(System.out, file), true, StandardCharsets.UTF_8));
            System.setErr(new PrintStream(tee(System.err, file), true, StandardCharsets.UTF_8));

            // A thread dying silently is the hardest failure to diagnose after
            // the fact, and the packaged app has no console to print it to.
            Thread.setDefaultUncaughtExceptionHandler((thread, error) -> {
                System.err.println("Uncaught exception on thread " + thread.getName());
                error.printStackTrace();
            });

            System.out.println("--- Yano Wallet started " + Instant.now() + " ---");
            return logFile;
        } catch (IOException | RuntimeException e) {
            // Deliberately quiet: the only place to report this is the stream we
            // just failed to set up.
            return null;
        }
    }

    /** Keeps one previous file, mirroring how the node rotates node.log. */
    private static void rotateIfLarge(Path logFile) throws IOException {
        if (Files.exists(logFile) && Files.size(logFile) > MAX_BYTES) {
            Files.move(logFile, logFile.resolveSibling("wallet.log.1"),
                    StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static OutputStream tee(PrintStream console, PrintStream file) {
        return new OutputStream() {
            @Override
            public void write(int b) {
                console.write(b);
                file.write(b);
            }

            @Override
            public void write(byte[] bytes, int off, int len) {
                console.write(bytes, off, len);
                file.write(bytes, off, len);
            }

            @Override
            public void flush() {
                console.flush();
                file.flush();
            }
        };
    }
}
