package com.bloxbean.cardano.yano.wallet.launcher;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class NodeOptionsTest {
    @TempDir Path nodeDir;

    @Test void withoutAFileTheNodeIsSizedForADesktopItShares() {
        NodeOptions options = NodeOptions.load(nodeDir);

        assertThat(options.sizingProfile()).isEqualTo("medium");
        // yano.sh pairs medium with this heap; the wallet spawns the node itself,
        // so if this drifts the node runs on the JVM default instead — tens of
        // gigabytes on a large machine, beside caches sized for 4 GiB.
        assertThat(options.maxHeap()).isEqualTo("1536m");
    }

    @Test void aChosenProfileBringsItsOwnHeap() throws Exception {
        write("sizingProfile=xsmall");
        assertThat(NodeOptions.load(nodeDir)).isEqualTo(new NodeOptions("xsmall", "384m"));

        write("sizingProfile=large");
        assertThat(NodeOptions.load(nodeDir)).isEqualTo(new NodeOptions("large", "2g"));
    }

    @Test void anExplicitHeapWinsOverTheProfileDefault() throws Exception {
        // Matches yano.sh, where an -Xmx in JAVA_OPTS overrides the profile.
        write("sizingProfile=xsmall\nmaxHeap=3g");

        assertThat(NodeOptions.load(nodeDir)).isEqualTo(new NodeOptions("xsmall", "3g"));
    }

    @Test void unusableValuesFallBackInsteadOfStoppingTheNode() throws Exception {
        // This file is hand-edited. A typo must cost the setting, not the wallet:
        // "1.5gb" passed through would reach the JVM as an unrecognised option
        // and kill the node at startup, far from the edit that caused it.
        write("sizingProfile=enormous\nmaxHeap=1.5gb");

        assertThat(NodeOptions.load(nodeDir)).isEqualTo(new NodeOptions("medium", "1536m"));
    }

    @Test void commentsAndBlanksAreJustAbsentValues() throws Exception {
        write("# sizingProfile=large\nmaxHeap=\n");

        assertThat(NodeOptions.load(nodeDir)).isEqualTo(NodeOptions.defaults());
    }

    @Test void theTemplateIsWrittenOnceAndChangesNothing() throws Exception {
        NodeOptions.writeTemplateIfMissing(nodeDir);
        Path file = nodeDir.resolve(NodeOptions.FILE_NAME);
        assertThat(file).exists();
        // Every line commented: creating it must not alter how the node starts.
        assertThat(NodeOptions.load(nodeDir)).isEqualTo(NodeOptions.defaults());
        assertThat(Files.readString(file)).contains(NodeOptions.PROFILE_KEY, NodeOptions.MAX_HEAP_KEY);

        Files.writeString(file, "maxHeap=2g");
        NodeOptions.writeTemplateIfMissing(nodeDir);

        // An existing file is never overwritten: it holds the user's own edits.
        assertThat(Files.readString(file)).isEqualTo("maxHeap=2g");
    }

    @Test void aMissingDirectoryIsNotAFailure() {
        assertThat(NodeOptions.load(nodeDir.resolve("not-created-yet")))
                .isEqualTo(NodeOptions.defaults());
        assertThat(NodeOptions.load(null)).isEqualTo(NodeOptions.defaults());
    }

    private void write(String contents) throws Exception {
        Files.writeString(nodeDir.resolve(NodeOptions.FILE_NAME), contents);
    }
}
