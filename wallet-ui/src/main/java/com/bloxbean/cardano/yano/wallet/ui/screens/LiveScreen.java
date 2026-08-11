package com.bloxbean.cardano.yano.wallet.ui.screens;

import com.bloxbean.cardano.yano.wallet.ui.Shell;
import com.bloxbean.cardano.yano.wallet.ui.contract.WalletUiController;
import com.bloxbean.cardano.yano.wallet.ui.live.LiveBlocksView;
import com.bloxbean.cardano.yano.wallet.ui.live.LiveChainModel;
import com.bloxbean.cardano.yano.wallet.ui.live.LivePalette;
import com.bloxbean.cardano.yano.wallet.ui.util.Ui;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.Label;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.shape.ArcType;
import javafx.scene.shape.StrokeLineCap;
import javafx.scene.text.Font;
import javafx.scene.text.TextAlignment;

/**
 * The immersive "Live" page (ADR-033 live-chain feature, P3): big live tip stats,
 * an epoch-progress ring, and the animated block train. Reads the shared
 * {@link LiveChainModel} the shell keeps fed; the canvas animates itself and only
 * runs while this page is on screen and the window is focused.
 */
public class LiveScreen implements Shell.Screen {
    private final LiveChainModel model;
    private final LiveBlocksView blocks;
    private final VBox root;

    private final Label heightVal = statValue();
    private final Label slotVal = statValue();
    private final Label txVal = statValue();
    private final Label rateVal = statValue();
    private final Label ageVal = statValue();
    private final Label stateVal = statValue();
    private final Label treasuryVal = statValue();
    private final Label reservesVal = statValue();
    private final Label circulatingVal = statValue();
    private final Label activeStakeVal = statValue();
    private final Canvas ring = new Canvas(88, 88);
    private final Label waiting = new Label("Waiting for blocks…");
    private VBox supplyCard;

    public LiveScreen(WalletUiController controller, StackPane overlay, LiveChainModel model) {
        this.model = model;
        this.blocks = new LiveBlocksView(model, false);
        this.root = build();
        model.addListener(this::updateFromModel);
    }

    private VBox build() {
        Label title = new Label("Live");
        title.getStyleClass().add("screen-title");
        Label subtitle = Ui.muted("Your node's view of the chain, in real time.");

        HBox ringBox = new HBox(ring);
        ringBox.setAlignment(Pos.CENTER);
        VBox ringTile = new VBox(6, ringBox, caption("epoch"));
        ringTile.setAlignment(Pos.CENTER);

        FlowPane stats = new FlowPane(16, 16,
                ringTile,
                statTile(heightVal, "block height"),
                statTile(slotVal, "slot"),
                statTile(txVal, "txs in tip block"),
                statTile(rateVal, "throughput"),
                statTile(ageVal, "tip age"),
                statTile(stateVal, "state"));
        stats.setPrefWrapLength(760);
        VBox statsCard = Ui.card(null, stats);

        FlowPane supply = new FlowPane(16, 16,
                statTile(treasuryVal, "treasury"),
                statTile(reservesVal, "reserves"),
                statTile(circulatingVal, "circulating supply"),
                statTile(activeStakeVal, "active stake"));
        supply.setPrefWrapLength(760);
        supplyCard = Ui.card("Epoch & supply", supply);

        blocks.setMinHeight(180);
        waiting.getStyleClass().add("muted");
        StackPane trainStack = new StackPane(blocks, waiting);
        trainStack.setMinHeight(200);
        VBox trainCard = Ui.card("Recent blocks", trainStack);
        VBox.setVgrow(trainCard, Priority.ALWAYS);
        VBox.setVgrow(trainStack, Priority.ALWAYS);

        VBox column = new VBox(16, title, subtitle, statsCard, supplyCard, trainCard);
        column.setPadding(new Insets(24));
        // The Live page owns the animation while it is on screen (the canvas also
        // gates on window focus and scene attachment internally).
        blocks.setActive(true);
        updateFromModel();
        return column;
    }

    @Override
    public Node root() {
        return root;
    }

    @Override
    public void refresh() {
        blocks.setActive(true);
        updateFromModel();
    }

    @Override
    public void poll() {
        // Data is pushed by the shell's poller via the model listener; nothing to do.
    }

    private void updateFromModel() {
        WalletUiController.LiveChainView v = model.latest();
        boolean up = v.reachable();
        waiting.setVisible(up && model.recentBlocks().isEmpty());
        if (!up) {
            stateVal.setText("offline");
            waiting.setVisible(false);
            setVisible(supplyCard, false);
            drawRing(0, 0);
            return;
        }
        heightVal.setText(String.format("%,d", v.blockHeight()));
        slotVal.setText(String.format("%,d", v.slot()));
        txVal.setText(String.valueOf(v.txCount()));
        rateVal.setText(rate(model.blocksPerSec()));
        ageVal.setText(age(v.ageSeconds()));
        stateVal.setText(v.synced() ? "synced" : "syncing");
        drawRing(v.epochProgress(), v.epoch());

        boolean hasSupply = v.hasSupply();
        setVisible(supplyCard, hasSupply);
        if (hasSupply) {
            treasuryVal.setText(ada(v.treasuryLovelace()));
            reservesVal.setText(ada(v.reservesLovelace()));
            circulatingVal.setText(ada(v.circulatingLovelace()));
            activeStakeVal.setText(ada(v.activeStakeLovelace()));
        }
    }

    private static void setVisible(javafx.scene.Node node, boolean visible) {
        if (node != null) {
            node.setVisible(visible);
            node.setManaged(visible);
        }
    }

    /** Compact ADA from lovelace, e.g. "1.52B ₳" / "740.0M ₳"; "—" when unknown. */
    private static String ada(long lovelace) {
        if (lovelace <= 0) {
            return "—";
        }
        double ada = lovelace / 1_000_000.0;
        if (ada >= 1e9) {
            return String.format("%.2fB ₳", ada / 1e9);
        }
        if (ada >= 1e6) {
            return String.format("%.1fM ₳", ada / 1e6);
        }
        if (ada >= 1e3) {
            return String.format("%.1fK ₳", ada / 1e3);
        }
        return String.format("%.0f ₳", ada);
    }

    private void drawRing(double progress, long epoch) {
        GraphicsContext g = ring.getGraphicsContext2D();
        double s = ring.getWidth();
        double c = s / 2;
        double r = c - 9;
        g.clearRect(0, 0, s, s);
        g.setLineWidth(7);
        g.setStroke(LivePalette.track());
        g.strokeOval(c - r, c - r, 2 * r, 2 * r);
        if (progress > 0) {
            g.setStroke(LivePalette.accent());
            g.setLineCap(StrokeLineCap.ROUND);
            g.strokeArc(c - r, c - r, 2 * r, 2 * r, 90, -360 * progress, ArcType.OPEN);
        }
        g.setTextAlign(TextAlignment.CENTER);
        g.setFill(LivePalette.text());
        g.setFont(Font.font(17));
        g.fillText(epoch > 0 ? String.valueOf(epoch) : "—", c, c + 3);
        if (progress > 0) {
            g.setFill(LivePalette.muted());
            g.setFont(Font.font(9));
            g.fillText(Math.round(progress * 100) + "%", c, c + 18);
        }
    }

    private static String rate(double blocksPerSec) {
        if (blocksPerSec <= 0) {
            return "—";
        }
        return blocksPerSec >= 10
                ? String.format("%.0f blk/s", blocksPerSec)
                : String.format("%.1f blk/s", blocksPerSec);
    }

    private static String age(long seconds) {
        if (seconds <= 0) {
            return "just now";
        }
        return seconds < 90 ? seconds + "s ago" : (seconds / 60) + "m ago";
    }

    private static VBox statTile(Label value, String caption) {
        VBox tile = new VBox(4, value, caption(caption));
        tile.setAlignment(Pos.CENTER_LEFT);
        tile.setMinWidth(110);
        return tile;
    }

    private static Label statValue() {
        Label label = new Label("—");
        label.getStyleClass().add("stat-value");
        return label;
    }

    private static Label caption(String text) {
        Label label = new Label(text);
        label.getStyleClass().add("stat-caption");
        return label;
    }
}
