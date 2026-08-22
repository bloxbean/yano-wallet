package com.bloxbean.cardano.yano.wallet.ui.screens;

import com.bloxbean.cardano.yano.wallet.ui.BuildInfo;
import com.bloxbean.cardano.yano.wallet.ui.Shell;
import com.bloxbean.cardano.yano.wallet.ui.util.Icons;
import com.bloxbean.cardano.yano.wallet.ui.util.Ui;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

/**
 * Which build this is.
 *
 * <p>A top-level screen rather than a card inside Settings: the first question
 * on any bug report is "which version?", and someone answering it should not
 * have to know that versions live under Settings.
 *
 * <p>Every value comes from {@link BuildInfo}, generated from gradle.properties
 * at build time, so this cannot drift from the release it shipped in.
 */
public class AboutScreen implements Shell.Screen {
    private static final String REPO = "https://github.com/bloxbean/yano-wallet";

    private final StackPane overlay;
    private final ScrollPane root;

    public AboutScreen(StackPane overlay) {
        this.overlay = overlay;
        this.root = build();
    }

    private ScrollPane build() {
        Label title = new Label("About");
        title.getStyleClass().add("screen-title");

        VBox versionCard = Ui.card("Version",
                mono("Yano Wallet " + BuildInfo.WALLET_VERSION),
                mono("Managed node: Yano " + BuildInfo.NODE_VERSION),
                mono("Runtime: " + runtimeDescription()),
                mono("Platform: " + platformDescription()),
                Ui.muted("Include these details in a bug report — they identify the exact "
                        + "build, which nothing else in the app can tell you."),
                copyButton());

        Hyperlink repo = new Hyperlink(REPO);
        repo.setOnAction(e -> {
            Ui.copyToClipboard(REPO);
            Ui.toast(overlay, "Link copied", false);
        });

        VBox projectCard = Ui.card("Project",
                Ui.muted("A full-node Cardano wallet: it runs its own Yano node, so balances "
                        + "and history come from a chain you validated yourself."),
                repo,
                Ui.muted("MIT licensed. Under active development and not audited — use test "
                        + "networks, and never a recovery phrase holding real funds."));

        VBox column = new VBox(16, title, versionCard, projectCard);
        column.setPadding(new Insets(24));
        column.setMaxWidth(860);

        ScrollPane scroll = new ScrollPane(column);
        scroll.setFitToWidth(true);
        scroll.getStyleClass().add("screen-scroll");
        return scroll;
    }

    private Button copyButton() {
        Button copy = new Button("Copy version details", Icons.icon(Icons.COPY, 14, "nav-icon"));
        copy.getStyleClass().add("primary-button");
        copy.setOnAction(e -> {
            Ui.copyToClipboard(report());
            Ui.toast(overlay, "Version details copied", false);
        });
        return copy;
    }

    private static Label mono(String text) {
        Label label = new Label(text);
        label.getStyleClass().add("mono");
        label.setWrapText(true);
        return label;
    }

    /**
     * A native image has no JVM to report, and printing a Java version there
     * would send a bug report down the wrong path — several native-image
     * failures reproduce only in that build.
     */
    private static String runtimeDescription() {
        if (System.getProperty("org.graalvm.nativeimage.imagecode") != null) {
            return "native image (no JVM)";
        }
        return "Java " + Runtime.version().feature() + " (" + System.getProperty("java.vendor") + ")";
    }

    private static String platformDescription() {
        return System.getProperty("os.name") + " " + System.getProperty("os.version")
                + " (" + System.getProperty("os.arch") + ")";
    }

    private static String report() {
        return "Yano Wallet " + BuildInfo.WALLET_VERSION
                + "\nManaged node: Yano " + BuildInfo.NODE_VERSION
                + "\nRuntime: " + runtimeDescription()
                + "\nPlatform: " + platformDescription();
    }

    @Override
    public Node root() {
        return root;
    }

    @Override
    public void refresh() {
        // Build facts are fixed for the life of the process.
    }
}
