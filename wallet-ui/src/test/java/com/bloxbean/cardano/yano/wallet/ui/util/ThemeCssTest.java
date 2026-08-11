package com.bloxbean.cardano.yano.wallet.ui.util;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards the themeable-CSS refactor without needing a live JavaFX scene:
 * a mistyped {@code -token} in a rule (which would silently blank a color at
 * runtime) or a Light theme missing a tint token (which would leak a dark tint
 * onto a light background) fails here instead of in the user's face.
 */
class ThemeCssTest {

    private static final String DIR = "/com/bloxbean/cardano/yano/wallet/ui/";

    // Custom looked-up colors: leading '-', not a JavaFX built-in (-fx-…), and not
    // a hyphen inside a word (e.g. linear-GRADIENT) or a negative number (-10%).
    private static final Pattern TOKEN = Pattern.compile("(?<![A-Za-z0-9])-(?!fx-)[a-z][a-z0-9-]*");
    private static final Pattern DEFINITION =
            Pattern.compile("(?m)^\\s*(-(?!fx-)[a-z][a-z0-9-]*)\\s*:");

    /** Every theme must redefine these — they are dark-specific derive() tints. */
    private static final List<String> THEME_TOKENS = List.of(
            "-surface-0", "-surface-1", "-surface-2", "-surface-3", "-border",
            "-text-1", "-text-2", "-accent", "-accent-2", "-ok", "-warn", "-bad",
            "-ok-bg", "-ok-fg", "-warn-bg", "-warn-fg", "-bad-bg", "-bad-fg",
            "-net-bg", "-net-fg", "-sel-bg", "-sel-fg", "-warn-text",
            "-toast-ok-bg", "-toast-bad-bg", "-hero-from", "-hero-to", "-hero-border", "-bg-glow");

    @Test
    void baseStylesheetHasNoDanglingTokenReferences() throws IOException {
        String css = read("wallet.css");
        Set<String> defined = matches(DEFINITION, css, 1);
        Set<String> used = matches(TOKEN, css, 0);

        Set<String> undefined = new LinkedHashSet<>(used);
        undefined.removeAll(defined);
        assertThat(undefined)
                .as("every -token used in wallet.css must be defined in it")
                .isEmpty();
    }

    @Test
    void baseStylesheetDefinesEveryThemeToken() throws IOException {
        Set<String> defined = matches(DEFINITION, read("wallet.css"), 1);
        assertThat(defined).containsAll(THEME_TOKENS);
    }

    @Test
    void lightThemeOverridesEveryThemeToken() throws IOException {
        // A missing token here means Light would inherit a dark value → unreadable.
        Set<String> defined = matches(DEFINITION, read("wallet-theme-light.css"), 1);
        assertThat(defined).containsAll(THEME_TOKENS);
    }

    @Test
    void auroraThemeReskinsTheAccent() throws IOException {
        // Aurora is a partial override — it only needs to move the accent (the tint
        // tokens re-derive from it); the rest inherits the dark base.
        Set<String> defined = matches(DEFINITION, read("wallet-theme-aurora.css"), 1);
        assertThat(defined).contains("-accent", "-accent-2");
    }

    private static Set<String> matches(Pattern pattern, String text, int group) {
        Set<String> found = new LinkedHashSet<>();
        Matcher matcher = pattern.matcher(text);
        while (matcher.find()) {
            found.add(matcher.group(group));
        }
        return found;
    }

    private static String read(String name) throws IOException {
        try (InputStream in = ThemeCssTest.class.getResourceAsStream(DIR + name)) {
            assertThat(in).as("stylesheet on classpath: " + name).isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
