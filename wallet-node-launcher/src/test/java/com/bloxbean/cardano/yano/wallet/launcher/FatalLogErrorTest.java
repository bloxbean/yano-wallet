package com.bloxbean.cardano.yano.wallet.launcher;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A managed node that hits an unrecoverable error stays alive serving failures,
 * so readiness would otherwise wait out the full start timeout (45 min on real
 * networks) in silence. The log scan must catch the fatal line in seconds —
 * the exact field failure: RocksDB corruption after macOS's /tmp cleanup
 * deleted cold SST files out from under a valid manifest.
 */
class FatalLogErrorTest {

    @TempDir
    Path tempDir;

    @Test
    void detectsRocksDbCorruption() throws Exception {
        Path log = tempDir.resolve("node.log");
        Files.writeString(log, """
                2026-07-19 INFO  starting quarkus
                Caused by: org.rocksdb.RocksDBException: Corruption: IO error: No such file or directory: \
                While open a file for random read: /tmp/yano-wallet/preprod/node/chainstate/003947.sst
                \tat org.rocksdb.RocksDB.open(Native Method)
                """);

        String fatal = ManagedNode.fatalLogError(log);

        assertThat(fatal).isNotNull().contains("RocksDBException").contains("003947.sst");
    }

    @Test
    void detectsBadJarAndPortInUse() throws Exception {
        Path badJar = tempDir.resolve("badjar.log");
        Files.writeString(badJar, "Error: Invalid or corrupt jarfile /path/yano.jar\n");
        assertThat(ManagedNode.fatalLogError(badJar)).contains("Invalid or corrupt jarfile");

        Path portTaken = tempDir.resolve("port.log");
        Files.writeString(portTaken, "java.net.BindException: Address already in use\n");
        assertThat(ManagedNode.fatalLogError(portTaken)).contains("Address already in use");
    }

    @Test
    void healthySyncLogIsNotFatal() throws Exception {
        Path log = tempDir.resolve("healthy.log");
        Files.writeString(log, """
                INFO  chain sync started from slot 4200000
                INFO  applied block 3947 (this mentions no error)
                WARN  slow response from peer, retrying
                """);

        assertThat(ManagedNode.fatalLogError(log)).isNull();
    }

    @Test
    void missingLogIsNotFatal() {
        assertThat(ManagedNode.fatalLogError(tempDir.resolve("nope.log"))).isNull();
        assertThat(ManagedNode.fatalLogError(null)).isNull();
    }
}
