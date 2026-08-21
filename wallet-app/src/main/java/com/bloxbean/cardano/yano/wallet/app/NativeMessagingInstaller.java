package com.bloxbean.cardano.yano.wallet.app;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Locale;
import java.util.Set;

/**
 * Installs the Chrome Native Messaging host for the CIP-30 connector
 * (ADR-035 M5): the proxy jar + launcher script under {@code ~/.yano}, and the
 * host manifest into each installed Chromium-family browser. After this,
 * Chrome can launch the proxy itself and no localhost port is involved.
 *
 * <p>Everything is derived from the running app: the launcher pins the same
 * Java runtime that runs the wallet ({@code java.home} — the bundled runtime in
 * a packaged install), and the socket path matches what the wallet listens on.
 */
final class NativeMessagingInstaller {

    /** Chrome-side host name; must match the extension's connectNative call. */
    static final String HOST_NAME = "com.bloxbean.yano.cip30";

    /** The extension id pinned by the "key" in the extension's manifest.json. */
    static final String EXTENSION_ID = "bjnkcmbkjaebecgllkgbeapbjcknnedn";

    private final Path yanoDir;
    /**
     * Where the browser keeps its NativeMessagingHosts directories — the real
     * user home, NOT anything derived from the data directory.
     *
     * <p>Kept separate deliberately. Connector files follow {@code --data-dir},
     * but browser registration is machine-level: one manifest per browser,
     * whatever data directory installed it. Deriving this from the connector
     * path (as this once did, via {@code yanoDir.getParent()}) silently wrote
     * manifests to {@code <dataDir>/Library/Application Support/...} once the
     * connector moved out of {@code ~/.yano}.
     */
    private final Path browserHome;

    NativeMessagingInstaller() {
        this(Path.of(System.getProperty("user.home"), ".yano-wallet"));
    }

    /**
     * @param dataDir the wallet's data directory (default {@code ~/.yano-wallet},
     *                or whatever {@code --data-dir} selected). Connector files
     *                live under {@code <dataDir>/connector}.
     *
     * <p>These used to sit in a hardcoded {@code ~/.yano}, which meant
     * {@code --data-dir} did not isolate them: two wallets with separate data
     * directories contended for one socket, and a dApp reached whichever bound
     * it rather than the instance the user was looking at. It also squatted the
     * obvious directory name for the Yano <em>node</em>.
     */
    NativeMessagingInstaller(Path dataDir) {
        this(dataDir, Path.of(System.getProperty("user.home")));
    }

    /** @param browserHome overridden by tests; production always uses the real home. */
    NativeMessagingInstaller(Path dataDir, Path browserHome) {
        this.yanoDir = dataDir.resolve("connector");
        this.browserHome = browserHome;
    }

    /** Where the wallet's native-messaging socket lives — shared with the server. */
    Path socketPath() {
        return yanoDir.resolve("cip30.sock");
    }

    /** Installs everything; returns a human-readable summary for the UI. */
    String install() throws IOException {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("win")) {
            throw new IOException("Native Messaging setup on Windows isn't automated yet — "
                    + "it needs a registry entry. Coming with the installer work.");
        }
        Files.createDirectories(yanoDir);
        Path proxyJar = writeProxyJar();
        Path script = writeLauncherScript(proxyJar);
        List<Path> manifests = writeBrowserManifests(script);
        if (manifests.isEmpty()) {
            throw new IOException("No Chromium-family browser profile found to register the host with.");
        }
        StringBuilder summary = new StringBuilder("Native Messaging host installed. Registered with: ");
        for (int i = 0; i < manifests.size(); i++) {
            if (i > 0) {
                summary.append(", ");
            }
            summary.append(browserLabel(manifests.get(i)));
        }
        summary.append(". Restart the browser to pick it up.");
        return summary.toString();
    }

    private Path writeProxyJar() throws IOException {
        Path target = yanoDir.resolve("cip30-proxy.jar");
        try (InputStream in = NativeMessagingInstaller.class.getResourceAsStream("/native-host/cip30-proxy.jar")) {
            if (in == null) {
                // A native build does not need it: the wallet binary hosts the
                // relay itself. Only a JVM build genuinely requires the jar.
                if (nativeImageBinary().isPresent()) {
                    return target;
                }
                throw new IOException("Bundled proxy jar missing from the app build");
            }
            Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
        }
        return target;
    }

    private Path writeLauncherScript(Path proxyJar) throws IOException {
        Path script = yanoDir.resolve("cip30-host.sh");
        String content = nativeImageBinary()
                .map(this::nativeLauncher)
                .orElseGet(() -> jvmLauncher(proxyJar));
        Files.writeString(script, content);
        Files.setPosixFilePermissions(script, Set.of(
                PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE));
        return script;
    }

    /**
     * The wallet's own executable when running as a GraalVM native image, else
     * empty.
     *
     * <p>{@code java.home} is null in a native image, which is how this used to
     * fail: {@code Path.of(null, "bin", "java")} threw an NPE that surfaced as
     * "Install failed: null". More fundamentally there is no JVM to run the
     * bundled proxy jar with, so the native build hosts the relay itself via
     * {@code --cip30-proxy}.
     */
    private static Optional<Path> nativeImageBinary() {
        if (System.getProperty("java.home") != null) {
            return Optional.empty();   // ordinary JVM run — use the bundled jar
        }
        return ProcessHandle.current().info().command().map(Path::of);
    }

    private String nativeLauncher(Path walletBinary) {
        return """
                #!/bin/sh
                # Yano CIP-30 Native Messaging host — written by the Yano wallet.
                # Chrome launches this per dApp connection; it relays stdio to the
                # wallet's local socket. This is the NATIVE build, so the wallet
                # binary hosts the relay itself — there is no JVM to run a jar with.
                # Re-run 'Install browser connector' after moving or updating the app.
                exec "%s" --cip30-proxy="%s" "$@"
                """.formatted(walletBinary, socketPath());
    }

    private String jvmLauncher(Path proxyJar) {
        Path java = Path.of(System.getProperty("java.home"), "bin", "java");
        return """
                #!/bin/sh
                # Yano CIP-30 Native Messaging host — written by the Yano wallet.
                # Chrome launches this per dApp connection; it relays stdio to the
                # wallet's local socket. Re-run 'Install browser connector' in the
                # wallet's Settings after moving or updating the app.
                exec "%s" -cp "%s" com.bloxbean.cardano.yano.wallet.connector.proxy.Cip30NativeProxy "%s" "$@"
                """.formatted(java, proxyJar, socketPath());
    }

    private List<Path> writeBrowserManifests(Path script) throws IOException {
        String manifest = """
                {
                  "name": "%s",
                  "description": "Yano Wallet CIP-30 connector",
                  "path": "%s",
                  "type": "stdio",
                  "allowed_origins": ["chrome-extension://%s/"]
                }
                """.formatted(HOST_NAME, script, EXTENSION_ID);

        List<Path> written = new ArrayList<>();
        for (Path dir : browserHostDirs()) {
            // Chrome's own dir is created if needed (the browser reads it on
            // start); other browsers only if they are actually installed.
            boolean isPrimary = dir.toString().contains("Google/Chrome")
                    || dir.toString().contains("google-chrome");
            if (!isPrimary && !Files.isDirectory(dir.getParent())) {
                continue;
            }
            Files.createDirectories(dir);
            Path file = dir.resolve(HOST_NAME + ".json");
            Files.writeString(file, manifest);
            written.add(file);
        }
        return written;
    }

    private List<Path> browserHostDirs() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        Path home = browserHome;
        if (os.contains("mac")) {
            Path support = home.resolve("Library/Application Support");
            return List.of(
                    support.resolve("Google/Chrome/NativeMessagingHosts"),
                    support.resolve("Chromium/NativeMessagingHosts"),
                    support.resolve("Microsoft Edge/NativeMessagingHosts"),
                    support.resolve("BraveSoftware/Brave-Browser/NativeMessagingHosts"));
        }
        Path config = home.resolve(".config");
        return List.of(
                config.resolve("google-chrome/NativeMessagingHosts"),
                config.resolve("chromium/NativeMessagingHosts"),
                config.resolve("microsoft-edge/NativeMessagingHosts"),
                config.resolve("BraveSoftware/Brave-Browser/NativeMessagingHosts"));
    }

    private static String browserLabel(Path manifest) {
        String path = manifest.toString();
        if (path.contains("Chrome") || path.contains("google-chrome")) {
            return "Chrome";
        }
        if (path.contains("Edge") || path.contains("edge")) {
            return "Edge";
        }
        if (path.contains("Brave")) {
            return "Brave";
        }
        return "Chromium";
    }
}
