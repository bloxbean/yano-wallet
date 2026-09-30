package com.bloxbean.cardano.yano.wallet.launcher;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.regex.Pattern;

/**
 * Node sizing the user can change without a rebuild: {@code node.properties} in
 * the managed node's own directory, beside {@code node.log}.
 *
 * <p>The wallet launches the node itself rather than through {@code yano.sh}, so
 * none of that script's {@code JAVA_OPTS} handling applies and the defaults here
 * are the only sizing the node gets. Everything the script decides from a
 * resource profile is therefore mirrored: the profile name and the heap that
 * goes with it.
 *
 * <p>Every value is optional and a bad one is ignored rather than fatal — this
 * file is edited by hand, and a typo in it must not leave the wallet unable to
 * start its node. A rejected value is logged with what was used instead.
 */
record NodeOptions(String sizingProfile, String maxHeap) {
    private static final Logger log = LoggerFactory.getLogger(NodeOptions.class);

    static final String FILE_NAME = "node.properties";
    static final String PROFILE_KEY = "sizingProfile";
    static final String MAX_HEAP_KEY = "maxHeap";

    /** Dev/CI escape hatch, checked before the file so a {@code -D} run needs no edit. */
    static final String MAX_HEAP_PROPERTY = "yano.wallet.node.max-heap";
    static final String PROFILE_PROPERTY = "yano.wallet.node.sizing-profile";

    static final String DEFAULT_PROFILE = "medium";

    /** yano.sh's own mapping (yano.sh:355-373), so the two cannot disagree. */
    private static final Map<String, String> HEAP_BY_PROFILE = Map.of(
            "xsmall", "384m",
            "small", "384m",
            "medium", "1536m",
            "large", "2g");

    /** What the JVM and a native image both accept: digits with an optional unit. */
    private static final Pattern HEAP = Pattern.compile("\\d+[kKmMgG]?");

    static NodeOptions defaults() {
        return new NodeOptions(DEFAULT_PROFILE, HEAP_BY_PROFILE.get(DEFAULT_PROFILE));
    }

    /**
     * Reads {@code <nodeDir>/node.properties}, falling back to the defaults for
     * anything absent or unusable. An unreadable file is not an error: a managed
     * node that refuses to start because of a stray character in an optional
     * settings file would be a worse failure than ignoring it.
     */
    static NodeOptions load(Path nodeDir) {
        Properties properties = new Properties();
        Path file = nodeDir == null ? null : nodeDir.resolve(FILE_NAME);
        if (file != null && Files.isReadable(file)) {
            try (Reader reader = Files.newBufferedReader(file)) {
                properties.load(reader);
            } catch (IOException | IllegalArgumentException unreadable) {
                log.warn("Ignoring {}: {}. Using default node sizing.", file, unreadable.toString());
            }
        }
        String profile = profile(value(PROFILE_PROPERTY, properties, PROFILE_KEY), file);
        return new NodeOptions(profile, heap(value(MAX_HEAP_PROPERTY, properties, MAX_HEAP_KEY),
                HEAP_BY_PROFILE.get(profile), file));
    }

    private static String value(String systemProperty, Properties properties, String key) {
        String override = System.getProperty(systemProperty);
        return override != null && !override.isBlank() ? override : properties.getProperty(key);
    }

    private static String profile(String requested, Path file) {
        if (requested == null || requested.isBlank()) {
            return DEFAULT_PROFILE;
        }
        String name = requested.trim().toLowerCase(Locale.ROOT);
        if (!HEAP_BY_PROFILE.containsKey(name)) {
            log.warn("{} in {} names no known sizing profile ({}); using {}.",
                    name, file, HEAP_BY_PROFILE.keySet(), DEFAULT_PROFILE);
            return DEFAULT_PROFILE;
        }
        return name;
    }

    /**
     * An explicit heap wins over the profile's, matching {@code yano.sh}, where
     * an {@code -Xmx} in {@code JAVA_OPTS} overrides the resource profile.
     */
    private static String heap(String requested, String fromProfile, Path file) {
        if (requested == null || requested.isBlank()) {
            return fromProfile;
        }
        String value = requested.trim();
        if (!HEAP.matcher(value).matches()) {
            // Passed through, this reaches the JVM as an unrecognised option and
            // the node dies at startup with a message about -Xmx rather than
            // about this file — a long way from the edit that caused it.
            log.warn("{}={} in {} is not a heap size such as 1536m or 2g; using {}.",
                    MAX_HEAP_KEY, value, file, fromProfile);
            return fromProfile;
        }
        return value;
    }

    /**
     * Writes a commented template the first time a managed node starts, so the
     * settings are discoverable in the folder the user already visits for
     * {@code node.log} instead of only in the docs. Every line is commented out,
     * so creating it changes nothing.
     */
    static void writeTemplateIfMissing(Path nodeDir) {
        if (nodeDir == null) {
            return;
        }
        Path file = nodeDir.resolve(FILE_NAME);
        if (Files.exists(file)) {
            return;
        }
        String template = """
                # Yano Wallet — managed node settings. Optional; edit and restart the wallet.
                #
                # sizingProfile: how the node sizes its RocksDB caches and queues.
                #   xsmall | small | medium | large      (default: medium)
                # It is passed to the node as a Quarkus profile, after the network
                # profile and before the wallet profile that enables history.
                #
                #sizingProfile=medium
                #
                # maxHeap: the node's -Xmx. Set it only to override the profile's
                # default (xsmall/small 384m, medium 1536m, large 2g). Digits with an
                # optional k/m/g, for example 1536m or 2g.
                #
                #maxHeap=1536m
                """;
        try {
            Files.createDirectories(nodeDir);
            Files.writeString(file, template);
        } catch (IOException ignored) {
            // Cosmetic: the defaults apply either way, and a node that would not
            // start because a template could not be written helps nobody.
        }
    }
}
