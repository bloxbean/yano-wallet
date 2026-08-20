package com.bloxbean.cardano.yano.wallet.app;

import com.bloxbean.cardano.yano.wallet.core.config.WalletConnectionConfig;
import com.bloxbean.cardano.yano.wallet.core.config.WalletNetwork;
import com.bloxbean.cardano.yano.wallet.ui.YanoWalletApplication;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.image.PixelReader;
import javafx.scene.image.WritableImage;

import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;

/**
 * Desktop wallet entry point (ADR-033 M3). Wires the wallet stack against a
 * local Yano node and launches the JavaFX UI.
 *
 * <pre>
 * Options:
 *   --network=devnet|preview|preprod|mainnet   (default preprod)
 *   --base-url=http://localhost:7070/api/v1/
 *   --data-dir=~/.yano-wallet
 *   --auto-connect                             reconnect to the saved node on launch
 *                                              (default: stop at the Connect screen)
 *   --enable-ws-connector                      opt into the legacy CIP-30 WebSocket
 *                                              (default: Native Messaging only)
 *   --screenshot=/path/out.png [--screenshot-delay-ms=3000]   verification harness
 *   --auto-unlock-wallet-id=… --auto-passphrase=…              verification harness
 * </pre>
 */
public final class YanoWalletApp {

    public static void main(String[] args) {
        Map<String, String> opts = parseOptions(args);
        Path dataDirRoot = Paths.get(expandHome(opts.getOrDefault("data-dir",
                System.getProperty("user.home") + "/.yano-wallet")));

        // --cip30-proxy <socket>: act as the CIP-30 Native Messaging host and
        // relay stdio to the wallet's local socket. Chrome launches this per dApp
        // connection. On a JVM build the installer points Chrome at
        // `java -cp cip30-proxy.jar …`; a native build has no JVM, so the wallet
        // binary hosts the same relay itself. Must run before any UI setup —
        // Chrome speaks a binary protocol on stdout and a stray log line corrupts it.
        if (opts.containsKey("cip30-proxy")) {
            com.bloxbean.cardano.yano.wallet.connector.proxy.Cip30NativeProxy
                    .main(new String[]{opts.get("cip30-proxy")});
            return;
        }

        // Before anything else can log: a packaged app has no console, so without
        // this a failure that happens before the node starts leaves no trace.
        WalletLog.install(dataDirRoot);

        // --hid-probe: enumerate USB HID devices and exit, without starting the
        // UI. Exists because hardware-wallet faults are otherwise reachable only
        // by clicking through the Connect dialog, which cannot be scripted — and
        // under a native image that made a JNA/JNI fault take a full rebuild plus
        // a manual click per attempt. Same binary, same code path, headless.
        if (opts.containsKey("hid-probe")) {
            System.exit(runHidProbe());
        }
        // --vault-probe=<walletId> --network=<net>: read the vault envelope and
        // report its second-factor status. Parsing happens BEFORE any decryption,
        // so this verifies a v4 (hardware-factored) vault is readable without
        // needing the passphrase or the key — which is exactly what broke when
        // the nested Slot type was unregistered.
        if (opts.containsKey("vault-probe")) {
            System.exit(runVaultProbe(dataDirRoot, opts));
        }
        // --utxo-probe=<address> --base-url=<url>: fetch UTxOs through the same
        // CCL supplier the balance screen uses. Native images fail on unregistered
        // DTO constructors only when a response is NON-EMPTY, so an empty-wallet
        // test passes while a funded one does not — this makes that verifiable.
        if (opts.containsKey("utxo-probe")) {
            System.exit(runUtxoProbe(opts));
        }

        // The controller resolves its node connection lazily via the manager;
        // the UI's Connect screen (or the CLI pre-seed below) drives it.
        WalletBackendManager backendManager = new WalletBackendManager(dataDirRoot);
        Runtime.getRuntime().addShutdownHook(new Thread(backendManager::close, "wallet-shutdown"));
        DefaultWalletUiController controller = new DefaultWalletUiController(backendManager);
        // CIP-30 transport: Native Messaging is the default (ADR-035 M5). The
        // legacy localhost WebSocket is opt-in — enable with --enable-ws-connector
        // (or -Dyano.connector.ws=true) so a dApp can only reach the wallet over
        // the browser-brokered native host otherwise.
        controller.setWsConnectorEnabled(wsConnectorEnabled(opts));
        // --node/--network below PERSIST the choice, so every later launch replays
        // it — but a saved connection no longer reconnects on its own. Launching
        // stops at the Connect screen with the choice prefilled, so nothing
        // starts a node until the user asks. --auto-connect restores the old
        // one-click behaviour; --no-auto-connect is now the default and kept so
        // existing scripts keep working.
        if (opts.containsKey("auto-connect") && !opts.containsKey("no-auto-connect")) {
            YanoWalletApplication.setAutoConnect(true);
        }
        YanoWalletApplication.setController(controller);

        String screenshot = opts.get("screenshot");
        boolean screenshotRun = screenshot != null;

        // CLI pre-seed (verification / power users): --node=managed|external,
        // --network=…, --base-url=… (external), --managed-port=N.
        String nodeMode = opts.get("node");
        if (nodeMode != null && opts.containsKey("network")) {
            WalletNetwork network = WalletNetwork.fromId(opts.get("network"));
            WalletConnectionConfig config;
            if ("external".equalsIgnoreCase(nodeMode)) {
                config = WalletConnectionConfig.external(network,
                        opts.getOrDefault("base-url", "http://localhost:7070/api/v1/"));
            } else if (opts.containsKey("managed-port")) {
                // Pin the managed node's REST port (e.g. to hit the devnet faucet).
                config = WalletConnectionConfig.managed(network, Integer.parseInt(opts.get("managed-port")));
            } else {
                config = WalletConnectionConfig.managed(network);
            }
            if (screenshotRun) {
                // The headless capture path needs the backend live before it
                // navigates/snapshots, so connect synchronously here.
                try {
                    backendManager.connect(config);
                } catch (RuntimeException e) {
                    System.err.println("Pre-seed connect failed, opening Connect screen: " + e.getMessage());
                }
            } else {
                // Interactive path: only persist the choice. Launching a managed
                // node (and, on a public network, building the wallet index) can
                // take minutes; doing it here would block the window from opening.
                // The Connect screen shows immediately and reconnects async.
                // NOTE: this persists — later launches WITHOUT these flags replay
                // it. Use --no-auto-connect (or Settings -> Change network) to pick
                // a different one.
                backendManager.saveConfig(config);
            }
        }

        if (screenshotRun) {
            long delayMs = Long.parseLong(opts.getOrDefault("screenshot-delay-ms", "4000"));
            String autoWalletId = opts.get("auto-unlock-wallet-id");
            String autoPassphrase = opts.get("auto-passphrase");
            if (autoWalletId != null && autoPassphrase != null) {
                YanoWalletApplication.setAutoUnlock(autoWalletId, autoPassphrase.toCharArray());
            }
            String windowSize = opts.get("window-size");
            if (windowSize != null && windowSize.contains("x")) {
                String[] wh = windowSize.split("x", 2);
                YanoWalletApplication.setWindowSize(
                        Integer.parseInt(wh[0].trim()), Integer.parseInt(wh[1].trim()));
            }
            if (opts.containsKey("demo-prompt")) {
                YanoWalletApplication.setDemoPrompt(opts.getOrDefault("demo-prompt", "incomplete"));
            }
            String autoScreen = opts.get("screen");
            if (autoScreen != null) {
                YanoWalletApplication.setAutoNavigate(autoScreen);
            }
            // Drives the controller's send path (same code the Send screen calls)
            // so the money cycle is verifiable headlessly: --auto-send=addr,unit,amount
            String autoSend = opts.get("auto-send");
            if (autoSend != null) {
                String[] parts = autoSend.split(",", 3);
                String unit = parts.length == 3 ? parts[1] : "lovelace";
                String amount = parts.length == 3 ? parts[2] : parts[1];
                new Thread(() -> {
                    try {
                        Thread.sleep(3000); // wait for auto-unlock
                        var draft = controller.draftSend(parts[0], unit, amount, "yano-wallet-ui-e2e").get();
                        var submit = controller.confirmDraft(draft.draftId()).get();
                        System.out.println("AUTO_SEND_SUBMITTED " + submit.txHash());
                    } catch (Exception e) {
                        System.err.println("AUTO_SEND_FAILED " + e);
                    }
                }, "auto-send").start();
            }
            YanoWalletApplication.setOnSceneReady(scene -> {
                Thread capture = new Thread(() -> {
                    try {
                        Thread.sleep(delayMs);
                        Platform.runLater(() -> {
                            try {
                                // A screen taller than the window cannot be
                                // verified otherwise: the snapshot only contains
                                // what is rendered.
                                if ("bottom".equalsIgnoreCase(opts.getOrDefault("scroll", ""))) {
                                    var node = scene.lookup(".screen-scroll");
                                    if (node instanceof javafx.scene.control.ScrollPane pane) {
                                        pane.setVvalue(1.0);
                                        pane.layout();
                                    }
                                }
                                writePng(scene.snapshot(null), new File(screenshot));
                                System.out.println("SCREENSHOT_WRITTEN " + screenshot);
                            } catch (Exception e) {
                                System.err.println("Screenshot failed: " + e);
                            } finally {
                                Platform.exit();
                            }
                        });
                    } catch (InterruptedException ignored) {
                        Thread.currentThread().interrupt();
                    }
                }, "screenshot");
                capture.setDaemon(true);
                capture.start();
            });
        }

        Application.launch(YanoWalletApplication.class);
    }

    /** WritableImage → PNG without javafx-swing (manual ARGB copy). */
    private static void writePng(WritableImage image, File out) throws Exception {
        int width = (int) image.getWidth();
        int height = (int) image.getHeight();
        PixelReader reader = image.getPixelReader();
        BufferedImage buffered = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                buffered.setRGB(x, y, reader.getArgb(x, y));
            }
        }
        javax.imageio.ImageIO.write(buffered, "png", out);
    }

    /**
     * The legacy WebSocket CIP-30 transport is off unless explicitly enabled,
     * via {@code --enable-ws-connector} (bare or {@code =true}) or the
     * {@code yano.connector.ws} system property (for packaged/gradle launches).
     */
    private static boolean wsConnectorEnabled(Map<String, String> opts) {
        if (opts.containsKey("enable-ws-connector")) {
            return !"false".equalsIgnoreCase(opts.get("enable-ws-connector"));
        }
        return Boolean.getBoolean("yano.connector.ws");
    }

    /**
     * Expand a leading {@code ~} / {@code ~/} to the user's home dir. Shells do
     * not expand {@code ~} inside {@code --args="--data-dir=~/.yano-wallet"}
     * (it is quoted and not at word start), and the app must not then create a
     * literal {@code ./~} directory. {@code $HOME}/absolute paths pass through.
     */
    private static String expandHome(String path) {
        if (path.equals("~")) {
            return System.getProperty("user.home");
        }
        if (path.startsWith("~/") || path.startsWith("~" + File.separator)) {
            return System.getProperty("user.home") + path.substring(1);
        }
        return path;
    }

    /** Reads a vault envelope and reports its factor status (no passphrase needed). */
    private static int runVaultProbe(java.nio.file.Path dataDirRoot, Map<String, String> opts) {
        try {
            var network = com.bloxbean.cardano.yano.wallet.core.config.WalletNetwork
                    .fromId(opts.getOrDefault("network", "preprod"));
            var repo = new com.bloxbean.cardano.yano.wallet.core.wallet.FileStoredWalletRepository(
                    dataDirRoot.resolve(network.id()), network);
            String id = opts.get("vault-probe");
            var factors = repo.walletFactors(id);
            System.out.println("VAULT-PROBE OK: " + id + " factors=" + factors);
            return 0;
        } catch (Throwable t) {
            System.out.println("VAULT-PROBE FAILED: " + t);
            t.printStackTrace();
            return 1;
        }
    }

    /** Fetches UTxOs for an address through the same CCL supplier the UI uses. */
    private static int runUtxoProbe(Map<String, String> opts) {
        try {
            var network = com.bloxbean.cardano.yano.wallet.core.config.WalletNetwork
                    .fromId(opts.getOrDefault("network", "preprod"));
            var backend = com.bloxbean.cardano.yano.wallet.nodeclient.YanoNodeBackend
                    .connect(network, opts.get("base-url"));
            var utxos = backend.utxoSupplier().getAll(opts.get("utxo-probe"));
            System.out.println("UTXO-PROBE OK: " + utxos.size() + " utxo(s)");
            utxos.stream().limit(2).forEach(u -> System.out.println("  " + u));
            return 0;
        } catch (Throwable t) {
            System.out.println("UTXO-PROBE FAILED: " + t);
            t.printStackTrace();
            return 1;
        }
    }

    /** Enumerates HID devices through the same service the Connect dialog uses. */
    private static int runHidProbe() {
        try {
            var service = new com.bloxbean.cardano.yano.wallet.hardware.ledger
                    .LedgerHardwareWalletService();
            var devices = service.enumerate();
            System.out.println("HID-PROBE: enumerated " + devices.size() + " device(s)");
            devices.forEach(d -> System.out.println("  " + d));
            if (devices.isEmpty()) {
                System.out.println("HID-PROBE OK (no device attached; open not exercised)");
                return 0;
            }
            // Enumeration alone does NOT open the device, and opening is where JNA
            // maps HidDeviceStructure — a different set of reflective field
            // accesses. Probing only enumerate() reports success while Connect
            // still fails, so go all the way to talking to the device.
            var version = service.getCardanoAppVersion(devices.get(0));
            System.out.println("HID-PROBE OK: opened device, Cardano app version " + version);
            return 0;
        } catch (Throwable t) {
            System.out.println("HID-PROBE FAILED: " + t);
            t.printStackTrace();
            return 1;
        }
    }

    private static Map<String, String> parseOptions(String[] args) {
        Map<String, String> opts = new HashMap<>();
        for (String arg : args) {
            if (arg.startsWith("--")) {
                int eq = arg.indexOf('=');
                if (eq > 0) {
                    opts.put(arg.substring(2, eq), arg.substring(eq + 1));
                } else {
                    opts.put(arg.substring(2), "");
                }
            }
        }
        return opts;
    }

    private YanoWalletApp() {
    }
}
