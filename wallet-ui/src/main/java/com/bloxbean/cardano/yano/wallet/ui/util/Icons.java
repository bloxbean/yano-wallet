package com.bloxbean.cardano.yano.wallet.ui.util;

import javafx.scene.layout.Region;
import javafx.scene.shape.SVGPath;

/** Inline SVG icons (24x24 grid) — no external assets, native-image friendly. */
public final class Icons {
    private Icons() {
    }

    public static final String DASHBOARD = "M3 3h8v10H3V3zm10 0h8v6h-8V3zM3 15h8v6H3v-6zm10-4h8v10h-8V11z";
    public static final String SEND = "M4 20l16-8L4 4v6l10 2-10 2v6z";
    // Rendered as a filled shape (Region.setShape), so it must be closed/solid —
    // an open-stroke outline has zero fill area and shows nothing. Solid download
    // arrow into a tray.
    public static final String RECEIVE = "M10 3h4v8h4l-6 7-6-7h4z M4 20h16v2H4z";
    public static final String HISTORY = "M12 8v5l3.5 2M12 3a9 9 0 1 0 9 9 9 9 0 0 0-9-9z";
    public static final String STAKING = "M12 2l9 5-9 5-9-5 9-5zm-9 10l9 5 9-5M3 17l9 5 9-5";
    public static final String GOVERNANCE = "M5 4h14v16H5V4zm3 7l2.5 2.5L16 8";
    public static final String PROPOSALS = "M7 3h10l2 4v14H5V7l2-4zm-1 6h12M9 13h6M9 17h4";
    public static final String SETTINGS = "M12 8a4 4 0 1 0 4 4 4 4 0 0 0-4-4zm9 4a7.8 7.8 0 0 0-.1-1.4l2-1.6"
            + "-2-3.5-2.4 1a8.3 8.3 0 0 0-2.4-1.4L15.7 2h-4l-.4 2.6a8.3 8.3 0 0 0-2.4 1.4l-2.4-1-2 3.5 2 1.6a8 8 0 0 0"
            + " 0 2.8l-2 1.6 2 3.5 2.4-1a8.3 8.3 0 0 0 2.4 1.4l.4 2.6h4l.4-2.6a8.3 8.3 0 0 0 2.4-1.4l2.4 1 2-3.5-2-1.6"
            + "a7.8 7.8 0 0 0 .1-1.4z";
    public static final String COPY = "M8 8h12v12H8V8zm-4 8V4h12";
    public static final String WALLET = "M3 6h16a2 2 0 0 1 2 2v10a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V6zm0 0V5a2 2 0 0 1"
            + " 2-2h12M16 13h3";
    public static final String LOCK = "M7 11V7a5 5 0 0 1 10 0v4M5 11h14v10H5V11z";
    // Filled equalizer bars — reads as live activity/stats (must be solid, not stroked).
    public static final String LIVE = "M4 13h3v7H4v-7z M10 8h3v12h-3V8z M16 3h3v17h-3V3z";

    public static Region icon(String svgPath, double size, String styleClass) {
        SVGPath path = new SVGPath();
        path.setContent(svgPath);
        Region region = new Region();
        region.setShape(path);
        region.setMinSize(size, size);
        region.setPrefSize(size, size);
        region.setMaxSize(size, size);
        region.getStyleClass().add(styleClass);
        return region;
    }
}
