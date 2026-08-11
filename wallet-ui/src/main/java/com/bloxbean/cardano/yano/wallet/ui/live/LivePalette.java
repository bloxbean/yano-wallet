package com.bloxbean.cardano.yano.wallet.ui.live;

import com.bloxbean.cardano.yano.wallet.ui.util.ThemeManager;
import javafx.scene.paint.Color;

/**
 * Theme-aware colors for the canvas-drawn live visualization. A Canvas paints with
 * explicit {@link Color}s (not CSS variables), so these mirror the active theme's
 * accent/surface tokens and are re-read per frame (cheap) so a live theme switch
 * is reflected immediately.
 */
public final class LivePalette {
    private LivePalette() {
    }

    private static boolean light() {
        return "light".equals(ThemeManager.currentId());
    }

    public static Color accent() {
        return switch (ThemeManager.currentId()) {
            case "aurora" -> Color.web("#7c5cff");
            default -> Color.web("#2f6fed");
        };
    }

    public static Color accent2() {
        return switch (ThemeManager.currentId()) {
            case "aurora" -> Color.web("#d16bff");
            case "light" -> Color.web("#0f9fc0");
            default -> Color.web("#21c8de");
        };
    }

    public static Color text() {
        return light() ? Color.web("#1a2230") : Color.web("#e8edf4");
    }

    public static Color muted() {
        return light() ? Color.web("#5b6676") : Color.web("#8b98ab");
    }

    public static Color track() {
        return light() ? Color.web("#d3dae6") : Color.web("#232d3c");
    }

    public static Color pageTop() {
        return light() ? Color.web("#eef3fb") : Color.web("#0f1622");
    }

    public static Color pageBottom() {
        return light() ? Color.web("#e3ebf7") : Color.web("#0a0f18");
    }
}
