package com.bloxbean.cardano.yano.wallet.ui;

import com.bloxbean.cardano.yano.wallet.ui.contract.WalletUiController;
import com.bloxbean.cardano.yano.wallet.ui.screens.ConnectScreen;
import com.bloxbean.cardano.yano.wallet.ui.screens.OnboardingScreen;
import javafx.application.Application;
import javafx.scene.Scene;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;

import java.nio.file.Path;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * JavaFX entry point. The controller is injected by the assembly module
 * (wallet-app) BEFORE {@code Application.launch}; this class owns only view
 * composition — onboarding until a wallet is unlocked, then the shell.
 */
public class YanoWalletApplication extends Application {
    private static WalletUiController controller;
    private static Consumer<Scene> onSceneReady;
    private static String autoUnlockWalletId;
    private static char[] autoUnlockPassphrase;
    private static String autoNavigate;
    // Verification harness: the default 1200x800 cannot show a long screen, so a
    // capture of anything below the fold is impossible without this.
    private static int windowWidth = 1200;
    private static int windowHeight = 800;
    /**
     * Whether a saved connection reconnects on launch without asking.
     *
     * <p>Off by default. Auto-connecting made the Connect screen flash past on
     * every launch, which left a user who had picked the wrong network no
     * reliable moment to change it — and it started a local node (potentially a
     * long sync) as a side effect of opening the app rather than as something
     * the user asked for. Opt back in with --auto-connect.
     */
    private static boolean autoConnect = false;

    private final StackPane sceneRoot = new StackPane();
    private Shell shell;

    public static void setController(WalletUiController walletUiController) {
        controller = walletUiController;
    }

    /** Test/verification hook: runs once the scene is showing (e.g. screenshots). */
    public static void setOnSceneReady(Consumer<Scene> callback) {
        onSceneReady = callback;
    }

    /** Verification hook: unlock a wallet on startup and open the shell directly. */
    public static void setAutoUnlock(String walletId, char[] passphrase) {
        autoUnlockWalletId = walletId;
        autoUnlockPassphrase = passphrase;
    }

    /** Verification hook: navigate to a screen right after auto-unlock. */
    public static void setAutoNavigate(String screenName) {
        autoNavigate = screenName;
    }

    /** Verification hook: window size for screenshots of long screens. */
    public static void setWindowSize(int width, int height) {
        if (width > 0 && height > 0) {
            windowWidth = width;
            windowHeight = height;
        }
    }

    /**
     * When false, the Connect screen prefills the saved connection but waits for
     * the user instead of reconnecting on its own ({@code --no-auto-connect}).
     */
    public static void setAutoConnect(boolean enabled) {
        autoConnect = enabled;
    }

    @Override
    public void start(Stage stage) {
        Objects.requireNonNull(controller, "WalletUiController not set — call setController before launch");
        // Let screens open explorer links (tx hashes) in the user's browser.
        com.bloxbean.cardano.yano.wallet.ui.util.Ui.setHostServices(getHostServices());

        // Verification harness: auto-connect + auto-unlock straight to the shell.
        if (autoUnlockWalletId != null && autoUnlockPassphrase != null && controller.isConnected()) {
            char[] passphrase = autoUnlockPassphrase;
            autoUnlockPassphrase = null;
            showOnboarding();
            controller.unlock(autoUnlockWalletId, passphrase).whenComplete((wallet, error) ->
                    javafx.application.Platform.runLater(() -> {
                        if (error == null) {
                            showShell();
                        }
                    }));
        } else if (controller.isConnected()) {
            showOnboarding();
        } else {
            showConnect();
        }

        Scene scene = new Scene(sceneRoot, windowWidth, windowHeight);
        com.bloxbean.cardano.yano.wallet.ui.util.ThemeManager.install(scene);
        stage.setTitle("Yano Wallet");
        addAppIcons(stage);
        stage.setScene(scene);
        stage.setMinWidth(960);
        stage.setMinHeight(640);
        stage.show();

        // Start the CIP-30 dApp connector bridge; the prompt renders on the overlay.
        controller.startDappConnector(new FxCip30Prompt(sceneRoot));
        warnIfWeakConnectorTransport();

        if (onSceneReady != null) {
            onSceneReady.accept(scene);
        }
    }

    /**
     * Warns, on EVERY launch, while the connector is on the localhost WebSocket
     * (ADR-035).
     *
     * <p>Deliberately not a one-time notice. The WebSocket accepts a self-asserted
     * origin, so any program on this machine can present itself as a dApp; that is
     * a standing property of how the wallet is currently reachable, not an event
     * that happened once. A user who switched to unblock themselves should be
     * reminded it is still on — and offered the way back in the same breath —
     * rather than left to forget.
     */
    private void warnIfWeakConnectorTransport() {
        WalletUiController.ConnectorSettingsView connector;
        try {
            connector = controller.connectorSettings();
        } catch (RuntimeException e) {
            return; // never let a settings read stop the wallet opening
        }
        if (connector == null || !connector.weak()) {
            return;
        }
        javafx.application.Platform.runLater(() -> {
            javafx.scene.control.Alert alert = new javafx.scene.control.Alert(
                    javafx.scene.control.Alert.AlertType.WARNING);
            alert.setTitle("Browser connector");
            alert.setHeaderText("dApps reach this wallet over a localhost WebSocket");
            alert.setContentText("Port " + connector.wsPort() + " is open to any program on this "
                    + "computer, and the calling page's identity is self-asserted — the wallet cannot "
                    + "tell a real dApp from something else running locally. Native Messaging has your "
                    + "browser vouch for the extension instead.\n\n"
                    + "Every signature is still approved by you. Switch back in Settings → Browser "
                    + "connector once Native Messaging works.");
            alert.initOwner(sceneRoot.getScene() != null ? sceneRoot.getScene().getWindow() : null);
            alert.show();
        });
    }

    /** Taskbar / window icon (used on Windows and Linux; macOS uses the bundled .icns). */
    private void addAppIcons(Stage stage) {
        for (String resource : new String[]{"/icons/logo-256.png", "/icons/logo-512.png"}) {
            try (var in = getClass().getResourceAsStream(resource)) {
                if (in != null) {
                    stage.getIcons().add(new javafx.scene.image.Image(in));
                }
            } catch (Exception ignored) {
                // A missing icon must never block startup.
            }
        }
    }

    private void showConnect() {
        showConnect(autoConnect);
    }

    /**
     * @param auto reconnect to the saved node immediately. False when the user
     *             asked to change networks — auto-connecting would drag them
     *             straight back to the node they are trying to leave.
     */
    private void showConnect(boolean auto) {
        if (shell != null) {
            shell.dispose();
            shell = null;
        }
        ConnectScreen connect = new ConnectScreen(controller, sceneRoot, auto, info -> showOnboarding());
        sceneRoot.getChildren().setAll(connect.root());
    }

    private void showOnboarding() {
        showOnboarding(null);
    }

    /**
     * @param unlockTarget when non-null, opens straight at this wallet's unlock
     *                     step — the account switcher's re-open (ADR-037)
     */
    private void showOnboarding(WalletUiController.WalletItem unlockTarget) {
        if (shell != null) {
            shell.dispose();
            shell = null;
        }
        OnboardingScreen onboarding = new OnboardingScreen(controller, sceneRoot, wallet -> showShell(),
                () -> showConnect(false));
        sceneRoot.getChildren().setAll(onboarding.root());
        if (unlockTarget != null) {
            onboarding.showUnlockFor(unlockTarget);
        }
    }

    private void showShell() {
        shell = new Shell(controller, this::showOnboarding, this::showOnboarding,
                () -> showConnect(false));
        sceneRoot.getChildren().setAll(shell.root());
        if (autoNavigate != null) {
            shell.navigate(autoNavigate);
        }
    }

    /** Captures the current scene into an image (verification harness). */
    public static WritableImage snapshot(Scene scene) {
        return scene.snapshot(null);
    }
}
