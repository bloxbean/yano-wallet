package com.bloxbean.cardano.yano.wallet.launcher;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ManagedNode#tailLines} backs the wallet's live node-log view, so it must
 * be cheap on a large sync log and robust to a log being written concurrently.
 */
class LogTailTest {

    @TempDir
    Path tempDir;

    @Test
    void returnsLastNLinesInOrder() throws Exception {
        Path log = tempDir.resolve("node.log");
        Files.writeString(log, "line1\nline2\nline3\nline4\nline5\n");

        assertThat(ManagedNode.tailLines(log, 2)).containsExactly("line4", "line5");
        assertThat(ManagedNode.tailLines(log, 10)).containsExactly("line1", "line2", "line3", "line4", "line5");
    }

    @Test
    void handlesNoTrailingNewlineAndCrlf() throws Exception {
        Path log = tempDir.resolve("node.log");
        Files.writeString(log, "alpha\r\nbeta\r\ngamma"); // CRLF, last line unterminated

        assertThat(ManagedNode.tailLines(log, 5)).containsExactly("alpha", "beta", "gamma");
    }

    @Test
    void missingOrEmptyOrNonPositiveYieldsEmpty() throws Exception {
        assertThat(ManagedNode.tailLines(tempDir.resolve("nope.log"), 10)).isEmpty();
        assertThat(ManagedNode.tailLines(null, 10)).isEmpty();

        Path empty = tempDir.resolve("empty.log");
        Files.writeString(empty, "");
        assertThat(ManagedNode.tailLines(empty, 10)).isEmpty();

        Path log = tempDir.resolve("node.log");
        Files.writeString(log, "one\ntwo\n");
        assertThat(ManagedNode.tailLines(log, 0)).isEmpty();
    }

    @Test
    void boundedReadDropsPartialFirstLineOnHugeLog() throws Exception {
        // Larger than the 256 KB tail window, so the read starts mid-file and the
        // first (partial) line must be dropped — never surfaced as a bogus line.
        Path log = tempDir.resolve("big.log");
        StringBuilder sb = new StringBuilder();
        int i = 0;
        while (sb.length() < 400 * 1024) {
            sb.append("block ").append(i++).append(" applied — filler padding to grow the log\n");
        }
        Files.writeString(log, sb.toString(), StandardCharsets.UTF_8);

        List<String> tail = ManagedNode.tailLines(log, 3);
        assertThat(tail).hasSize(3);
        // The last written line is "block <i-1> applied …"; the tail must end with it.
        assertThat(tail.get(2)).isEqualTo("block " + (i - 1) + " applied — filler padding to grow the log");
        // Every returned line is whole (starts with "block "), i.e. no partial head line.
        assertThat(tail).allSatisfy(line -> assertThat(line).startsWith("block "));
    }
}
