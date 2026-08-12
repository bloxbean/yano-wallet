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

        // Before anything else can log: a packaged app has no console, so without
        // this a failure that happens before the node starts leaves no trace.
        WalletLog.install(dataDirRoot);

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
