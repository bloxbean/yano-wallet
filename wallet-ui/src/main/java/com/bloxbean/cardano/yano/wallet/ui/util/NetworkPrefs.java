package com.bloxbean.cardano.yano.wallet.ui.util;

import java.util.prefs.Preferences;

/**
 * Remembers, per network, how the user last reached it — managed local node or
 * an external URL. Only ONE connection is persisted in the wallet's data dir
 * (connection.json, the thing that is auto-reconnected); this fills the gap so
 * that flipping the network picker restores that network's own last choice
 * instead of carrying the previous network's mode over.
 *
 * <p>Stored per-user next to the theme and live-background toggles, deliberately
 * outside the wallet data directory: it is a UI convenience, not wallet state.
 */
public final class NetworkPrefs {
    private NetworkPrefs() {
    }

    private static final Preferences PREFS =
            Preferences.userRoot().node("com/bloxbean/cardano/yano/wallet/ui");
    private static final String MODE_PREFIX = "net.mode.";
    private static final String URL_PREFIX = "net.url.";

    /** True if the user last reached this network with a managed local node. */
    public static boolean managed(String networkId, boolean fallback) {
        String mode = PREFS.get(MODE_PREFIX + networkId, null);
        return mode == null ? fallback : "MANAGED".equals(mode);
    }

    /** The external URL last used for this network, or {@code fallback} if none. */
    public static String url(String networkId, String fallback) {
        String url = PREFS.get(URL_PREFIX + networkId, null);
        return url == null || url.isBlank() ? fallback : url;
    }

    /** Records a successful (or attempted) choice so the next visit prefills it. */
    public static void remember(String networkId, boolean managed, String url) {
        if (networkId == null || networkId.isBlank()) {
            return;
        }
        PREFS.put(MODE_PREFIX + networkId, managed ? "MANAGED" : "EXTERNAL");
        if (!managed && url != null && !url.isBlank()) {
            PREFS.put(URL_PREFIX + networkId, url.trim());
        }
    }
}
