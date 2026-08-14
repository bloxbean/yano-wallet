package com.bloxbean.cardano.yano.wallet.launcher;

import java.util.List;

/**
 * What a starting node is currently doing, read out of its log.
 *
 * <p>The node's REST API is the wallet's readiness signal, and it does not bind
 * its HTTP port until the startup sequence finishes. Some of that sequence is
 * long: rebuilding the account-history index over a fully synced chain runs at
 * roughly 900 blocks/s, so on preview (~4.5M blocks) it is well over an hour
 * during which {@code /status} refuses every connection. The node reports its
 * position every 1000 blocks; without reading that, the wallet can only show an
 * indeterminate spinner and the start is indistinguishable from a hang.
 *
 * <p>Parsed from the log rather than asked over HTTP because there is no HTTP
 * yet — that is the whole problem. It also means this works against the node
 * releases already published, instead of waiting for one that serves a
 * warming-up status.
 *
 * <p>Best-effort by design: an unrecognised log is {@link Phase#BOOTING} with no
 * fraction, never an error. New node versions may reword these lines, and a
 * reworded line must cost a progress bar, not a startup.
 */
public record NodeStartupProgress(Phase phase, String detail, long current, long total) {

    /**
     * Startup stages the wallet can distinguish, in the order the node runs them.
     *
     * <p>Only stages the node actually reports are listed. Its UTxO reconcile,
     * for instance, logs one line when it finishes and nothing while it runs —
     * there is no honest way to show it as a phase in progress, so it stays
     * folded into {@link #BOOTING} rather than becoming a label that claims more
     * than the log says.
     */
    public enum Phase {
        /** Process is up; nothing recognisable in the log yet. */
        BOOTING,
        /** Rebuilding account history over the synced chain — the long one. */
        RECONCILING_ACCOUNT_HISTORY,
        /** HTTP is bound; {@code /status} should answer imminently. */
        LISTENING
    }

    private static final String ACCOUNT_HISTORY_MARKER = "Account history reconcile progress: block ";
    private static final String LISTENING_MARKER = "Listening on:";

    public static NodeStartupProgress booting() {
        return new NodeStartupProgress(Phase.BOOTING, "Starting the node…", 0, 0);
    }

    /**
     * Fraction complete in [0, 1], or -1 when this phase reports no position.
     * Callers map -1 onto an indeterminate bar.
     */
    public double fraction() {
        if (total <= 0 || current <= 0) {
            return -1;
        }
        return Math.min(1.0, (double) current / total);
    }

    /** True when a position is available, i.e. {@link #fraction()} is usable. */
    public boolean determinate() {
        return fraction() >= 0;
    }

    /**
     * Reads the newest recognisable progress line from a log tail.
     *
     * <p>Scans backwards and stops at the first hit: the tail holds hundreds of
     * progress lines and only the last one is current. Lines are matched by
     * substring rather than by a compiled pattern over the whole line, so a
     * change to the log's timestamp or category format does not break parsing.
     *
     * @param tailLines newest-last, as returned by {@link ManagedNode#tailLines}
     */
    public static NodeStartupProgress parse(List<String> tailLines) {
        if (tailLines == null || tailLines.isEmpty()) {
            return booting();
        }
        for (int i = tailLines.size() - 1; i >= 0; i--) {
            String line = tailLines.get(i);
            if (line == null) {
                continue;
            }
            if (line.contains(LISTENING_MARKER)) {
                return new NodeStartupProgress(Phase.LISTENING,
                        "The node's API is up — completing the connection…", 0, 0);
            }
            int marker = line.indexOf(ACCOUNT_HISTORY_MARKER);
            if (marker >= 0) {
                long[] position = parsePosition(line.substring(marker + ACCOUNT_HISTORY_MARKER.length()));
                return new NodeStartupProgress(Phase.RECONCILING_ACCOUNT_HISTORY,
                        "Building the account-history index", position[0], position[1]);
            }
        }
        return booting();
    }

    /**
     * Reads {@code <current>/<total>} from the start of {@code text}, returning
     * {@code {0, 0}} if it is not in that shape — a half-flushed line is normal
     * when reading a log the node is still writing.
     */
    private static long[] parsePosition(String text) {
        int slash = text.indexOf('/');
        if (slash <= 0) {
            return new long[]{0, 0};
        }
        long current = parseLeadingLong(text.substring(0, slash));
        long total = parseLeadingLong(text.substring(slash + 1));
        return new long[]{current, total};
    }

    private static long parseLeadingLong(String text) {
        int end = 0;
        String trimmed = text.strip();
        while (end < trimmed.length() && Character.isDigit(trimmed.charAt(end))) {
            end++;
        }
        if (end == 0) {
            return 0;
        }
        try {
            return Long.parseLong(trimmed.substring(0, end));
        } catch (NumberFormatException e) {
            return 0; // a number too long for a long is not worth failing a UI read over
        }
    }
}
