package com.bloxbean.cardano.yano.wallet.ui.live;

import javafx.animation.AnimationTimer;
import javafx.beans.value.ChangeListener;
import javafx.scene.Scene;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.layout.Region;
import javafx.scene.paint.Color;
import javafx.scene.paint.CycleMethod;
import javafx.scene.effect.DropShadow;
import javafx.scene.paint.LinearGradient;
import javafx.scene.paint.Stop;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.stage.Window;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A Canvas that animates the chain tip as a train of block tiles sliding in from
 * the right — newest at the leading edge, older easing left, each tile brightening
 * with its transaction count.
 *
 * <p>The {@code ambient} mode (dim, mouse-transparent, drawn behind every
 * screen) is no longer reachable: the wallet dropped the animated background
 * setting it existed for. The flag is kept because unpicking it means editing
 * the drawing code the Live page depends on, for no user-visible gain.
 */
public final class LiveBlocksView extends Region {
    private static final double TILE_W = 104;    // full-mode block card (px)
    private static final double TILE_H = 62;
    private static final double AMBIENT_TILE = 26;
    private static final double GAP = 12;
    private static final double ENTER_MS = 550;  // tile fade/slide-in duration

    private final LiveChainModel model;
    private final boolean ambient;
    private final Canvas canvas = new Canvas();
    private final AnimationTimer timer;
    private final Map<Long, Double> tileX = new HashMap<>();
    private final ChangeListener<Boolean> focusListener = (obs, was, focused) -> updateRunning();

    private boolean active;
    private long lastFrameNanos;
    private Window hookedWindow;

    public LiveBlocksView(LiveChainModel model, boolean ambient) {
        this.model = model;
        this.ambient = ambient;
        setMinSize(0, 0);
        setPrefSize(Region.USE_COMPUTED_SIZE, Region.USE_COMPUTED_SIZE);
        getChildren().add(canvas);
        // Unmanaged + size bound to the region: the canvas fills its parent without
        // feeding back into the region's preferred-size computation.
        canvas.setManaged(false);
        canvas.widthProperty().bind(widthProperty());
        canvas.heightProperty().bind(heightProperty());
        canvas.setMouseTransparent(true);
        setMouseTransparent(ambient);
        widthProperty().addListener((o, a, b) -> draw());
        heightProperty().addListener((o, a, b) -> draw());

        timer = new AnimationTimer() {
            @Override
            public void handle(long now) {
                double dt = lastFrameNanos == 0 ? 0 : Math.min(0.05, (now - lastFrameNanos) / 1e9);
                lastFrameNanos = now;
                step(dt, now);
                draw();
            }
        };
        sceneProperty().addListener((o, oldScene, newScene) -> onSceneChanged(newScene));
    }

    /** External gate: whether the Live page is on screen. */
    public void setActive(boolean on) {
        if (on == active) {
            return;
        }
        active = on;
        if (on) {
            hookFocus();
        } else {
            unhookFocus();
        }
        updateRunning();
    }

    /** Stop and detach all listeners (call from the owner's dispose/teardown). */
    public void dispose() {
        active = false;
        timer.stop();
        unhookFocus();
    }

    private void onSceneChanged(Scene scene) {
        if (scene == null) {
            unhookFocus();
            timer.stop();
        } else if (active) {
            hookFocus();
            updateRunning();
        }
    }

    private void hookFocus() {
        Scene scene = getScene();
        Window window = scene != null ? scene.getWindow() : null;
        if (window != hookedWindow) {
            unhookFocus();
            if (window != null) {
                window.focusedProperty().addListener(focusListener);
                hookedWindow = window;
            }
        }
    }

    private void unhookFocus() {
        if (hookedWindow != null) {
            hookedWindow.focusedProperty().removeListener(focusListener);
            hookedWindow = null;
        }
    }

    private void updateRunning() {
        boolean focused = hookedWindow == null || hookedWindow.isFocused();
        if (active && getScene() != null && focused) {
            lastFrameNanos = 0;
            timer.start();
        } else {
            timer.stop();
            draw(); // leave a clean static frame
        }
    }

    private double tileW() {
        return ambient ? AMBIENT_TILE : TILE_W;
    }

    private double tileH() {
        return ambient ? AMBIENT_TILE : TILE_H;
    }

    /** Ease each tile toward its target slot; prune tiles no longer present. */
    private void step(double dt, long nowNanos) {
        List<LiveChainModel.Block> blocks = model.recentBlocks();
        double stride = tileW() + GAP;
        double right = getWidth() - (ambient ? 24 : 32) - tileW();
        int n = blocks.size();
        double lerp = Math.min(1, dt * 9);
        java.util.Set<Long> present = new java.util.HashSet<>();
        for (int i = 0; i < n; i++) {
            long height = blocks.get(i).height();
            present.add(height);
            double target = right - (n - 1 - i) * stride;
            double current = tileX.getOrDefault(height, target + stride); // new tiles enter from the right
            tileX.put(height, current + (target - current) * lerp);
        }
        tileX.keySet().removeIf(h -> !present.contains(h));
    }

    private void draw() {
        double w = getWidth();
        double h = getHeight();
        if (w <= 0 || h <= 0) {
            return;
        }
        GraphicsContext g = canvas.getGraphicsContext2D();
        g.clearRect(0, 0, w, h);

        if (!ambient) {
            g.setFill(new LinearGradient(0, 0, 0, 1, true, CycleMethod.NO_CYCLE,
                    new Stop(0, LivePalette.pageTop()), new Stop(1, LivePalette.pageBottom())));
            g.fillRect(0, 0, w, h);
        }

        List<LiveChainModel.Block> blocks = model.recentBlocks();
        if (blocks.isEmpty() || !model.latest().reachable()) {
            return;
        }
        double tw = tileW();
        double th = tileH();
        double y = (h - th) / 2;
        Color accent = LivePalette.accent();
        Color accent2 = LivePalette.accent2();
        long now = System.nanoTime();
        long newest = blocks.get(blocks.size() - 1).height();

        for (LiveChainModel.Block block : blocks) {
            Double x = tileX.get(block.height());
            if (x == null || x < -tw || x > w + tw) {
                continue;
            }
            double enter = Math.min(1, (now - block.bornNanos()) / 1e6 / ENTER_MS);
            double intensity = Math.min(1, block.txCount() / 18.0);
            Color color = accent.interpolate(accent2, intensity);
            double drop = (1 - enter) * 10;
            if (ambient) {
                g.setGlobalAlpha(0.34 * (0.35 + 0.65 * enter));
                g.setFill(color);
                g.fillRoundRect(x, y + drop, tw, th, 7, 7);
            } else {
                drawBlockCard(g, x, y + drop, tw, th, block, color, enter, block.height() == newest);
            }
        }
        g.setGlobalAlpha(1);
        g.setEffect(null);
    }

    /** A vibrant block card: gradient fill, sheen, glow on arrival, block # + tx count. */
    private void drawBlockCard(GraphicsContext g, double x, double y, double tw, double th,
                               LiveChainModel.Block block, Color color, double enter, boolean newest) {
        double alpha = 0.4 + 0.6 * enter;
        Color top = color.deriveColor(0, 1, 1.18, 1);
        Color bottom = color.deriveColor(0, 1, 0.68, 1);

        if (newest && enter < 1) {
            g.setEffect(new DropShadow(8 + 22 * (1 - enter), color.brighter()));
        }
        g.setGlobalAlpha(0.96 * alpha);
        g.setFill(new LinearGradient(0, 0, 0, 1, true, CycleMethod.NO_CYCLE,
                new Stop(0, top), new Stop(1, bottom)));
        g.fillRoundRect(x, y, tw, th, 12, 12);
        g.setEffect(null);

        g.setGlobalAlpha(0.35 * alpha);
        g.setStroke(Color.WHITE);
        g.setLineWidth(1);
        g.strokeLine(x + 11, y + 7, x + tw - 11, y + 7);

        g.setGlobalAlpha(0.9 * alpha);
        g.setStroke(top.brighter());
        g.setLineWidth(1.4);
        g.strokeRoundRect(x, y, tw, th, 12, 12);

        g.setGlobalAlpha(0.85 * alpha);
        g.setFill(Color.WHITE);
        g.setFont(Font.font("monospaced", 11));
        g.fillText("#" + String.format("%,d", block.height()), x + 11, y + 19);
        g.setGlobalAlpha(alpha);
        g.setFont(Font.font(null, FontWeight.BOLD, 16));
        g.fillText(block.txCount() + " tx", x + 11, y + th - 13);
    }
}
