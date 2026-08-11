package com.bloxbean.cardano.yano.wallet.ui.util;

import javafx.scene.Scene;

import java.util.List;
import java.util.Objects;
import java.util.prefs.Preferences;

/**
 * Color themes for the wallet. The structural stylesheet {@code wallet.css} holds
 * the dark palette as defaults; each non-dark theme is a small override stylesheet
 * ({@code wallet-theme-<id>.css}) that redefines only the palette / tint tokens and
 * is layered on top. Switching = add/remove that one override sheet on the live
 * {@link Scene}, so every open screen restyles instantly. The choice is persisted
 * per-user via {@link Preferences} (a cosmetic setting, independent of the wallet
 * data directory).
 */
public final class ThemeManager {
    private ThemeManager() {
    }

    public record Theme(String id, String label) {
    }

    /** "dark" is the base (no override sheet); others layer on top. */
    public static final List<Theme> THEMES = List.of(
            new Theme("dark", "Dark"),
            new Theme("light", "Light"),
            new Theme("aurora", "Aurora"));

    private static final String BASE_SHEET = "/com/bloxbean/cardano/yano/wallet/ui/wallet.css";
    // Substring unique to the override sheets (never matches wallet.css itself).
    private static final String THEME_MARKER = "/wallet-theme-";
    private static final Preferences PREFS =
            Preferences.userRoot().node("com/bloxbean/cardano/yano/wallet/ui");
    private static final String PREF_KEY = "theme";

    private static volatile String current = normalize(PREFS.get(PREF_KEY, "dark"));

    public static List<Theme> themes() {
        return THEMES;
    }

    public static String currentId() {
        return current;
    }

    public static String labelFor(String id) {
        String wanted = normalize(id);
        for (Theme theme : THEMES) {
            if (theme.id().equals(wanted)) {
                return theme.label();
            }
        }
        return "Dark";
    }

    /** Install the base stylesheet plus the saved theme on a freshly created scene. */
    public static void install(Scene scene) {
        scene.getStylesheets().add(url(BASE_SHEET));
        applyTheme(scene, current, false);
    }

    /** Swap the active theme on a live scene and persist the choice. */
    public static void apply(Scene scene, String id) {
        applyTheme(scene, id, true);
    }

    private static void applyTheme(Scene scene, String id, boolean persist) {
        String themeId = normalize(id);
        scene.getStylesheets().removeIf(sheet -> sheet.contains(THEME_MARKER));
        if (!"dark".equals(themeId)) {
            scene.getStylesheets().add(url(
                    "/com/bloxbean/cardano/yano/wallet/ui/wallet-theme-" + themeId + ".css"));
        }
        current = themeId;
        if (persist) {
            PREFS.put(PREF_KEY, themeId);
        }
    }

    private static String normalize(String id) {
        for (Theme theme : THEMES) {
            if (theme.id().equals(id)) {
                return id;
            }
        }
        return "dark";
    }

    private static String url(String resource) {
        return Objects.requireNonNull(ThemeManager.class.getResource(resource),
                "Missing stylesheet: " + resource).toExternalForm();
    }
}
