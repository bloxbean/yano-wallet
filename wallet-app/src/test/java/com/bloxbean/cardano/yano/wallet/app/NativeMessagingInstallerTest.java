package com.bloxbean.cardano.yano.wallet.app;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The installer must produce a working Chrome Native Messaging setup from
 * nothing: proxy jar, executable launcher pinned to this JVM's runtime, and a
 * host manifest that names our extension id (ADR-035 M5).
 */
@DisabledOnOs(OS.WINDOWS) // Windows needs a registry entry; not automated yet.
class NativeMessagingInstallerTest {

    @TempDir
    Path tempHome;

    @Test
    void installsProxyScriptAndBrowserManifest() throws Exception {
        NativeMessagingInstaller installer = new NativeMessagingInstaller(tempHome.resolve(".yano-wallet"), tempHome);

        String summary = installer.install();
        assertThat(summary).contains("Restart the browser");

        // The proxy jar is real (extracted from the app's resources).
        Path proxyJar = tempHome.resolve(".yano-wallet/connector/cip30-proxy.jar");
        assertThat(proxyJar).exists();
        assertThat(Files.size(proxyJar)).isGreaterThan(1000);

        // The launcher is executable and wires java + jar + socket together.
        Path script = tempHome.resolve(".yano-wallet/connector/cip30-host.sh");
        assertThat(Files.getPosixFilePermissions(script)).contains(PosixFilePermission.OWNER_EXECUTE);
        String launcher = Files.readString(script);
        assertThat(launcher).contains(System.getProperty("java.home"));
        assertThat(launcher).contains(proxyJar.toString());
        assertThat(launcher).contains(installer.socketPath().toString());
        assertThat(launcher).contains("Cip30NativeProxy");

        // At least Chrome's manifest is registered, and it pins our extension.
        List<Path> manifests;
        try (Stream<Path> walk = Files.walk(tempHome)) {
            manifests = walk.filter(p -> p.getFileName().toString()
                    .equals(NativeMessagingInstaller.HOST_NAME + ".json")).toList();
        }
        assertThat(manifests).isNotEmpty();
        JsonNode manifest = new ObjectMapper().readTree(Files.readString(manifests.get(0)));
        assertThat(manifest.path("name").asText()).isEqualTo(NativeMessagingInstaller.HOST_NAME);
        assertThat(manifest.path("type").asText()).isEqualTo("stdio");
        assertThat(manifest.path("path").asText()).isEqualTo(script.toString());
        assertThat(manifest.path("allowed_origins").get(0).asText())
                .isEqualTo("chrome-extension://" + NativeMessagingInstaller.EXTENSION_ID + "/");
    }

    @Test
    void reinstallIsIdempotent() throws Exception {
        NativeMessagingInstaller installer = new NativeMessagingInstaller(tempHome.resolve(".yano-wallet"), tempHome);
        installer.install();
        installer.install(); // must overwrite, not fail

        assertThat(tempHome.resolve(".yano-wallet/connector/cip30-proxy.jar")).exists();
    }
}
