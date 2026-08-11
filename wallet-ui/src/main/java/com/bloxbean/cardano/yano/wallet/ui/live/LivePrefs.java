package com.bloxbean.cardano.yano.wallet.ui.live;

import java.util.prefs.Preferences;

/**
 * Persisted toggle for the opt-in animated "live chain" ambient background.
 * Off by default — the animation costs frames, so a user opts in. Stored per-user
 * (like the theme), independent of the wallet data directory.
 */
public final class LivePrefs {
    private LivePrefs() {
    }

    private static final Preferences PREFS =
            Preferences.userRoot().node("com/bloxbean/cardano/yano/wallet/ui");
    private static final String KEY = "liveBackground";

    private static volatile boolean ambientEnabled = PREFS.getBoolean(KEY, false);
    // A single listener (the live shell) so a Settings toggle updates the ambient
    // layer immediately. The shell registers on show and clears it on dispose.
    private static volatile Runnable onChange;

    public static boolean ambientEnabled() {
        return ambientEnabled;
    }

    public static void setAmbientEnabled(boolean enabled) {
        ambientEnabled = enabled;
        PREFS.putBoolean(KEY, enabled);
        Runnable listener = onChange;
        if (listener != null) {
            listener.run();
        }
    }

    /** Register (or clear, with {@code null}) the change listener; last one wins. */
    public static void setOnChange(Runnable listener) {
        onChange = listener;
    }
}
