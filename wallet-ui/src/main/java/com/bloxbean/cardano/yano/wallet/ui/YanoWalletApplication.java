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
    private static boolean autoConnect = true;

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

        Scene scene = new Scene(sceneRoot, 1200, 800);
        com.bloxbean.cardano.yano.wallet.ui.util.ThemeManager.install(scene);
        stage.setTitle("Yano Wallet");
        addAppIcons(stage);
        stage.setScene(scene);
        stage.setMinWidth(960);
        stage.setMinHeight(640);
        stage.show();

        // Start the CIP-30 dApp connector bridge; the prompt renders on the overlay.
        controller.startDappConnector(new FxCip30Prompt(sceneRoot));

        if (onSceneReady != null) {
            onSceneReady.accept(scene);
        }
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
        OnboardingScreen onboarding = new OnboardingScreen(controller, sceneRoot, wallet -> showShell());
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
